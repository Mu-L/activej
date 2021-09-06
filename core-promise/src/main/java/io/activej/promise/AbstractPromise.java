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
import io.activej.common.ApplicationSettings;
import io.activej.common.Checks;
import io.activej.common.collection.Try;
import io.activej.common.function.*;
import io.activej.common.recycle.Recyclers;
import org.jetbrains.annotations.Async;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static io.activej.common.Checks.checkState;
import static io.activej.eventloop.Eventloop.getCurrentEventloop;
import static io.activej.eventloop.util.RunnableWithContext.wrapContext;

@SuppressWarnings({"unchecked", "WeakerAccess", "unused"})
abstract class AbstractPromise<T> implements Promise<T> {
	static {
		Recyclers.register(AbstractPromise.class, promise -> promise.whenResult(Recyclers::recycle));
	}

	private static final boolean CHECK = Checks.isEnabled(AbstractPromise.class);

	private static final Object PROMISE_NOT_SET = new Object();
	private static final boolean RESET_CALLBACKS = ApplicationSettings.getBoolean(AbstractPromise.class, "resetCallbacks", false);

	protected T result = (T) PROMISE_NOT_SET;

	@Nullable
	protected Exception exception;

	@Nullable
	protected Callback<? super T> next;

	public void reset() {
		this.result = (T) PROMISE_NOT_SET;
		this.exception = null;
		this.next = null;
	}

	public void resetCallbacks() {
		this.next = null;
	}

	@Override
	public final boolean isComplete() {
		return result != PROMISE_NOT_SET;
	}

	@Override
	public final boolean isResult() {
		return result != PROMISE_NOT_SET && exception == null;
	}

	@Override
	public final boolean isException() {
		return exception != null;
	}

	@Override
	public T getResult() {
		return result != PROMISE_NOT_SET ? result : null;
	}

	@Override
	public Exception getException() {
		return exception;
	}

	@Override
	public Try<T> getTry() {
		if (isResult()) return Try.of(result);
		if (isException()) return Try.ofException(exception);
		return null;
	}

	protected void complete(@Nullable T value, @Nullable Exception e) {
		if (CHECK) checkState(!isComplete(), "Promise has already been completed");
		if (e == null) {
			complete(value);
		} else {
			completeExceptionally(e);
		}
	}

	@Async.Execute
	protected void complete(@Nullable T value) {
		if (CHECK) checkState(!isComplete(), "Promise has already been completed");
		result = value;
		if (next != null) {
			next.accept(value, null);
			if (RESET_CALLBACKS) {
				next = null;
			}
		}
	}

	@Async.Execute
	protected void completeExceptionally(@Nullable Exception e) {
		if (CHECK) checkState(!isComplete(), "Promise has already been completed");
		result = null;
		exception = e;
		if (next != null) {
			next.accept(null, e);
			if (RESET_CALLBACKS) {
				next = null;
			}
		}
	}

	protected boolean tryComplete(@Nullable T value, @Nullable Exception e) {
		if (!isComplete()) {
			complete(value, e);
			return true;
		}
		return false;
	}

	protected boolean tryComplete(@Nullable T value) {
		if (!isComplete()) {
			complete(value);
			return true;
		}
		return false;
	}

	protected boolean tryCompleteExceptionally(@NotNull Exception e) {
		if (!isComplete()) {
			completeExceptionally(e);
			return true;
		}
		return false;
	}

	@NotNull
	@Override
	public <U, P extends Callback<? super T> & Promise<U>> Promise<U> next(@Async.Schedule @NotNull P promise) {
		if (isComplete()) {
			promise.accept(result, exception);
			return promise;
		}
		subscribe(promise);
		return promise;
	}

	@Async.Schedule
	protected void subscribe(@NotNull Callback<? super T> callback) {
		if (CHECK) checkState(!isComplete(), "Promise has already been completed");
		if (next == null) {
			next = callback;
		} else if (next instanceof CallbackList) {
			((CallbackList<T>) next).add(callback);
		} else {
			next = new CallbackList<>(next, callback);
		}
	}

	@NotNull
	@Override
	public <U> Promise<U> map(@NotNull FunctionEx<? super T, ? extends U> fn) {
		if (isComplete()) {
			try {
				return isResult() ? Promise.of(fn.apply(result)) : (Promise<U>) this;
			} catch (Exception ex) {
				if (ex instanceof RuntimeException) {
					getCurrentEventloop().recordFatalError(ex, this);
				}
				return Promise.ofException(ex);
			}
		}
		NextPromise<T, U> resultPromise = new NextPromise<T, U>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				if (e == null) {
					U newResult;
					try {
						newResult = fn.apply(result);
					} catch (Exception ex) {
						if (ex instanceof RuntimeException) {
							getCurrentEventloop().recordFatalError(ex, this);
						}
						completeExceptionally(ex);
						return;
					}
					complete(newResult);
				} else {
					completeExceptionally(e);
				}
			}

			@Override
			public String describe() {
				return ".map(" + formatToString(fn) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@NotNull
	@Override
	public <U> Promise<U> map(@NotNull BiFunctionEx<? super T, Exception, ? extends U> fn) {
		if (isComplete()) {
			try {
				return Promise.of(fn.apply(result, exception));
			} catch (Exception ex) {
				if (ex instanceof RuntimeException) {
					getCurrentEventloop().recordFatalError(ex, this);
				}
				return Promise.ofException(ex);
			}
		}
		NextPromise<T, U> resultPromise = new NextPromise<T, U>() {
			@Override
			public void accept(T result, Exception e) {
				U newResult;
				try {
					newResult = fn.apply(result, e);
				} catch (Exception ex) {
					if (ex instanceof RuntimeException) {
						getCurrentEventloop().recordFatalError(ex, this);
					}
					completeExceptionally(ex);
					return;
				}
				complete(newResult);
			}

			@Override
			public String describe() {
				return ".map(" + formatToString(fn) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@Override
	public @NotNull <U> Promise<U> map(@NotNull FunctionEx<? super T, ? extends U> fn, @NotNull FunctionEx<@NotNull Exception, ? extends U> exceptionFn) {
		if (isComplete()) {
			try {
				return Promise.of(exception == null ? fn.apply(result) : exceptionFn.apply(exception));
			} catch (Exception ex) {
				if (ex instanceof RuntimeException) {
					getCurrentEventloop().recordFatalError(ex, this);
				}
				return Promise.ofException(ex);
			}
		}
		NextPromise<T, U> resultPromise = new NextPromise<T, U>() {
			@Override
			public void accept(T result, Exception e) {
				U newResult;
				try {
					newResult = e == null ? fn.apply(result) : exceptionFn.apply(e);
				} catch (Exception ex) {
					if (ex instanceof RuntimeException) {
						getCurrentEventloop().recordFatalError(ex, this);
					}
					completeExceptionally(ex);
					return;
				}
				complete(newResult);
			}

			@Override
			public String describe() {
				return ".map(" + formatToString(fn) + ", " + formatToString(exceptionFn) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@Override
	public @NotNull <U> Promise<U> then(@NotNull SupplierEx<? extends Promise<? extends U>> fn) {
		if (isComplete()) {
			try {
				return isResult() ? (Promise<U>) fn.get() : (Promise<U>) this;
			} catch (Exception ex) {
				if (ex instanceof RuntimeException) {
					getCurrentEventloop().recordFatalError(ex, this);
				}
				return Promise.ofException(ex);
			}
		}
		NextPromise<T, U> resultPromise = new NextPromise<T, U>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				if (e == null) {
					Promise<? extends U> promise;
					try {
						promise = fn.get();
					} catch (Exception ex) {
						if (ex instanceof RuntimeException) {
							getCurrentEventloop().recordFatalError(ex, this);
						}
						completeExceptionally(ex);
						return;
					}
					promise.run(this::complete);
				} else {
					completeExceptionally(e);
				}
			}

			@Override
			public String describe() {
				return ".then(" + formatToString(fn) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@NotNull
	@Override
	public <U> Promise<U> then(@NotNull FunctionEx<? super T, ? extends Promise<? extends U>> fn) {
		if (isComplete()) {
			try {
				return isResult() ? (Promise<U>) fn.apply(result) : (Promise<U>) this;
			} catch (Exception ex) {
				if (ex instanceof RuntimeException) {
					getCurrentEventloop().recordFatalError(ex, this);
				}
				return Promise.ofException(ex);
			}
		}
		NextPromise<T, U> resultPromise = new NextPromise<T, U>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				if (e == null) {
					Promise<? extends U> promise;
					try {
						promise = fn.apply(result);
					} catch (Exception ex) {
						if (ex instanceof RuntimeException) {
							getCurrentEventloop().recordFatalError(ex, this);
						}
						completeExceptionally(ex);
						return;
					}
					promise.run(this::complete);
				} else {
					completeExceptionally(e);
				}
			}

			@Override
			public String describe() {
				return ".then(" + formatToString(fn) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@NotNull
	@Override
	public <U> Promise<U> then(@NotNull BiFunctionEx<? super T, Exception, ? extends Promise<? extends U>> fn) {
		if (isComplete()) {
			try {
				return (Promise<U>) fn.apply(result, exception);
			} catch (Exception ex) {
				if (ex instanceof RuntimeException) {
					getCurrentEventloop().recordFatalError(ex, this);
				}
				return Promise.ofException(ex);
			}
		}
		NextPromise<T, U> resultPromise = new NextPromise<T, U>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				if (e == null) {
					Promise<? extends U> promise;
					try {
						promise = fn.apply(result, null);
					} catch (Exception ex) {
						if (ex instanceof RuntimeException) {
							getCurrentEventloop().recordFatalError(ex, this);
						}
						completeExceptionally(ex);
						return;
					}
					promise.run(this::complete);
				} else {
					Promise<? extends U> promise;
					try {
						promise = fn.apply(null, e);
					} catch (Exception ex) {
						if (ex instanceof RuntimeException) {
							getCurrentEventloop().recordFatalError(ex, this);
						}
						completeExceptionally(ex);
						return;
					}
					promise.run(this::complete);
				}
			}

			@Override
			public String describe() {
				return ".then(" + formatToString(fn) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@Override
	public @NotNull Promise<T> whenComplete(@NotNull BiConsumerEx<? super T, Exception> action) {
		if (isComplete()) {
			try {
				action.accept(result, exception);
			} catch (Exception ex) {
				if (ex instanceof RuntimeException) {
					getCurrentEventloop().recordFatalError(ex, this);
				}
				return Promise.ofException(ex);
			}
			return this;
		}
		NextPromise<T, T> resultPromise = new NextPromise<T, T>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				try {
					action.accept(result, e);
				} catch (Exception ex) {
					if (ex instanceof RuntimeException) {
						getCurrentEventloop().recordFatalError(ex, this);
					}
					completeExceptionally(ex);
					return;
				}
				complete(result, e);
			}

			@Override
			public String describe() {
				return ".whenComplete(" + formatToString(action) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@Override
	public @NotNull Promise<T> whenComplete(@NotNull RunnableEx action) {
		if (isComplete()) {
			try {
				action.run();
			} catch (Exception ex) {
				if (ex instanceof RuntimeException) {
					getCurrentEventloop().recordFatalError(ex, this);
				}
				return Promise.ofException(ex);
			}
			return this;
		}
		NextPromise<T, T> resultPromise = new NextPromise<T, T>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				try {
					action.run();
				} catch (Exception ex) {
					if (ex instanceof RuntimeException) {
						getCurrentEventloop().recordFatalError(ex, this);
					}
					completeExceptionally(ex);
					return;
				}
				complete(result, e);
			}

			@Override
			public String describe() {
				return ".whenComplete(" + formatToString(action) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@NotNull
	@Override
	public Promise<T> whenResult(ConsumerEx<? super T> action) {
		if (isComplete()) {
			if (isResult()) {
				try {
					action.accept(result);
				} catch (Exception ex) {
					if (ex instanceof RuntimeException) {
						getCurrentEventloop().recordFatalError(ex, this);
					}
					return Promise.ofException(ex);
				}
			}
			return this;
		}
		NextPromise<T, T> resultPromise = new NextPromise<T, T>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				if (e == null) {
					try {
						action.accept(result);
					} catch (Exception ex) {
						if (ex instanceof RuntimeException) {
							getCurrentEventloop().recordFatalError(ex, this);
						}
						completeExceptionally(ex);
						return;
					}
					complete(result);
				} else {
					completeExceptionally(e);
				}
			}

			@Override
			public String describe() {
				return ".whenResult(" + formatToString(action) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@Override
	public @NotNull Promise<T> whenResult(@NotNull RunnableEx action) {
		if (isComplete()) {
			if (isResult()) {
				try {
					action.run();
				} catch (Exception ex) {
					if (ex instanceof RuntimeException) {
						getCurrentEventloop().recordFatalError(ex, this);
					}
					return Promise.ofException(ex);
				}
			}
			return this;
		}
		NextPromise<T, T> resultPromise = new NextPromise<T, T>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				if (e == null) {
					try {
						action.run();
					} catch (Exception ex) {
						if (ex instanceof RuntimeException) {
							getCurrentEventloop().recordFatalError(ex, this);
						}
						completeExceptionally(ex);
						return;
					}
					complete(result);
				} else {
					completeExceptionally(e);
				}
			}

			@Override
			public String describe() {
				return ".whenResult(" + formatToString(action) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@Override
	public @NotNull Promise<T> whenException(@NotNull ConsumerEx<Exception> action) {
		if (isComplete()) {
			if (isException()) {
				try {
					action.accept(exception);
				} catch (Exception ex) {
					if (ex instanceof RuntimeException) {
						getCurrentEventloop().recordFatalError(ex, this);
					}
					return Promise.ofException(ex);
				}
			}
			return this;
		}
		NextPromise<T, T> resultPromise = new NextPromise<T, T>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				if (e == null) {
					complete(result);
				} else {
					try {
						action.accept(e);
					} catch (Exception ex) {
						if (ex instanceof RuntimeException) {
							getCurrentEventloop().recordFatalError(ex, this);
						}
						completeExceptionally(ex);
						return;
					}
					completeExceptionally(e);
				}
			}

			@Override
			public String describe() {
				return ".whenException(" + formatToString(action) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@Override
	public @NotNull Promise<T> whenException(@NotNull RunnableEx action) {
		if (isComplete()) {
			if (isException()) {
				try {
					action.run();
				} catch (Exception ex) {
					if (ex instanceof RuntimeException) {
						getCurrentEventloop().recordFatalError(ex, this);
					}
					return Promise.ofException(ex);
				}
			}
			return this;
		}
		NextPromise<T, T> resultPromise = new NextPromise<T, T>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				if (e == null) {
					complete(result);
				} else {
					try {
						action.run();
					} catch (Exception ex) {
						if (ex instanceof RuntimeException) {
							getCurrentEventloop().recordFatalError(ex, this);
						}
						completeExceptionally(ex);
						return;
					}
					completeExceptionally(e);
				}
			}

			@Override
			public String describe() {
				return ".whenException(" + formatToString(action) + ')';
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	private static final Object NO_RESULT = new Object();

	@NotNull
	@Override
	public Promise<T> async() {
		if (isComplete()) {
			SettablePromise<T> promise = new SettablePromise<>();
			getCurrentEventloop().post(wrapContext(promise,
					exception == null ?
							() -> promise.set(result) :
							() -> promise.setException(exception)));
			return promise;
		}
		return this;
	}

	@NotNull
	@Override
	public <U, V> Promise<V> combine(@NotNull Promise<? extends U> other, @NotNull BiFunction<? super T, ? super U, ? extends V> fn) {
		if (this.isComplete()) {
			if (this.isResult()) {
				return (Promise<V>) other
						.map(otherResult -> fn.apply(this.getResult(), otherResult))
						.whenException(() -> Recyclers.recycle(this.getResult()));
			}
			other.whenResult(Recyclers::recycle);
			return (Promise<V>) this;
		}
		if (other.isComplete()) {
			if (other.isResult()) {
				return (Promise<V>) this
						.map(result -> fn.apply(result, other.getResult()))
						.whenException(() -> Recyclers.recycle(other.getResult()));
			}
			this.whenResult(Recyclers::recycle);
			return (Promise<V>) other;
		}
		PromiseCombine<T, V, U> resultPromise = new PromiseCombine<>(fn);
		other.run(resultPromise::acceptOther);
		subscribe(resultPromise);
		return resultPromise;
	}

	@SuppressWarnings({"unchecked", "WeakerAccess"})
	private static class PromiseCombine<T, V, U> extends NextPromise<T, V> {
		final BiFunction<? super T, ? super U, ? extends V> fn;
		@Nullable
		T thisResult = (T) NO_RESULT;
		@Nullable
		U otherResult = (U) NO_RESULT;

		PromiseCombine(BiFunction<? super T, ? super U, ? extends V> fn) {
			this.fn = fn;
		}

		@Override
		public void accept(T result, @Nullable Exception e) {
			if (e == null) {
				if (otherResult != NO_RESULT) {
					onBothResults(result, otherResult);
				} else {
					thisResult = result;
				}
			} else {
				onAnyException(e);
			}
		}

		public void acceptOther(U result, @Nullable Exception e) {
			if (e == null) {
				if (thisResult != NO_RESULT) {
					onBothResults(thisResult, result);
				} else {
					otherResult = result;
				}
			} else {
				onAnyException(e);
			}
		}

		void onBothResults(@Nullable T thisResult, @Nullable U otherResult) {
			tryComplete(fn.apply(thisResult, otherResult));
		}

		void onAnyException(@NotNull Exception e) {
			if (tryCompleteExceptionally(e)) {
				if (thisResult != NO_RESULT) Recyclers.recycle(thisResult);
				if (otherResult != NO_RESULT) Recyclers.recycle(otherResult);
			}
		}

		@Override
		public String describe() {
			return ".combine(" + formatToString(fn) + ')';
		}
	}

	@NotNull
	@Override
	public Promise<Void> both(@NotNull Promise<?> other) {
		if (this.isComplete()) {
			if (this.isResult()) {
				Recyclers.recycle(this.getResult());
				return other.map(AbstractPromise::recycleToVoid);
			}
			other.whenResult(Recyclers::recycle);
			return (Promise<Void>) this;
		}
		if (other.isComplete()) {
			if (other.isResult()) {
				Recyclers.recycle(other.getResult());
				return this.map(AbstractPromise::recycleToVoid);
			}
			this.whenResult(Recyclers::recycle);
			return (Promise<Void>) other;
		}
		PromiseBoth<Object> resultPromise = new PromiseBoth<>();
		other.run(resultPromise);
		subscribe(resultPromise);
		return resultPromise;
	}

	@Nullable
	protected static Void recycleToVoid(Object item) {
		Recyclers.recycle(item);
		return null;
	}

	private static class PromiseBoth<T> extends NextPromise<T, Void> {
		int counter = 2;

		@Override
		public void accept(T result, @Nullable Exception e) {
			if (e == null) {
				Recyclers.recycle(result);
				if (--counter == 0) {
					complete(null);
				}
			} else {
				tryCompleteExceptionally(e);
			}
		}

		@Override
		public String describe() {
			return ".both()";
		}
	}

	@NotNull
	@Override
	public Promise<T> either(@NotNull Promise<? extends T> other) {
		if (isComplete()) {
			if (isResult()) {
				other.whenResult(Recyclers::recycle);
				return this;
			}
			return (Promise<T>) other;
		}
		if (other.isComplete()) {
			if (other.isResult()) {
				this.whenResult(Recyclers::recycle);
				return (Promise<T>) other;
			}
			return this;
		}
		EitherPromise<T> resultPromise = new EitherPromise<>();
		other.run(resultPromise);
		subscribe(resultPromise);
		return resultPromise;
	}

	private static final class EitherPromise<T> extends NextPromise<T, T> {
		int errors = 2;

		@Override
		public void accept(T result, @Nullable Exception e) {
			if (e == null) {
				if (!tryComplete(result)) {
					Recyclers.recycle(result);
				}
			} else {
				if (--errors == 0) {
					completeExceptionally(new Exception("Both promises completed exceptionally"));
				}
			}
		}

		@Override
		public String describe() {
			return ".either()";
		}
	}

	@NotNull
	@Override
	public Promise<Try<T>> toTry() {
		if (isComplete()) {
			return Promise.of(isResult() ? Try.of(result) : Try.ofException(exception));
		}
		NextPromise<T, Try<T>> resultPromise = new NextPromise<T, Try<T>>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				if (e == null) {
					complete(Try.of(result));
				} else {
					complete(Try.ofException(e));
				}
			}

			@Override
			public String describe() {
				return ".toTry()";
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@NotNull
	@Override
	public Promise<Void> toVoid() {
		if (isComplete()) {
			return isResult() ? Promise.complete() : (Promise<Void>) this;
		}
		NextPromise<T, Void> resultPromise = new NextPromise<T, Void>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				if (e == null) {
					complete(null);
				} else {
					completeExceptionally(e);
				}
			}

			@Override
			public String describe() {
				return ".toVoid()";
			}
		};
		subscribe(resultPromise);
		return resultPromise;
	}

	@Override
	public void run(@NotNull Callback<? super T> action) {
		if (isComplete()) {
			action.accept(result, exception);
			return;
		}
		subscribe(action);
	}

	@NotNull
	@Override
	public CompletableFuture<T> toCompletableFuture() {
		if (isComplete()) {
			if (isResult()) {
				return CompletableFuture.completedFuture(result);
			} else {
				CompletableFuture<T> future = new CompletableFuture<>();
				future.completeExceptionally(exception);
				return future;
			}
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		subscribe(new SimpleCallback<T>() {
			@Override
			public void accept(T result, @Nullable Exception e) {
				if (e == null) {
					future.complete(result);
				} else {
					future.completeExceptionally(e);
				}
			}

			@Override
			public String toString() {
				return ".toCompletableFuture()";
			}
		});
		return future;
	}

	private static class CallbackList<T> implements Callback<T> {
		private int index = 2;
		private Callback<? super T>[] callbacks = new Callback[4];

		public CallbackList(Callback<? super T> first, Callback<? super T> second) {
			callbacks[0] = first;
			callbacks[1] = second;
		}

		public void add(Callback<? super T> callback) {
			if (index == callbacks.length) {
				callbacks = Arrays.copyOf(callbacks, callbacks.length * 2);
			}
			callbacks[index++] = callback;
		}

		@Override
		public void accept(T result, @Nullable Exception e) {
			for (int i = 0; i < index; i++) {
				callbacks[i].accept(result, e);
			}
		}
	}

	private static final String INDENT = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
	private static final Pattern PACKAGE_NAME_AND_LAMBDA_PART = Pattern.compile("^(?:" + INDENT + "\\.)*((?:" + INDENT + "?)\\$\\$Lambda\\$\\d+)/.*$");

	private static <T> void appendChildren(StringBuilder sb, Callback<T> callback, String indent) {
		if (callback == null) {
			return;
		}
		if (callback instanceof CallbackList) {
			CallbackList<? super T> callbackList = (CallbackList<? super T>) callback;
			for (int i = 0; i < callbackList.index; i++) {
				appendChildren(sb, callbackList.callbacks[i], indent);
			}
		} else {
			indent += "\t";
			sb.append("\n");
			if (callback instanceof AbstractPromise) {
				sb.append(((AbstractPromise<T>) callback).toString(indent));
			} else if (!(callback instanceof SimpleCallback)) {
				sb.append(indent)
						.append(".whenComplete(")
						.append(formatToString(callback))
						.append(')');
			} else {
				sb.append(indent).append(callback);
			}
		}
	}

	private static String formatToString(Object object) {
		return PACKAGE_NAME_AND_LAMBDA_PART.matcher(object.toString()).replaceAll("$1");
	}

	private String toString(String indent) {
		StringBuilder sb = new StringBuilder(indent);
		sb.append(describe());
		if (isComplete()) {
			sb.append('{');
			if (exception == null) {
				sb.append(result);
			} else {
				sb.append("exception=");
				sb.append(exception.getClass().getSimpleName());
			}
			sb.append('}');
		}
		appendChildren(sb, next, indent);
		return sb.toString();
	}

	protected String describe() {
		return "AbstractPromise";
	}

	@Override
	public String toString() {
		return toString("");
	}
}
