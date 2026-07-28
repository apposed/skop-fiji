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

import org.apposed.skop.fiji.wire.Role;

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
 * display model justifies it. That module is this one.
 * <p>
 * Where this is going, and where it is:
 *
 * <table>
 *   <caption>Roles in Fiji</caption>
 *   <tr><th>Role</th><th>Eventually</th><th>Today</th></tr>
 *   <tr><td>{@code image}</td><td>{@code Dataset}</td><td>{@code Dataset}</td></tr>
 *   <tr><td>{@code labels}</td><td>{@code ImgLabeling}</td><td>{@code Dataset}</td></tr>
 *   <tr><td>{@code masks}</td><td>ROI Manager / {@code Overlay}</td><td>{@code Dataset}</td></tr>
 *   <tr><td>{@code points}</td><td>{@code PointRoi}</td><td>{@code Dataset}</td></tr>
 *   <tr><td>{@code shapes}</td><td>{@code Overlay} of rectangles</td><td>{@code Dataset}</td></tr>
 *   <tr><td>{@code vectors}</td><td>{@code Overlay} of arrows</td><td>{@code Dataset}</td></tr>
 *   <tr><td>{@code tracks}</td><td>{@code Table}, then TrackMate</td><td>{@code Dataset}</td></tr>
 *   <tr><td>{@code surface}</td><td>imagej-mesh</td><td>{@code Dataset}</td></tr>
 *   <tr><td>none</td><td>a row in a {@code Table}</td><td>{@code Dataset}</td></tr>
 * </table>
 *
 * Every array is a {@code Dataset} for now, in and out. That is deliberately
 * the crude answer: a set of coordinates rendered as an N&times;3 image is not
 * useful, but it is <em>lossless</em>, which a dropped output is not. The
 * specialized types arrive with the roles in the next phase, and
 * {@link #specialized(Role)} is what says which ones are still waiting.
 * <p>
 * {@code labels} will be an {@code ImgLabeling} rather than a glasbey-LUT
 * {@code Dataset}, because that is what a label image <em>is</em>. A LUT'd
 * Dataset is a rendering of one, and picking the rendering as the
 * representation would throw away the structure every downstream ImgLib2
 * consumer wants.
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
	 * @return the type; {@link Dataset} throughout, for now.
	 */
	public static Class<?> type(Role role) {
		return Dataset.class;
	}

	/**
	 * Whether this role still wants a type of its own that it does not have.
	 * <p>
	 * True means the value survives as a {@code Dataset} but is not yet shown
	 * as the thing it is -- worth saying out loud in a log, and worth nothing
	 * more than that. An op is perfectly runnable in the meantime.
	 *
	 * @param role the role, or null.
	 * @return whether a better representation is still owed.
	 */
	public static boolean specialized(Role role) {
		return role != null && role != Role.IMAGE;
	}

	/**
	 * What a value of this role will eventually be shown as, for a message
	 * that explains why it is not yet.
	 *
	 * @param role the role, or null.
	 * @return the eventual representation's name, or null if none is owed.
	 */
	public static String eventualType(Role role) {
		if (role == null) return null;
		switch (role) {
			case LABELS: return "ImgLabeling";
			case MASKS: return "the ROI Manager";
			case POINTS: return "PointRoi";
			case SHAPES: return "an Overlay of rectangles";
			case VECTORS: return "an Overlay of arrows";
			case TRACKS: return "a Table";
			case SURFACE: return "a mesh";
			default: return null;
		}
	}
}
