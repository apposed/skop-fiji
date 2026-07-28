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
 * An op's computation form, in the SciJava Ops sense.
 * <p>
 * The distinction is load-bearing for a host rather than for a user: a
 * {@link #FUNCTION} returns its results over the wire, while the other two
 * write into buffers the host allocated, so their results have to be copied
 * back out of shared memory instead of decoded from task outputs.
 *
 * @author Curtis Rueden
 */
public enum Form {

	/** Inputs in, freshly allocated output out. */
	FUNCTION("function"),

	/** The caller supplies the output buffer; the op fills it. */
	COMPUTER("computer"),

	/** The op mutates one of its inputs. */
	INPLACE("inplace");

	private final String wireName;

	Form(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}

	public static Form forWire(String wireName) {
		for (Form form : values()) {
			if (form.wireName.equals(wireName)) return form;
		}
		throw new IllegalArgumentException("Unknown op form: " + wireName);
	}
}
