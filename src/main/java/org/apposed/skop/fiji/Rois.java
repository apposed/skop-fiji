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

import net.imagej.roi.DefaultROITree;
import net.imagej.roi.ROITree;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.appose.ShmImg;
import net.imglib2.converter.Converters;
import net.imglib2.roi.MaskPredicate;
import net.imglib2.roi.Masks;
import net.imglib2.roi.geom.GeomMasks;
import net.imglib2.roi.geom.real.Box;
import net.imglib2.roi.geom.real.PointMask;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

import org.apposed.appose.NDArray;
import org.apposed.skop.fiji.wire.Role;

/**
 * Boxes, points and masks, as ROIs.
 * <p>
 * Everything here comes back as a {@link ROITree}, which is ImageJ2's own way
 * of saying "some ROIs". imagej-legacy is what turns one into ImageJ1
 * {@code Roi}s and puts them on an image or in the ROI Manager, so nothing in
 * this project needs to know ImageJ1 exists.
 * <p>
 * <strong>{@code masks} is the role that comes out better here than in
 * napari.</strong> A napari Labels layer cannot show overlapping objects, so
 * {@code skop.masks} has to project a mask stack down to a label image before
 * anything can be seen at all. Fiji's ROI Manager holds overlapping ROIs
 * natively, so N masks become N ROIs and the projection becomes one of several
 * things a user may ask for rather than a precondition for seeing anything.
 * <p>
 * <strong>Coordinates are reversed here too.</strong> skop's boxes are
 * {@code [min_y, min_x, max_y, max_x]} and its points are in the axis order of
 * the image they came from, which is numpy's; ImgLib2 wants x first. It is the
 * same trap as {@link Axes}, wearing a different hat, and it is just as quiet
 * when you get it wrong -- a transposed box is still a box.
 *
 * @author Curtis Rueden
 */
public final class Rois {

	private Rois() {
		// Prevent instantiation of utility class.
	}

	/**
	 * ROIs for an array, read according to what skop said it means.
	 *
	 * @param array the result array.
	 * @param role {@code boxes}, {@code shapes}, {@code points} or {@code masks}.
	 * @return the ROIs.
	 * @throws IllegalArgumentException if the role is not one this can read.
	 */
	public static ROITree toRois(NDArray array, Role role) {
		switch (role) {
			case BOXES: return tree(boxes(array));
			// Freeform shapes have no reader here yet, and no op makes them; an
			// older skop also said "shapes" where it meant boxes. So read them
			// as boxes until a freeform reader exists.
			case SHAPES: return tree(boxes(array));
			case POINTS: return tree(points(array));
			case MASKS: return tree(masks(array));
			default:
				throw new IllegalArgumentException(
					"Not a ROI role: " + (role == null ? "none" : role.wireName()));
		}
	}

	/** Bundles ROIs into the tree an ImageJ2 output is expected to be. */
	public static ROITree tree(List<? extends MaskPredicate<?>> rois) {
		ROITree roiTree = new DefaultROITree();
		roiTree.addROIs(new ArrayList<>(rois));
		return roiTree;
	}

	// -- boxes -------------------------------------------------------------

	/**
	 * Axis-aligned boxes from an {@code (N, 4)} array.
	 * <p>
	 * skop's order is {@code [min_y, min_x, max_y, max_x]} -- see
	 * {@code skop.boxes}, which exists precisely because every library orders
	 * these differently. ImgLib2 wants {@code (x, y)}, so the pairs swap.
	 *
	 * @param array the boxes, as {@code (N, 4)}.
	 * @return one box per row.
	 */
	public static List<Box> boxes(NDArray array) {
		double[][] rows = rows(array, 4);
		List<Box> boxes = new ArrayList<>(rows.length);
		for (double[] row : rows) {
			// row is [min_y, min_x, max_y, max_x]; ImgLib2 wants x then y.
			boxes.add(GeomMasks.closedBox(
				new double[] { row[1], row[0] },
				new double[] { row[3], row[2] }));
		}
		return boxes;
	}

	/**
	 * An {@code (N, 4)} box array for a set of ROIs, to send to an op.
	 * <p>
	 * Anything that is not a box contributes its bounding box, which is the
	 * only honest reading: an op that asked for boxes wants boxes, and a
	 * polygon's bounds are the box it is inside.
	 *
	 * @param rois the ROIs to send.
	 * @return the array, {@code (N, 4)} float64, in skop's order.
	 */
	public static NDArray toBoxArray(List<? extends MaskPredicate<?>> rois) {
		double[][] rows = new double[rois.size()][4];
		for (int i = 0; i < rois.size(); i++) {
			double[] min = new double[2];
			double[] max = new double[2];
			bounds(rois.get(i), min, max);
			// Back to skop's order: [min_y, min_x, max_y, max_x].
			rows[i] = new double[] { min[1], min[0], max[1], max[0] };
		}
		return array(rows, 4);
	}

	private static void bounds(MaskPredicate<?> roi, double[] min, double[] max) {
		if (roi instanceof net.imglib2.RealInterval) {
			net.imglib2.RealInterval interval = (net.imglib2.RealInterval) roi;
			for (int d = 0; d < 2 && d < interval.numDimensions(); d++) {
				min[d] = interval.realMin(d);
				max[d] = interval.realMax(d);
			}
			return;
		}
		throw new IllegalArgumentException(
			"Cannot take the bounds of an unbounded ROI: " + roi);
	}

	// -- points ------------------------------------------------------------

	/**
	 * Points from an {@code (N, D)} array.
	 * <p>
	 * skop's coordinates are in the axis order of the image they came from,
	 * which is numpy's, so they are reversed on the way in.
	 *
	 * @param array the points, as {@code (N, D)}.
	 * @return one point mask per row.
	 */
	public static List<PointMask> points(NDArray array) {
		int[] shape = array.shape().toIntArray(NDArray.Shape.Order.C_ORDER);
		if (shape.length != 2) {
			throw new IllegalArgumentException(
				"Points are an (N, D) array, but this one has " + shape.length +
					" dimension(s)");
		}
		double[][] rows = rows(array, shape[1]);
		List<PointMask> points = new ArrayList<>(rows.length);
		for (double[] row : rows) {
			double[] position = new double[row.length];
			for (int d = 0; d < row.length; d++) position[d] = row[row.length - 1 - d];
			points.add(GeomMasks.pointMask(position));
		}
		return points;
	}

	// -- masks -------------------------------------------------------------

	/**
	 * One ROI per plane of an {@code (N, Y, X)} stack of binary masks.
	 * <p>
	 * They may overlap, and nothing here minds: that is the whole point of
	 * this role reaching Fiji as ROIs rather than as a label image.
	 *
	 * @param array the masks, as {@code (N, Y, X)}.
	 * @return one mask per plane, in the order the op produced them.
	 */
	public static <T extends RealType<T> & net.imglib2.type.NativeType<T>>
		List<MaskPredicate<?>> masks(NDArray array)
	{
		int[] shape = array.shape().toIntArray(NDArray.Shape.Order.C_ORDER);
		if (shape.length != 3) {
			throw new IllegalArgumentException(
				"Masks are an (N, Y, X) array, but this one has " + shape.length +
					" dimension(s)");
		}
		// numpy (N, Y, X) is ImgLib2 (X, Y, N), so N is the last dimension.
		ShmImg<T> stack = new ShmImg<>(array);
		int count = shape[0];
		List<MaskPredicate<?>> masks = new ArrayList<>(count);
		for (int n = 0; n < count; n++) {
			RandomAccessibleInterval<T> plane =
				Views.hyperSlice(stack, stack.numDimensions() - 1, n);
			RandomAccessibleInterval<BoolType> binary = Converters.convert(plane,
				(in, out) -> out.set(in.getRealDouble() > 0), new BoolType());
			masks.add(Masks.toMaskInterval(binary));
		}
		return masks;
	}

	// -- reading and writing flat arrays ------------------------------------

	/**
	 * An {@code (N, columns)} array as rows of doubles.
	 * <p>
	 * Read through a {@code ShmImg}, so it works whatever numeric dtype the op
	 * chose -- and ops do differ: boxes come back as float and points as int.
	 */
	private static <T extends RealType<T> & net.imglib2.type.NativeType<T>>
		double[][] rows(NDArray array, int columns)
	{
		int[] shape = array.shape().toIntArray(NDArray.Shape.Order.C_ORDER);
		if (shape.length != 2 || shape[1] != columns) {
			throw new IllegalArgumentException("Expected an (N, " + columns +
				") array, but got " + java.util.Arrays.toString(shape));
		}
		ShmImg<T> img = new ShmImg<>(array);
		// ImgLib2 order: dimension 0 is the column, dimension 1 the row.
		RandomAccess<T> access = img.randomAccess();
		double[][] rows = new double[shape[0]][columns];
		for (int row = 0; row < shape[0]; row++) {
			for (int column = 0; column < columns; column++) {
				access.setPosition(new long[] { column, row });
				rows[row][column] = access.get().getRealDouble();
			}
		}
		return rows;
	}

	private static <T extends RealType<T> & net.imglib2.type.NativeType<T>>
		NDArray array(double[][] rows, int columns)
	{
		NDArray array =
			Images.allocate(NDArray.DType.FLOAT64, rows.length, columns);
		if (rows.length == 0) return array;
		ShmImg<T> img = new ShmImg<>(array);
		RandomAccess<T> access = img.randomAccess();
		for (int row = 0; row < rows.length; row++) {
			for (int column = 0; column < columns; column++) {
				access.setPosition(new long[] { column, row });
				access.get().setReal(rows[row][column]);
			}
		}
		return array;
	}

	/** Every ROI in a tree, depth first. */
	public static List<MaskPredicate<?>> flatten(ROITree tree) {
		List<MaskPredicate<?>> rois = new ArrayList<>();
		collect(tree, rois);
		return rois;
	}

	private static void collect(org.scijava.util.TreeNode<?> node,
		List<MaskPredicate<?>> into)
	{
		Object data = node.data();
		if (data instanceof MaskPredicate) into.add((MaskPredicate<?>) data);
		List<org.scijava.util.TreeNode<?>> children = node.children();
		if (children == null) return;
		for (org.scijava.util.TreeNode<?> child : children) collect(child, into);
	}

}
