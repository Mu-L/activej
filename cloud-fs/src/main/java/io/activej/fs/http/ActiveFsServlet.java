/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.fs.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.function.BiFunctionEx;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelSupplier;
import io.activej.fs.ActiveFs;
import io.activej.fs.exception.FileNotFoundException;
import io.activej.fs.exception.FsException;
import io.activej.http.*;
import io.activej.http.MultipartDecoder.MultipartDataHandler;
import io.activej.promise.Promise;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

import static io.activej.fs.http.FsCommand.*;
import static io.activej.fs.util.MessageTypes.STRING_SET_TYPE;
import static io.activej.fs.util.MessageTypes.STRING_STRING_MAP_TYPE;
import static io.activej.fs.util.RemoteFsUtils.*;
import static io.activej.http.ContentTypes.JSON_UTF_8;
import static io.activej.http.ContentTypes.PLAIN_TEXT_UTF_8;
import static io.activej.http.HttpHeaderValue.ofContentType;
import static io.activej.http.HttpHeaders.*;
import static io.activej.http.HttpMethod.GET;
import static io.activej.http.HttpMethod.POST;

/**
 * An HTTP servlet that exposes exposes some given {@link ActiveFs}.
 * <p>
 * Servlet is fully compatible with {@link HttpActiveFs} client.
 * <p>
 * It also defines additional endpoints that can be useful for accessing via web browser,
 * such as uploading multiple files using <i>multipart/form-data</i> content type
 * and downloading a file using range requests.
 * <p>
 * This server may  be launched as a publicly available server.
 */
public final class ActiveFsServlet {
	private ActiveFsServlet() {
	}

	public static RoutingServlet create(ActiveFs fs) {
		return create(fs, true);
	}

	public static RoutingServlet create(ActiveFs fs, boolean inline) {
		return RoutingServlet.create()
				.map(POST, "/" + UPLOAD + "/*", request -> {
					String contentLength = request.getHeader(CONTENT_LENGTH);
					Long size = contentLength == null ? null : Long.valueOf(contentLength);
					return (size == null ?
							fs.upload(decodePath(request)) :
							fs.upload(decodePath(request), size))
							.map(uploadAcknowledgeFn(request));
				})
				.map(POST, "/" + UPLOAD, request -> request.handleMultipart(MultipartDataHandler.file(fs::upload))
						.map(errorHandlerFn()))
				.map(POST, "/" + APPEND + "/*", request -> {
					long offset = getNumberParameterOr(request, "offset", 0);
					return fs.append(decodePath(request), offset)
							.map(uploadAcknowledgeFn(request));
				})
				.map(GET, "/" + DOWNLOAD + "/*", request -> {
					String name = decodePath(request);
					String rangeHeader = request.getHeader(HttpHeaders.RANGE);
					if (rangeHeader != null) {
						return rangeDownload(fs, inline, name, rangeHeader);
					}
					long offset = getNumberParameterOr(request, "offset", 0);
					long limit = getNumberParameterOr(request, "limit", Long.MAX_VALUE);
					return fs.download(name, offset, limit)
							.map(errorHandlerFn(supplier -> HttpResponse.ok200()
									.withHeader(ACCEPT_RANGES, "bytes")
									.withBodyStream(supplier)));
				})
				.map(GET, "/" + LIST, request -> {
					String glob = request.getQueryParameter("glob");
					glob = glob != null ? glob : "**";
					return (fs.list(glob))
							.map(errorHandlerFn(list ->
									HttpResponse.ok200()
											.withBody(toJson(list))
											.withHeader(CONTENT_TYPE, ofContentType(JSON_UTF_8))));
				})
				.map(GET, "/" + INFO + "/*", request ->
						fs.info(decodePath(request))
								.map(errorHandlerFn(meta ->
										HttpResponse.ok200()
												.withBody(toJson(meta))
												.withHeader(CONTENT_TYPE, ofContentType(JSON_UTF_8)))))
				.map(GET, "/" + INFO_ALL, request -> request.loadBody()
						.map(body -> fromJson(STRING_SET_TYPE, body))
						.then(fs::infoAll)
						.map(errorHandlerFn(map ->
								HttpResponse.ok200()
										.withBody(toJson(map))
										.withHeader(CONTENT_TYPE, ofContentType(JSON_UTF_8)))))
				.map(GET, "/" + PING, request -> fs.ping()
						.map(errorHandlerFn()))
				.map(POST, "/" + MOVE, request -> {
					String name = getQueryParameter(request, "name");
					String target = getQueryParameter(request, "target");
					return fs.move(name, target)
							.map(errorHandlerFn());
				})
				.map(POST, "/" + MOVE_ALL, request -> request.loadBody()
						.map(body -> fromJson(STRING_STRING_MAP_TYPE, body))
						.then(fs::moveAll)
						.map(errorHandlerFn()))
				.map(POST, "/" + COPY, request -> {
					String name = getQueryParameter(request, "name");
					String target = getQueryParameter(request, "target");
					return fs.copy(name, target)
							.map(errorHandlerFn());
				})
				.map(POST, "/" + COPY_ALL, request -> request.loadBody()
						.map(body -> fromJson(STRING_STRING_MAP_TYPE, body))
						.then(fs::copyAll)
						.map(errorHandlerFn()))
				.map(HttpMethod.DELETE, "/" + DELETE + "/*", request ->
						fs.delete(decodePath(request))
								.map(errorHandlerFn()))
				.map(POST, "/" + DELETE_ALL, request -> request.loadBody()
						.map(body -> fromJson(STRING_SET_TYPE, body))
						.then(fs::deleteAll)
						.map(errorHandlerFn()));
	}

	private static @NotNull Promise<HttpResponse> rangeDownload(ActiveFs fs, boolean inline, String name, String rangeHeader) {
		return fs.info(name)
				.then(meta -> {
					if (meta == null) {
						throw new FileNotFoundException();
					}
					return HttpResponse.file(
							(offset, limit) -> fs.download(name, offset, limit),
							name,
							meta.getSize(),
							rangeHeader,
							inline);
				})
				.map(errorHandlerFn(Function.identity()));
	}

	private static String decodePath(HttpRequest request) throws HttpError {
		String value = UrlParser.urlParse(request.getRelativePath());
		if (value == null) {
			throw HttpError.ofCode(400, "Path contains invalid UTF");
		}
		return value;
	}

	private static String getQueryParameter(HttpRequest request, String parameterName) throws HttpError {
		String value = request.getQueryParameter(parameterName);
		if (value == null) {
			throw HttpError.ofCode(400, "No '" + parameterName + "' query parameter");
		}
		return value;
	}

	private static long getNumberParameterOr(HttpRequest request, String parameterName, long defaultValue) throws HttpError {
		String value = request.getQueryParameter(parameterName);
		if (value == null) {
			return defaultValue;
		}
		try {
			long val = Long.parseLong(value);
			if (val < 0) {
				throw new NumberFormatException();
			}
			return val;
		} catch (NumberFormatException ignored) {
			throw HttpError.ofCode(400, "Invalid '" + parameterName + "' value");
		}
	}

	private static HttpResponse getErrorResponse(Exception e) {
		return HttpResponse.ofCode(500)
				.withHeader(CONTENT_TYPE, ofContentType(JSON_UTF_8))
				.withBody(toJson(FsException.class, castError(e)));
	}

	private static <T> BiFunctionEx<T, Exception, HttpResponse> errorHandlerFn() {
		return errorHandlerFn($ -> HttpResponse.ok200().withHeader(CONTENT_TYPE, ofContentType(PLAIN_TEXT_UTF_8)));
	}

	private static <T> BiFunctionEx<T, Exception, HttpResponse> errorHandlerFn(Function<T, HttpResponse> successful) {
		return (res, e) -> e == null ? successful.apply(res) : getErrorResponse(e);
	}

	private static BiFunctionEx<ChannelConsumer<ByteBuf>, Exception, HttpResponse> uploadAcknowledgeFn(@NotNull HttpRequest request) {
		return errorHandlerFn(consumer -> HttpResponse.ok200()
				.withHeader(CONTENT_TYPE, ofContentType(JSON_UTF_8))
				.withBodyStream(ChannelSupplier.ofPromise(request.getBodyStream()
						.streamTo(consumer)
						.map($ -> UploadAcknowledgement.ok(), e -> UploadAcknowledgement.ofError(castError(e)))
						.map(ack -> ChannelSupplier.of(toJson(ack))))));
	}

}
