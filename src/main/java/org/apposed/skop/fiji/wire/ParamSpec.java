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

import java.util.List;
import java.util.Map;

/**
 * One of an op's parameters: one input widget, in a generated dialog.
 *
 * @author Curtis Rueden
 */
public class ParamSpec {

	private final String name;
	private final TypeSpec type;
	private final boolean required;
	private final Object defaultValue;
	private final Direction direction;
	private final Map<String, Object> ui;
	private final Role role;
	private final AxesSpec axes;
	private final List<Choice> choices;
	private final ParamsFor paramsFor;

	public ParamSpec(String name, TypeSpec type, boolean required,
		Object defaultValue, Direction direction, Map<String, Object> ui,
		Role role, AxesSpec axes)
	{
		this(name, type, required, defaultValue, direction, ui, role, axes,
			java.util.Collections.emptyList(), null);
	}

	public ParamSpec(String name, TypeSpec type, boolean required,
		Object defaultValue, Direction direction, Map<String, Object> ui,
		Role role, AxesSpec axes, List<Choice> choices, ParamsFor paramsFor)
	{
		this.choices = java.util.Collections.unmodifiableList(
			new java.util.ArrayList<>(choices));
		this.paramsFor = paramsFor;
		this.name = name;
		this.type = type;
		this.required = required;
		this.defaultValue = defaultValue;
		this.direction = direction;
		this.ui = Wire.copy(ui);
		this.role = role;
		this.axes = axes;
	}

	public String name() {
		return name;
	}

	public TypeSpec type() {
		return type;
	}

	/** Whether the op has no default for this, so a value must be supplied. */
	public boolean required() {
		return required;
	}

	/**
	 * The op's default, or null when there is none.
	 * <p>
	 * Null is ambiguous on its own -- a nullable parameter may default to null
	 * -- so ask {@link #required()} rather than testing this for null.
	 */
	public Object defaultValue() {
		return defaultValue;
	}

	public Direction direction() {
		return direction;
	}

	/**
	 * magicgui-style widget hints: {@code widget_type}, {@code min},
	 * {@code max}, {@code step}, {@code label}.
	 * <p>
	 * The vocabulary is magicgui's because that is where it came from, and
	 * translating it is the front end's job -- a {@code FloatSlider} becomes
	 * {@code NumberWidget.SLIDER_STYLE}, and a hint nothing here understands
	 * is ignored rather than an error.
	 */
	public Map<String, Object> ui() {
		return ui;
	}

	/** What this parameter <em>is</em>, or null if skop did not say. */
	public Role role() {
		return role;
	}

	/** How many axes this parameter consumes, or null if it takes no array. */
	public AxesSpec axes() {
		return axes;
	}

	/**
	 * The ops this parameter may be filled with, for a workflow's chooser.
	 * <p>
	 * Empty for every ordinary parameter. A curated list rather than an
	 * inventory: it means "these have been tested together", where an
	 * inventory would mean only "these are installed".
	 *
	 * @return the choices, as label and op ID.
	 */
	public List<Choice> choices() {
		return choices;
	}

	/**
	 * The chooser whose settings this parameter holds, or null.
	 * <p>
	 * A chooser needs somewhere to put the chosen op's own arguments, and this
	 * parameter is that somewhere.
	 */
	public ParamsFor paramsFor() {
		return paramsFor;
	}

	/** One op a chooser offers: what to show, and which op it is. */
	public static class Choice {

		private final String label;
		private final String op;

		public Choice(String label, String op) {
			this.label = label;
			this.op = op;
		}

		/** What a menu should show. */
		public String label() {
			return label;
		}

		/** The op's ID, opaque as always. */
		public String op() {
			return op;
		}

		@Override
		public String toString() {
			return label + " (" + op + ")";
		}
	}

	/** Which chooser a settings parameter belongs to, and what it need not ask. */
	public static class ParamsFor {

		private final String chooser;
		private final List<String> binds;

		public ParamsFor(String chooser, List<String> binds) {
			this.chooser = chooser;
			this.binds = java.util.Collections.unmodifiableList(
				new java.util.ArrayList<>(binds));
		}

		/** Name of the parameter that chooses the op. */
		public String chooser() {
			return chooser;
		}

		/**
		 * The chosen op's parameters the workflow supplies itself.
		 * <p>
		 * A front end renders every <em>other</em> parameter of the chosen op
		 * and leaves these alone, which is what stops two stages that both take
		 * an image from asking for it twice.
		 */
		public List<String> binds() {
			return binds;
		}

		@Override
		public String toString() {
			return chooser + " minus " + binds;
		}
	}

	/** Whether a user should be asked for this at all. */
	public boolean harvestable() {
		// An output buffer is allocated, never asked for.
		return direction != Direction.OUT;
	}

	/**
	 * Whether leaving this parameter out would stop the op from running.
	 * <p>
	 * This is the question an unrenderable parameter turns on: an optional one
	 * is left at its default and mentioned, while a required one disables the
	 * run. See {@link TypeSpec}.
	 */
	public boolean blocking() {
		return required && !type.renderable();
	}

	@Override
	public String toString() {
		return name + ": " + type + (required ? "" : " = " + defaultValue);
	}

	public static ParamSpec fromJson(Map<String, Object> data) {
		Map<String, Object> axes = Wire.map(data, "axes");
		List<Choice> choices = new java.util.ArrayList<>();
		for (Map<String, Object> choice : Wire.maps(data, "choices")) {
			choices.add(new Choice(Wire.string(choice, "label"),
				Wire.string(choice, "op")));
		}
		Map<String, Object> paramsFor = Wire.map(data, "params_for");
		return new ParamSpec(
			Wire.string(data, "name"),
			TypeSpec.fromJson(Wire.map(data, "type")),
			Wire.bool(data, "required", false),
			data.get("default"),
			Direction.forWire(Wire.optionalString(data, "direction")),
			Wire.map(data, "ui"),
			Role.forWire(Wire.optionalString(data, "role")),
			axes.isEmpty() ? null : AxesSpec.fromJson(axes),
			choices,
			paramsFor.isEmpty() ? null : new ParamsFor(
				Wire.string(paramsFor, "chooser"),
				java.util.Arrays.asList(Wire.strings(paramsFor, "binds"))));
	}
}
