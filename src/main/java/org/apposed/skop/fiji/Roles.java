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

import net.imagej.Dataset;
import net.imagej.roi.ROITree;
import net.imglib2.roi.labeling.ImgLabeling;

import org.apposed.skop.fiji.wire.Role;
import org.scijava.table.Table;

/**
 * What each of skop's semantic roles becomes in Fiji.
 * <p>
 * The only Fiji-specific part of the front end, and the counterpart of
 * skop-napari's {@code _roles.py}. A role says what a value <em>means</em>
 * beyond what its type says -- every op passes arrays around, and only the
 * role distinguishes a picture from a segmentation from a set of
 * coordinates -- so this is where that meaning turns into a Java type.
 * <p>
 * <strong>Roles are never guessed in skop, and they are guessed here.</strong>
 * An op that returns a bare unannotated array has told this side nothing, and
 * this side has to display it anyway. Every front end will want to guess, and
 * every front end should -- differently, in its own module, where its own
 * display model justifies it. That module is this one, and its guess is
 * "an array with no role is a picture", which is right far more often than
 * not.
 *
 * <table>
 *   <caption>Roles in Fiji</caption>
 *   <tr><th>Role</th><th>Type</th><th></th></tr>
 *   <tr><td>{@code image}</td><td>{@link Dataset}</td><td></td></tr>
 *   <tr><td>{@code labels}</td><td>{@link ImgLabeling}</td><td></td></tr>
 *   <tr><td>{@code masks}</td><td>{@link ROITree}</td><td>one ROI per mask, overlapping allowed</td></tr>
 *   <tr><td>{@code points}</td><td>{@link ROITree}</td><td>of point masks</td></tr>
 *   <tr><td>{@code shapes}</td><td>{@link ROITree}</td><td>of boxes</td></tr>
 *   <tr><td>{@code tracks}</td><td>{@link Table}</td><td>a TrackMate model later</td></tr>
 *   <tr><td>{@code vectors}</td><td>{@link Dataset}</td><td>still owed an Overlay of arrows</td></tr>
 *   <tr><td>{@code surface}</td><td>{@link Dataset}</td><td>still owed a mesh</td></tr>
 *   <tr><td>none</td><td>{@link Dataset}</td><td></td></tr>
 * </table>
 *
 * {@code labels} is an {@code ImgLabeling} rather than a glasbey-LUT
 * {@code Dataset} because that is what a label image <em>is</em>. A LUT'd
 * Dataset is a rendering of one, and picking the rendering as the
 * representation would throw away the structure every downstream ImgLib2
 * consumer wants. Conversions between labelings and images are useful in
 * their own right and belong in {@code skop.ops.labels}, not here.
 * <p>
 * {@code masks} is the role that comes out <em>better</em> here than in
 * napari. A napari Labels layer cannot show overlapping objects, so
 * {@code skop.masks} has to project them first; Fiji's ROI Manager holds
 * overlapping ROIs natively, so the projection becomes one of several things
 * a user may ask for rather than a precondition for seeing anything at all.
 * <p>
 * The two roles still falling back to {@code Dataset} have no op producing
 * them, which is why they are last in the queue rather than a gap in it.
 * {@link #owed(Role)} is what says so out loud.
 *
 * @author Curtis Rueden
 */
public final class Roles {

	private Roles() {
		// Prevent instantiation of utility class.
	}

	/**
	 * The Java type a value of this role is harvested and displayed as.
	 *
	 * @param role the role, or null for a value skop said nothing about.
	 * @return the type.
	 */
	public static Class<?> type(Role role) {
		if (role == null) return Dataset.class;
		switch (role) {
			case LABELS:
				return ImgLabeling.class;
			case MASKS:
			case POINTS:
			case SHAPES:
				return ROITree.class;
			case TRACKS:
				return Table.class;
			case IMAGE:
			case VECTORS:
			case SURFACE:
			default:
				return Dataset.class;
		}
	}

	/** Whether values of this role become ROIs. */
	public static boolean isRoi(Role role) {
		return role == Role.MASKS || role == Role.POINTS || role == Role.SHAPES;
	}

	/**
	 * What a value of this role is still owed, or null if it has what it needs.
	 * <p>
	 * A value falling back to a {@code Dataset} still survives -- an
	 * N&times;3&times;2 array of arrows shown as an image is useless but
	 * lossless, and a dropped output is neither. Worth one line in the log so
	 * that nobody has to guess whether it was meant to look like that.
	 *
	 * @param role the role, or null.
	 * @return the representation still owed, or null.
	 */
	public static String owed(Role role) {
		if (role == null) return null;
		switch (role) {
			case VECTORS: return "an Overlay of arrows";
			case SURFACE: return "a mesh";
			default: return null;
		}
	}
}
