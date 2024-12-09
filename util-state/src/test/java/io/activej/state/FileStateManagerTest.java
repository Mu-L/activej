package io.activej.state;

import io.activej.fs.BlockingFileSystem;
import io.activej.serializer.stream.DiffStreamCodec;
import io.activej.serializer.stream.StreamInput;
import io.activej.serializer.stream.StreamOutput;
import io.activej.state.file.FileNamingScheme;
import io.activej.state.file.FileNamingSchemes;
import io.activej.state.file.FileStateManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class FileStateManagerTest {

	@Rule
	public final TemporaryFolder tmpFolder = new TemporaryFolder();

	public static final FileNamingScheme NAMING_SCHEME = FileNamingSchemes.create("", "", "", "", '-');

	private FileStateManager<Integer> manager;

	private BlockingFileSystem fileSystem;

	@Before
	public void setUp() throws Exception {
		Path storage = tmpFolder.newFolder().toPath();
		fileSystem = BlockingFileSystem.create(storage);
		fileSystem.start();

		manager = FileStateManager.<Integer>builder(fileSystem, NAMING_SCHEME)
			.withCodec(new IntegerCodec())
			.build();
	}

	@Test
	public void saveAndLoad() throws IOException {
		long revision = manager.save(100);
		//noinspection DataFlowIssue
		long lastSnapshotRevision = manager.getLastSnapshotRevision();
		Integer loaded = manager.loadSnapshot(lastSnapshotRevision);

		assertEquals(100, (int) loaded);
		assertEquals(revision, lastSnapshotRevision);
	}

	@Test
	public void saveAndLoadWithRevisions() throws IOException {
		manager = FileStateManager.<Integer>builder(fileSystem, NAMING_SCHEME)
			.withCodec(new IntegerCodec())
			.withMaxSaveDiffs(3)
			.build();

		manager.save(100);
		manager.save(101);
		manager.save(110);
		manager.save(150);
		long lastRevision = manager.save(300);

		//noinspection DataFlowIssue
		long lastSnapshotRevision = manager.getLastSnapshotRevision();
		Integer loaded = manager.loadSnapshot(lastSnapshotRevision);

		assertEquals(300, (int) loaded);
		assertEquals(lastRevision, lastSnapshotRevision);
	}

	@Test
	public void newRevision() throws IOException {
		assertEquals(1, (long) manager.newRevision());
		long newRevision = manager.newRevision();

		assertEquals(1, newRevision);
		manager.saveSnapshot(123, newRevision);

		assertEquals(2, (long) manager.newRevision());
	}

	@Test
	public void getLastSnapshotRevision() throws IOException {
		manager.saveSnapshot(12, 3L);
		manager.saveSnapshot(13, 123L);
		manager.saveSnapshot(11, 12L);
		manager.saveSnapshot(16, 56L);

		assertEquals(Long.valueOf(123), manager.getLastSnapshotRevision());
	}

	@Test
	public void getLastDiffRevision() throws IOException {
		int maxSaveDiffs = 3;
		manager = FileStateManager.<Integer>builder(fileSystem, NAMING_SCHEME)
			.withCodec(new IntegerCodec())
			.withMaxSaveDiffs(maxSaveDiffs)
			.build();

		Long revision = manager.save(100);
		manager.save(200);
		manager.save(300);
		manager.save(400);
		manager.save(500);

		Long lastDiffRevision = manager.getLastDiffRevision(revision);
		assertEquals(Long.valueOf(revision + maxSaveDiffs), lastDiffRevision);
	}

	@Test
	public void saveAndLoadSnapshot() throws IOException {
		manager.saveSnapshot(123, 10L);
		manager.saveSnapshot(345, 112L);
		manager.saveSnapshot(-3245, 99999L);

		assertEquals(123, (int) manager.loadSnapshot(10L));
		assertEquals(345, (int) manager.loadSnapshot(112L));
		assertEquals(-3245, (int) manager.loadSnapshot(99999L));
	}

	@Test
	public void saveAndLoadDiff() throws IOException {
		manager.saveDiff(100, 10L, 25, 1L);

		int integer = manager.loadDiff(25, 1L, 10L);
		assertEquals(100, integer);
	}

	@Test
	public void uploadsAreAtomic() throws IOException {
		IOException expectedException = new IOException("Failed");
		manager = FileStateManager.<Integer>builder(fileSystem, NAMING_SCHEME)
			.withEncoder((stream, item) -> {
				stream.writeInt(1); // some header
				if (item <= 100) {
					stream.writeInt(item);
				} else {
					throw expectedException;
				}
			})
			.build();

		manager.save(50);
		assertEquals(1, fileSystem.list("**").size());

		manager.save(75);
		assertEquals(2, fileSystem.list("**").size());

		IOException e = assertThrows(IOException.class, () -> manager.save(125));
		assertSame(expectedException, e);
		assertEquals(2, fileSystem.list("**").size()); // no new files are created
	}

	@Test
	public void saveArbitraryRevision() throws IOException {
		manager.save(100, 10L);
		manager.save(200, 20L);
		manager.save(150, 30L);

		assertThrows(IllegalArgumentException.class, () -> manager.save(500, 25L));

		//noinspection DataFlowIssue
		long lastSnapshotRevision = manager.getLastSnapshotRevision();
		Integer loaded = manager.loadSnapshot(lastSnapshotRevision);
		assertEquals(150, loaded.intValue());
		assertEquals(30L, lastSnapshotRevision);

		assertEquals(100, manager.loadSnapshot(10L).intValue());
	}

	@Test
	public void saveAndLoadFromWithoutDiff() throws IOException {
		manager = FileStateManager.<Integer>builder(fileSystem, FileNamingSchemes.create("", ""))
			.withCodec(new IntegerCodec())
			.build();

		manager.saveSnapshot(123, 1L);
		manager.saveSnapshot(345, 2L);
		manager.saveSnapshot(-3245, 3L);

		//noinspection DataFlowIssue
		long lastSnapshotRevision = manager.getLastSnapshotRevision();
		Integer loaded = manager.loadSnapshot(lastSnapshotRevision);
		assertEquals(-3245, loaded.intValue());
		assertEquals(3L, lastSnapshotRevision);
	}

	private static class IntegerCodec implements DiffStreamCodec<Integer> {
		@Override
		public Integer decode(StreamInput input) throws IOException {
			return input.readInt();
		}

		@Override
		public void encode(StreamOutput output, Integer item) throws IOException {
			output.writeInt(item);
		}

		@Override
		public Integer decodeDiff(StreamInput input, Integer from) throws IOException {
			return from + input.readInt();
		}

		@Override
		public void encodeDiff(StreamOutput output, Integer from, Integer to) throws IOException {
			output.writeInt(to - from);
		}
	}
}
