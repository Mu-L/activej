package io.activej.http;

import io.activej.bytebuf.ByteBufStrings;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.inspector.EventloopInspector;
import io.activej.eventloop.inspector.ThrottlingController;
import io.activej.promise.Promise;
import io.activej.reactor.nio.NioReactor;

import java.util.Random;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.exception.FatalErrorHandlers.rethrow;
import static io.activej.test.TestUtils.getFreePort;

public class HttpThrottlingServer {
	private static final Random rand = new Random();
	private static final int defaultLoadBusinessLogic = 0; // without load on the business logic
	private static final String TEST_RESPONSE = "Hello, World!";
	public static final int SERVER_PORT = getFreePort();

	static class ServerOptions {
		private final int loadBusinessLogic;

		public ServerOptions(int loadBusinessLogic) {
			checkArgument(loadBusinessLogic >= 0, "Load business logic should be a non-negative value");
			this.loadBusinessLogic = loadBusinessLogic;
		}

		public int getLoadBusinessLogic() {
			return loadBusinessLogic;
		}

		public static ServerOptions parseCommandLine(String[] args) {
			int loadBusinessLogic = defaultLoadBusinessLogic;
			for (int i = defaultLoadBusinessLogic; i < args.length; i++) {
				switch (args[i]) {
					case "-l" -> loadBusinessLogic = Integer.parseInt(args[++i]);
					case "-?", "-h" -> {
						usage();
						return null;
					}
				}
			}
			return new ServerOptions(loadBusinessLogic);
		}

		public static void usage() {
			System.err.println(HttpThrottlingServer.class.getSimpleName() + " [options]\n" +
					"\t-l    - value of load server\n" +
					"\t-h/-? - this help.");
		}

		@Override
		public String toString() {
			return "Load business logic : " + loadBusinessLogic;
		}
	}

	private final HttpServer server;

	public HttpThrottlingServer(NioReactor reactor, ServerOptions options) {
		server = buildHttpServer(reactor, options.getLoadBusinessLogic());
	}

	private static HttpServer buildHttpServer(NioReactor reactor, int loadBusinessLogic) {
//		final ByteBufPool byteBufferPool = new ByteBufPool(16, 65536);
		return HttpServer.builder(reactor,
						request -> longBusinessLogic(TEST_RESPONSE, loadBusinessLogic))
				.withListenPort(SERVER_PORT)
				.build();
	}

	@SuppressWarnings("SameParameterValue")
	protected static Promise<HttpResponse> longBusinessLogic(String response, int loadBusinessLogic) {
		long result = 0;
		for (int i = 0; i < loadBusinessLogic; ++i) {
			for (int j = 0; j < 200; ++j) {
				int index = Math.abs(rand.nextInt()) % response.length();
				result += response.charAt(index) * rand.nextLong();
			}
		}
		if (result % 3 != 0) {
			response += "!";
		}
		return HttpResponse.Builder.ok200()
				.withBody(ByteBufStrings.encodeAscii(response))
				.toPromise();
	}

	public void start() throws Exception {
		server.listen();
	}

	public void stop() {
		server.close();
	}

	public static void info(ServerOptions options) {
		System.out.println("<" + HttpThrottlingServer.class.getSimpleName() + ">\n" + options.toString());
	}

	public static void main(String[] args) throws Exception {
		ServerOptions options = ServerOptions.parseCommandLine(args);
		if (options == null) {
			return;
		}
		info(options);

		EventloopInspector throttlingController = ThrottlingController.create();
		Eventloop eventloop = Eventloop.builder()
				.withFatalErrorHandler(rethrow())
				.withCurrentThread()
				.withInspector(throttlingController)
				.build();

		HttpThrottlingServer server = new HttpThrottlingServer(eventloop, options);

		server.start();

		eventloop.run();
	}

}
