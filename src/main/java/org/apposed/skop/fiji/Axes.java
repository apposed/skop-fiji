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
import java.util.Collections;
import java.util.List;

import org.apposed.appose.NDArray;

/**
 * Axis labels, and the order flip between ImgLib2 and numpy.
 * <p>
 * <strong>ImgLib2 is x-fastest; numpy is last-fastest.</strong> An image whose
 * ImgLib2 axes are {@code (X, Y, Z)} is a numpy array of shape
 * {@code (z, y, x)}. So the label list handed to skop is the ImgLib2 axis
 * order <em>reversed</em>, and so is any per-axis answer that comes back.
 * <p>
 * Getting this wrong does not crash. Every label still matches a slot, the
 * plan still looks reasonable, the op still runs -- on transposed data,
 * producing a plausible and wrong answer. That is the whole reason this is a
 * class of its own with tests of its own rather than a {@code reverse()} call
 * scattered through the front end: there is exactly one place to get it right,
 * and nothing downstream can detect it if that place is wrong.
 * <p>
 * Note that the <em>shape</em> needs no such care, because Appose's
 * {@link NDArray.Shape} already carries its own order and converts on demand.
 * Only the labels, which Appose knows nothing about, are this class's problem.
 *
 * @author Curtis Rueden
 * @see <a href="https://github.com/apposed/scikit-ops/blob/main/docs/spec/fiji-front-end.md">the spec</a>
 */
public final class Axes {

	private Axes() {
		// Prevent instantiation of utility class.
	}

	/**
	 * Turn ImgLib2-order axis labels into the numpy-order list skop expects.
	 *
	 * @param imglib2Labels one label per dimension, x first.
	 * @return the same labels, last-fastest, ready for {@code skop.plan}.
	 */
	public static List<String> toNumpy(List<String> imglib2Labels) {
		List<String> reversed = new ArrayList<>(imglib2Labels);
		Collections.reverse(reversed);
		return reversed;
	}

	/**
	 * Turn ImgLib2-order axis labels into the numpy-order list skop expects.
	 *
	 * @param imglib2Labels one label per dimension, x first.
	 * @return the same labels, last-fastest, ready for {@code skop.plan}.
	 */
	public static List<String> toNumpy(String... imglib2Labels) {
		return toNumpy(Arrays.asList(imglib2Labels));
	}

	/**
	 * Turn numpy-order axis labels back into ImgLib2 order.
	 * <p>
	 * The return trip: a plan's {@code output_axes} names the result's axes in
	 * numpy order, and calibrating the resulting image needs them x-first.
	 *
	 * @param numpyLabels one label per dimension, last-fastest.
	 * @return the same labels, x first.
	 */
	public static List<String> toImgLib2(List<String> numpyLabels) {
		// The flip is its own inverse; both directions are named anyway,
		// because a call site saying which way it is going is the point.
		return toNumpy(numpyLabels);
	}

	/**
	 * The numpy-order shape of an array, as skop's {@code plan} wants it.
	 * <p>
	 * A convenience over {@link NDArray.Shape#toIntArray(NDArray.Shape.Order)},
	 * so that a caller assembling a plan request reads the shape and the labels
	 * out of the same place and in the same order.
	 *
	 * @param array the array whose shape is wanted.
	 * @return the dimensions, last-fastest.
	 */
	public static List<Integer> numpyShape(NDArray array) {
		int[] dims = array.shape().toIntArray(NDArray.Shape.Order.C_ORDER);
		List<Integer> result = new ArrayList<>(dims.length);
		for (int dim : dims) result.add(dim);
		return result;
	}

	/**
	 * The index the same axis has in the other order.
	 * <p>
	 * skop answers per-axis questions -- which axis to iterate, which position
	 * to select -- by index into the numpy-order list. A viewer's sliders are
	 * indexed the other way.
	 *
	 * @param index an axis index in either order.
	 * @param numDimensions how many axes there are in total.
	 * @return the same axis's index in the other order.
	 */
	public static int flip(int index, int numDimensions) {
		if (index < 0 || index >= numDimensions) {
			throw new IndexOutOfBoundsException(
				"Axis " + index + " of " + numDimensions);
		}
		return numDimensions - 1 - index;
	}
}
