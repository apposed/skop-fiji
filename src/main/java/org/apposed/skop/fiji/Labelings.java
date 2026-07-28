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
import java.util.List;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.appose.ShmImg;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.IntegerType;

import org.apposed.appose.NDArray;

/**
 * Label images, which are not pictures.
 * <p>
 * A segmentation comes back as an array of integer object IDs, and the
 * question is what to make of it. A glasbey-LUT {@code Dataset} is a
 * <em>rendering</em> of a label image; an {@link ImgLabeling} is a label
 * image. Picking the rendering as the representation would throw away the
 * structure that every downstream ImgLib2 consumer -- {@code LabelRegions},
 * every per-object measurement, every conversion to ROIs -- actually wants.
 * <p>
 * The index image is the array the worker wrote, unwrapped and not copied: an
 * {@code ImgLabeling} <em>is</em> a wrapper around an index image, which is
 * exactly the shape of what arrives.
 * <p>
 * Labels are the ID numbers as strings. skop does not send names -- an op
 * numbers its objects and says nothing more -- so inventing anything richer
 * here would be inventing information.
 *
 * @author Curtis Rueden
 */
public final class Labelings {

	private Labelings() {
		// Prevent instantiation of utility class.
	}

	/**
	 * A label array a worker produced, as an {@code ImgLabeling}.
	 *
	 * @param array the result array; its dtype must be an integer one.
	 * @return the labeling, over the array's own shared memory.
	 * @throws IllegalArgumentException if the array is not of integers.
	 */
	public static <I extends IntegerType<I> & NativeType<I>>
		ImgLabeling<String, I> toImgLabeling(NDArray array)
	{
		if (!isIntegral(array.dType())) {
			throw new IllegalArgumentException(
				"A label image must have an integer dtype, but this one is " +
					array.dType().label());
		}
		ShmImg<I> index = new ShmImg<>(array);
		return ImgLabeling.fromImageAndLabels(index, labelsUpTo(highest(index)));
	}

	/** Whether a label array can become a labeling at all. */
	public static boolean isIntegral(NDArray.DType dType) {
		switch (dType) {
			case INT8:
			case INT16:
			case INT32:
			case INT64:
			case UINT8:
			case UINT16:
			case UINT32:
			case UINT64:
				return true;
			default:
				return false;
		}
	}

	/**
	 * A labeling over an index image this side already has.
	 * <p>
	 * The other direction from {@link #toImgLabeling}, for an image that never
	 * came from a worker -- a label TIFF a user just opened, typically.
	 *
	 * @param index the index image; 0 is background.
	 * @return the labeling.
	 */
	public static <I extends IntegerType<I>> ImgLabeling<String, I>
		fromIndexImage(RandomAccessibleInterval<I> index)
	{
		return ImgLabeling.fromImageAndLabels(index, labelsUpTo(highest(index)));
	}

	/**
	 * The index image behind a labeling, as shared memory a worker can read.
	 * <p>
	 * A labeling that came back from an op is already backed by the block the
	 * worker wrote, so feeding it to the next op costs nothing at all. That is
	 * the case that matters: chaining a segmentation into a measurement is
	 * what a label image is for.
	 *
	 * @param labeling the labeling to send.
	 * @return an NDArray over its index image.
	 */
	public static NDArray toNDArray(ImgLabeling<?, ?> labeling) {
		return Images.toNDArray(labeling.getIndexImg());
	}

	/** Whether sending a labeling costs a copy. */
	public static boolean isShared(ImgLabeling<?, ?> labeling) {
		return Images.isShared(labeling.getIndexImg());
	}

	/**
	 * The largest index in an index image.
	 * <p>
	 * One pass over the pixels, which is the price of not being told. skop
	 * says an array holds labels; it does not say how many, and
	 * {@code ImgLabeling} needs one label per index value that occurs.
	 */
	private static <I extends IntegerType<I>> int highest(
		RandomAccessibleInterval<I> index)
	{
		int highest = 0;
		Cursor<I> cursor = net.imglib2.view.Views.iterable(index).cursor();
		while (cursor.hasNext()) {
			int value = cursor.next().getInteger();
			if (value > highest) highest = value;
		}
		return highest;
	}

	private static List<String> labelsUpTo(int highest) {
		List<String> labels = new ArrayList<>(highest);
		for (int i = 1; i <= highest; i++) labels.add(String.valueOf(i));
		return labels;
	}
}
