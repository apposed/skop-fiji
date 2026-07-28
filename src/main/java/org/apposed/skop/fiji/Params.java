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
package org.apposed.skop.fiji;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apposed.skop.fiji.wire.OutputSpec;
import org.apposed.skop.fiji.wire.ParamSpec;
import org.apposed.skop.fiji.wire.Role;
import org.apposed.skop.fiji.wire.TypeSpec;
import org.scijava.ItemIO;
import org.scijava.module.DefaultMutableModuleItem;
import org.scijava.module.ModuleInfo;
import org.scijava.module.MutableModuleItem;
import org.scijava.widget.NumberWidget;

/**
 * One of skop's parameters, as one widget in a generated dialog.
 * <p>
 * The mapping is mostly obvious -- an {@code int} is a number field, a
 * {@code bool} is a checkbox -- and the interesting cases are the two at the
 * edges. An {@code enum} becomes a {@code String} item with its choices set,
 * because the Python enum class does not exist over here and its
 * <em>values</em> are what the worker rebuilds it from. And an
 * {@code unknown} type becomes no widget at all: see
 * {@link #item(ModuleInfo, ParamSpec, String)}.
 *
 * @author Curtis Rueden
 */
public final class Params {

	private Params() {
		// Prevent instantiation of utility class.
	}

	/**
	 * The magicgui widget names skop's UI hints use, and their SciJava
	 * equivalents. The vocabulary is magicgui's because that is where skop's
	 * hints came from; a hint not named here is ignored rather than an error,
	 * since a hint is advice about presentation and never a requirement.
	 */
	private static String widgetStyle(String widgetType) {
		if (widgetType == null) return null;
		switch (widgetType) {
			case "FloatSlider":
			case "Slider":
				return NumberWidget.SLIDER_STYLE;
			case "SpinBox":
			case "FloatSpinBox":
				return NumberWidget.SPINNER_STYLE;
			default:
				return null;
		}
	}

	/**
	 * Builds the module item for a parameter, or null if none can be built.
	 * <p>
	 * Null is the {@code unknown}-type case, and it is a deliberate outcome
	 * rather than a failure. A front end that cannot render one parameter
	 * leaves it at its default and says so; only if the parameter is
	 * <em>required</em> does the op become unrunnable, and then the honest
	 * thing is to name the parameter rather than to hide the op. See
	 * {@link org.apposed.skop.fiji.wire.OpSpec#blockingParams()}.
	 *
	 * @param info the module info the item belongs to.
	 * @param param the parameter to render.
	 * @param description the parameter's documentation, or null.
	 * @return the item, or null if this parameter cannot be a widget.
	 */
	public static MutableModuleItem<?> item(ModuleInfo info, ParamSpec param,
		String description)
	{
		Class<?> type = javaType(param);
		if (type == null) return null;

		MutableModuleItem<?> item = create(info, param.name(), type);
		item.setIOType(ItemIO.INPUT);
		item.setLabel(label(param.name()));
		if (description != null) item.setDescription(description);

		// Note: an optional parameter is never *required* of a user, because
		// skop already has an answer for it. A nullable one is not required
		// either -- None is a value the op accepts.
		item.setRequired(param.required() && !param.type().nullable());

		applyDefault(item, param);
		applyChoices(item, param);
		applyHints(item, param.ui());
		return item;
	}

	/** The output item for one of an op's results. */
	public static MutableModuleItem<?> output(ModuleInfo info, OutputSpec output) {
		MutableModuleItem<?> item =
			create(info, output.name(), outputType(output));
		item.setIOType(ItemIO.OUTPUT);
		item.setLabel(label(output.name()));
		return item;
	}

	/**
	 * The Java type an output is displayed as.
	 * <p>
	 * Unlike an input, an output has no {@code unknown} escape hatch: skop
	 * computed a value and it has to go somewhere. So anything this side
	 * cannot type becomes a String, which is worse than a real widget and
	 * infinitely better than a discarded result.
	 *
	 * @param output the output.
	 * @return the type to declare the output item as.
	 */
	public static Class<?> outputType(OutputSpec output) {
		Class<?> type = javaType(output.type(), output.role());
		return type == null ? String.class : type;
	}

	private static <T> MutableModuleItem<T> create(ModuleInfo info, String name,
		Class<T> type)
	{
		return new DefaultMutableModuleItem<>(info, name, type);
	}

	/**
	 * The Java type a parameter is harvested as.
	 *
	 * @param param the parameter.
	 * @return the type, or null if this side has no widget for it.
	 */
	public static Class<?> javaType(ParamSpec param) {
		return javaType(param.type(), param.role());
	}

	/**
	 * The Java type a value of the given wire type and role is handled as.
	 *
	 * @param type the wire type.
	 * @param role what the value means, or null.
	 * @return the type, or null if this side has no widget for it.
	 */
	public static Class<?> javaType(TypeSpec type, Role role) {
		switch (type.kind()) {
			case INT:
				// Note: Long rather than Integer, because a size in voxels is
				// not obliged to fit in 31 bits and a dialog should not be the
				// thing that decides it must.
				return Long.class;
			case FLOAT:
				return Double.class;
			case STR:
				return String.class;
			case BOOL:
				return Boolean.class;
			case PATH:
				return File.class;
			case ENUM:
				// A String with choices: the Python class is not here, and its
				// values are what the worker rebuilds the member from.
				return String.class;
			case NDARRAY:
				return Roles.type(role);
			case UNKNOWN:
			default:
				return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static void applyDefault(MutableModuleItem<?> item, ParamSpec param) {
		if (param.required()) return;
		Object value = coerce(item.getType(), param.defaultValue());
		if (value != null) ((MutableModuleItem<Object>) item).setDefaultValue(value);
	}

	@SuppressWarnings("unchecked")
	private static void applyChoices(MutableModuleItem<?> item, ParamSpec param) {
		if (param.type().kind() != TypeSpec.Kind.ENUM) return;
		List<String> choices = new ArrayList<>();
		for (TypeSpec.Choice choice : param.type().choices()) {
			choices.add(String.valueOf(choice.value()));
		}
		if (!choices.isEmpty()) {
			((MutableModuleItem<String>) item).setChoices(choices);
		}
	}

	@SuppressWarnings("unchecked")
	private static void applyHints(MutableModuleItem<?> item,
		Map<String, Object> ui)
	{
		if (ui.isEmpty()) return;
		MutableModuleItem<Object> mutable = (MutableModuleItem<Object>) item;

		Object label = ui.get("label");
		if (label instanceof String) mutable.setLabel((String) label);

		Object tooltip = ui.get("tooltip");
		if (tooltip instanceof String) mutable.setDescription((String) tooltip);

		Object min = coerce(item.getType(), ui.get("min"));
		if (min != null) mutable.setMinimumValue(min);

		Object max = coerce(item.getType(), ui.get("max"));
		if (max != null) mutable.setMaximumValue(max);

		Object step = ui.get("step");
		if (step instanceof Number) mutable.setStepSize((Number) step);

		String style = widgetStyle(asString(ui.get("widget_type")));
		if (style != null) mutable.setWidgetStyle(style);
	}

	private static String asString(Object value) {
		return value instanceof String ? (String) value : null;
	}

	/**
	 * A JSON value as the Java type an item wants.
	 * <p>
	 * JSON has one number type and Java has several, so skop's {@code 2.0}
	 * arrives as whatever the parser felt like and has to become a Double for
	 * a Double item and a Long for a Long one. A value that cannot become the
	 * item's type is dropped rather than forced: a default that does not fit
	 * is a wrong default, and no default is better than a wrong one.
	 */
	private static Object coerce(Class<?> type, Object value) {
		if (value == null) return null;
		if (type.isInstance(value)) return value;
		if (value instanceof Number) {
			Number number = (Number) value;
			if (type == Long.class) return number.longValue();
			if (type == Double.class) return number.doubleValue();
			if (type == String.class) return number.toString();
		}
		if (type == String.class) return String.valueOf(value);
		if (type == File.class && value instanceof String) {
			return new File((String) value);
		}
		return null;
	}

	/**
	 * A parameter name as a dialog should show it: {@code label_objects}
	 * becomes {@code Label objects}. Python's naming convention is not a
	 * user's.
	 */
	public static String label(String name) {
		String spaced = name.replace('_', ' ').trim();
		if (spaced.isEmpty()) return name;
		return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
	}
}
