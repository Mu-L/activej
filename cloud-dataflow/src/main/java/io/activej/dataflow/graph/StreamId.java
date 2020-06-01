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

package io.activej.dataflow.graph;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a unique id of the stream.
 */
public final class StreamId {
	private static final AtomicLong seed = new AtomicLong(0);
//	private static final AtomicLong seed = new AtomicLong(new Random().nextInt() & (Integer.MAX_VALUE >>> 1));

	private final long id;

	public StreamId() {
		id = seed.getAndIncrement();
	}

	public StreamId(long id) {
		this.id = id;
	}

	public long getId() {
		return id;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		StreamId streamId = (StreamId) o;

		return id == streamId.id;
	}

	@Override
	public int hashCode() {
		return (int) (id ^ (id >>> 32));
	}

	@Override
	public String toString() {
		return "{" + id + '}';
	}
}
