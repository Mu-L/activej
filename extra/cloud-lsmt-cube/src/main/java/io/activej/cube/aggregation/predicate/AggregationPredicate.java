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

package io.activej.cube.aggregation.predicate;

import io.activej.codegen.expression.Expression;
import io.activej.cube.aggregation.fieldtype.FieldType;

import java.util.Map;
import java.util.Set;

public interface AggregationPredicate {

	AggregationPredicate simplify();

	Set<String> getDimensions();

	Map<String, Object> getFullySpecifiedDimensions();

	Expression createPredicate(Expression record, ValueResolver valueResolver);

	@Override
	boolean equals(Object o);

	@Override
	int hashCode();

	interface ValueResolver {
		Expression getProperty(Expression record, String key);

		Object transformArg(String key, Object value);

		Expression toString(String key, Expression value);
	}
}
