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
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.IntStream;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.*;

public interface OTRepository<K, D> extends OTCommitFactory<K, D> {
	Promise<Void> push(Collection<OTCommit<K, D>> commits);

	default Promise<Void> push(OTCommit<K, D> commit) {
		return push(singletonList(commit));
	}

	Promise<Void> updateHeads(Set<K> newHeads, Set<K> excludedHeads);

	default Promise<Void> pushAndUpdateHead(OTCommit<K, D> commit) {
		return push(commit)
				.then(() -> updateHeads(singleton(commit.getId()), commit.getParentIds()));
	}

	default Promise<Void> pushAndUpdateHeads(Collection<OTCommit<K, D>> commits) {
		Set<K> parents = commits.stream()
				.flatMap(commit -> commit.getParentIds().stream())
				.collect(toSet());
		Set<K> heads = commits.stream()
				.map(OTCommit::getId)
				.filter(commit -> !parents.contains(commit)).collect(toSet());
		return push(commits)
				.then(() -> updateHeads(heads, parents));
	}

	@NotNull
	default Promise<Long> getLevel(@NotNull K commitId) {
		return loadCommit(commitId)
				.map(OTCommit::getLevel);
	}

	@NotNull
	default Promise<Map<K, Long>> getLevels(@NotNull Set<K> commitIds) {
		ArrayList<K> ids = new ArrayList<>(commitIds);
		return Promises.toList(ids.stream().map(this::getLevel))
				.map(list -> IntStream.range(0, ids.size()).boxed().collect(toMap(ids::get, list::get)));
	}

	@NotNull
	default Promise<Set<K>> getHeads() {
		return getHeadCommits()
				.map(headCommits -> headCommits.stream().map(OTCommit::getId).collect(toSet()));
	}

	@NotNull
	default Promise<Collection<OTCommit<K, D>>> getHeadCommits() {
		return getAllHeadCommits()
				.map(allHeadCommits -> {
					int maxEpoch = allHeadCommits.stream().mapToInt(OTCommit::getEpoch).max().orElse(0);
					return allHeadCommits.stream().filter(commit -> commit.getEpoch() == maxEpoch).collect(toList());
				});
	}

	@NotNull
	Promise<Set<K>> getAllHeads();

	@NotNull
	default Promise<Collection<OTCommit<K, D>>> getAllHeadCommits() {
		return getAllHeads()
				.then(allHeads -> Promises.toList(allHeads.stream().map(this::loadCommit)));
	}

	@NotNull
	default AsyncSupplier<Set<K>> pollHeads() {
		return this::getHeads;
	}

	@NotNull
	Promise<Boolean> hasCommit(@NotNull K revisionId);

	@NotNull
	Promise<OTCommit<K, D>> loadCommit(@NotNull K revisionId);

	@NotNull
	default Promise<Boolean> hasSnapshot(@NotNull K revisionId) {
		return loadSnapshot(revisionId).map(Optional::isPresent);
	}

	@NotNull
	Promise<Optional<List<D>>> loadSnapshot(@NotNull K revisionId);

	@NotNull
	Promise<Void> saveSnapshot(@NotNull K revisionId, @NotNull List<D> diffs);

}
