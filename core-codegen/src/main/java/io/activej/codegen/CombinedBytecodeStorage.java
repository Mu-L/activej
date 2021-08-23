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

package io.activej.codegen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class CombinedBytecodeStorage implements BytecodeStorage {
	private final List<BytecodeStorage> storages;

	private CombinedBytecodeStorage(List<BytecodeStorage> storages) {
		this.storages = storages;
	}

	public static CombinedBytecodeStorage create(List<BytecodeStorage> storages) {
		if (storages.isEmpty()) throw new IllegalArgumentException("At least on storage should be present");

		return new CombinedBytecodeStorage(new ArrayList<>(storages));
	}

	public static CombinedBytecodeStorage create(BytecodeStorage storage, BytecodeStorage... otherStorages) {
		List<BytecodeStorage> storages = new ArrayList<>(otherStorages.length + 1);
		storages.add(storage);
		Collections.addAll(storages, otherStorages);
		return new CombinedBytecodeStorage(storages);
	}

	@Override
	public Optional<byte[]> loadBytecode(String className) {
		for (BytecodeStorage storage : storages) {
			Optional<byte[]> maybeBytecode = storage.loadBytecode(className);
			if (maybeBytecode.isPresent()) {
				return maybeBytecode;
			}
		}
		return Optional.empty();
	}

	@Override
	public void saveBytecode(String className, byte[] bytecode) {
	}
}
