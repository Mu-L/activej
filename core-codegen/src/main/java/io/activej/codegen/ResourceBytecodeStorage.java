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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

public final class ResourceBytecodeStorage extends AbstractIOBytecodeStorage {
	private final ClassLoader classLoader;

	private ResourceBytecodeStorage(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	public static ResourceBytecodeStorage create(ClassLoader classLoader) {
		return new ResourceBytecodeStorage(classLoader);
	}

	@Override
	protected Optional<InputStream> getInputStream(String className) {
		return Optional.ofNullable(classLoader.getResourceAsStream(className));
	}

	@Override
	protected Optional<OutputStream> getOutputStream(String className) {
		return Optional.empty();
	}
}
