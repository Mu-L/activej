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

package io.activej.serializer.impl;

import io.activej.codegen.expression.Expression;
import io.activej.codegen.expression.Variable;
import io.activej.serializer.AbstractSerializerDef;
import io.activej.serializer.CompatibilityLevel;
import io.activej.serializer.SerializerDef;

import static io.activej.codegen.expression.Expression.*;
import static io.activej.serializer.CompatibilityLevel.LEVEL_3;
import static io.activej.serializer.impl.SerializerExpressions.*;

public final class SerializerDef_Enum extends AbstractSerializerDef implements SerializerDef_WithNullable {
	private final Class<? extends Enum<?>> enumType;
	private final boolean nullable;

	public SerializerDef_Enum(Class<? extends Enum<?>> enumType, boolean nullable) {
		this.enumType = enumType;
		this.nullable = nullable;
	}

	public SerializerDef_Enum(Class<? extends Enum<?>> enumType) {
		this(enumType, false);
	}

	@Override
	public Class<?> getEncodeType() {
		return enumType;
	}

	@Override
	public Expression encode(StaticEncoders staticEncoders, Expression buf, Variable pos, Expression value, int version, CompatibilityLevel compatibilityLevel) {
		Expression ordinal = call(cast(value, Enum.class), "ordinal");
		if (isSmallEnum()) {
			return !nullable ?
					writeByte(buf, pos, cast(ordinal, byte.class)) :
					ifNull(value,
							writeByte(buf, pos, value((byte) 0)),
							writeByte(buf, pos, cast(add(ordinal, value(1)), byte.class)));
		} else {
			return !nullable ?
					writeVarInt(buf, pos, ordinal) :
					ifNull(value,
							writeByte(buf, pos, value((byte) 0)),
							writeVarInt(buf, pos, add(ordinal, value((byte) 1))));
		}
	}

	@Override
	public Expression decode(StaticDecoders staticDecoders, Expression in, int version, CompatibilityLevel compatibilityLevel) {
		return isSmallEnum() ?
				let(readByte(in), b ->
						!nullable ?
								arrayGet(staticCall(enumType, "values"), b) :
								ifEq(b, value((byte) 0),
										nullRef(enumType),
										arrayGet(staticCall(enumType, "values"), dec(b)))) :
				let(readVarInt(in), value ->
						!nullable ?
								arrayGet(staticCall(enumType, "values"), value) :
								ifEq(value, value(0),
										nullRef(enumType),
										arrayGet(staticCall(enumType, "values"), dec(value))));
	}

	private boolean isSmallEnum() {
		int size = enumType.getEnumConstants().length + (nullable ? 1 : 0);
		if (size >= 16384) throw new IllegalArgumentException();
		return size <= Byte.MAX_VALUE;
	}

	@Override
	public SerializerDef ensureNullable(CompatibilityLevel compatibilityLevel) {
		if (compatibilityLevel.getLevel() < LEVEL_3.getLevel()) {
			return new SerializerDef_Nullable(this);
		}
		return new SerializerDef_Enum(enumType, true);
	}
}
