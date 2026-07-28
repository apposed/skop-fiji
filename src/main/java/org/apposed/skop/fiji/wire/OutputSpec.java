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

import java.util.Map;

/**
 * One of an op's outputs: one thing a front end has to display.
 *
 * @author Curtis Rueden
 */
public class OutputSpec {

	private final String name;
	private final TypeSpec type;
	private final Role role;

	public OutputSpec(String name, TypeSpec type, Role role) {
		this.name = name;
		this.type = type;
		this.role = role;
	}

	public String name() {
		return name;
	}

	public TypeSpec type() {
		return type;
	}

	/**
	 * What this output <em>is</em>, or null if skop did not say.
	 * <p>
	 * A null role is not a gap to be filled in skop: guessing what an
	 * unannotated array means is the front end's business, and every front end
	 * should guess differently, in its own module, where its own display model
	 * justifies it.
	 */
	public Role role() {
		return role;
	}

	@Override
	public String toString() {
		return name + ": " + type + (role == null ? "" : " [" + role.wireName() + "]");
	}

	public static OutputSpec fromJson(Map<String, Object> data) {
		return new OutputSpec(
			Wire.string(data, "name"),
			TypeSpec.fromJson(Wire.map(data, "type")),
			Role.forWire(Wire.optionalString(data, "role")));
	}
}
