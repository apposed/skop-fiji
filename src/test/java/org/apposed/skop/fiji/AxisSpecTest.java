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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apposed.appose.util.Json;
import org.apposed.skop.fiji.wire.AdaptationPlan;
import org.apposed.skop.fiji.wire.AxesSpec;
import org.apposed.skop.fiji.wire.Wire;
import org.junit.jupiter.api.Test;

/**
 * The axis-mapping line, read and written.
 * <p>
 * No worker and no dialog: a spec is a string, a plan is a value, and the
 * translation between them is arithmetic that ought to be checkable in
 * milliseconds. The one thing every test here is really about is the order
 * flip -- a person writes x first because that is how Fiji lists the axes, and
 * skop counts from the other end.
 *
 * @author Curtis Rueden
 */
public class AxisSpecTest {

	/** An op's declared axes, e.g. {@code axes("y", "x")}. */
	private static AxesSpec axes(String... names) {
		List<AxesSpec.Slot> slots = new ArrayList<>();
		for (String name : names) {
			boolean optional = name.endsWith("?");
			String bare = optional ? name.substring(0, name.length() - 1) : name;
			slots.add(new AxesSpec.Slot("*".equals(bare) ? null : bare, optional));
		}
		return new AxesSpec(slots, false);
	}

	private static AxesSpec variadic() {
		return new AxesSpec(Collections.emptyList(), true);
	}

	private static AdaptationPlan plan(String json) {
		return AdaptationPlan.fromJson(Wire.asMap(Json.parseJson(json), "plan"));
	}

	private static List<String> labels(String... numpyOrder) {
		return Arrays.asList(numpyOrder);
	}

	private static List<Integer> shape(Integer... numpyOrder) {
		return Arrays.asList(numpyOrder);
	}

	// -- writing a plan out --------------------------------------------------

	@Test
	public void testFormatAStackPlan() {
		// A (z, y, x) stack fed to a 2-D op: y and x fill the slots, z is
		// iterated. A person reads it x first.
		AdaptationPlan p = plan("{\"param\": \"image\", " +
			"\"input_axes\": [\"z\", \"y\", \"x\"], \"mapping\": [1, 2], " +
			"\"select\": [], \"iterate\": [0], \"passed\": [], " +
			"\"transpose\": [0, 1, 2], \"output_axes\": [\"z\", \"y\", \"x\"], " +
			"\"calls\": 3, \"lossless\": true, \"warnings\": [], " +
			"\"summary\": \"\"}");

		assertEquals("x y z!", AxisSpec.format(p, axes("y", "x")));
	}

	@Test
	public void testFormatASelection() {
		AdaptationPlan p = plan("{\"param\": \"image\", " +
			"\"input_axes\": [\"z\", \"y\", \"x\"], \"mapping\": [1, 2], " +
			"\"select\": [[0, 27]], \"iterate\": [], \"passed\": [], " +
			"\"transpose\": [0, 1], \"output_axes\": [\"y\", \"x\"], " +
			"\"calls\": 1, \"lossless\": false, \"warnings\": [], " +
			"\"summary\": \"\"}");

		assertEquals("x y z=27", AxisSpec.format(p, axes("y", "x")));
	}

	@Test
	public void testFormatAVariadicPassThrough() {
		AdaptationPlan p = plan("{\"param\": \"image\", " +
			"\"input_axes\": [\"z\", \"y\", \"x\"], \"mapping\": [], " +
			"\"select\": [], \"iterate\": [], \"passed\": [0, 1, 2], " +
			"\"transpose\": [0, 1, 2], \"output_axes\": [\"z\", \"y\", \"x\"], " +
			"\"calls\": 1, \"lossless\": true, \"warnings\": [], " +
			"\"summary\": \"\"}");

		assertEquals("x+ y+ z+", AxisSpec.format(p, variadic()));
	}

	@Test
	public void testFormatTheSpecFromTheDocumentation() {
		// The XYZCT-into-"c? x y" example, which is the one everyone will read.
		AdaptationPlan p = plan("{\"param\": \"image\", " +
			"\"input_axes\": [\"t\", \"c\", \"z\", \"y\", \"x\"], " +
			"\"mapping\": [1, 4, 3], \"select\": [[2, 27]], \"iterate\": [0], " +
			"\"passed\": [], \"transpose\": [0, 1, 2, 3], " +
			"\"output_axes\": [\"t\", \"c\", \"y\", \"x\"], \"calls\": 5, " +
			"\"lossless\": false, \"warnings\": [], \"summary\": \"\"}");

		assertEquals("x y z=27 c t!", AxisSpec.format(p, axes("c?", "x", "y")));
	}

	@Test
	public void testFormatAnUnnamedAxis() {
		// A plain TIFF has no axis names, so there is nothing to write in front
		// of the modifier.
		AdaptationPlan p = plan("{\"param\": \"image\", " +
			"\"input_axes\": [\"\", \"\", \"\"], \"mapping\": [1, 2], " +
			"\"select\": [], \"iterate\": [0], \"passed\": [], " +
			"\"transpose\": [0, 1, 2], \"output_axes\": [\"\", \"\", \"\"], " +
			"\"calls\": 3, \"lossless\": true, \"warnings\": [], " +
			"\"summary\": \"\"}");

		assertEquals("x y !", AxisSpec.format(p, axes("y", "x")));
	}

	// -- reading a line back in ----------------------------------------------

	@Test
	public void testBlankMeansSkopsOwnChoice() {
		assertNull(AxisSpec.parse(null, axes("y", "x"), labels("z", "y", "x"),
			shape(3, 8, 8)));
		assertNull(AxisSpec.parse("   ", axes("y", "x"), labels("z", "y", "x"),
			shape(3, 8, 8)));
	}

	@Test
	public void testTokensAreInImgLib2OrderAndTheMappingIsNot() {
		// The whole point, in one assertion. "x y z!" is written x first; the
		// mapping that comes out is in numpy indices, where x is last.
		AxisSpec.Request request = AxisSpec.parse("x y z!", axes("y", "x"),
			labels("z", "y", "x"), shape(3, 8, 8));

		assertEquals(Arrays.asList(1, 2), request.mapping());
		assertEquals(AdaptationPlan.ITERATE, request.dispositions().get(0));
		assertTrue(request.positions().isEmpty());
	}

	@Test
	public void testSwappingTwoSlots() {
		// Feed the op's y slot from the image's Z axis, and iterate over Y
		// instead. Tokens stay in the image's order -- X, Y, Z -- so the one
		// that moves is what each of them says, not where it sits.
		AxisSpec.Request request = AxisSpec.parse("x y! y", axes("y", "x"),
			labels("z", "y", "x"), shape(2, 4, 8));

		// Slot y <- numpy axis 0 (z); slot x <- numpy axis 2 (x).
		assertEquals(Arrays.asList(0, 2), request.mapping());
		assertEquals(AdaptationPlan.ITERATE, request.dispositions().get(1));
	}

	@Test
	public void testASlotNameAndAnAxisNameCanBeTheSameWord() {
		// "y" is both an axis of this image and a slot of this op, and the two
		// readings are told apart by the modifier: bare fills the slot, "y!"
		// iterates over the axis.
		AxisSpec.Request request = AxisSpec.parse("x y! y", axes("y", "x"),
			labels("z", "y", "x"), shape(2, 4, 8));
		assertEquals(Integer.valueOf(0), request.mapping().get(0));
		assertTrue(request.dispositions().containsKey(1));
	}

	@Test
	public void testTooManyWildcards() {
		IllegalArgumentException exc =
			assertThrows(IllegalArgumentException.class, () -> AxisSpec.parse(
				"* * *", axes("*", "*"), labels("z", "y", "x"), shape(3, 8, 8)));
		assertTrue(exc.getMessage().contains("more often"), exc.getMessage());
	}

	@Test
	public void testASelection() {
		AxisSpec.Request request = AxisSpec.parse("x y z=2", axes("y", "x"),
			labels("z", "y", "x"), shape(3, 8, 8));

		assertEquals(AdaptationPlan.SELECT, request.dispositions().get(0));
		assertEquals(2, request.positions().get("0").intValue());
	}

	@Test
	public void testAPassThrough() {
		AxisSpec.Request request = AxisSpec.parse("x+ y+ z+", variadic(),
			labels("z", "y", "x"), shape(3, 8, 8));

		for (int axis = 0; axis < 3; axis++) {
			assertEquals(AdaptationPlan.PASS, request.dispositions().get(axis));
		}
	}

	@Test
	public void testTheNameInFrontOfAModifierIsOptional() {
		AxisSpec.Request bare = AxisSpec.parse("x y ! =1", axes("y", "x"),
			labels("t", "z", "y", "x"), shape(4, 3, 8, 8));

		assertEquals(AdaptationPlan.ITERATE, bare.dispositions().get(1));
		assertEquals(AdaptationPlan.SELECT, bare.dispositions().get(0));
		assertEquals(1, bare.positions().get("0").intValue());
	}

	@Test
	public void testAnOptionalSlotIsLeftEmptyByNotMentioningIt() {
		AxisSpec.Request request = AxisSpec.parse("x y z!", axes("c?", "x", "y"),
			labels("z", "y", "x"), shape(3, 8, 8));

		// c is unfilled; skop is what refuses if a *required* slot is.
		assertNull(request.mapping().get(0));
		assertEquals(Integer.valueOf(2), request.mapping().get(1));
		assertEquals(Integer.valueOf(1), request.mapping().get(2));
	}

	@Test
	public void testAWildcardSlot() {
		AxisSpec.Request request = AxisSpec.parse("* * z!", axes("*", "*"),
			labels("z", "y", "x"), shape(3, 8, 8));

		// Wildcards fill in the order they are written, x first.
		assertEquals(Arrays.asList(2, 1), request.mapping());
	}

	@Test
	public void testTheDocumentationExampleRoundTrips() {
		AxesSpec declared = axes("c?", "x", "y");
		AxisSpec.Request request = AxisSpec.parse("x y z=27 c t!", declared,
			labels("t", "c", "z", "y", "x"), shape(5, 2, 40, 8, 8));

		// numpy indices: t=0, c=1, z=2, y=3, x=4.
		assertEquals(Arrays.asList(1, 4, 3), request.mapping());
		assertEquals(AdaptationPlan.SELECT, request.dispositions().get(2));
		assertEquals(27, request.positions().get("2").intValue());
		assertEquals(AdaptationPlan.ITERATE, request.dispositions().get(0));
	}

	// -- what it refuses -----------------------------------------------------

	@Test
	public void testTheTokenCountMustMatchTheImage() {
		IllegalArgumentException exc =
			assertThrows(IllegalArgumentException.class, () -> AxisSpec.parse(
				"x y", axes("y", "x"), labels("z", "y", "x"), shape(3, 8, 8)));
		assertTrue(exc.getMessage().contains("2 token(s)"), exc.getMessage());
		assertTrue(exc.getMessage().contains("3 axes"), exc.getMessage());
		// And it says what the axes are, x first, so the fix is obvious.
		assertTrue(exc.getMessage().contains("x y z"), exc.getMessage());
	}

	@Test
	public void testAnUnknownSlotName() {
		IllegalArgumentException exc =
			assertThrows(IllegalArgumentException.class, () -> AxisSpec.parse(
				"x y z", axes("y", "x"), labels("z", "y", "x"), shape(3, 8, 8)));
		assertTrue(exc.getMessage().contains("'z' is not one of this op's axes"),
			exc.getMessage());
		// And it suggests the two things the writer probably meant.
		assertTrue(exc.getMessage().contains("z!"), exc.getMessage());
		assertTrue(exc.getMessage().contains("z=0"), exc.getMessage());
	}

	@Test
	public void testASlotUsedTwice() {
		IllegalArgumentException exc =
			assertThrows(IllegalArgumentException.class, () -> AxisSpec.parse(
				"x x z!", axes("y", "x"), labels("z", "y", "x"), shape(3, 8, 8)));
		assertTrue(exc.getMessage().contains("twice"), exc.getMessage());
	}

	@Test
	public void testTokensInTheWrongOrderAreCaught() {
		// The mistake this syntax invites: writing the line in skop's order
		// instead of Fiji's. The name in front of a modifier is a comment, and
		// a comment naming another of this image's axes is the giveaway.
		IllegalArgumentException exc =
			assertThrows(IllegalArgumentException.class, () -> AxisSpec.parse(
				"z! y x", axes("y", "x"), labels("z", "y", "x"), shape(3, 8, 8)));
		assertTrue(exc.getMessage().contains("in the position of"),
			exc.getMessage());
	}

	@Test
	public void testAnUnrecognizedCommentIsLetAlone() {
		// skop's axis vocabulary is open -- "lifetime" is a real axis name --
		// so a name this side does not know is not a name this side may refuse.
		AxisSpec.Request request = AxisSpec.parse("x y lifetime!", axes("y", "x"),
			labels("z", "y", "x"), shape(3, 8, 8));
		assertEquals(AdaptationPlan.ITERATE, request.dispositions().get(0));
	}

	@Test
	public void testAPositionOutsideTheAxis() {
		IllegalArgumentException exc =
			assertThrows(IllegalArgumentException.class, () -> AxisSpec.parse(
				"x y z=9", axes("y", "x"), labels("z", "y", "x"), shape(3, 8, 8)));
		assertTrue(exc.getMessage().contains("3 position(s)"), exc.getMessage());
	}

	@Test
	public void testAPositionThatIsNotANumber() {
		IllegalArgumentException exc =
			assertThrows(IllegalArgumentException.class, () -> AxisSpec.parse(
				"x y z=mid", axes("y", "x"), labels("z", "y", "x"), shape(3, 8, 8)));
		assertTrue(exc.getMessage().contains("not a number"), exc.getMessage());
	}

	@Test
	public void testPassingToAnOpThatCannotTakeIt() {
		IllegalArgumentException exc =
			assertThrows(IllegalArgumentException.class, () -> AxisSpec.parse(
				"x y z+", axes("y", "x"), labels("z", "y", "x"), shape(3, 8, 8)));
		assertTrue(exc.getMessage().contains("not variadic"), exc.getMessage());
		assertTrue(exc.getMessage().contains("Iterate"), exc.getMessage());
	}

	@Test
	public void testCommasAreAcceptableSeparators() {
		AxisSpec.Request request = AxisSpec.parse("x, y, z!", axes("y", "x"),
			labels("z", "y", "x"), shape(3, 8, 8));
		assertEquals(Arrays.asList(1, 2), request.mapping());
	}
}
