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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * How many axes an image parameter consumes, and what it likes to call them.
 * <p>
 * Names are hints and never requirements: what binds is the <em>arity</em>,
 * because that is what the op's own indexing depends on. Feeding an op an
 * axis it did not name produces a warning on the plan, never a refusal.
 *
 * @author Curtis Rueden
 */
public class AxesSpec {

	/** One axis a parameter consumes. */
	public static class Slot {

		private final String name;
		private final boolean optional;

		public Slot(String name, boolean optional) {
			this.name = name;
			this.optional = optional;
		}

		/** The preferred axis name, or null for a wildcard slot. */
		public String name() {
			return name;
		}

		/** Whether the slot need not be filled. */
		public boolean optional() {
			return optional;
		}

		@Override
		public String toString() {
			return (name == null ? "*" : name) + (optional ? "?" : "");
		}

		static Slot fromJson(Map<String, Object> data) {
			return new Slot(Wire.optionalString(data, "name"),
				Wire.bool(data, "optional", false));
		}
	}

	private final List<Slot> slots;
	private final boolean variadic;

	public AxesSpec(List<Slot> slots, boolean variadic) {
		this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
		this.variadic = variadic;
	}

	public List<Slot> slots() {
		return slots;
	}

	/** Whether the op copes with any number of further axes on its own. */
	public boolean variadic() {
		return variadic;
	}

	/** How many axes the op always consumes. */
	public int arity() {
		int count = 0;
		for (Slot slot : slots) {
			if (!slot.optional()) count++;
		}
		return count;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("Axes(");
		for (int i = 0; i < slots.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(slots.get(i));
		}
		if (variadic) sb.append(slots.isEmpty() ? "variadic" : ", variadic");
		return sb.append(")").toString();
	}

	public static AxesSpec fromJson(Map<String, Object> data) {
		List<Slot> slots = new ArrayList<>();
		for (Map<String, Object> slot : Wire.maps(data, "slots")) {
			slots.add(Slot.fromJson(slot));
		}
		return new AxesSpec(slots, Wire.bool(data, "variadic", false));
	}
}
