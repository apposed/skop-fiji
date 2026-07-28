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
 * What a value <em>means</em>, beyond what its type says.
 * <p>
 * skop's own vocabulary, mirrored member for member. Mapping these onto Fiji
 * types is the front end's job and lives in {@code Roles}; this enum is only
 * the reading of what skop said.
 * <p>
 * A role skop grows that this enum has not caught up with is
 * {@link #forWire(String)}'s problem, and its answer is null rather than an
 * exception: an unrecognized role costs one output its display type, not the
 * whole op.
 *
 * @author Curtis Rueden
 */
public enum Role {

	IMAGE("image"),
	LABELS("labels"),
	MASKS("masks"),
	POINTS("points"),
	SHAPES("shapes"),
	SURFACE("surface"),
	TRACKS("tracks"),
	VECTORS("vectors");

	private final String wireName;

	Role(String wireName) {
		this.wireName = wireName;
	}

	/** This role as skop spells it. */
	public String wireName() {
		return wireName;
	}

	/**
	 * The role with the given wire name, or null if there is no such role.
	 *
	 * @param wireName the name skop used, or null.
	 * @return the matching role, or null for null input or an unknown name.
	 */
	public static Role forWire(String wireName) {
		if (wireName == null) return null;
		for (Role role : values()) {
			if (role.wireName.equals(wireName)) return role;
		}
		return null;
	}
}
