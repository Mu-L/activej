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

package io.activej.fs;

import io.activej.bytebuf.ByteBuf;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelSupplier;
import io.activej.fs.exception.ForbiddenPathException;
import io.activej.fs.exception.FsBatchException;
import io.activej.fs.exception.FsScalarException;
import io.activej.promise.Promise;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Utils.isBijection;
import static java.lang.Boolean.TRUE;
import static java.util.stream.Collectors.*;

/**
 * A file system that can be configured to forbid certain paths and filenames.
 * <p>
 * Inherits all of the limitations of parent {@link ActiveFs}
 */
final class FilterActiveFs implements ActiveFs {

	private final ActiveFs parent;
	private final Predicate<String> predicate;

	FilterActiveFs(ActiveFs parent, Predicate<String> predicate) {
		this.parent = parent;
		this.predicate = predicate;
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(@NotNull String name) {
		if (!predicate.test(name)) {
			return Promise.ofException(new ForbiddenPathException());
		}
		return parent.upload(name);
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(@NotNull String name, long size) {
		if (!predicate.test(name)) {
			return Promise.ofException(new ForbiddenPathException());
		}
		return parent.upload(name);
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> append(@NotNull String name, long offset) {
		if (!predicate.test(name)) {
			return Promise.ofException(new ForbiddenPathException());
		}
		return parent.append(name, offset);
	}

	@Override
	public Promise<ChannelSupplier<ByteBuf>> download(@NotNull String name, long offset, long limit) {
		if (!predicate.test(name)) {
			return Promise.ofException(new ForbiddenPathException());
		}
		return parent.download(name, offset, limit);
	}

	@Override
	public Promise<Void> copy(@NotNull String name, @NotNull String target) {
		return filteringOp(name, target, parent::copy);
	}

	@Override
	public Promise<Void> copyAll(Map<String, String> sourceToTarget) {
		checkArgument(isBijection(sourceToTarget), "Targets must be unique");
		if (sourceToTarget.isEmpty()) return Promise.complete();

		return filteringOp(sourceToTarget, parent::copyAll);
	}

	@Override
	public Promise<Void> move(@NotNull String name, @NotNull String target) {
		return filteringOp(name, target, parent::move);
	}

	@Override
	public Promise<Void> moveAll(Map<String, String> sourceToTarget) {
		checkArgument(isBijection(sourceToTarget), "Targets must be unique");
		if (sourceToTarget.isEmpty()) return Promise.complete();

		return filteringOp(sourceToTarget, parent::moveAll);
	}

	@Override
	public Promise<Map<String, FileMetadata>> list(@NotNull String glob) {
		return parent.list(glob)
				.map(map -> map.entrySet().stream()
						.filter(entry -> predicate.test(entry.getKey()))
						.collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));
	}

	@Override
	public Promise<@Nullable FileMetadata> info(@NotNull String name) {
		if (!predicate.test(name)) {
			return Promise.of(null);
		}
		return parent.info(name);
	}

	@Override
	public Promise<Map<String, @NotNull FileMetadata>> infoAll(@NotNull Set<String> names) {
		Map<Boolean, Set<String>> partitioned = names.stream().collect(partitioningBy(predicate, toSet()));
		Set<String> query = partitioned.get(TRUE);
		return query.isEmpty() ?
				Promise.of(Collections.emptyMap()) :
				parent.infoAll(query);
	}

	@Override
	public Promise<Void> ping() {
		return parent.ping();
	}

	@Override
	public Promise<Void> delete(@NotNull String name) {
		if (!predicate.test(name)) {
			return Promise.complete();
		}
		return parent.delete(name);
	}

	@Override
	public Promise<Void> deleteAll(Set<String> toDelete) {
		return parent.deleteAll(toDelete.stream()
				.filter(predicate)
				.collect(toSet()));
	}

	private Promise<Void> filteringOp(String source, String target, BiFunction<String, String, Promise<Void>> original) {
		if (!predicate.test(source)) {
			return Promise.ofException(new ForbiddenPathException("Path '" + source + "' is forbidden"));
		}
		if (!predicate.test(target)) {
			return Promise.ofException(new ForbiddenPathException("Path '" + target + "' is forbidden"));
		}
		return original.apply(source, target);
	}

	private Promise<Void> filteringOp(Map<String, String> sourceToTarget, Function<Map<String, String>, Promise<Void>> original) {
		Map<String, String> renamed = new LinkedHashMap<>();
		Map<String, FsScalarException> exceptions = new HashMap<>();
		for (Map.Entry<String, String> entry : sourceToTarget.entrySet()) {
			String source = entry.getKey();
			if (!predicate.test(source)) {
				exceptions.put(source, new ForbiddenPathException("Path '" + source + "' is forbidden"));
			}
			String target = entry.getValue();
			if (!predicate.test(target)) {
				exceptions.put(source, new ForbiddenPathException("Path '" + target + "' is forbidden"));
			}
			renamed.put(source, target);
		}
		if (!exceptions.isEmpty()) {
			return Promise.ofException(new FsBatchException(exceptions));
		}
		return original.apply(renamed);
	}
}
