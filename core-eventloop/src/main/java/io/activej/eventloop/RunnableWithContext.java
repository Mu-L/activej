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

package io.activej.eventloop;

import io.activej.common.ApplicationSettings;

public interface RunnableWithContext extends Runnable {
	boolean WRAP_CONTEXT = ApplicationSettings.getBoolean(RunnableWithContext.class, "wrapContext", false);

	Object getContext();

	static RunnableWithContext of(Object context, Runnable runnable) {
		return new RunnableWithContext() {
			@Override
			public Object getContext() {
				return context;
			}

			@Override
			public void run() {
				runnable.run();
			}

			@Override
			public String toString() {
				return "RunnableWithContext{" +
						"runnable=" + runnable +
						", context=" + context +
						'}';
			}
		};
	}

	static Runnable wrapContext(Object context, Runnable runnable) {
		return WRAP_CONTEXT ? of(context, runnable) : runnable;
	}

}
