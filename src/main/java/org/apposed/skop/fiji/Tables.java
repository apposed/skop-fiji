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

import net.imglib2.RandomAccess;
import net.imglib2.appose.ShmImg;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;

import org.apposed.appose.NDArray;
import org.scijava.table.DefaultGenericTable;
import org.scijava.table.GenericTable;

/**
 * A two-dimensional array as a table.
 * <p>
 * The destination for anything that is a row per object rather than a picture:
 * tracks today, and per-object measurements when skop grows them. A table is
 * also what a user can actually do something with -- sort it, plot it, save it
 * as CSV -- which an N&times;7 image is not.
 * <p>
 * Column names come from the role, because that is all skop says. A tracks
 * array is {@code (N, D+2)}: track ID, time, then coordinates. Anything else
 * gets numbered columns, which is honest about knowing nothing.
 *
 * @author Curtis Rueden
 */
public final class Tables {

	private Tables() {
		// Prevent instantiation of utility class.
	}

	/**
	 * A table for an {@code (N, C)} array.
	 *
	 * @param array the array; must be two-dimensional.
	 * @param headings one name per column, or null for numbered columns. Extra
	 *          names are ignored and missing ones are numbered.
	 * @return the table, one row per row of the array.
	 */
	public static <T extends RealType<T> & NativeType<T>> GenericTable toTable(
		NDArray array, String[] headings)
	{
		int[] shape = array.shape().toIntArray(NDArray.Shape.Order.C_ORDER);
		if (shape.length != 2) {
			throw new IllegalArgumentException(
				"A table is an (N, C) array, but this one has " + shape.length +
					" dimension(s)");
		}
		int rows = shape[0];
		int columns = shape[1];

		GenericTable table = new DefaultGenericTable(columns, rows);
		for (int c = 0; c < columns; c++) {
			table.setColumnHeader(c,
				headings != null && c < headings.length ? headings[c]
					: "Column " + (c + 1));
		}
		if (rows == 0 || columns == 0) return table;

		ShmImg<T> img = new ShmImg<>(array);
		// ImgLib2 order: dimension 0 is the column, dimension 1 the row.
		RandomAccess<T> access = img.randomAccess();
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < columns; c++) {
				access.setPosition(new long[] { c, r });
				table.set(c, r, access.get().getRealDouble());
			}
		}
		return table;
	}

	/**
	 * The column names for a tracks array: {@code (N, D+2)} of track ID, time,
	 * then coordinates.
	 * <p>
	 * The coordinates are in the axis order of the image the tracks came from,
	 * which is numpy's, so the last column is x.
	 *
	 * @param columns how many columns the array has.
	 * @return the headings.
	 */
	public static String[] trackHeadings(int columns) {
		String[] headings = new String[columns];
		headings[0] = "Track";
		if (columns > 1) headings[1] = "T";
		// After track and time come D coordinates, last-fastest: ..., z, y, x.
		String[] canonical = { "X", "Y", "Z" };
		for (int c = 2; c < columns; c++) {
			int fromEnd = columns - 1 - c;
			headings[c] = fromEnd < canonical.length ? canonical[fromEnd]
				: "Axis " + (c - 1);
		}
		return headings;
	}
}
