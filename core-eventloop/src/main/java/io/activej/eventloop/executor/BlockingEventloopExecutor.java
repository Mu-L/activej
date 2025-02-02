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

package io.activej.eventloop.executor;

import io.activej.async.callback.AsyncComputation;
import io.activej.common.function.RunnableEx;
import io.activej.eventloop.Eventloop;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An implementation of the {@link EventloopExecutor} which posts
 * only some tasks at a time to some underlying {@link Eventloop},
 * blocking when the queue is filled until some task completes and
 * frees place for a new ones.
 */
public final class BlockingEventloopExecutor implements EventloopExecutor {
	private final Eventloop eventloop;
	private final Lock lock = new ReentrantLock();
	private final Condition notFull = lock.newCondition();
	private final AtomicInteger tasks = new AtomicInteger();

	private final int limit;

	// region builders
	private BlockingEventloopExecutor(Eventloop eventloop, int limit) {
		this.eventloop = eventloop;
		this.limit = limit;
	}

	public static BlockingEventloopExecutor create(Eventloop eventloop, int limit) {
		return new BlockingEventloopExecutor(eventloop, limit);
	}
	// endregion

	public int getLimit() {
		return limit;
	}

	private void post(Runnable runnable) throws InterruptedException {
		lock.lock();
		try {
			while (tasks.get() > limit) {
				notFull.await();
			}
			tasks.incrementAndGet();
			eventloop.execute(runnable);
		} finally {
			lock.unlock();
		}
	}

	private void post(Runnable runnable, CompletableFuture<?> future) {
		try {
			post(runnable);
		} catch (InterruptedException e) {
			future.completeExceptionally(e);
		}
	}

	private void complete() {
		lock.lock();
		try {
			tasks.decrementAndGet();
			notFull.signal();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void execute(@NotNull Runnable runnable) {
		try {
			post(() -> {
				try {
					runnable.run();
				} finally {
					complete();
				}
			});
		} catch (InterruptedException ignored) {
		}
	}

	@Override
	public @NotNull CompletableFuture<Void> submit(@NotNull RunnableEx computation) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		post(() -> {
			try {
				computation.run();
			} catch (RuntimeException ex) {
				throw ex;
			} catch (Exception ex) {
				future.completeExceptionally(ex);
				return;
			} finally {
				complete();
			}
			future.complete(null);
		}, future);
		return future;
	}

	@Override
	public <T> @NotNull CompletableFuture<T> submit(AsyncComputation<? extends T> computation) {
		CompletableFuture<T> future = new CompletableFuture<>();
		post(() -> {
			try {
				computation.run((result, e) -> {
					if (e == null) {
						future.complete(result);
					} else {
						future.completeExceptionally(e);
					}
				});
			} catch (RuntimeException ex) {
				throw ex;
			} catch (Exception ex) {
				future.completeExceptionally(ex);
			} finally {
				complete();
			}
		}, future);
		return future;
	}

}
