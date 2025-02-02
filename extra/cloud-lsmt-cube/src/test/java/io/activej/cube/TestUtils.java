package io.activej.cube;

import io.activej.aggregation.AggregationChunkStorage;
import io.activej.async.function.AsyncSupplier;
import io.activej.cube.linear.CubeUplinkMySql;
import io.activej.cube.ot.CubeDiff;
import io.activej.etl.LogDiff;
import io.activej.etl.LogOTProcessor;
import io.activej.eventloop.Eventloop;
import io.activej.ot.OTCommit;
import io.activej.ot.OTState;
import io.activej.ot.OTStateManager;
import io.activej.ot.repository.OTRepositoryMySql;
import org.junit.function.ThrowingRunnable;

import java.util.concurrent.ExecutionException;

import static io.activej.promise.TestUtils.await;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toSet;

public final class TestUtils {

	public static void initializeUplink(CubeUplinkMySql uplink) {
		noFail(() -> {
			uplink.initialize();
			uplink.truncateTables();
		});
	}

	public static void initializeRepository(OTRepositoryMySql<LogDiff<CubeDiff>> repository) {
		noFail(() -> {
			repository.initialize();
			repository.truncateTables();
		});
		Long id = await(repository.createCommitId());
		await(repository.pushAndUpdateHead(OTCommit.ofRoot(id)));
		await(repository.saveSnapshot(id, emptyList()));
	}

	public static <T> void runProcessLogs(AggregationChunkStorage<Long> aggregationChunkStorage, OTStateManager<Long, LogDiff<CubeDiff>> logCubeStateManager, LogOTProcessor<T, CubeDiff> logOTProcessor) {
		LogDiff<CubeDiff> logDiff = await(logOTProcessor.processLog());
		await(aggregationChunkStorage
				.finish(logDiff.diffs().flatMap(CubeDiff::addedChunks).map(id -> (long) id).collect(toSet())));
		logCubeStateManager.add(logDiff);
		await(logCubeStateManager.sync());
	}

	public static final OTState<CubeDiff> STUB_CUBE_STATE = new OTState<CubeDiff>() {
		@Override
		public void init() {
		}

		@Override
		public void apply(CubeDiff op) {
		}
	};

	public static <T> T asyncAwait(Eventloop eventloop, AsyncSupplier<T> supplier) {
		try {
			return eventloop.submit(supplier::get).get();
		} catch (InterruptedException | ExecutionException e) {
			throw new AssertionError(e);
		}
	}

	public static void noFail(ThrowingRunnable runnable) {
		try {
			runnable.run();
		} catch (Throwable e) {
			throw new AssertionError(e);
		}
	}
}
