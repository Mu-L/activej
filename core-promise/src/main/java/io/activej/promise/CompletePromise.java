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
 * Represents a completed {@code Promise} with a result of unspecified type.
 */
@SuppressWarnings("unchecked")
public abstract class CompletePromise<T> implements Promise<T> {
	static {
		Recyclers.register(CompletePromise.class, promise -> Recyclers.recycle(promise.getResult()));
	}

	@Override
	public final boolean isComplete() {
		return true;
	}

	@Override
	public final boolean isResult() {
		return true;
	}

	@Override
	public final boolean isException() {
		return false;
	}

	@Override
	abstract public T getResult();

	@Override
	public final Exception getException() {
		return null;
	}

	@Override
	public Try<T> getTry() {
		return Try.of(getResult());
	}

	@NotNull
	@Override
	public final <U, S extends Callback<? super T> & Promise<U>> Promise<U> next(@NotNull S promise) {
		promise.accept(getResult(), null);
		return promise;
	}

	@NotNull
	@Override
	public final <U> Promise<U> map(@NotNull FunctionEx<? super T, ? extends U> fn) {
		try {
			return Promise.of(fn.apply(getResult()));
		} catch (Exception ex) {
			if (ex instanceof RuntimeException) {
				getCurrentEventloop().recordFatalError(ex, this);
			}
			return Promise.ofException(ex);
		}
	}

	@NotNull
	@Override
	public final <U> Promise<U> map(@NotNull BiFunctionEx<? super T, Exception, ? extends U> fn) {
		try {
			return Promise.of(fn.apply(getResult(), null));
		} catch (Exception ex) {
			if (ex instanceof RuntimeException) {
				getCurrentEventloop().recordFatalError(ex, this);
			}
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull <U> Promise<U> map(@NotNull FunctionEx<? super T, ? extends U> fn, @NotNull FunctionEx<@NotNull Exception, ? extends U> exceptionFn) {
		try {
			return Promise.of(fn.apply(getResult()));
		} catch (Exception ex) {
			if (ex instanceof RuntimeException) {
				getCurrentEventloop().recordFatalError(ex, this);
			}
			return Promise.ofException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	@NotNull
	@Override
	public final <U> Promise<U> then(@NotNull FunctionEx<? super T, ? extends Promise<? extends U>> fn) {
		try {
			return (Promise<U>) fn.apply(getResult());
		} catch (Exception ex) {
			if (ex instanceof RuntimeException) {
				getCurrentEventloop().recordFatalError(ex, this);
			}
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull <U> Promise<U> then(@NotNull SupplierEx<? extends Promise<? extends U>> fn) {
		try {
			return (Promise<U>) fn.get();
		} catch (Exception ex) {
			if (ex instanceof RuntimeException) {
				getCurrentEventloop().recordFatalError(ex, this);
			}
			return Promise.ofException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	@NotNull
	@Override
	public final <U> Promise<U> then(@NotNull BiFunctionEx<? super T, Exception, ? extends Promise<? extends U>> fn) {
		try {
			return (Promise<U>) fn.apply(getResult(), null);
		} catch (Exception ex) {
			if (ex instanceof RuntimeException) {
				getCurrentEventloop().recordFatalError(ex, this);
			}
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull Promise<T> whenComplete(@NotNull BiConsumerEx<? super T, Exception> action) {
		try {
			action.accept(getResult(), null);
			return this;
		} catch (Exception ex) {
			if (ex instanceof RuntimeException) {
				getCurrentEventloop().recordFatalError(ex, this);
			}
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull Promise<T> whenComplete(@NotNull RunnableEx action) {
		try {
			action.run();
			return this;
		} catch (Exception ex) {
			if (ex instanceof RuntimeException) {
				getCurrentEventloop().recordFatalError(ex, this);
			}
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull Promise<T> whenResult(ConsumerEx<? super T> action) {
		try {
			action.accept(getResult());
			return this;
		} catch (Exception ex) {
			if (ex instanceof RuntimeException) {
				getCurrentEventloop().recordFatalError(ex, this);
			}
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull Promise<T> whenResult(@NotNull RunnableEx action) {
		try {
			action.run();
			return this;
		} catch (Exception ex) {
			if (ex instanceof RuntimeException) {
				getCurrentEventloop().recordFatalError(ex, this);
			}
			return Promise.ofException(ex);
		}
	}

	@Override
	public @NotNull Promise<T> whenException(@NotNull ConsumerEx<Exception> action) {
		return this;
	}

	@Override
	public @NotNull Promise<T> whenException(@NotNull RunnableEx action) {
		return this;
	}

	@NotNull
	@Override
	public final <U, V> Promise<V> combine(@NotNull Promise<? extends U> other, @NotNull BiFunction<? super T, ? super U, ? extends V> fn) {
		return (Promise<V>) other
				.map(otherResult -> fn.apply(this.getResult(), otherResult))
				.whenException(() -> Recyclers.recycle(this.getResult()));
	}

	@NotNull
	@Override
	public final Promise<Void> both(@NotNull Promise<?> other) {
		Recyclers.recycle(getResult());
		return other.map(AbstractPromise::recycleToVoid);
	}

	@NotNull
	@Override
	public final Promise<T> either(@NotNull Promise<? extends T> other) {
		other.whenResult(Recyclers::recycle);
		return this;
	}

	@NotNull
	@Override
	public final Promise<T> async() {
		SettablePromise<T> result = new SettablePromise<>();
		getCurrentEventloop().post(wrapContext(result, () -> result.set(getResult())));
		return result;
	}

	@NotNull
	@Override
	public final Promise<Try<T>> toTry() {
		return Promise.of(Try.of(getResult()));
	}

	@NotNull
	@Override
	public final Promise<Void> toVoid() {
		return Promise.complete();
	}

	@Override
	public void run(@NotNull Callback<? super T> action) {
		action.accept(getResult(), null);
	}

	@NotNull
	@Override
	public final CompletableFuture<T> toCompletableFuture() {
		return CompletableFuture.completedFuture(getResult());
	}
}
