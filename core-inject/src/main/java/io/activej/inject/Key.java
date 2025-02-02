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

package io.activej.inject;

import io.activej.inject.util.ReflectionUtils;
import io.activej.inject.util.Utils;
import io.activej.types.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * The key defines an identity of a binding. In any DI, a key is usually a type of the object along
 * with some optional tag to distinguish between bindings which make objects of the same type.
 * <p>
 * In ActiveJ Inject, a key is also a type token - special abstract class that can store type information
 * with shortest syntax possible in Java.
 * <p>
 * For example, to create a key of type Map&lt;String, List&lt;Integer&gt;&gt;, you can just use
 * this syntax: <code>new Key&lt;Map&lt;String, List&lt;Integer&gt;&gt;&gt;(){}</code>.
 * <p>
 * If your types are not known at compile time, you can use {@link io.activej.types.Types#parameterizedType} to make a
 * parameterized type and give it to a {@link #ofType Key.ofType} constructor.
 */
public abstract class Key<T> {
	private final @NotNull Type type;
	private final @Nullable Object qualifier;

	public Key() {
		this.type = getTypeParameter();
		this.qualifier = null;
	}

	public Key(@Nullable Object qualifier) {
		this.type = getTypeParameter();
		this.qualifier = qualifier;
	}

	Key(@NotNull Type type, @Nullable Object qualifier) {
		this.type = type;
		this.qualifier = qualifier;
	}

	private static final class KeyImpl<T> extends Key<T> {
		private KeyImpl(Type type, Object qualifier) {
			super(type, qualifier);
		}
	}

	public static @NotNull <T> Key<T> of(@NotNull Class<T> type) {
		return new KeyImpl<>(type, null);
	}

	public static @NotNull <T> Key<T> of(@NotNull Class<T> type, @Nullable Object qualifier) {
		return new KeyImpl<>(type, qualifier);
	}

	public static @NotNull <T> Key<T> ofType(@NotNull Type type) {
		return new KeyImpl<>(type, null);
	}

	public static @NotNull <T> Key<T> ofType(@NotNull Type type, @Nullable Object qualifier) {
		return new KeyImpl<>(type, qualifier);
	}

	/**
	 * Returns a new key with same type but the qualifier replaced with a given one
	 */
	public Key<T> qualified(Object qualifier) {
		return new KeyImpl<>(type, qualifier);
	}

	private @NotNull Type getTypeParameter() {
		// this cannot possibly fail so not even a check here
		Type typeArgument = ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
		Object outerInstance = ReflectionUtils.getOuterClassInstance(this);
//		// the outer instance is null in static context
		return outerInstance != null ? Types.bind(typeArgument, Types.getAllTypeBindings(outerInstance.getClass())) : typeArgument;
	}

	public @NotNull Type getType() {
		return type;
	}

	/**
	 * A shortcut for <code>{@link Types#getRawType(Type)}(key.getType())</code>.
	 * Also casts the result to a properly parameterized class.
	 */
	@SuppressWarnings("unchecked")
	public @NotNull Class<T> getRawType() {
		return (Class<T>) Types.getRawType(type);
	}

	/**
	 * Returns a type parameter of the underlying type wrapped as a key with no qualifier.
	 *
	 * @throws IllegalStateException when underlying type is not a parameterized one.
	 */
	public <U> Key<U> getTypeParameter(int index) {
		if (type instanceof ParameterizedType) {
			return new KeyImpl<>(((ParameterizedType) type).getActualTypeArguments()[index], null);
		}
		throw new IllegalStateException("Expected type from key " + getDisplayString() + " to be parameterized");
	}

	public @Nullable Object getQualifier() {
		return qualifier;
	}

	/**
	 * Returns an underlying type with display string formatting (package names stripped)
	 * and prepended qualifier display string if this key has a qualifier.
	 */
	public String getDisplayString() {
		return (qualifier != null ? Utils.getDisplayString(qualifier) + " " : "") + ReflectionUtils.getDisplayName(type);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Key)) {
			return false;
		}
		Key<?> that = (Key<?>) o;
		return type.equals(that.type) && Objects.equals(qualifier, that.qualifier);
	}

	@Override
	public int hashCode() {
		return 31 * type.hashCode() + (qualifier == null ? 0 : qualifier.hashCode());
	}

	@Override
	public String toString() {
		return (qualifier != null ? qualifier + " " : "") + type.getTypeName();
	}
}
