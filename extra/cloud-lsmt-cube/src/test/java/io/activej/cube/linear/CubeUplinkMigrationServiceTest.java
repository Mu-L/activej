package io.activej.cube.linear;

import io.activej.aggregation.AggregationChunk;
import io.activej.aggregation.PrimaryKey;
import io.activej.aggregation.ot.AggregationDiff;
import io.activej.cube.Cube;
import io.activej.cube.IdGeneratorStub;
import io.activej.cube.ot.CubeDiff;
import io.activej.cube.ot.CubeDiffCodec;
import io.activej.cube.ot.CubeOT;
import io.activej.etl.LogDiff;
import io.activej.etl.LogDiffCodec;
import io.activej.etl.LogOT;
import io.activej.etl.LogPositionDiff;
import io.activej.eventloop.Eventloop;
import io.activej.multilog.LogFile;
import io.activej.multilog.LogPosition;
import io.activej.ot.OTCommit;
import io.activej.ot.repository.OTRepositoryMySql;
import io.activej.ot.system.OTSystem;
import io.activej.ot.uplink.OTUplink.FetchData;
import io.activej.ot.util.IdGenerator;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static io.activej.aggregation.fieldtype.FieldTypes.*;
import static io.activej.aggregation.measure.Measures.sum;
import static io.activej.common.Utils.*;
import static io.activej.cube.Cube.AggregationConfig.id;
import static io.activej.cube.TestUtils.initializeRepository;
import static io.activej.cube.linear.CubeUplinkMigrationService.createEmptyCube;
import static io.activej.promise.TestUtils.await;
import static io.activej.test.TestUtils.dataSource;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CubeUplinkMigrationServiceTest {

	public static final OTSystem<LogDiff<CubeDiff>> OT_SYSTEM = LogOT.createLogOT(CubeOT.createCubeOT());
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@ClassRule
	public static EventloopRule eventloopRule = new EventloopRule();

	private DataSource dataSource;
	private Cube cube;

	private OTRepositoryMySql<LogDiff<CubeDiff>> repo;
	private CubeUplinkMySql uplink;

	@Before
	public void setUp() throws Exception {
		dataSource = dataSource("test.properties");

		Eventloop eventloop = Eventloop.getCurrentEventloop();
		Executor executor = Executors.newCachedThreadPool();

		cube = createEmptyCube(eventloop, executor)
				.withDimension("campaign", ofInt())
				.withDimension("advertiser", ofInt())
				.withMeasure("impressions", sum(ofLong()))
				.withMeasure("clicks", sum(ofLong()))
				.withMeasure("conversions", sum(ofLong()))
				.withMeasure("revenue", sum(ofDouble()))
				.withAggregation(id("campaign")
						.withDimensions("campaign")
						.withMeasures("impressions", "clicks", "conversions", "revenue"))
				.withAggregation(id("advertiser-campaign")
						.withDimensions("advertiser", "campaign")
						.withMeasures("impressions", "clicks", "conversions", "revenue"));

		IdGenerator<Long> idGenerator = new IdGeneratorStub();
		LogDiffCodec<CubeDiff> diffCodec = LogDiffCodec.create(CubeDiffCodec.create(cube));

		repo = OTRepositoryMySql.create(eventloop, executor, dataSource, idGenerator, OT_SYSTEM, diffCodec);
		initializeRepository(repo);

		PrimaryKeyCodecs codecs = PrimaryKeyCodecs.ofCube(cube);
		uplink = CubeUplinkMySql.create(executor, dataSource, codecs)
				.withMeasuresValidator(MeasuresValidator.ofCube(cube));

		uplink.initialize();
		uplink.truncateTables();
	}

	@Test
	public void migration() throws ExecutionException, InterruptedException {
		FetchData<Long, LogDiff<CubeDiff>> checkoutData = await(uplink.checkout());
		assertEquals(0, (long) checkoutData.getCommitId());
		assertEquals(0, checkoutData.getLevel());
		assertTrue(checkoutData.getDiffs().isEmpty());

		CubeUplinkMigrationService service = new CubeUplinkMigrationService();
		service.cube = cube;

		List<LogDiff<CubeDiff>> diffs1 = singletonList(
				LogDiff.of(
						mapOf(
								"a", new LogPositionDiff(LogPosition.initial(), LogPosition.create(new LogFile("a", 12), 13)),
								"b", new LogPositionDiff(LogPosition.initial(), LogPosition.create(new LogFile("b", 23), 34))
						),
						singletonList(
								CubeDiff.of(
										mapOf(
												"campaign", AggregationDiff.of(setOf(
														AggregationChunk.create(1L, asList("clicks", "impressions"), PrimaryKey.ofArray(12), PrimaryKey.ofArray(34), 10),
														AggregationChunk.create(2L, singletonList("impressions"), PrimaryKey.ofArray(123), PrimaryKey.ofArray(345), 20)
												)),
												"advertiser-campaign", AggregationDiff.of(setOf(
														AggregationChunk.create(3L, asList("clicks", "impressions", "revenue"), PrimaryKey.ofArray(15, 654), PrimaryKey.ofArray(35, 76763), 1234),
														AggregationChunk.create(4L, singletonList("conversions"), PrimaryKey.ofArray(12, 23), PrimaryKey.ofArray(124, 543), 22)
												))
										)
								)
						)
				));

		List<LogDiff<CubeDiff>> diffs2 = singletonList(
				LogDiff.of(
						mapOf(
								"a", new LogPositionDiff(
										LogPosition.create(new LogFile("a", 12), 13),
										LogPosition.create(new LogFile("a2", 53), 1381)),
								"b", new LogPositionDiff(
										LogPosition.create(new LogFile("b", 23), 34),
										LogPosition.create(new LogFile("b4", 231), 3124)
								)
						),
						singletonList(
								CubeDiff.of(
										mapOf(
												"campaign", AggregationDiff.of(
														setOf(
																AggregationChunk.create(5L, singletonList("clicks"), PrimaryKey.ofArray(12453), PrimaryKey.ofArray(12453121), 23523),
																AggregationChunk.create(6L, asList("impressions", "clicks", "conversions", "revenue"), PrimaryKey.ofArray(1113), PrimaryKey.ofArray(34512412), 52350)
														),
														setOf(AggregationChunk.create(1L, asList("clicks", "impressions"), PrimaryKey.ofArray(12), PrimaryKey.ofArray(34), 10))),
												"advertiser-campaign", AggregationDiff.of(setOf(
														AggregationChunk.create(7L, asList("clicks", "revenue"), PrimaryKey.ofArray(1125, 53), PrimaryKey.ofArray(1422142, 653), 122134),
														AggregationChunk.create(8L, asList("conversions", "impressions"), PrimaryKey.ofArray(44, 52), PrimaryKey.ofArray(124124, 122), 65472)
												))
										)
								)
						)
				));

		push(diffs1);
		push(diffs2);

		service.migrate(dataSource, dataSource);

		checkoutData = await(uplink.checkout());
		assertEquals(1, (long) checkoutData.getCommitId());
		assertEquals(1, checkoutData.getLevel());

		List<LogDiff<CubeDiff>> expected = OT_SYSTEM.squash(concat(diffs1, diffs2));

		assertEquals(expected, checkoutData.getDiffs());
	}

	private void push(List<LogDiff<CubeDiff>> diffs) {
		OTCommit<Long, LogDiff<CubeDiff>> parent = await(repo.loadCommit(first(await(repo.getHeads()))));
		Long commitId = await(repo.createCommitId());
		await(repo.pushAndUpdateHead(OTCommit.ofCommit(0, commitId, parent.getId(), diffs, parent.getLevel())));
	}
}
