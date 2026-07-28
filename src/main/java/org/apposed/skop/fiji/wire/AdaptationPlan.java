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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How to get from the array a caller has to the one an op accepts.
 * <p>
 * skop computes this; nothing here recomputes it. That is deliberate: the
 * axis arithmetic is fiddly, it is already written and tested once, and a
 * second implementation in Java would be a second implementation to be wrong
 * in a way nothing detects -- a transposed array is still a valid array.
 * <p>
 * A plan is a value, not a verdict. skop's own is best-effort and never
 * discards data; whoever owns the data may edit the {@link #mapping()} or the
 * per-axis dispositions and ask for a different one. The
 * {@link #warnings()} say where the default looks doubtful.
 *
 * @author Curtis Rueden
 */
public class AdaptationPlan {

	/** What becomes of an input axis that no slot consumed. */
	public static final String ITERATE = "iterate";

	/** Index down to one position, discarding the rest. */
	public static final String SELECT = "select";

	/** Hand it to the op as an axis, for a variadic op to deal with. */
	public static final String PASS = "pass";

	private final String param;
	private final List<String> inputAxes;
	private final Integer[] mapping;
	private final int[][] select;
	private final int[] iterate;
	private final int[] passed;
	private final int[] transpose;
	private final List<String> outputAxes;
	private final int calls;
	private final boolean lossless;
	private final List<String> warnings;
	private final String summary;
	private final Map<String, Object> raw;

	private AdaptationPlan(String param, List<String> inputAxes, Integer[] mapping,
		int[][] select, int[] iterate, int[] passed, int[] transpose,
		List<String> outputAxes, int calls, boolean lossless,
		List<String> warnings, String summary, Map<String, Object> raw)
	{
		this.param = param;
		this.inputAxes = Collections.unmodifiableList(new ArrayList<>(inputAxes));
		this.mapping = mapping;
		this.select = select;
		this.iterate = iterate;
		this.passed = passed;
		this.transpose = transpose;
		this.outputAxes = Collections.unmodifiableList(new ArrayList<>(outputAxes));
		this.calls = calls;
		this.lossless = lossless;
		this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
		this.summary = summary;
		this.raw = raw;
	}

	/** Name of the parameter this plan is for. */
	public String param() {
		return param;
	}

	/** The caller's own axis labels, in numpy order: last axis fastest. */
	public List<String> inputAxes() {
		return inputAxes;
	}

	/**
	 * Which input axis fills which declared slot.
	 * <p>
	 * One entry per slot; null means an optional slot was left empty. This is
	 * the editable half of a plan -- everything else follows from it.
	 */
	public Integer[] mapping() {
		return mapping.clone();
	}

	/** Axes indexed down to a single position, as {@code {axis, index}} pairs. */
	public int[][] select() {
		return select.clone();
	}

	/** Axes the op is called once per position of. */
	public int[] iterate() {
		return iterate.clone();
	}

	/** Axes handed through to a variadic op whole. */
	public int[] passed() {
		return passed.clone();
	}

	/** The transposition applied after selection. */
	public int[] transpose() {
		return transpose.clone();
	}

	/** Axis labels of the result, in the caller's own vocabulary. */
	public List<String> outputAxes() {
		return outputAxes;
	}

	/** How many times the op will be called. */
	public int calls() {
		return calls;
	}

	/** Whether the plan feeds the op every voxel it was given. */
	public boolean lossless() {
		return lossless;
	}

	/**
	 * Where skop's default looks doubtful -- a slot fed an axis it did not ask
	 * for, typically. Worth showing; never worth refusing to run over.
	 */
	public List<String> warnings() {
		return warnings;
	}

	/** One line saying what will happen, for a front end to show. */
	public String summary() {
		return summary;
	}

	/**
	 * The JSON this was read from, ready to be sent straight back.
	 * <p>
	 * A plan makes a round trip: skop computes it, a user may edit it, and it
	 * goes back to the worker to be executed. Sending back exactly what
	 * arrived means a field this Java does not model yet still survives the
	 * trip.
	 */
	public Map<String, Object> toJson() {
		return raw;
	}

	@Override
	public String toString() {
		return param + ": " + summary;
	}

	public static AdaptationPlan fromJson(Map<String, Object> data) {
		List<Object> rawSelect = Wire.list(data, "select");
		int[][] select = new int[rawSelect.size()][];
		for (int i = 0; i < select.length; i++) {
			List<Object> pair = Wire.asList(rawSelect.get(i), "select entry");
			if (pair.size() != 2) throw new IllegalArgumentException(
				"A select entry is an [axis, index] pair, but got: " + pair);
			select[i] = new int[] {
				Wire.toInt(pair.get(0), "select axis"),
				Wire.toInt(pair.get(1), "select index")
			};
		}
		return new AdaptationPlan(
			Wire.string(data, "param"),
			Arrays.asList(Wire.strings(data, "input_axes")),
			Wire.boxedInts(data, "mapping"),
			select,
			Wire.ints(data, "iterate"),
			Wire.ints(data, "passed"),
			Wire.ints(data, "transpose"),
			Arrays.asList(Wire.strings(data, "output_axes")),
			Wire.integer(data, "calls", 1),
			Wire.bool(data, "lossless", true),
			Arrays.asList(Wire.strings(data, "warnings")),
			Wire.string(data, "summary"),
			Collections.unmodifiableMap(new LinkedHashMap<>(data)));
	}
}
