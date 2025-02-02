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

package io.activej.ot;

import io.activej.async.function.AsyncSupplier;
import io.activej.async.process.AsyncExecutors;
import io.activej.async.service.EventloopService;
import io.activej.eventloop.Eventloop;
import io.activej.ot.exception.TransformException;
import io.activej.ot.system.OTSystem;
import io.activej.ot.uplink.OTUplink;
import io.activej.promise.Promise;
import io.activej.promise.RetryPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.activej.async.function.AsyncSuppliers.reuse;
import static io.activej.async.util.LogUtils.thisMethod;
import static io.activej.async.util.LogUtils.toLogger;
import static io.activej.common.Checks.checkNotNull;
import static io.activej.common.Checks.checkState;
import static io.activej.common.Utils.concat;
import static io.activej.common.Utils.nonNullElseEmpty;
import static io.activej.promise.Promises.sequence;
import static java.util.Collections.singletonList;

public final class OTStateManager<K, D> implements EventloopService {
	private static final Logger logger = LoggerFactory.getLogger(OTStateManager.class);

	private final Eventloop eventloop;
	private final OTSystem<D> otSystem;
	private final OTUplink<K, D, Object> uplink;

	private final AsyncSupplier<Boolean> fetch = reuse(this::doFetch);

	private OTState<D> state;

	private @Nullable K commitId;
	private @Nullable K originCommitId;

	private long level;
	private long originLevel;

	private List<D> workingDiffs = new ArrayList<>();
	private List<D> originDiffs = new ArrayList<>();

	private @Nullable Object pendingProtoCommit;
	private @Nullable List<D> pendingProtoCommitDiffs;

	private final AsyncSupplier<Void> sync = reuse(this::doSync);
	private boolean isSyncing;

	private @Nullable AsyncSupplier<Void> poll;
	private boolean isPolling;

	@SuppressWarnings("unchecked")
	private OTStateManager(Eventloop eventloop, OTSystem<D> otSystem, OTUplink<K, D, ?> uplink, OTState<D> state) {
		this.eventloop = eventloop;
		this.otSystem = otSystem;
		this.uplink = (OTUplink<K, D, Object>) uplink;
		this.state = state;
	}

	public static @NotNull <K, D> OTStateManager<K, D> create(@NotNull Eventloop eventloop, @NotNull OTSystem<D> otSystem,
			@NotNull OTUplink<K, D, ?> repository, @NotNull OTState<D> state) {
		return new OTStateManager<>(eventloop, otSystem, repository, state);
	}

	public @NotNull OTStateManager<K, D> withPoll() {
		return withPoll(Function.identity());
	}

	public @NotNull OTStateManager<K, D> withPoll(@NotNull RetryPolicy<?> pollRetryPolicy) {
		return withPoll(poll -> poll.withExecutor(AsyncExecutors.retry(pollRetryPolicy)));
	}

	public @NotNull OTStateManager<K, D> withPoll(@NotNull Function<AsyncSupplier<Void>, AsyncSupplier<Void>> pollPolicy) {
		this.poll = pollPolicy.apply(this::doPoll);
		return this;
	}

	@Override
	public @NotNull Eventloop getEventloop() {
		return eventloop;
	}

	@Override
	public @NotNull Promise<Void> start() {
		return checkout()
				.whenResult(this::poll);
	}

	@Override
	public @NotNull Promise<Void> stop() {
		poll = null;
		return isValid() ?
				sync().whenComplete(this::invalidateInternalState) :
				Promise.complete();
	}

	public @NotNull Promise<Void> checkout() {
		checkState(commitId == null);
		return uplink.checkout()
				.whenResult(checkoutData -> {
					state.init();
					apply(checkoutData.getDiffs());

					commitId = originCommitId = checkoutData.getCommitId();
					level = originLevel = checkoutData.getLevel();
				})
				.toVoid()
				.whenComplete(toLogger(logger, thisMethod(), this));
	}

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	private boolean isSyncing() {
		return isSyncing;
	}

	private boolean isPolling() {
		return isPolling;
	}

	public @NotNull Promise<Void> sync() {
		return sync.get();
	}

	/**
	 * Fetches changes from {@link #uplink}, but does not apply them. Moves <b>origin</b> commit ID forward.
	 * Always returns a promise of {@code false} if there is a pending commit.
	 *
	 * @return a {@link Boolean} promise which indicates whether or not changes have been fetched and stored,
	 * and <b>origin</b> commit ID has been moved forward
	 */
	public Promise<Boolean> fetch() {
		checkState(isValid());
		return fetch.get();
	}

	private void updateOrigin(OTUplink.FetchData<K, D> fetchData) {
		assert pendingProtoCommit == null;
		originCommitId = fetchData.getCommitId();
		originLevel = fetchData.getLevel();
		originDiffs = otSystem.squash(concat(originDiffs, fetchData.getDiffs()));
	}

	private @NotNull Promise<Void> doSync() {
		checkState(isValid());
		isSyncing = true;
		return sequence(
				this::push,
				poll == null ? this::pull : Promise::complete,
				this::commit,
				this::push)
				.whenComplete(() -> isSyncing = false)
				.whenComplete(this::poll)
				.whenComplete(toLogger(logger, thisMethod(), this));
	}

	private void poll() {
		if (poll != null && !isPolling() && pendingProtoCommit == null) {
			isPolling = true;
			poll.get()
					.async()
					.whenComplete(() -> isPolling = false)
					.whenComplete(() -> {
						if (!isSyncing()) {
							poll();
						}
					});
		}
	}

	private @NotNull Promise<Void> pull() {
		return fetch()
				.whenResult(this::rebase)
				.toVoid()
				.whenComplete(toLogger(logger, thisMethod(), this));
	}

	private @NotNull Promise<Void> doPoll() {
		if (!isValid()) return Promise.complete();
		K pollCommitId = this.originCommitId;
		return uplink.poll(pollCommitId)
				.whenResult(fetchData -> {
					if (!isSyncing() && pollCommitId == this.originCommitId) {
						updateOrigin(fetchData);
						if (pendingProtoCommit == null) {
							rebase();
						}
					}
				})
				.toVoid()
				.whenComplete(toLogger(logger, thisMethod(), this));
	}

	private Promise<Boolean> doFetch() {
		if (pendingProtoCommit != null) return Promise.of(false);

		K fetchCommitId = this.originCommitId;
		return uplink.fetch(fetchCommitId)
				.map(fetchData -> {
					if (fetchCommitId == this.originCommitId && pendingProtoCommit == null) {
						if (fetchData.getCommitId() != fetchCommitId) {
							updateOrigin(fetchData);
							return true;
						}
						return false;
					}
					return false;
				})
				.whenComplete(toLogger(logger, thisMethod(), this));
	}

	private void rebase() throws TransformException {
		assert pendingProtoCommit == null;
		if (commitId == originCommitId) return;
		logger.info("Rebasing - {} {}", commitId, originCommitId);

		TransformResult<D> transformed;
		try {
			transformed = otSystem.transform(
					otSystem.squash(workingDiffs),
					otSystem.squash(originDiffs));
		} catch (TransformException e) {
			invalidateInternalState();
			throw e;
		}

		apply(transformed.left);
		workingDiffs = new ArrayList<>(transformed.right);

		commitId = originCommitId;
		level = originLevel;
		originDiffs.clear();
	}

	private @NotNull Promise<Void> commit() {
		assert pendingProtoCommit == null;
		if (workingDiffs.isEmpty()) return Promise.complete();
		int originalSize = workingDiffs.size();
		List<D> diffs = new ArrayList<>(otSystem.squash(workingDiffs));
		return uplink.createProtoCommit(commitId, diffs, level)
				.whenResult(protoCommit -> {
					assert pendingProtoCommit == null;
					pendingProtoCommit = protoCommit;
					pendingProtoCommitDiffs = diffs;
					workingDiffs = new ArrayList<>(workingDiffs.subList(originalSize, workingDiffs.size()));
					resetOrigin();
				})
				.toVoid()
				.whenComplete(toLogger(logger, thisMethod(), this));
	}

	private @NotNull Promise<Void> push() {
		if (pendingProtoCommit == null) return Promise.complete();
		return uplink.push(pendingProtoCommit)
				.whenResult(fetchData -> {
					pendingProtoCommit = null;
					pendingProtoCommitDiffs = null;

					assert commitId == originCommitId;
					updateOrigin(fetchData);
					rebase();
				})
				.toVoid()
				.whenComplete(toLogger(logger, thisMethod(), this));
	}

	public void reset() {
		checkState(!isSyncing());
		apply(otSystem.invert(
				concat(nonNullElseEmpty(pendingProtoCommitDiffs), workingDiffs)));
		workingDiffs.clear();
		pendingProtoCommit = null;
		pendingProtoCommitDiffs = null;
		resetOrigin();
	}

	void resetOrigin() {
		originCommitId = commitId;
		originLevel = level;
		originDiffs.clear();
	}

	public void add(@NotNull D diff) {
		checkState(isValid());
		addAll(singletonList(diff));
	}

	public void addAll(@NotNull List<? extends D> diffs) {
		checkState(isValid());
		try {
			for (D diff : diffs) {
				if (!otSystem.isEmpty(diff)) {
					workingDiffs.add(diff);
					state.apply(diff);
				}
			}
		} catch (RuntimeException e) {
			invalidateInternalState();
			throw e;
		}
	}

	private void apply(List<D> diffs) {
		try {
			for (D op : diffs) {
				state.apply(op);
			}
		} catch (RuntimeException e) {
			invalidateInternalState();
			throw e;
		}
	}

	@SuppressWarnings("AssignmentToNull") // state is invalid, no further calls should be made
	public void invalidateInternalState() {
		state = null;

		commitId = null;
		originCommitId = null;
		level = 0;
		originLevel = 0;
		workingDiffs = null;
		originDiffs = null;

		pendingProtoCommit = null;
		pendingProtoCommitDiffs = null;

		poll = null;
	}

	public K getCommitId() {
		return checkNotNull(commitId, "Internal state has been invalidated");
	}

	public K getOriginCommitId() {
		return checkNotNull(originCommitId, "Internal state has been invalidated");
	}

	public OTState<D> getState() {
		return state;
	}

	public boolean isValid() {
		return commitId != null;
	}

	public boolean hasWorkingDiffs() {
		return !workingDiffs.isEmpty();
	}

	public boolean hasPendingCommits() {
		return pendingProtoCommit != null;
	}

	@Override
	public String toString() {
		return "{" +
				"revision=" + commitId +
				(originCommitId != commitId ? " origin revision=" + originCommitId : "") +
				" workingDiffs:" + (workingDiffs != null ? workingDiffs.size() : null) +
				" pendingCommits:" + (pendingProtoCommit != null) +
				"}";
	}
}
