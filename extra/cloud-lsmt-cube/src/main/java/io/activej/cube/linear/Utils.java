package io.activej.cube.linear;
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

import io.activej.aggregation.ActiveFsChunkStorage;
import io.activej.aggregation.AggregationChunk;
import io.activej.async.function.AsyncSupplier;
import io.activej.cube.linear.CubeBackupController.ChunksBackupService;
import io.activej.cube.linear.CubeCleanerController.ChunksCleanerService;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

final class Utils {
	static byte[] loadResource(String name) throws IOException {
		try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
			assert stream != null;
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int size;
			while ((size = stream.read(buffer)) != -1) {
				baos.write(buffer, 0, size);
			}
			return baos.toByteArray();
		}
	}

	static void executeSqlScript(DataSource dataSource, String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			try (Statement statement = connection.createStatement()) {
				statement.execute(sql);
			}
		}
	}

	static List<String> measuresFromString(String measuresString) {
		return Arrays.stream(measuresString.split(" ")).collect(Collectors.toList());
	}

	static String measuresToString(List<String> measures) {
		return String.join(" ", measures);
	}

	static ChunksBackupService backupServiceOfStorage(ActiveFsChunkStorage<Long> storage) {
		return (revisionId, chunkIds) ->
				execute(storage, () -> storage.backup(String.valueOf(revisionId), chunkIds),
						"Failed to backup chunks on storage ");
	}

	static ChunksCleanerService cleanerServiceOfStorage(ActiveFsChunkStorage<Long> storage) {
		return new ChunksCleanerService() {
			@Override
			public void checkRequiredChunks(Set<Long> chunkIds) throws IOException {
				execute(storage, () -> storage.checkRequiredChunks(chunkIds),
						"Required chunks check failed");
			}

			@Override
			public void cleanup(Set<Long> chunkIds, Instant safePoint) throws IOException {
				execute(storage, () -> storage.cleanup(chunkIds, safePoint),
						"Failed to cleanup chunks");
			}
		};
	}

	private static void execute(ActiveFsChunkStorage<Long> storage, AsyncSupplier<Void> supplier, String errorMessage) throws IOException {
		try {
			storage.getEventloop().submit(supplier::get).get();
		} catch (InterruptedException e) {
			throw new IOException("Eventloop thread was interrupted", e);
		} catch (ExecutionException e) {
			throw new IOException(errorMessage, e);
		}
	}

	static class ChunkWithAggregationId {
		private final AggregationChunk chunk;
		private final String aggregationId;

		ChunkWithAggregationId(AggregationChunk chunk, String aggregationId) {
			this.chunk = chunk;
			this.aggregationId = aggregationId;
		}

		AggregationChunk getChunk() {
			return chunk;
		}

		String getAggregationId() {
			return aggregationId;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ChunkWithAggregationId that = (ChunkWithAggregationId) o;
			return chunk.equals(that.chunk);
		}

		@Override
		public int hashCode() {
			return Objects.hash(chunk);
		}
	}
}
