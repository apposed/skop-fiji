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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.imagej.Dataset;
import net.imagej.roi.ROITree;
import net.imglib2.RandomAccess;
import net.imglib2.appose.ShmImg;
import net.imglib2.roi.MaskPredicate;
import net.imglib2.roi.geom.real.Box;
import net.imglib2.roi.geom.real.PointMask;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.roi.labeling.LabelRegions;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.DoubleType;

import org.apposed.appose.NDArray;
import org.apposed.skop.fiji.wire.Role;
import org.junit.jupiter.api.Test;
import org.scijava.table.GenericTable;

/**
 * Every role, turned into the Fiji thing it means.
 * <p>
 * No worker and no environment: these are conversions over arrays this test
 * fills in itself, which is the only way to check the coordinate order without
 * an op's opinion in the way. And the coordinate order is the point -- skop's
 * boxes are {@code [min_y, min_x, ...]} and its points are numpy-ordered,
 * while ImgLib2 wants x first, so every one of these is the axis-order trap
 * wearing a different hat.
 *
 * @author Curtis Rueden
 */
public class RolesTest {

	/** An {@code (N, C)} float64 array, filled row by row. */
	private static NDArray table(double[][] rows) {
		// Note: `new double[0][4]` is a zero-length array of arrays in Java, so
		// an empty case has to be told its width; see empty().
		return table(rows.length == 0 ? 0 : rows[0].length, rows);
	}

	/** An empty {@code (0, columns)} array, as a detector finding nothing gives. */
	private static NDArray empty(int columns) {
		return table(columns, new double[0][]);
	}

	private static NDArray table(int columns, double[][] rows) {
		NDArray array =
			Images.allocate(NDArray.DType.FLOAT64, rows.length, columns);
		if (rows.length == 0) return array;
		ShmImg<DoubleType> img = new ShmImg<>(array);
		RandomAccess<DoubleType> access = img.randomAccess();
		for (int r = 0; r < rows.length; r++) {
			for (int c = 0; c < columns; c++) {
				access.setPosition(new long[] { c, r });
				access.get().set(rows[r][c]);
			}
		}
		return array;
	}

	// -- the type table ----------------------------------------------------

	@Test
	public void testRoleTypes() {
		assertEquals(Dataset.class, Roles.type(Role.IMAGE));
		assertEquals(Dataset.class, Roles.type(null));
		assertEquals(ImgLabeling.class, Roles.type(Role.LABELS));
		assertEquals(ROITree.class, Roles.type(Role.MASKS));
		assertEquals(ROITree.class, Roles.type(Role.POINTS));
		assertEquals(ROITree.class, Roles.type(Role.BOXES));
		assertEquals(ROITree.class, Roles.type(Role.SHAPES));
		assertEquals(org.scijava.table.Table.class, Roles.type(Role.TRACKS));
	}

	@Test
	public void testTheRolesStillOwedSayWhat() {
		assertNull(Roles.owed(Role.IMAGE));
		assertNull(Roles.owed(Role.LABELS));
		assertNull(Roles.owed(null));
		assertNotNull(Roles.owed(Role.VECTORS));
		assertNotNull(Roles.owed(Role.SURFACE));
	}

	// -- labels ------------------------------------------------------------

	@Test
	public void testALabelImageBecomesALabeling() {
		// A 4x4 index image (numpy order) with two objects.
		NDArray array = new NDArray(NDArray.DType.UINT16,
			new NDArray.Shape(NDArray.Shape.Order.C_ORDER, 4, 4));
		ShmImg<UnsignedShortType> img = new ShmImg<>(array);
		RandomAccess<UnsignedShortType> access = img.randomAccess();
		access.setPosition(new long[] { 0, 0 });
		access.get().set(1);
		access.setPosition(new long[] { 1, 0 });
		access.get().set(1);
		access.setPosition(new long[] { 3, 3 });
		access.get().set(2);

		ImgLabeling<String, UnsignedShortType> labeling =
			Labelings.toImgLabeling(array);

		assertEquals(2, labeling.getMapping().getLabels().size());
		LabelRegions<String> regions = new LabelRegions<>(labeling);
		assertEquals(2, regions.getExistingLabels().size());
		assertEquals(2, regions.getLabelRegion("1").size());
		assertEquals(1, regions.getLabelRegion("2").size());
	}

	@Test
	public void testALabelingIsBackedByTheWorkersOwnBlock() {
		NDArray array = new NDArray(NDArray.DType.UINT16,
			new NDArray.Shape(NDArray.Shape.Order.C_ORDER, 4, 4));
		ImgLabeling<String, UnsignedShortType> labeling =
			Labelings.toImgLabeling(array);
		// Chaining a segmentation into the next op must not copy anything.
		assertTrue(Labelings.isShared(labeling));
		assertEquals(array, Labelings.toNDArray(labeling));
	}

	@Test
	public void testAFloatArrayIsNotALabelImage() {
		NDArray array = new NDArray(NDArray.DType.FLOAT32,
			new NDArray.Shape(NDArray.Shape.Order.C_ORDER, 2, 2));
		assertFalse(Labelings.isIntegral(array.dType()));
		assertThrows(IllegalArgumentException.class,
			() -> Labelings.toImgLabeling(array));
	}

	// -- boxes -------------------------------------------------------------

	@Test
	public void testBoxesSwapTheirCoordinates() {
		// skop's order is [min_y, min_x, max_y, max_x].
		List<Box> boxes = Rois.boxes(table(new double[][] {
			{ 10, 20, 30, 40 }, // y: 10..30, x: 20..40
		}));
		assertEquals(1, boxes.size());
		Box box = boxes.get(0);
		assertEquals(20.0, box.realMin(0), 0, "x min");
		assertEquals(40.0, box.realMax(0), 0, "x max");
		assertEquals(10.0, box.realMin(1), 0, "y min");
		assertEquals(30.0, box.realMax(1), 0, "y max");
	}

	@Test
	public void testBoxesRoundTrip() {
		double[][] rows = { { 10, 20, 30, 40 }, { 1, 2, 3, 4 } };
		NDArray back = Rois.toBoxArray(Rois.boxes(table(rows)));
		List<Box> again = Rois.boxes(back);
		assertEquals(20.0, again.get(0).realMin(0), 0);
		assertEquals(30.0, again.get(0).realMax(1), 0);
		assertEquals(2.0, again.get(1).realMin(0), 0);
	}

	@Test
	public void testAnEmptyDetectionIsNotAnError() {
		// A detector that finds nothing returns a (0, 4) array, which is an
		// ordinary result and not a failure.
		assertTrue(Rois.boxes(empty(4)).isEmpty());
	}

	// -- points ------------------------------------------------------------

	@Test
	public void testPointsAreReversed() {
		// skop gives coordinates in the image's own axis order, which is
		// numpy's: (z, y, x). ImgLib2 wants (x, y, z).
		List<PointMask> points = Rois.points(table(new double[][] {
			{ 5, 6, 7 },
		}));
		assertEquals(1, points.size());
		PointMask point = points.get(0);
		assertEquals(7.0, point.getDoublePosition(0), 0, "x");
		assertEquals(6.0, point.getDoublePosition(1), 0, "y");
		assertEquals(5.0, point.getDoublePosition(2), 0, "z");
	}

	@Test
	public void testTwoDimensionalPoints() {
		List<PointMask> points = Rois.points(table(new double[][] { { 3, 8 } }));
		assertEquals(8.0, points.get(0).getDoublePosition(0), 0, "x");
		assertEquals(3.0, points.get(0).getDoublePosition(1), 0, "y");
	}

	// -- masks -------------------------------------------------------------

	@Test
	public void testOverlappingMasksStayOverlapping() {
		// Two 4x4 masks that share a pixel. In napari this role has to be
		// projected to a label image first, and the overlap is lost; here it
		// is two ROIs and nothing minds.
		NDArray array = new NDArray(NDArray.DType.UINT8,
			new NDArray.Shape(NDArray.Shape.Order.C_ORDER, 2, 4, 4));
		ShmImg<net.imglib2.type.numeric.integer.UnsignedByteType> img =
			new ShmImg<>(array);
		RandomAccess<net.imglib2.type.numeric.integer.UnsignedByteType> access =
			img.randomAccess();
		// ImgLib2 order for numpy (N, Y, X) is (X, Y, N).
		for (int n = 0; n < 2; n++) {
			for (int x = n; x < n + 2; x++) {
				access.setPosition(new long[] { x, 1, n });
				access.get().set(1);
			}
		}

		List<MaskPredicate<?>> masks = Rois.masks(array);
		assertEquals(2, masks.size());

		net.imglib2.roi.MaskInterval first = (net.imglib2.roi.MaskInterval) masks.get(0);
		net.imglib2.roi.MaskInterval second = (net.imglib2.roi.MaskInterval) masks.get(1);
		// x=1,y=1 is in both.
		assertTrue(first.test(new net.imglib2.Point(1, 1)));
		assertTrue(second.test(new net.imglib2.Point(1, 1)));
		assertTrue(first.test(new net.imglib2.Point(0, 1)));
		assertFalse(second.test(new net.imglib2.Point(0, 1)));
	}

	@Test
	public void testMasksNeedThreeDimensions() {
		assertThrows(IllegalArgumentException.class,
			() -> Rois.masks(table(new double[][] { { 1, 2 } })));
	}

	// -- ROI trees ---------------------------------------------------------

	@Test
	public void testRoisArriveAsATree() {
		// shapes is read as boxes too, until a freeform reader exists.
		for (Role role : new Role[] { Role.BOXES, Role.SHAPES }) {
			ROITree tree = Rois.toRois(table(new double[][] { { 0, 0, 2, 2 } }),
				role);
			assertEquals(1, Rois.flatten(tree).size());
			assertTrue(Rois.flatten(tree).get(0) instanceof Box);
		}
	}

	@Test
	public void testARoleWithNoRoiReadingIsRefused() {
		assertThrows(IllegalArgumentException.class,
			() -> Rois.toRois(table(new double[][] { { 1 } }), Role.IMAGE));
	}

	// -- tables ------------------------------------------------------------

	@Test
	public void testATable() {
		GenericTable table = Tables.toTable(
			table(new double[][] { { 1, 2, 3 }, { 4, 5, 6 } }), null);
		assertEquals(3, table.getColumnCount());
		assertEquals(2, table.getRowCount());
		assertEquals(5.0, ((Number) table.get(1, 1)).doubleValue(), 0);
	}

	@Test
	public void testTrackHeadings() {
		// (N, D+2): track, time, then coordinates last-fastest.
		assertArrayEquals(new String[] { "Track", "T", "Y", "X" },
			Tables.trackHeadings(4));
		assertArrayEquals(new String[] { "Track", "T", "Z", "Y", "X" },
			Tables.trackHeadings(5));
	}

	@Test
	public void testAnEmptyTable() {
		GenericTable table = Tables.toTable(empty(3), null);
		assertEquals(3, table.getColumnCount());
		assertEquals(0, table.getRowCount());
	}

	private static void assertArrayEquals(String[] expected, String[] actual) {
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
	}
}
