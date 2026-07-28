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

import net.imagej.DatasetService;

import org.apposed.appose.NDArray;
import org.apposed.skop.fiji.wire.OutputSpec;
import org.apposed.skop.fiji.wire.Role;
import org.scijava.log.Logger;

/**
 * A worker's output, as the thing Fiji should show.
 * <p>
 * One switch on the role, and the conversions themselves live in
 * {@link Images}, {@link Labelings}, {@link Rois} and {@link Tables}. It is
 * kept separate from {@link OpModule} because deciding what a result
 * <em>is</em> and deciding when to run an op are unrelated jobs, and only one
 * of them needs a worker to test.
 * <p>
 * Nothing here throws. A conversion that fails costs one output its intended
 * form and falls back to a {@code Dataset}, which is always available because
 * every result is an array; the alternative -- losing the whole run to a
 * malformed points array -- serves nobody.
 *
 * @author Curtis Rueden
 */
public final class Results {

	private Results() {
		// Prevent instantiation of utility class.
	}

	/**
	 * Converts one of an op's outputs.
	 *
	 * @param datasets the service that makes Datasets.
	 * @param value the raw output: an {@link NDArray}, or a scalar.
	 * @param output what skop says this output is.
	 * @param name what to call the result.
	 * @param axes the result's axis labels in numpy order, or null.
	 * @param log where a fallback explains itself.
	 * @return the converted result, ready to be set on the module.
	 */
	public static Object convert(DatasetService datasets, Object value,
		OutputSpec output, String name, List<String> axes, Logger log)
	{
		if (!(value instanceof NDArray)) return value;
		NDArray array = (NDArray) value;
		Role role = output.role();

		try {
			if (role == Role.LABELS && Labelings.isIntegral(array.dType())) {
				return Labelings.toImgLabeling(array);
			}
			if (Roles.isRoi(role)) {
				return Rois.toRois(array, role);
			}
			if (role == Role.TRACKS) {
				int[] shape = array.shape().toIntArray(NDArray.Shape.Order.C_ORDER);
				return Tables.toTable(array,
					shape.length == 2 ? Tables.trackHeadings(shape[1]) : null);
			}
		}
		catch (RuntimeException exc) {
			// An op whose output does not have the shape its role implies is a
			// bug in the op, and the useful response is to say so and still
			// hand back the pixels rather than to lose them.
			log.warn("Output '" + output.name() + "' is " + role.wireName() +
				", but could not be read as one (" + exc.getMessage() +
				"); showing it as an image instead.");
		}

		String owed = Roles.owed(role);
		if (owed != null) {
			log.info("Output '" + output.name() + "' is " + role.wireName() +
				", which will become " + owed + "; for now it is an image.");
		}
		return Images.<DoubleLike>toDataset(datasets, array, name, axes);
	}

	/**
	 * The type bound {@link Images#toDataset} needs, named once.
	 * <p>
	 * The pixel type of a result is not known until the result arrives, so
	 * every call site would otherwise carry the same unreadable pair of bounds
	 * for a type argument that is inferred anyway.
	 */
	private interface DoubleLike extends net.imglib2.type.NativeType<DoubleLike>,
		net.imglib2.type.numeric.RealType<DoubleLike>
	{
		// Marker only; never instantiated.
	}
}
