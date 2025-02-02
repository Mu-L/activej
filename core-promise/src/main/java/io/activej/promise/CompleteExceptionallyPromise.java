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

package io.activej.promise;

import io.activej.async.callback.Callback;
import io.activej.common.collection.Try;
import io.activej.common.function.*;
import io.activej.common.recycle.Recyclers;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static io.activej.eventloop.Eventloop.getCurrentEventloop;
import static io.activej.eventloop.util.RunnableWithContext.wrapContext;

/**
 * Represents a {@code Promise} which is completed with an exception.
 */
@SuppressWarnings("unchecked")
final class CompleteExceptionallyPromise<T> implements Promise<T> {
	private final @NotNull Exception exception;

	public CompleteExceptionallyPromise(@NotNull Exception e) {
		this.exception = e;
	}

	@Override
	public boolean isComplete() {
		return true;
	}

	@Override
	public boolean isResult() {
		return false;
	}

	@Override
	public boolean isException() {
		return true;
	}

	@Override
	public T getResult() {
		return null;
	}

	@Override
	public Exception getException() {
		return exception;
	}

	@Override
	public Try<T> getTry() {
		return Try.ofException(exception);
	}

	@Override
	public <U, S extends Callback<? super T> & Promise<U>> @NotNull Promise<U> next(@NotNull S promise) {
		promise.accept(null, exception);
		return promise;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <U> @NotNull Promise<U> map(@NotNull FunctionEx<? super T, ? extends U> fn) {
		return (Promise<U>) this;
	}

	@Override
	public <U> @NotNull Promise<U> map(@NotNull BiFunctionEx<? super T, Exception, ? extends U> fn) {
		try {
			return Promise.of(fn.apply(null, exception));
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull <U> Promise<U> map(@NotNull FunctionEx<? super T, ? extends U> fn, @NotNull FunctionEx<@NotNull Exception, ? extends U> exceptionFn) {
		try {
			return Promise.of(exceptionFn.apply(exception));
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull Promise<T> mapException(@NotNull FunctionEx<@NotNull Exception, Exception> exceptionFn) {
		try {
			return Promise.ofException(exceptionFn.apply(exception));
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull <U> Promise<U> then(@NotNull FunctionEx<? super T, ? extends Promise<? extends U>> fn) {
		return (Promise<U>) this;
	}

	@Override
	public @NotNull <U> Promise<U> then(@NotNull SupplierEx<? extends Promise<? extends U>> fn) {
		return (Promise<U>) this;
	}

	@Override
	public <U> @NotNull Promise<U> then(@NotNull BiFunctionEx<? super T, Exception, ? extends Promise<? extends U>> fn) {
		try {
			return (Promise<U>) fn.apply(null, exception);
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull <U> Promise<U> then(
			@NotNull FunctionEx<? super T, ? extends Promise<? extends U>> fn,
			@NotNull FunctionEx<@NotNull Exception, ? extends Promise<? extends U>> exceptionFn) {
		try {
			return (Promise<U>) exceptionFn.apply(exception);
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull Promise<T> whenComplete(@NotNull BiConsumerEx<? super T, Exception> fn) {
		try {
			fn.accept(null, exception);
			return this;
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull Promise<T> whenComplete(@NotNull ConsumerEx<? super T> fn, @NotNull ConsumerEx<@NotNull Exception> exceptionFn) {
		try {
			exceptionFn.accept(exception);
			return this;
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull Promise<T> whenComplete(@NotNull RunnableEx action) {
		try {
			action.run();
			return this;
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull Promise<T> whenResult(ConsumerEx<? super T> fn) {
		return this;
	}

	@Override
	public @NotNull Promise<T> whenResult(@NotNull RunnableEx action) {
		return this;
	}

	@Override
	public @NotNull Promise<T> whenException(@NotNull ConsumerEx<Exception> fn) {
		try {
			fn.accept(exception);
			return this;
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull Promise<T> whenException(@NotNull RunnableEx action) {
		try {
			action.run();
			return this;
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			return Promise.ofException(ex);
		}
	}

	@Override
	public <U, V> @NotNull Promise<V> combine(@NotNull Promise<? extends U> other, @NotNull BiFunction<? super T, ? super U, ? extends V> fn) {
		other.whenResult(Recyclers::recycle);
		return (Promise<V>) this;
	}

	@Override
	public @NotNull Promise<Void> both(@NotNull Promise<?> other) {
		other.whenResult(Recyclers::recycle);
		return (Promise<Void>) this;
	}

	@Override
	public @NotNull Promise<T> either(@NotNull Promise<? extends T> other) {
		return (Promise<T>) other;
	}

	@Override
	public @NotNull Promise<T> async() {
		SettablePromise<T> result = new SettablePromise<>();
		getCurrentEventloop().post(wrapContext(result, () -> result.setException(exception)));
		return result;
	}

	@Override
	public @NotNull Promise<Try<T>> toTry() {
		return Promise.of(Try.ofException(exception));
	}

	@Override
	public @NotNull Promise<Void> toVoid() {
		return (Promise<Void>) this;
	}

	@Override
	public void run(@NotNull Callback<? super T> callback) {
		callback.accept(null, exception);
	}

	@Override
	public @NotNull CompletableFuture<T> toCompletableFuture() {
		CompletableFuture<T> future = new CompletableFuture<>();
		future.completeExceptionally(exception);
		return future;
	}
}
