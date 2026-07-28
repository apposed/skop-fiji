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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apposed.skop.fiji.wire.AdaptationPlan;
import org.apposed.skop.fiji.wire.AxesSpec;
import org.apposed.skop.fiji.wire.ParamSpec;
import org.scijava.ItemVisibility;
import org.scijava.module.Module;
import org.scijava.module.MutableModuleItem;

/**
 * The per-axis decisions, as widgets.
 * <p>
 * skop supplies a default plan and the warnings against it, never a veto:
 * which of a caller's axes fills which slot, and what becomes of the ones left
 * over, is a property of the experiment rather than of the op. Whether a stack
 * should be processed plane by plane or as a volume is something only the
 * person who acquired it knows. This is where they get to say.
 * <p>
 * Two kinds of item, per image parameter:
 * <ul>
 * <li>one <strong>slot</strong> combo per axis the op consumes, choosing which
 * of the image's axes fills it;</li>
 * <li>one <strong>disposition</strong> combo per axis left over -- iterate over
 * it, take a single position, or (for a variadic op) hand it through whole --
 * with a position spinner beside it.</li>
 * </ul>
 * The items are built from a default plan, so the dialog opens showing what
 * would have happened anyway. Nothing here changes the default; it only makes
 * it visible and editable, which is the difference between a fallback and a
 * feature.
 * <p>
 * The circularity -- which axes are "left over" depends on the mapping the
 * user is still choosing -- is settled by offering dispositions for the axes
 * the <em>default</em> plan left over. Change a slot and a different axis may
 * become leftover; it then takes skop's default, exactly as it would have
 * before. So an unattended item is never wrong, only absent.
 *
 * @author Curtis Rueden
 */
public final class AxisMapping {

	private AxisMapping() {
		// Prevent instantiation of utility class.
	}

	/** Prefix for every item this class adds, and for none that it does not. */
	public static final String PREFIX = "axis.";

	/** Choice meaning "leave this optional slot empty". */
	public static final String NONE = "(none)";

	/** Disposition choices, as a user reads them. */
	public static final String ITERATE = "Iterate over every position";
	public static final String SELECT = "Use a single position";
	public static final String PASS = "Pass through to the op";

	/**
	 * Adds the mapping items for one image parameter to a module's own info.
	 *
	 * @param info the module info to add to -- a per-instance one, since the
	 *          items depend on the image this run was given.
	 * @param param the image parameter.
	 * @param labels the image's axis labels, in numpy order.
	 * @param shape the image's shape, in numpy order.
	 * @param plan skop's default plan for this parameter.
	 */
	public static void addItems(OpModuleInfo info, ParamSpec param,
		List<String> labels, List<Integer> shape, AdaptationPlan plan)
	{
		AxesSpec axes = param.axes();
		if (axes == null) return;

		List<String> axisChoices = new ArrayList<>();
		for (int i = 0; i < labels.size(); i++) axisChoices.add(choice(labels, shape, i));

		List<AxesSpec.Slot> slots = axes.slots();
		Integer[] mapping = plan.mapping();
		for (int s = 0; s < slots.size(); s++) {
			AxesSpec.Slot slot = slots.get(s);
			List<String> choices = new ArrayList<>(axisChoices);
			if (slot.optional()) choices.add(NONE);

			MutableModuleItem<String> item = item(info, slotName(param.name(), s));
			item.setLabel(slotLabel(param, slot, s));
			item.setDescription("Which of " + param.name() +
				"'s axes fills the op's " + slot + " slot.");
			item.setChoices(choices);
			item.setDefaultValue(s < mapping.length && mapping[s] != null
				? axisChoices.get(mapping[s]) : NONE);
			info.addInput(item);
		}

		for (int axis : leftover(plan)) {
			List<String> choices = new ArrayList<>();
			choices.add(ITERATE);
			choices.add(SELECT);
			if (axes.variadic()) choices.add(PASS);

			MutableModuleItem<String> item =
				item(info, dispositionName(param.name(), axis));
			item.setLabel("What to do with " + name(labels, axis));
			item.setDescription(
				"The op does not consume this axis, so something has to be " +
					"decided about it. Iterating never discards data.");
			item.setChoices(choices);
			item.setDefaultValue(defaultDisposition(plan, axis));
			info.addInput(item);

			int size = axis < shape.size() ? shape.get(axis) : 1;
			if (size > 1) {
				MutableModuleItem<Long> position =
					position(info, positionName(param.name(), axis));
				position.setLabel(name(labels, axis) + " position");
				position.setDescription("Used only when '" +
					name(labels, axis) + "' is set to \"" + SELECT + "\".");
				position.setMinimumValue(0L);
				position.setMaximumValue((long) (size - 1));
				position.setDefaultValue(selectedPosition(plan, axis));
				info.addInput(position);
			}
		}
	}


	/**
	 * The slot mapping a user chose, or null if they were not asked.
	 *
	 * @param module the module being run.
	 * @param param the image parameter.
	 * @param slots how many slots the op declares.
	 * @param labels the image's axis labels, in numpy order.
	 * @return one axis index (or null) per slot, or null if there are no items.
	 */
	public static List<Integer> mapping(Module module, String param, int slots,
		List<String> labels)
	{
		List<Integer> mapping = new ArrayList<>(slots);
		boolean any = false;
		for (int s = 0; s < slots; s++) {
			Object value = module.getInput(slotName(param, s));
			if (value == null) {
				mapping.add(null);
				continue;
			}
			any = true;
			mapping.add(indexOf(String.valueOf(value), labels));
		}
		return any ? mapping : null;
	}

	/**
	 * The dispositions a user chose, by axis index.
	 *
	 * @param module the module being run.
	 * @param param the image parameter.
	 * @param labels the image's axis labels, in numpy order.
	 * @return the dispositions, in skop's vocabulary; empty if none were set.
	 */
	public static Map<Integer, String> dispositions(Module module, String param,
		List<String> labels)
	{
		Map<Integer, String> chosen = new LinkedHashMap<>();
		for (int axis = 0; axis < labels.size(); axis++) {
			Object value = module.getInput(dispositionName(param, axis));
			if (value == null) continue;
			String wire = toWire(String.valueOf(value));
			if (wire != null) chosen.put(axis, wire);
		}
		return chosen;
	}

	/**
	 * The positions a user chose, by axis index.
	 * <p>
	 * Only consulted by skop for an axis being indexed down to one position,
	 * so a value left over from a disposition the user then changed is
	 * harmless.
	 *
	 * @param module the module being run.
	 * @param param the image parameter.
	 * @param labels the image's axis labels, in numpy order.
	 * @return the positions, keyed by axis index as a string, as JSON wants.
	 */
	public static Map<String, Integer> positions(Module module, String param,
		List<String> labels)
	{
		Map<String, Integer> chosen = new LinkedHashMap<>();
		for (int axis = 0; axis < labels.size(); axis++) {
			Object value = module.getInput(positionName(param, axis));
			if (value instanceof Number) {
				chosen.put(String.valueOf(axis), ((Number) value).intValue());
			}
		}
		return chosen;
	}

	// -- item names --------------------------------------------------------

	public static String slotName(String param, int slot) {
		return PREFIX + param + ".slot" + slot;
	}

	public static String dispositionName(String param, int axis) {
		return PREFIX + param + ".axis" + axis;
	}

	public static String positionName(String param, int axis) {
		return PREFIX + param + ".axis" + axis + ".position";
	}

	// -- helpers -----------------------------------------------------------

	private static MutableModuleItem<String> item(OpModuleInfo info, String name) {
		org.scijava.module.DefaultMutableModuleItem<String> item =
			new org.scijava.module.DefaultMutableModuleItem<>(info, name, String.class);
		item.setIOType(org.scijava.ItemIO.INPUT);
		item.setVisibility(ItemVisibility.NORMAL);
		item.setRequired(false);
		item.setPersisted(false);
		return item;
	}

	private static MutableModuleItem<Long> position(OpModuleInfo info, String name) {
		org.scijava.module.DefaultMutableModuleItem<Long> item =
			new org.scijava.module.DefaultMutableModuleItem<>(info, name, Long.class);
		item.setIOType(org.scijava.ItemIO.INPUT);
		item.setRequired(false);
		item.setPersisted(false);
		return item;
	}

	/** An axis as a combo entry: its name and how long it is. */
	private static String choice(List<String> labels, List<Integer> shape, int axis) {
		String size = axis < shape.size() ? " (" + shape.get(axis) + ")" : "";
		return name(labels, axis) + size;
	}

	/** One axis, named if it has a name and numbered if it does not. */
	private static String name(List<String> labels, int axis) {
		String label = axis < labels.size() ? labels.get(axis) : null;
		return label == null || label.isEmpty()
			? "axis " + axis : label.toUpperCase();
	}

	private static String slotLabel(ParamSpec param, AxesSpec.Slot slot, int index) {
		String name = slot.name() == null ? "axis " + (index + 1)
			: slot.name().toUpperCase();
		return Params.label(param.name()) + ": " + name;
	}

	private static int indexOf(String choice, List<String> labels) {
		for (int axis = 0; axis < labels.size(); axis++) {
			// The choice carries the size in brackets; match on the name.
			if (choice.startsWith(name(labels, axis))) return axis;
		}
		return -1;
	}

	private static int[] leftover(AdaptationPlan plan) {
		int[] iterate = plan.iterate();
		int[] passed = plan.passed();
		int[][] select = plan.select();
		int[] all = new int[iterate.length + passed.length + select.length];
		int n = 0;
		for (int axis : iterate) all[n++] = axis;
		for (int axis : passed) all[n++] = axis;
		for (int[] pair : select) all[n++] = pair[0];
		java.util.Arrays.sort(all);
		return all;
	}

	private static String defaultDisposition(AdaptationPlan plan, int axis) {
		for (int i : plan.passed()) {
			if (i == axis) return PASS;
		}
		for (int[] pair : plan.select()) {
			if (pair[0] == axis) return SELECT;
		}
		return ITERATE;
	}

	private static long selectedPosition(AdaptationPlan plan, int axis) {
		for (int[] pair : plan.select()) {
			if (pair[0] == axis) return pair[1];
		}
		return 0L;
	}

	private static String toWire(String choice) {
		if (ITERATE.equals(choice)) return AdaptationPlan.ITERATE;
		if (SELECT.equals(choice)) return AdaptationPlan.SELECT;
		if (PASS.equals(choice)) return AdaptationPlan.PASS;
		return null;
	}
}
