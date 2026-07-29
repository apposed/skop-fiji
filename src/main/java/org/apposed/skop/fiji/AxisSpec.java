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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apposed.skop.fiji.wire.AdaptationPlan;
import org.apposed.skop.fiji.wire.AxesSpec;

/**
 * The per-axis decisions, as one line of text.
 * <p>
 * skop supplies a default plan and the warnings against it, never a veto:
 * which of a caller's axes fills which slot, and what becomes of the ones left
 * over, is a property of the experiment rather than of the op. Whether a stack
 * is processed plane by plane or as a volume is something only the person who
 * acquired it knows. This is how they say it.
 * <p>
 * <strong>One token per axis, in ImgLib2 order</strong> -- x first, the order
 * Fiji shows and the reverse of the one skop uses. Position identifies the
 * axis; the token says what becomes of it:
 *
 * <table>
 *   <caption>Tokens</caption>
 *   <tr><td>{@code y}</td><td>this axis fills the op's {@code y} slot</td></tr>
 *   <tr><td>{@code *}</td><td>fills the next slot the op named nothing in particular</td></tr>
 *   <tr><td>{@code z!}</td><td>iterate: call the op once per position</td></tr>
 *   <tr><td>{@code z=27}</td><td>run at index 27 and discard the rest</td></tr>
 *   <tr><td>{@code z+}</td><td>hand the axis to the op whole (variadic ops only)</td></tr>
 * </table>
 *
 * So an XYZCT image fed to an op declaring {@code c? x y} might read
 * {@code "x y z=27 c t!"}: X and Y fill the slots they are named for, C fills
 * the optional channel slot, Z is pinned to one plane, and the op runs once
 * per timepoint.
 * <p>
 * The name in front of {@code !}, {@code =} or {@code +} is a comment -- the
 * <em>position</em> says which axis is meant, and the name is there so the
 * line reads as something rather than as punctuation. It may be left out
 * ({@code "x y ! c !"} is legal), and it is checked only far enough to catch
 * the mistake worth catching: a name belonging to a <em>different</em> axis of
 * the same image is a line whose tokens are in the wrong order, and that is
 * refused rather than obeyed.
 * <p>
 * An empty line means skop's own default plan, which is what every run got
 * before this existed and never discards data.
 * <p>
 * A text field rather than a row of combo boxes, because SciJava's input
 * harvester builds its panel once: a combo that changes which <em>other</em>
 * widgets ought to exist has no way to bring them into being. One line of text
 * has no such problem, and reads back in a recorded macro as the whole
 * decision rather than as eight separate ones.
 *
 * @author Curtis Rueden
 */
public final class AxisSpec {

	private AxisSpec() {
		// Prevent instantiation of utility class.
	}

	/** Call the op once per position of this axis. */
	public static final char ITERATE = '!';

	/** Hand this axis to the op whole. Variadic ops only. */
	public static final char PASS = '+';

	/** Run at one position of this axis, and discard the rest. */
	public static final char SELECT = '=';

	/** Fills a slot the op named nothing in particular. */
	public static final String WILDCARD = "*";

	/** What a dialog should say about the syntax, in one tooltip. */
	public static final String SYNTAX =
		"One token per axis, in the order Fiji lists them (x first). " +
		"A slot name (\"y\") feeds that axis to the op; \"z!\" iterates over " +
		"it; \"z=27\" runs at one position; \"z+\" hands it to the op whole. " +
		"Leave blank for scikit-ops' own choice, which never discards data.";

	/**
	 * A plan, written out.
	 * <p>
	 * What the field is pre-filled with, so that a dialog opens showing what
	 * would have happened anyway. Making the default visible is most of the
	 * point: it is also the syntax lesson.
	 *
	 * @param plan the plan to describe.
	 * @param axes what the op declared it consumes.
	 * @return the spec, in ImgLib2 order.
	 */
	public static String format(AdaptationPlan plan, AxesSpec axes) {
		List<String> labels = plan.inputAxes();
		int n = labels.size();
		Integer[] mapping = plan.mapping();
		List<AxesSpec.Slot> slots = axes.slots();

		String[] tokens = new String[n];
		for (int slot = 0; slot < mapping.length && slot < slots.size(); slot++) {
			Integer axis = mapping[slot];
			if (axis == null || axis < 0 || axis >= n) continue;
			String name = slots.get(slot).name();
			tokens[axis] = name == null ? WILDCARD : name;
		}
		for (int[] pair : plan.select()) {
			tokens[pair[0]] = label(labels, pair[0]) + SELECT + pair[1];
		}
		for (int axis : plan.passed()) {
			tokens[axis] = label(labels, axis) + PASS;
		}
		for (int axis : plan.iterate()) {
			tokens[axis] = label(labels, axis) + ITERATE;
		}
		for (int axis = 0; axis < n; axis++) {
			// An axis the plan mentioned nowhere is one the op consumed under a
			// slot this Java did not match up; iterating is the safe reading.
			if (tokens[axis] == null) tokens[axis] = label(labels, axis) + ITERATE;
		}

		// The plan is in numpy order and a person reads ImgLib2 order.
		StringBuilder sb = new StringBuilder();
		for (int axis = n - 1; axis >= 0; axis--) {
			if (sb.length() > 0) sb.append(' ');
			sb.append(tokens[axis]);
		}
		return sb.toString();
	}

	/**
	 * Reads a spec into the arguments {@code skop.plan} takes.
	 *
	 * @param spec the line a user wrote, or null/blank for skop's default.
	 * @param axes what the op declared it consumes.
	 * @param labels the image's axis labels, in numpy order.
	 * @param shape the image's shape, in numpy order.
	 * @return the request, or null if the line was blank.
	 * @throws IllegalArgumentException if the line does not describe this image.
	 */
	public static Request parse(String spec, AxesSpec axes, List<String> labels,
		List<Integer> shape)
	{
		if (spec == null || spec.trim().isEmpty()) return null;

		int n = labels.size();
		List<String> tokens = Arrays.asList(spec.trim().split("[\\s,]+"));
		if (tokens.size() != n) {
			throw new IllegalArgumentException("The axis mapping has " +
				tokens.size() + " token(s) but the image has " + n + " axes (" +
				readable(labels) + "). Write one token per axis, x first.");
		}

		List<AxesSpec.Slot> slots = axes.slots();
		Integer[] mapping = new Integer[slots.size()];
		Map<Integer, String> dispositions = new LinkedHashMap<>();
		Map<String, Integer> positions = new LinkedHashMap<>();

		for (int i = 0; i < n; i++) {
			// Token i is the i'th ImgLib2 axis, which is the last-but-i'th
			// numpy one. This single line is the whole order flip.
			int axis = n - 1 - i;
			token(tokens.get(i), axis, labels, shape, axes, slots, mapping,
				dispositions, positions);
		}
		return new Request(Arrays.asList(mapping), dispositions, positions);
	}

	private static void token(String token, int axis, List<String> labels,
		List<Integer> shape, AxesSpec axes, List<AxesSpec.Slot> slots,
		Integer[] mapping, Map<Integer, String> dispositions,
		Map<String, Integer> positions)
	{
		int equals = token.indexOf(SELECT);
		char last = token.isEmpty() ? 0 : token.charAt(token.length() - 1);

		if (equals >= 0) {
			checkComment(token.substring(0, equals), axis, labels);
			int position = position(token.substring(equals + 1), token);
			int size = axis < shape.size() ? shape.get(axis) : 0;
			if (position < 0 || position >= size) {
				throw new IllegalArgumentException("'" + token + "': " +
					name(labels, axis) + " has " + size + " position(s), so " +
					position + " is not one of them.");
			}
			dispositions.put(axis, AdaptationPlan.SELECT);
			positions.put(String.valueOf(axis), position);
			return;
		}
		if (last == ITERATE) {
			checkComment(token.substring(0, token.length() - 1), axis, labels);
			dispositions.put(axis, AdaptationPlan.ITERATE);
			return;
		}
		if (last == PASS) {
			checkComment(token.substring(0, token.length() - 1), axis, labels);
			if (!axes.variadic()) {
				throw new IllegalArgumentException("'" + token + "': this op is " +
					"not variadic, so it cannot be handed extra axes. Iterate " +
					"over " + name(labels, axis) + ", or pick one position.");
			}
			dispositions.put(axis, AdaptationPlan.PASS);
			return;
		}
		slot(token, axis, labels, slots, mapping);
	}

	/** Fills the slot a bare token names. */
	private static void slot(String token, int axis, List<String> labels,
		List<AxesSpec.Slot> slots, Integer[] mapping)
	{
		int wildcards = 0;
		for (int s = 0; s < slots.size(); s++) {
			AxesSpec.Slot slot = slots.get(s);
			boolean wildcard = slot.name() == null;
			if (wildcard) wildcards++;
			boolean matches = wildcard ? WILDCARD.equals(token)
				: slot.name().equalsIgnoreCase(token);
			if (!matches) continue;
			if (mapping[s] != null) {
				// A named slot exists once, so a second mention is a mistake.
				// Wildcards are interchangeable by definition, so a second '*'
				// simply means the next one.
				if (wildcard) continue;
				throw new IllegalArgumentException("'" + token + "' is used " +
					"twice; the op has one " + token + " slot, and " +
					name(labels, mapping[s]) + " already fills it.");
			}
			mapping[s] = axis;
			return;
		}
		if (WILDCARD.equals(token)) {
			throw new IllegalArgumentException("'" + WILDCARD + "' appears more " +
				"often than the op has axes it named nothing in particular (" +
				wildcards + ").");
		}
		throw new IllegalArgumentException("'" + token + "' is not one of this " +
			"op's axes (" + slotNames(slots) + "). To keep " +
			name(labels, axis) + " out of the op, write " + token + ITERATE +
			" to iterate over it or " + token + SELECT + "0 for one position.");
	}

	/**
	 * Refuses a name that belongs to a different axis of the same image.
	 * <p>
	 * The name in front of a modifier is a comment, and a comment that
	 * contradicts the code is worth more than one that merely says nothing: a
	 * token naming another of this image's own axes is a line written in the
	 * wrong order, which is the mistake this syntax is most likely to invite.
	 * A name matching nothing is left alone -- skop's axis vocabulary is open,
	 * and this side has no business ruling on it.
	 */
	private static void checkComment(String comment, int axis,
		List<String> labels)
	{
		if (comment.isEmpty()) return;
		if (matches(comment, labels, axis)) return;
		for (int other = 0; other < labels.size(); other++) {
			if (other == axis || !matches(comment, labels, other)) continue;
			throw new IllegalArgumentException("'" + comment + "' names this " +
				"image's " + name(labels, other) + " axis, but it is in the " +
				"position of " + name(labels, axis) + ". Tokens go in the order " +
				"Fiji lists the axes: " + readable(labels) + ".");
		}
	}

	private static boolean matches(String name, List<String> labels, int axis) {
		String label = axis < labels.size() ? labels.get(axis) : null;
		return label != null && label.equalsIgnoreCase(name);
	}

	private static int position(String text, String token) {
		try {
			return Integer.parseInt(text.trim());
		}
		catch (NumberFormatException exc) {
			throw new IllegalArgumentException("'" + token + "': " + SELECT +
				" wants a position, and '" + text + "' is not a number.");
		}
	}

	private static String label(List<String> labels, int axis) {
		String label = axis < labels.size() ? labels.get(axis) : null;
		return label == null ? "" : label;
	}

	/** One axis, named if it has a name and numbered if it does not. */
	private static String name(List<String> labels, int axis) {
		String label = label(labels, axis);
		return label.isEmpty() ? "axis " + axis : label;
	}

	/** The image's axes as a person reads them: ImgLib2 order, x first. */
	private static String readable(List<String> labels) {
		List<String> names = new ArrayList<>();
		for (int axis = labels.size() - 1; axis >= 0; axis--) {
			names.add(name(labels, axis));
		}
		return String.join(" ", names);
	}

	private static String slotNames(List<AxesSpec.Slot> slots) {
		if (slots.isEmpty()) return "it declares none";
		List<String> names = new ArrayList<>();
		for (AxesSpec.Slot slot : slots) {
			names.add(slot.name() == null ? WILDCARD : slot.name());
		}
		return String.join(" ", names);
	}

	/** What a parsed spec asks {@code skop.plan} for. */
	public static final class Request {

		private final List<Integer> mapping;
		private final Map<Integer, String> dispositions;
		private final Map<String, Integer> positions;

		Request(List<Integer> mapping, Map<Integer, String> dispositions,
			Map<String, Integer> positions)
		{
			this.mapping = mapping;
			this.dispositions = dispositions;
			this.positions = positions;
		}

		/** One numpy axis index (or null) per declared slot. */
		public List<Integer> mapping() {
			return mapping;
		}

		/** What becomes of each axis no slot took, by numpy axis index. */
		public Map<Integer, String> dispositions() {
			return dispositions;
		}

		/** Where to index each selected axis, keyed as JSON needs it. */
		public Map<String, Integer> positions() {
			return positions;
		}
	}
}
