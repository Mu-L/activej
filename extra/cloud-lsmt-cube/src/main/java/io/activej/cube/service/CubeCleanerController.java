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

package io.activej.cube.service;

import io.activej.aggregation.ActiveFsChunkStorage;
import io.activej.async.function.AsyncSupplier;
import io.activej.common.Utils;
import io.activej.common.function.BiConsumerEx;
import io.activej.common.function.FunctionEx;
import io.activej.cube.exception.CubeException;
import io.activej.cube.ot.CubeDiffScheme;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.jmx.EventloopJmxBeanWithStats;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.api.attribute.JmxOperation;
import io.activej.ot.OTCommit;
import io.activej.ot.exception.GraphExhaustedException;
import io.activej.ot.reducers.DiffsReducer;
import io.activej.ot.repository.OTRepository;
import io.activej.ot.system.OTSystem;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.promise.jmx.PromiseStats;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static io.activej.async.function.AsyncSuppliers.reuse;
import static io.activej.async.util.LogUtils.Level.TRACE;
import static io.activej.async.util.LogUtils.thisMethod;
import static io.activej.async.util.LogUtils.toLogger;
import static io.activej.common.Utils.union;
import static io.activej.cube.Utils.chunksInDiffs;
import static io.activej.ot.OTAlgorithms.*;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.toSet;

public final class CubeCleanerController<K, D, C> implements EventloopJmxBeanWithStats {
	private static final Logger logger = LoggerFactory.getLogger(CubeCleanerController.class);

	public static final Duration DEFAULT_CHUNKS_CLEANUP_DELAY = Duration.ofMinutes(1);
	public static final int DEFAULT_SNAPSHOTS_COUNT = 1;
	public static final Duration DEFAULT_SMOOTHING_WINDOW = Duration.ofMinutes(5);

	private final Eventloop eventloop;

	private final OTSystem<D> otSystem;
	private final OTRepository<K, D> repository;
	private final ActiveFsChunkStorage<C> chunksStorage;

	private final CubeDiffScheme<D> cubeDiffScheme;

	private Duration freezeTimeout;

	private Duration chunksCleanupDelay = DEFAULT_CHUNKS_CLEANUP_DELAY;
	private int extraSnapshotsCount = DEFAULT_SNAPSHOTS_COUNT;

	private final PromiseStats promiseCleanup = PromiseStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final PromiseStats promiseCleanupCollectRequiredChunks = PromiseStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final PromiseStats promiseCleanupRepository = PromiseStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final PromiseStats promiseCleanupChunks = PromiseStats.create(DEFAULT_SMOOTHING_WINDOW);

	CubeCleanerController(Eventloop eventloop,
			CubeDiffScheme<D> cubeDiffScheme,
			OTRepository<K, D> repository,
			OTSystem<D> otSystem,
			ActiveFsChunkStorage<C> chunksStorage) {
		this.eventloop = eventloop;
		this.cubeDiffScheme = cubeDiffScheme;
		this.otSystem = otSystem;
		this.repository = repository;
		this.chunksStorage = chunksStorage;
	}

	public static <K, D, C> CubeCleanerController<K, D, C> create(Eventloop eventloop,
			CubeDiffScheme<D> cubeDiffScheme,
			OTRepository<K, D> repository,
			OTSystem<D> otSystem,
			ActiveFsChunkStorage<C> storage) {
		return new CubeCleanerController<>(eventloop, cubeDiffScheme, repository, otSystem, storage);
	}

	public CubeCleanerController<K, D, C> withChunksCleanupDelay(Duration chunksCleanupDelay) {
		this.chunksCleanupDelay = chunksCleanupDelay;
		return this;
	}

	public CubeCleanerController<K, D, C> withExtraSnapshotsCount(int extraSnapshotsCount) {
		this.extraSnapshotsCount = extraSnapshotsCount;
		return this;
	}

	public CubeCleanerController<K, D, C> withFreezeTimeout(Duration freezeTimeout) {
		this.freezeTimeout = freezeTimeout;
		return this;
	}

	private final AsyncSupplier<Void> cleanup = reuse(this::doCleanup);

	public Promise<Void> cleanup() {
		return cleanup.get();
	}

	private Promise<Void> doCleanup() {
		return repository.getHeads()
				.then(heads -> excludeParents(repository, otSystem, heads))
				.mapException(e -> e instanceof GraphExhaustedException ? e : new CubeException("Failed to get heads", e))
				.then(heads -> findFrozenCut(heads, eventloop.currentInstant().minus(freezeTimeout)))
				.then(this::cleanupFrozenCut)
				.then((v, e) -> {
					if (e instanceof GraphExhaustedException) return Promise.of(null);
					return Promise.of(v, e);
				})
				.whenComplete(promiseCleanup.recordStats())
				.whenComplete(toLogger(logger, thisMethod()));
	}

	private Promise<Set<K>> findFrozenCut(Set<K> heads, Instant freezeTimestamp) {
		return findCut(repository, otSystem, heads,
				commits -> commits.stream().allMatch(commit -> commit.getInstant().compareTo(freezeTimestamp) < 0))
				.mapException(e -> e instanceof GraphExhaustedException ?
						e :
						new CubeException("Failed to find frozen cut, freeze timestamp: " + freezeTimestamp, e))
				.whenComplete(toLogger(logger, thisMethod(), heads, freezeTimestamp));
	}

	private Promise<Void> cleanupFrozenCut(Set<K> frozenCut) {
		return findAllCommonParents(repository, otSystem, frozenCut)
				.then(parents -> findAnyCommonParent(repository, otSystem, parents))
				.then(checkpointNode -> repository.hasSnapshot(checkpointNode)
						.then(snapshot -> {
							if (snapshot) {
								logger.info("Snapshot already exists, skip cleanup");
								return Promise.complete();
							} else {
								return trySaveSnapshotAndCleanupChunks(checkpointNode);
							}
						}))
				.mapException(e -> e instanceof GraphExhaustedException ?
						e :
						new CubeException("Failed to cleanup frozen cut: " + Utils.toString(frozenCut), e))
				.whenComplete(toLogger(logger, thisMethod(), frozenCut));
	}

	private static class Tuple<K, D, C> {
		final Set<C> collectedChunks;
		final OTCommit<K, D> lastSnapshot;

		Tuple(Set<C> collectedChunks, OTCommit<K, D> lastSnapshot) {
			this.collectedChunks = collectedChunks;
			this.lastSnapshot = lastSnapshot;
		}
	}

	private Promise<Void> trySaveSnapshotAndCleanupChunks(K checkpointNode) {
		return checkout(repository, otSystem, checkpointNode)
				.then(checkpointDiffs -> repository.saveSnapshot(checkpointNode, checkpointDiffs)
						.then(() -> findSnapshot(singleton(checkpointNode), extraSnapshotsCount))
						.then(lastSnapshot -> {
							if (lastSnapshot.isPresent())
								return Promises.toTuple(Tuple::new,
												collectRequiredChunks(checkpointNode),
												repository.loadCommit(lastSnapshot.get()))
										.then(tuple ->
												cleanup(lastSnapshot.get(),
														union(chunksInDiffs(cubeDiffScheme, checkpointDiffs), tuple.collectedChunks),
														tuple.lastSnapshot.getInstant().minus(chunksCleanupDelay)));
							else {
								logger.info("Not enough snapshots, skip cleanup");
								return Promise.complete();
							}
						}))
				.whenComplete(toLogger(logger, thisMethod(), checkpointNode));
	}

	private Promise<Optional<K>> findSnapshot(Set<K> heads, int skipSnapshots) {
		return Promise.ofCallback(cb -> findSnapshotImpl(heads, skipSnapshots, cb));
	}

	private void findSnapshotImpl(Set<K> heads, int skipSnapshots, SettablePromise<Optional<K>> cb) {
		findParent(repository, otSystem, heads, DiffsReducer.toVoid(),
				commit -> repository.hasSnapshot(commit.getId()))
				.async()
				.whenResult(findResult -> {
					if (skipSnapshots <= 0) {
						cb.set(Optional.of(findResult.getCommit()));
					} else if (findResult.getCommitParents().isEmpty()) {
						cb.set(Optional.empty());
					} else {
						findSnapshotImpl(findResult.getCommitParents(), skipSnapshots - 1, cb);
					}
				})
				.whenException(cb::setException);
	}

	private Promise<Set<C>> collectRequiredChunks(K checkpointNode) {
		return repository.getHeads()
				.then(heads ->
						reduceEdges(repository, otSystem, heads, checkpointNode,
								DiffsReducer.of(
										new HashSet<>(),
										(Set<C> accumulatedChunks, List<? extends D> diffs) ->
												union(accumulatedChunks, chunksInDiffs(cubeDiffScheme, diffs)),
										Utils::union))
								.whenComplete(promiseCleanupCollectRequiredChunks.recordStats()))
				.map(accumulators -> accumulators.values().stream().flatMap(Collection::stream).collect(toSet()))
				.whenComplete(transform(Set::size,
						toLogger(logger, thisMethod(), checkpointNode)));
	}

	private Promise<Void> cleanup(K checkpointNode, Set<C> requiredChunks, Instant chunksCleanupTimestamp) {
		return chunksStorage.checkRequiredChunks(requiredChunks)
				.then(() -> repository.cleanup(checkpointNode)
						.whenComplete(promiseCleanupRepository.recordStats()))
				.then(() -> chunksStorage.cleanup(requiredChunks, chunksCleanupTimestamp)
						.whenComplete(promiseCleanupChunks.recordStats()))
				.whenComplete(logger.isTraceEnabled() ?
						toLogger(logger, TRACE, thisMethod(), checkpointNode, chunksCleanupTimestamp, requiredChunks) :
						toLogger(logger, thisMethod(), checkpointNode, chunksCleanupTimestamp, Utils.toString(requiredChunks)));
	}

	@JmxAttribute
	public Duration getChunksCleanupDelay() {
		return chunksCleanupDelay;
	}

	@JmxAttribute
	public void setChunksCleanupDelay(Duration chunksCleanupDelay) {
		this.chunksCleanupDelay = chunksCleanupDelay;
	}

	@JmxAttribute
	public int getExtraSnapshotsCount() {
		return extraSnapshotsCount;
	}

	@JmxAttribute
	public void setExtraSnapshotsCount(int extraSnapshotsCount) {
		this.extraSnapshotsCount = extraSnapshotsCount;
	}

	@JmxAttribute
	public Duration getFreezeTimeout() {
		return freezeTimeout;
	}

	@JmxAttribute
	public void setFreezeTimeout(Duration freezeTimeout) {
		this.freezeTimeout = freezeTimeout;
	}

	@JmxAttribute
	public PromiseStats getPromiseCleanup() {
		return promiseCleanup;
	}

	@JmxAttribute
	public PromiseStats getPromiseCleanupCollectRequiredChunks() {
		return promiseCleanupCollectRequiredChunks;
	}

	@JmxAttribute
	public PromiseStats getPromiseCleanupRepository() {
		return promiseCleanupRepository;
	}

	@JmxAttribute
	public PromiseStats getPromiseCleanupChunks() {
		return promiseCleanupChunks;
	}

	@JmxOperation
	public void cleanupNow() {
		cleanup();
	}

	@Override
	public @NotNull Eventloop getEventloop() {
		return eventloop;
	}

	private static <T, R> BiConsumerEx<R, Exception> transform(FunctionEx<? super R, ? extends T> fn, BiConsumerEx<? super T, Exception> toConsumer) {
		return (value, e) -> toConsumer.accept(value != null ? fn.apply(value) : null, e);
	}
}
