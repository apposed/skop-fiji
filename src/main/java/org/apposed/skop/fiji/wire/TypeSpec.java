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
 * A parameter or output type, in the vocabulary skop puts on the wire.
 * <p>
 * In Python a {@code ParamSpec.type} is a live type object; here it can only
 * be a name, because {@code <class 'numpy.ndarray'>} does not cross a process
 * boundary. The names are deliberately few: the set a generated dialog can
 * render a widget for, plus {@link Kind#UNKNOWN} for everything else.
 * <p>
 * {@code UNKNOWN} is the useful one. A front end that cannot render a
 * parameter leaves it at its default and reports it, or -- if the parameter
 * is required -- refuses to run and says which parameter and why. One awkward
 * parameter must not cost the whole op, which is what {@link #detail()} is
 * for: it carries the original annotation's spelling, so the message can name
 * the thing it could not render.
 *
 * @author Curtis Rueden
 */
public class TypeSpec {

	/** The vocabulary itself. Kept in step with {@code skop._spec.WIRE_TYPES}. */
	public enum Kind {
		INT("int"),
		FLOAT("float"),
		STR("str"),
		BOOL("bool"),
		NDARRAY("ndarray"),
		PATH("path"),
		ENUM("enum"),
		UNKNOWN("unknown");

		private final String wireName;

		Kind(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}

		/**
		 * The kind with the given wire name.
		 * <p>
		 * A name this Java has never heard of is {@link #UNKNOWN} rather than
		 * an error, so that a newer skop adding a wire type degrades to "cannot
		 * render this one parameter" instead of "cannot read this op".
		 *
		 * @param wireName the name skop used.
		 * @return the matching kind, or {@link #UNKNOWN}.
		 */
		public static Kind forWire(String wireName) {
			for (Kind kind : values()) {
				if (kind.wireName.equals(wireName)) return kind;
			}
			return UNKNOWN;
		}
	}

	/** One member of an enum parameter: what to show, and what to send. */
	public static class Choice {

		private final String name;
		private final Object value;

		public Choice(String name, Object value) {
			this.name = name;
			this.value = value;
		}

		/** The member's name, which is what a user should see. */
		public String name() {
			return name;
		}

		/** The member's value, which is what the worker must be sent. */
		public Object value() {
			return value;
		}

		@Override
		public String toString() {
			return name + "=" + value;
		}

		static Choice fromJson(Map<String, Object> data) {
			return new Choice(Wire.string(data, "name"), data.get("value"));
		}
	}

	private final Kind kind;
	private final String wireName;
	private final List<Choice> choices;
	private final boolean nullable;
	private final String detail;

	public TypeSpec(Kind kind, String wireName, List<Choice> choices,
		boolean nullable, String detail)
	{
		this.kind = kind;
		this.wireName = wireName;
		this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
		this.nullable = nullable;
		this.detail = detail;
	}

	public Kind kind() {
		return kind;
	}

	/**
	 * The name exactly as skop spelled it.
	 * <p>
	 * Differs from {@code kind().wireName()} only when skop names a type this
	 * Java does not know, which is precisely the case a diagnostic wants to
	 * report accurately.
	 */
	public String wireName() {
		return wireName;
	}

	/** The members of an enum type, or empty for every other kind. */
	public List<Choice> choices() {
		return choices;
	}

	/** Whether the parameter may be left empty -- it was {@code Optional[T]}. */
	public boolean nullable() {
		return nullable;
	}

	/** How the annotation was spelled, for a message about an UNKNOWN type. */
	public String detail() {
		return detail;
	}

	/** Whether a dialog can offer a widget for this at all. */
	public boolean renderable() {
		return kind != Kind.UNKNOWN;
	}

	@Override
	public String toString() {
		return detail == null ? wireName : wireName + "(" + detail + ")";
	}

	public static TypeSpec fromJson(Map<String, Object> data) {
		String name = Wire.string(data, "name");
		List<Choice> choices = new ArrayList<>();
		for (Map<String, Object> choice : Wire.maps(data, "choices")) {
			choices.add(Choice.fromJson(choice));
		}
		return new TypeSpec(Kind.forWire(name), name, choices,
			Wire.bool(data, "nullable", false),
			Wire.optionalString(data, "detail"));
	}
}
