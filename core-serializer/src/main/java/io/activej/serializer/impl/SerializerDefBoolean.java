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
import io.activej.serializer.CompatibilityLevel;
import io.activej.serializer.CorruptedDataException;
import io.activej.serializer.SerializerDef;

import static io.activej.codegen.expression.Expressions.*;
import static io.activej.serializer.CompatibilityLevel.LEVEL_4;
import static io.activej.serializer.impl.SerializerExpressions.*;

public final class SerializerDefBoolean extends SerializerDefPrimitive implements SerializerDefWithNullable {
	public static final byte NULLABLE_NULL = 0b00;
	public static final byte NULLABLE_FALSE = 0b10;
	public static final byte NULLABLE_TRUE = 0b11;

	private boolean nullable;

	public SerializerDefBoolean() {
		this(true);
	}

	public SerializerDefBoolean(boolean wrapped) {
		super(boolean.class, wrapped);
	}

	public SerializerDefBoolean(boolean wrapped, boolean nullable) {
		super(boolean.class, wrapped);
		this.nullable = nullable;
	}

	@Override
	public SerializerDef ensureWrapped() {
		return new SerializerDefBoolean(true, nullable);
	}

	@Override
	protected boolean castToPrimitive() {
		return !nullable;
	}

	@Override
	protected Expression doSerialize(Expression byteArray, Variable off, Expression value, CompatibilityLevel compatibilityLevel) {
		return !nullable ?
				writeBoolean(byteArray, off, value) :
				ifThenElse(isNull(value),
						writeByte(byteArray, off, value(NULLABLE_NULL)),
						ifThenElse(cast(value, boolean.class),
								writeByte(byteArray, off, value(NULLABLE_TRUE)),
								writeByte(byteArray, off, value(NULLABLE_FALSE))
						));
	}

	@Override
	protected Expression doDeserialize(Expression in, CompatibilityLevel compatibilityLevel) {
		return let(readByte(in), aByte ->
				!nullable ?
						aByte :
						ifThenElse(cmpEq(aByte, value(NULLABLE_NULL)),
								nullRef(Boolean.class), ifThenElse(cmpEq(aByte, value(NULLABLE_FALSE)),
										value(false, Boolean.class),
										ifThenElse(cmpEq(aByte, value(NULLABLE_TRUE)),
												value(true, Boolean.class),
												throwException(CorruptedDataException.class,
														concat(value("Unsupported nullable boolean value "), aByte,
																value(", allowed: " + NULLABLE_NULL + ", " + NULLABLE_FALSE + ", " + NULLABLE_TRUE)))))));
	}

	@Override
	public SerializerDef ensureNullable(CompatibilityLevel compatibilityLevel) {
		if (compatibilityLevel.getLevel() < LEVEL_4.getLevel()) {
			return new SerializerDefNullable(this);
		}
		return new SerializerDefBoolean(wrapped, true);
	}
}
