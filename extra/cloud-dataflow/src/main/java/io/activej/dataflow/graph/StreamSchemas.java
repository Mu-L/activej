package io.activej.dataflow.graph;

import io.activej.dataflow.inject.BinarySerializerModule;
import io.activej.serializer.BinarySerializer;

public final class StreamSchemas {
	public static <T> StreamSchema<T> simple(Class<T> cls) {
		return new Simple<>(cls);
	}

	public static class Simple<T> implements StreamSchema<T> {
		private final Class<T> cls;

		private Simple(Class<T> cls) {
			this.cls = cls;
		}

		@Override
		public Class<T> createClass() {
			return cls;
		}

		@Override
		public BinarySerializer<T> createSerializer(BinarySerializerModule.BinarySerializerLocator locator) {
			return locator.get(cls);
		}
	}
}
