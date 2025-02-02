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

package io.activej.rpc.client.sender;

import io.activej.async.callback.Callback;
import io.activej.rpc.client.RpcClientConnectionPool;
import io.activej.rpc.hash.ShardingFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static io.activej.rpc.client.sender.RpcStrategies.NO_SENDER_AVAILABLE_EXCEPTION;

public final class RpcStrategySharding implements RpcStrategy {
	private final RpcStrategyList list;
	private final ShardingFunction<?> shardingFunction;
	private final int minActiveSubStrategies;

	private RpcStrategySharding(@NotNull ShardingFunction<?> shardingFunction, @NotNull RpcStrategyList list, int minActiveSubStrategies) {
		this.shardingFunction = shardingFunction;
		this.list = list;
		this.minActiveSubStrategies = minActiveSubStrategies;
	}

	public static RpcStrategySharding create(ShardingFunction<?> shardingFunction, RpcStrategyList list) {
		return new RpcStrategySharding(shardingFunction, list, 0);
	}

	public RpcStrategySharding withMinActiveSubStrategies(int minActiveSubStrategies) {
		return new RpcStrategySharding(shardingFunction, list, minActiveSubStrategies);
	}

	@Override
	public DiscoveryService getDiscoveryService() {
		return list.getDiscoveryService();
	}

	@Override
	public @Nullable RpcSender createSender(RpcClientConnectionPool pool) {
		List<RpcSender> subSenders = list.listOfNullableSenders(pool);
		int activeSenders = 0;
		for (RpcSender subSender : subSenders) {
			if (subSender != null) {
				activeSenders++;
			}
		}
		if (activeSenders < minActiveSubStrategies)
			return null;
		if (subSenders.size() == 0)
			return null;
		if (subSenders.size() == 1)
			return subSenders.get(0);
		return new Sender(shardingFunction, subSenders);
	}

	private static final class Sender implements RpcSender {
		private final ShardingFunction<?> shardingFunction;
		private final RpcSender[] subSenders;

		Sender(@NotNull ShardingFunction<?> shardingFunction, @NotNull List<RpcSender> senders) {
			assert !senders.isEmpty();
			this.shardingFunction = shardingFunction;
			this.subSenders = senders.toArray(new RpcSender[0]);
		}

		@SuppressWarnings("unchecked")
		@Override
		public <I, O> void sendRequest(I request, int timeout, @NotNull Callback<O> cb) {
			int shardIndex = ((ShardingFunction<Object>) shardingFunction).getShard(request);
			RpcSender sender = subSenders[shardIndex];
			if (sender != null) {
				sender.sendRequest(request, timeout, cb);
			} else {
				cb.accept(null, NO_SENDER_AVAILABLE_EXCEPTION);
			}
		}

	}
}
