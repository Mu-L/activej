package banner;

import io.activej.common.Utils;
import io.activej.crdt.primitives.GSet;
import io.activej.eventloop.Eventloop;
import io.activej.inject.annotation.Inject;
import io.activej.launchers.crdt.rpc.CrdtRpcClientLauncher;
import io.activej.promise.Promises;
import io.activej.rpc.client.RpcClient;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static banner.BannerCommands.*;
import static banner.BannerServerLauncher.MESSAGE_TYPES;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.toList;

public final class BannerClientLauncher extends CrdtRpcClientLauncher {
	public static final int USER_IDS_SIZE = 100;
	public static final int BANNER_SIZE = 10;

	private static final ThreadLocalRandom RANDOM = ThreadLocalRandom.current();
	private static final List<Long> USER_IDS = Stream.generate(() -> RANDOM.nextLong(10 * USER_IDS_SIZE))
			.distinct()
			.limit(USER_IDS_SIZE)
			.collect(toList());

	private final Map<Long, Set<Integer>> controlMap = new TreeMap<>();

	@Inject
	Eventloop eventloop;

	@Inject
	RpcClient client;

	@Override
	protected List<Class<?>> getMessageTypes() {
		return MESSAGE_TYPES;
	}

	@Override
	protected void run() throws Exception {
		uploadBannerIds();

		long randomUserId = USER_IDS.get(RANDOM.nextInt(USER_IDS.size()));

		Set<Integer> fetchedBanners = fetchBannerIds(randomUserId);

		checkIfBannerIsSeen(randomUserId, fetchedBanners);
	}

	private void uploadBannerIds() throws Exception {
		eventloop.submit(() ->
				Promises.until(0, i -> Promises.all(USER_IDS.stream()
								.map(userId -> {
									int bannerId = RANDOM.nextInt(BANNER_SIZE) + 1;
									return client.sendRequest(new PutRequest(userId, GSet.of(bannerId)))
											.whenResult(() -> controlMap.merge(userId, singleton(bannerId), Utils::union));
								}))
								.map($ -> i + 1),
						i -> i == BANNER_SIZE / 2
				)).get();
		System.out.println("Banners are uploaded\n");
	}

	private @NotNull Set<Integer> fetchBannerIds(long randomUserId) throws Exception {
		Set<Integer> fetchedBanners = eventloop.submit(() ->
				client.<GetRequest, GetResponse>sendRequest(new GetRequest(randomUserId))
						.map(GetResponse::getBannerIds)
		).get();

		System.out.println("Fetched banners for user ID [" + randomUserId + "]: " + fetchedBanners);
		Set<Integer> localControlBannerIds = controlMap.get(randomUserId);
		System.out.println("Are banners correct? : " + localControlBannerIds.equals(fetchedBanners) + '\n');
		return fetchedBanners;
	}

	private void checkIfBannerIsSeen(long randomUserId, Set<Integer> fetchedBanners) throws Exception {
		// Banner should be seen
		int seenBannerId = fetchedBanners.stream()
				.skip(RANDOM.nextInt(fetchedBanners.size()))
				.findFirst()
				.orElseThrow(AssertionError::new);
		CompletableFuture<Boolean> shouldBeSeenFuture = eventloop.submit(() ->
				client.sendRequest(new IsBannerSeenRequest(randomUserId, seenBannerId)));
		System.out.println("Should be seen. Has banner with id '" + seenBannerId + "' been seen? : " + shouldBeSeenFuture.get());

		// Banner should not be seen
		int unseenBannerId = IntStream.range(1, BANNER_SIZE + 1)
				.filter(id -> !fetchedBanners.contains(id))
				.findAny()
				.orElseThrow(AssertionError::new);
		CompletableFuture<Boolean> shouldNotBeSeenFuture = eventloop.submit(() ->
				client.sendRequest(new IsBannerSeenRequest(randomUserId, unseenBannerId)));
		System.out.println("Should NOT be seen. Has banner with id '" + unseenBannerId + "' been seen? : " + shouldNotBeSeenFuture.get() + '\n');
	}

	public static void main(String[] args) throws Exception {
		new BannerClientLauncher().launch(args);
	}
}
