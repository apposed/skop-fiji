/*-
 * #%L
 * scikit-ops in Fiji: every op, as its own SciJava command.
 * %%
 * Copyright (C) 2026 scikit-ops developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.apposed.skop.fiji.wire;

/**
 * Whether a parameter is an input, an output buffer, or mutated in place.
 * <p>
 * {@link #OUT} is the one that changes a dialog: a user is never asked for an
 * output buffer, so an OUT parameter is not harvested at all.
 *
 * @author Curtis Rueden
 */
public enum Direction {

	/** An ordinary input. skop says nothing at all about these. */
	IN(null),

	/** A caller-allocated output buffer, declared {@code Out[...]}. */
	OUT("Out"),

	/** An input the op mutates, declared {@code Mut[...]}. */
	MUT("Mut");

	private final String wireName;

	Direction(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}

	/**
	 * The direction with the given wire name.
	 *
	 * @param wireName the name skop used, or null for an ordinary input.
	 * @return the matching direction; {@link #IN} for null.
	 */
	public static Direction forWire(String wireName) {
		if (wireName == null) return IN;
		for (Direction direction : values()) {
			if (wireName.equals(direction.wireName)) return direction;
		}
		throw new IllegalArgumentException("Unknown parameter direction: " + wireName);
	}
}
