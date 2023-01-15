package io.activej.cube.linear;

import io.activej.aggregation.AggregationChunkStorage;
import io.activej.aggregation.JsonCodec_ChunkId;
import io.activej.async.function.AsyncSupplier;
import io.activej.codegen.DefiningClassLoader;
import io.activej.common.ref.RefLong;
import io.activej.csp.process.frames.FrameFormat_LZ4;
import io.activej.cube.Cube;
import io.activej.cube.TestUtils;
import io.activej.cube.exception.CubeException;
import io.activej.cube.linear.CubeCleanerController.ChunksCleanerService;
import io.activej.cube.linear.OTUplink_CubeMySql.UplinkProtoCommit;
import io.activej.eventloop.Eventloop;
import io.activej.fs.FileSystem;
import io.activej.test.rules.ByteBufRule;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static io.activej.aggregation.fieldtype.FieldTypes.ofInt;
import static io.activej.aggregation.fieldtype.FieldTypes.ofLong;
import static io.activej.aggregation.measure.Measures.sum;
import static io.activej.common.exception.FatalErrorHandler.rethrow;
import static io.activej.cube.Cube.AggregationConfig.id;
import static io.activej.cube.TestUtils.initializeUplink;
import static io.activej.test.TestUtils.dataSource;

public class CubeCleanerControllerTest {

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private Eventloop eventloop;
	private Thread eventloopThread;
	private DataSource dataSource;
	private OTUplink_CubeMySql uplink;
	private AggregationChunkStorage<Long> aggregationChunkStorage;

	@Before
	public void setUp() throws Exception {
		dataSource = dataSource("test.properties");
		Path aggregationsDir = temporaryFolder.newFolder().toPath();
		Executor executor = Executors.newCachedThreadPool();

		eventloop = Eventloop.create()
				.withFatalErrorHandler(rethrow());

		eventloop.keepAlive(true);

		eventloopThread = new Thread(eventloop);
		eventloopThread.start();

		DefiningClassLoader classLoader = DefiningClassLoader.create();
		FileSystem fs = FileSystem.create(eventloop, executor, aggregationsDir);
		await(fs::start);
		aggregationChunkStorage = AggregationChunkStorage.create(eventloop, JsonCodec_ChunkId.ofLong(), AsyncSupplier.of(new RefLong(0)::inc),
				FrameFormat_LZ4.create(), fs);
		Cube cube = Cube.builder(eventloop, executor, classLoader, aggregationChunkStorage)
				.withDimension("pub", ofInt())
				.withDimension("adv", ofInt())
				.withMeasure("pubRequests", sum(ofLong()))
				.withMeasure("advRequests", sum(ofLong()))
				.withAggregation(id("pub").withDimensions("pub").withMeasures("pubRequests"))
				.withAggregation(id("adv").withDimensions("adv").withMeasures("advRequests"))
				.build();

		uplink = OTUplink_CubeMySql.create(eventloop, executor, dataSource, PrimaryKeyCodecs.ofCube(cube));
		uplink.initialize();
		uplink.truncateTables();
	}

	@After
	public void tearDown() throws Exception {
		eventloop.keepAlive(false);
		eventloopThread.join();
	}

	@Test
	public void testCleanupWithExtraSnapshotsCount() throws CubeException {
		// 1S -> 2N -> 3N -> 4S -> 5N
		initializeRepo();

		ChunksCleanerService cleanerService = ChunksCleanerService.ofReactiveAggregationChunkStorage(aggregationChunkStorage);
		CubeCleanerController cleanerController = CubeCleanerController.create(dataSource, cleanerService)
				.withChunksCleanupDelay(Duration.ofMillis(0));

		cleanerController.cleanup();
	}

	@Test
	public void testCleanupWithFreezeTimeout() throws CubeException {
		// 1S -> 2N -> 3N -> 4S -> 5N
		initializeRepo();

		ChunksCleanerService cleanerService = ChunksCleanerService.ofReactiveAggregationChunkStorage(aggregationChunkStorage);
		CubeCleanerController cleanerController = CubeCleanerController.create(dataSource, cleanerService)
				.withChunksCleanupDelay(Duration.ofSeconds(10));

		cleanerController.cleanup();
	}

	public void initializeRepo() {
		initializeUplink(uplink);

		UplinkProtoCommit proto1 = await(() -> uplink.createProtoCommit(0L, List.of(), 0));
		await(() -> uplink.push(proto1)); // 1N

		UplinkProtoCommit proto2 = await(() -> uplink.createProtoCommit(1L, List.of(), 1));
		await(() -> uplink.push(proto2)); // 2N

		UplinkProtoCommit proto3 = await(() -> uplink.createProtoCommit(2L, List.of(), 2));
		await(() -> uplink.push(proto3)); // 3N

		UplinkProtoCommit proto4 = await(() -> uplink.createProtoCommit(3L, List.of(), 3));
		await(() -> uplink.push(proto4)); // 4S

		UplinkProtoCommit proto5 = await(() -> uplink.createProtoCommit(4L, List.of(), 4));
		await(() -> uplink.push(proto5)); // 5N
	}

	private <T> T await(AsyncSupplier<T> supplier) {
		return TestUtils.asyncAwait(eventloop, supplier);
	}
}
