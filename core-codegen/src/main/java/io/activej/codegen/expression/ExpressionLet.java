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

package io.activej.codegen.expression;

import io.activej.codegen.Context;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;

final class ExpressionLet implements Variable {
	private final Expression value;
	private VarLocal var;

	ExpressionLet(Expression value) {
		this.value = value;
	}

	@Override
	public Type load(Context ctx) {
		if (var == null) {
			var = ctx.newLocal(value.load(ctx));
			var.store(ctx);
		}
		return var.load(ctx);
	}

	@Override
	public @Nullable Object beginStore(Context ctx) {
		return null;
	}

	@Override
	public void store(Context ctx, Object storeContext, Type type) {
		if (var == null) {
			Type fieldType = value.load(ctx);
			var = ctx.newLocal(fieldType);
			var.store(ctx);
		}

		var.store(ctx, storeContext, type);
	}
}
