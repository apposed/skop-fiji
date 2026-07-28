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

import java.util.List;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImgPlus;
import net.imagej.axis.AxisType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.appose.ShmImg;
import net.imglib2.appose.WrappedNDArray;
import net.imglib2.img.Img;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;

import org.apposed.appose.NDArray;
import org.apposed.appose.SharedMemory;

/**
 * Images across the boundary, in both directions.
 * <p>
 * The trip out is the interesting one. A worker reads its input straight out
 * of shared memory, so an image that is <em>already</em> a {@link ShmImg}
 * crosses without being copied at all; anything else -- a Dataset a user just
 * opened, a virtual stack, a view -- is copied into one, exactly once. That is
 * the same single copy skop's Python runner pays for a plain
 * {@code numpy.ndarray}, and no more.
 * <p>
 * The trip back is a wrap rather than a copy: a result arrives as an
 * {@link NDArray} that the worker allocated, and becomes an {@code ImgPlus}
 * over the same block. The block outlives the call, so whoever takes the
 * Dataset owns it -- see {@link #adopt}.
 *
 * @author Curtis Rueden
 */
public final class Images {

	private Images() {
		// Prevent instantiation of utility class.
	}

	/**
	 * The shared memory array for an image, copying only if it must.
	 *
	 * @param image the image to send.
	 * @return an NDArray over the image's pixels.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static NDArray toNDArray(RandomAccessibleInterval<?> image) {
		RandomAccessibleInterval<?> unwrapped = unwrap(image);
		if (unwrapped instanceof WrappedNDArray) {
			// Already in shared memory: hand the worker the very same block.
			return ((WrappedNDArray) unwrapped).ndArray();
		}
		// Note: raw. A Dataset's pixel type is `? extends RealType<?>`, which
		// no amount of wildcard capture turns into the `T extends
		// NativeType<T>` that ShmImg wants. The cast is safe because every
		// pixel type a Dataset can hold is a NativeType; the type system just
		// cannot be told so through a Dataset's signature.
		return ShmImg.copyOf((RandomAccessibleInterval) unwrapped).ndArray();
	}

	/**
	 * Whether an image is already backed by shared memory.
	 * <p>
	 * Worth asking before a run only to say so; the conversion asks it anyway.
	 *
	 * @param image the image.
	 * @return whether sending it costs a copy.
	 */
	public static boolean isShared(RandomAccessibleInterval<?> image) {
		return unwrap(image) instanceof WrappedNDArray;
	}

	/**
	 * Unwraps an {@code ImgPlus} (or any other wrapper) down to what it wraps.
	 * <p>
	 * A Dataset is an ImgPlus is a wrapper around an Img, and only the
	 * innermost thing knows whether it is shared memory. Without this, every
	 * image would be copied, including one that had just come back from a
	 * worker.
	 * <p>
	 * <strong>The order of the two tests below matters.</strong> A
	 * {@code ShmImg} is <em>both</em> a {@link WrappedNDArray} and a
	 * {@code WrappedImg} around an ordinary {@code ArrayImg}, so unwrapping
	 * first walks straight past the shared memory and reports there is none --
	 * and every image gets copied, including the ones that never needed to be.
	 * Nothing fails when that happens; it is merely twice the memory and twice
	 * the traffic, forever.
	 */
	private static RandomAccessibleInterval<?> unwrap(
		RandomAccessibleInterval<?> image)
	{
		RandomAccessibleInterval<?> current = image;
		while (true) {
			if (current instanceof WrappedNDArray) return current;
			if (!(current instanceof net.imglib2.img.WrappedImg)) return current;
			Img<?> wrapped = ((net.imglib2.img.WrappedImg<?>) current).getImg();
			if (wrapped == null || wrapped == current) return current;
			current = wrapped;
		}
	}

	/**
	 * Allocates shared memory for an array of the given numpy-order shape.
	 * <p>
	 * Handles the empty case, which is ordinary rather than exceptional: a
	 * detector that finds nothing returns a {@code (0, 4)} array of boxes, and
	 * a shared memory block must have a positive size. The shape and dtype
	 * travel over a token block that nothing ever reads -- which is exactly
	 * what skop's own codec does on the Python side.
	 *
	 * @param dType the element type.
	 * @param numpyShape the shape, last axis fastest.
	 * @return the array.
	 */
	public static NDArray allocate(NDArray.DType dType, int... numpyShape) {
		NDArray.Shape shape =
			new NDArray.Shape(NDArray.Shape.Order.C_ORDER, numpyShape);
		return shape.numElements() == 0
			? new NDArray(dType, shape, SharedMemory.create(1))
			: new NDArray(dType, shape);
	}

	/**
	 * An NDArray a worker produced, as an image.
	 *
	 * @param array the result array.
	 * @param name what to call it.
	 * @param axes the result's axis labels <strong>in numpy order</strong>,
	 *          typically an {@code AdaptationPlan}'s {@code output_axes}, or
	 *          null to leave the axes unnamed.
	 * @return an ImgPlus over the array's shared memory.
	 */
	public static <T extends NativeType<T> & RealType<T>> ImgPlus<T> toImgPlus(
		NDArray array, String name, List<String> axes)
	{
		ShmImg<T> img = new ShmImg<>(array);
		AxisType[] types = axisTypes(axes, img.numDimensions());
		return types == null ? new ImgPlus<>(img, name)
			: new ImgPlus<>(img, name, types);
	}

	/**
	 * An NDArray a worker produced, as a Dataset ready to be shown.
	 *
	 * @param datasets the service that makes Datasets.
	 * @param array the result array.
	 * @param name what to call it.
	 * @param axes the result's axis labels in numpy order, or null.
	 * @return the Dataset.
	 */
	public static <T extends NativeType<T> & RealType<T>> Dataset toDataset(
		DatasetService datasets, NDArray array, String name, List<String> axes)
	{
		return datasets.create(Images.<T>toImgPlus(array, name, axes));
	}

	/**
	 * Hands ownership of a result's shared memory to the image wrapping it.
	 * <p>
	 * Appose's rule is that the host process unlinks. A result block is
	 * allocated by the worker and released by us, and "us" here is whoever
	 * holds the Dataset for as long as it is on screen -- which is nobody in
	 * particular, and certainly not the method that ran the op. So the block
	 * is left alive deliberately rather than by omission, and this method is
	 * where that decision is written down.
	 * <p>
	 * A more careful answer -- a display listener that unlinks when the window
	 * closes -- is worth having and is not here yet.
	 *
	 * @param array the result array, now owned by the image over it.
	 */
	public static void adopt(NDArray array) {
		// Note: intentionally does not close. See above.
	}

	/**
	 * Axis labels as ImageJ axis types, in ImgLib2 order.
	 *
	 * @param numpyAxes the labels in numpy order, or null.
	 * @param numDimensions how many axes the image has.
	 * @return the types, x first, or null if the labels say nothing usable.
	 */
	private static AxisType[] axisTypes(List<String> numpyAxes, int numDimensions) {
		if (numpyAxes == null || numpyAxes.size() != numDimensions) {
			// A count mismatch means an op reshaped its output, and guessing
			// which axis went where is exactly the guess not to make.
			return null;
		}
		List<String> ordered = Axes.toImgLib2(numpyAxes);
		AxisType[] types = new AxisType[numDimensions];
		for (int d = 0; d < numDimensions; d++) {
			String label = ordered.get(d);
			types[d] = label == null || label.isEmpty()
				? net.imagej.axis.Axes.unknown()
				: net.imagej.axis.Axes.get(canonical(label));
		}
		return types;
	}

	/**
	 * An axis label as ImageJ prefers to spell it.
	 * <p>
	 * skop hands back the caller's own vocabulary, folded to lower case -- a
	 * remapped axis keeps the name its owner gave it, and its owner here was
	 * ImageJ. So {@code "channel"} has to find its way back to
	 * {@code Axes.CHANNEL} rather than becoming a second, differently-spelled
	 * channel axis. Anything not in the short canonical list is passed through
	 * as-is and becomes an axis type of its own, which is correct: ImageJ's
	 * {@code Axes.get} interns by label.
	 */
	private static String canonical(String label) {
		switch (label.toLowerCase()) {
			case "x": return net.imagej.axis.Axes.X.getLabel();
			case "y": return net.imagej.axis.Axes.Y.getLabel();
			case "z": return net.imagej.axis.Axes.Z.getLabel();
			case "c": return net.imagej.axis.Axes.CHANNEL.getLabel();
			case "t": return net.imagej.axis.Axes.TIME.getLabel();
			default: return label;
		}
	}
}
