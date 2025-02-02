package io.activej.types;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static io.activej.types.IsAssignableUtils.isAssignable;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.joining;

public class Types {
	public static final Type[] NO_TYPES = new Type[0];
	public static final WildcardType WILDCARD_TYPE_ANY = new WildcardTypeImpl(new Type[]{Object.class}, new Type[0]);
	private static final Map<Type, Map<TypeVariable<?>, Type>> typeBindingsCache = new ConcurrentHashMap<>();

	public static Class<?> getRawType(Type type) {
		if (type instanceof Class) {
			return (Class<?>) type;
		} else if (type instanceof ParameterizedType) {
			ParameterizedType parameterizedType = (ParameterizedType) type;
			return (Class<?>) parameterizedType.getRawType();
		} else if (type instanceof WildcardType) {
			WildcardType wildcardType = (WildcardType) type;
			Type[] upperBounds = wildcardType.getUpperBounds();
			return getRawType(getUppermostType(upperBounds));
		} else if (type instanceof GenericArrayType) {
			Class<?> rawComponentType = getRawType(((GenericArrayType) type).getGenericComponentType());
			try {
				return Class.forName("[L" + rawComponentType.getName() + ";");
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			}
		}
		throw new IllegalArgumentException("Unsupported type: " + type);
	}

	public static Type getUppermostType(Type[] types) {
		Type result = types[0];
		for (int i = 1; i < types.length; i++) {
			Type type = types[i];
			if (isAssignable(type, result)) {
				result = type;
				continue;
			} else if (isAssignable(result, type)) {
				continue;
			}
			throw new IllegalArgumentException("Unrelated types: " + result + " , " + type);
		}
		return result;
	}

	public static Type[] getActualTypeArguments(Type type) {
		if (type instanceof Class) {
			return ((Class<?>) type).isArray() ? new Type[]{((Class<?>) type).getComponentType()} : NO_TYPES;
		} else if (type instanceof ParameterizedType) {
			ParameterizedType parameterizedType = (ParameterizedType) type;
			return parameterizedType.getActualTypeArguments();
		} else if (type instanceof GenericArrayType) {
			return new Type[]{((GenericArrayType) type).getGenericComponentType()};
		}
		throw new IllegalArgumentException("Unsupported type: " + type);
	}

	public static Map<TypeVariable<?>, Type> getTypeBindings(Type type) {
		Type[] typeArguments = getActualTypeArguments(type);
		if (typeArguments.length == 0) return emptyMap();
		TypeVariable<?>[] typeVariables = getRawType(type).getTypeParameters();
		Map<TypeVariable<?>, Type> map = new HashMap<>();
		for (int i = 0; i < typeVariables.length; i++) {
			map.put(typeVariables[i], typeArguments[i]);
		}
		return map;
	}

	public static Map<TypeVariable<?>, Type> getAllTypeBindings(Type type) {
		return typeBindingsCache.computeIfAbsent(type,
				t -> {
					Map<TypeVariable<?>, Type> mapping = new HashMap<>();
					getAllTypeBindingsImpl(t, mapping);
					return mapping;
				});
	}

	private static void getAllTypeBindingsImpl(Type type, Map<TypeVariable<?>, Type> mapping) {
		Class<?> cls = getRawType(type);

		if (type instanceof ParameterizedType) {
			Type[] typeArguments = ((ParameterizedType) type).getActualTypeArguments();
			if (typeArguments.length != 0) {
				TypeVariable<? extends Class<?>>[] typeVariables = cls.getTypeParameters();
				for (int i = 0; i < typeArguments.length; i++) {
					Type typeArgument = typeArguments[i];
					mapping.put(typeVariables[i], typeArgument instanceof TypeVariable ?
							Objects.requireNonNull(mapping.get(typeArgument)) :
							typeArgument);
				}
			}
		}

		Type superclass = cls.getGenericSuperclass();
		if (superclass != null) {
			getAllTypeBindingsImpl(superclass, mapping);
		}

		for (Type anInterface : cls.getGenericInterfaces()) {
			getAllTypeBindingsImpl(anInterface, mapping);
		}
	}

	public static @NotNull Type bind(Type type, Map<TypeVariable<?>, Type> bindings) {
		return bind(type, bindings::get);
	}

	public static @NotNull Type bind(Type type, Function<TypeVariable<?>, @Nullable Type> bindings) {
		if (type instanceof Class) return type;
		if (type instanceof TypeVariable) {
			TypeVariable<?> typeVariable = (TypeVariable<?>) type;
			Type actualType = bindings.apply(typeVariable);
			if (actualType == null) {
				throw new IllegalArgumentException("Type variable not found: " + typeVariable +
						" ( " + typeVariable.getGenericDeclaration() + " ) ");
			}
			return actualType;
		}
		if (type instanceof ParameterizedType) {
			ParameterizedType parameterizedType = (ParameterizedType) type;
			Type[] typeArguments = parameterizedType.getActualTypeArguments();
			Type[] typeArguments2 = new Type[typeArguments.length];
			for (int i = 0; i < typeArguments.length; i++) {
				typeArguments2[i] = bind(typeArguments[i], bindings);
			}
			return new ParameterizedTypeImpl(parameterizedType.getOwnerType(), parameterizedType.getRawType(), typeArguments2);
		}
		if (type instanceof GenericArrayType) {
			GenericArrayType genericArrayType = (GenericArrayType) type;
			Type componentType = genericArrayType.getGenericComponentType();
			return new GenericArrayTypeImpl(bind(componentType, bindings));
		}
		if (type instanceof WildcardType) {
			WildcardType wildcardType = (WildcardType) type;
			Type[] upperBounds = wildcardType.getUpperBounds();
			Type[] upperBounds2 = new Type[upperBounds.length];
			for (int i = 0; i < upperBounds.length; i++) {
				upperBounds2[i] = bind(upperBounds[i], bindings);
			}
			Type[] lowerBounds = wildcardType.getLowerBounds();
			Type[] lowerBounds2 = new Type[lowerBounds.length];
			for (int i = 0; i < lowerBounds.length; i++) {
				lowerBounds2[i] = bind(lowerBounds[i], bindings);
			}
			return new WildcardTypeImpl(upperBounds2, lowerBounds2);
		}
		throw new IllegalArgumentException("Unsupported type: " + type);
	}

	public static ParameterizedType parameterizedType(@Nullable Type ownerType, Type rawType, Type[] parameters) {
		return new ParameterizedTypeImpl(ownerType, rawType, parameters);
	}

	public static ParameterizedType parameterizedType(Class<?> rawType, Type... parameters) {
		return new ParameterizedTypeImpl(null, rawType, parameters);
	}

	static final class ParameterizedTypeImpl implements ParameterizedType {
		private final @Nullable Type ownerType;
		private final Type rawType;
		private final Type[] actualTypeArguments;

		ParameterizedTypeImpl(@Nullable Type ownerType, Type rawType, Type[] actualTypeArguments) {
			this.ownerType = ownerType;
			this.rawType = rawType;
			this.actualTypeArguments = actualTypeArguments;
		}

		@Override
		public @NotNull Type getRawType() {
			return rawType;
		}

		@Override
		public Type @NotNull [] getActualTypeArguments() {
			return actualTypeArguments;
		}

		@Override
		public @Nullable Type getOwnerType() {
			return ownerType;
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(ownerType) ^ Arrays.hashCode(actualTypeArguments) ^ rawType.hashCode();
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof ParameterizedType)) return false;
			ParameterizedType that = (ParameterizedType) other;
			return this.getRawType().equals(that.getRawType()) && Objects.equals(this.getOwnerType(), that.getOwnerType()) && Arrays.equals(this.getActualTypeArguments(), that.getActualTypeArguments());
		}

		@Override
		public String toString() {
			return rawType.getTypeName() +
					Arrays.stream(actualTypeArguments).map(Types::toString).collect(joining(", ", "<", ">"));
		}
	}

	public static WildcardType wildcardType(Type[] upperBounds, Type[] lowerBounds) {
		return new WildcardTypeImpl(upperBounds, lowerBounds);
	}

	public static WildcardType wildcardTypeAny() {
		return WILDCARD_TYPE_ANY;
	}

	public static WildcardType wildcardTypeExtends(Type upperBound) {
		return new WildcardTypeImpl(new Type[]{upperBound}, NO_TYPES);
	}

	public static WildcardType wildcardTypeSuper(Type lowerBound) {
		return new WildcardTypeImpl(NO_TYPES, new Type[]{lowerBound});
	}

	static class WildcardTypeImpl implements WildcardType {
		private final Type[] upperBounds;
		private final Type[] lowerBounds;

		WildcardTypeImpl(Type[] upperBounds, Type[] lowerBounds) {
			this.upperBounds = upperBounds;
			this.lowerBounds = lowerBounds;
		}

		@Override
		public Type[] getUpperBounds() {
			return upperBounds;
		}

		@Override
		public Type[] getLowerBounds() {
			return lowerBounds;
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(upperBounds) ^ Arrays.hashCode(lowerBounds);
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof WildcardType)) return false;
			WildcardType that = (WildcardType) other;
			return Arrays.equals(this.getUpperBounds(), that.getUpperBounds()) && Arrays.equals(this.getLowerBounds(), that.getLowerBounds());
		}

		@Override
		public String toString() {
			return "?" +
					(upperBounds.length == 0 ? "" :
							" extends " + Arrays.stream(upperBounds).map(Types::toString).collect(joining(" & "))) +
					(lowerBounds.length == 0 ? "" :
							" super " + Arrays.stream(lowerBounds).map(Types::toString).collect(joining(" & ")));

		}
	}

	public static GenericArrayType genericArrayType(Type componentType) {
		return new GenericArrayTypeImpl(componentType);
	}

	static final class GenericArrayTypeImpl implements GenericArrayType {
		private final Type componentType;

		GenericArrayTypeImpl(Type componentType) {
			this.componentType = componentType;
		}

		@Override
		public Type getGenericComponentType() {
			return componentType;
		}

		@Override
		public int hashCode() {
			return componentType.hashCode();
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof GenericArrayType)) return false;
			GenericArrayType that = (GenericArrayType) other;
			return this.getGenericComponentType().equals(that.getGenericComponentType());
		}

		@Override
		public String toString() {
			return Types.toString(componentType) + "[]";
		}
	}

	private static String toString(Type type) {
		return type instanceof Class ? ((Class<?>) type).getName() : type.toString();
	}

	public static String getSimpleName(Type type) {
		if (type instanceof Class) {
			return ((Class<?>) type).getSimpleName();
		} else if (type instanceof ParameterizedType) {
			return Arrays.stream(((ParameterizedType) type).getActualTypeArguments())
					.map(Types::getSimpleName)
					.collect(joining(",", "<", ">"));
		} else if (type instanceof WildcardType) {
			WildcardType wildcardType = (WildcardType) type;
			Type[] upperBounds = wildcardType.getUpperBounds();
			Type[] lowerBounds = wildcardType.getLowerBounds();
			return "?" +
					(upperBounds.length == 0 ? "" :
							" extends " + Arrays.stream(upperBounds).map(Types::getSimpleName).collect(joining(" & "))) +
					(lowerBounds.length == 0 ? "" :
							" super " + Arrays.stream(lowerBounds).map(Types::getSimpleName).collect(joining(" & ")));
		} else if (type instanceof GenericArrayType) {
			return Types.getSimpleName(((GenericArrayType) type).getGenericComponentType()) + "[]";
		}

		return type.getTypeName();
	}
}
