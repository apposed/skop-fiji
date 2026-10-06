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
package org.apposed.skop.fiji.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apposed.appose.util.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Reading skop's JSON, against a captured {@code describe()} answer.
 * <p>
 * The fixture is real output, not a hand-written approximation of it, and it
 * runs without an environment. That combination is the point: this is where a
 * change to skop's wire format shows up as a red test rather than as a
 * misread field somewhere in a dialog.
 *
 * @author Curtis Rueden
 */
public class WireTest {

	private static Description description;

	@BeforeAll
	public static void readFixture() throws IOException {
		try (InputStream in =
			WireTest.class.getResourceAsStream("describe.json"))
		{
			assertNotNull(in, "describe.json is missing from the test resources");
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			for (int n; (n = in.read(chunk)) > 0;) buffer.write(chunk, 0, n);
			String json = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
			description = Description.fromJson(
				Wire.asMap(Json.parseJson(json), "description"));
		}
	}

	private static OpSpec op(String name) {
		OpSpec op = description.op(name);
		assertNotNull(op, "no such op in the fixture: " + name);
		return op;
	}

	// -- the description as a whole ---------------------------------------

	@Test
	public void testDescriptionReadsOpsAndFailures() {
		assertEquals("skop.ops.toy", description.pkg());
		assertFalse(description.ops().isEmpty());
		assertEquals(1, description.failures().size());
	}

	@Test
	public void testAFailureExplainsItself() {
		Description.LoadFailure failure = description.failures().get(0);
		assertEquals("skop.ops.pretend", failure.module());
		assertTrue(failure.error().contains("tensorflow"));
		// The whole reason a module fails to load in a minimal environment is
		// nearly always a module-scope import, so the diagnostic names them.
		assertEquals(Collections.singletonList("tensorflow (line 3)"),
			failure.heavyImports());
	}

	// -- one op -----------------------------------------------------------

	@Test
	public void testTheSimplestOp() {
		OpSpec add = op("skop.ops.toy:add");
		assertEquals("skop.ops.toy", add.module());
		assertEquals("add", add.function());
		assertEquals("minimal", add.env());
		assertEquals(Form.FUNCTION, add.form());
		assertEquals(Arrays.asList("a", "b"), names(add.params()));
		assertEquals(Collections.singletonList("result"), add.outputs());
		assertEquals("Add two numbers. The smallest op there is.", add.summary());
		for (ParamSpec param : add.params()) {
			assertEquals(TypeSpec.Kind.INT, param.type().kind());
			assertTrue(param.required());
		}
	}

	@Test
	public void testUiHints() {
		ParamSpec factor = op("skop.ops.toy:scale").param("factor");
		assertEquals(TypeSpec.Kind.FLOAT, factor.type().kind());
		assertFalse(factor.required());
		assertEquals(2.0, ((Number) factor.defaultValue()).doubleValue(), 0);

		Map<String, Object> ui = factor.ui();
		assertEquals("FloatSlider", ui.get("widget_type"));
		assertEquals(0.0, ((Number) ui.get("min")).doubleValue(), 0);
		assertEquals(10.0, ((Number) ui.get("max")).doubleValue(), 0);
		assertEquals(0.1, ((Number) ui.get("step")).doubleValue(), 1e-12);
	}

	@Test
	public void testUiHintsAreNotEditable() {
		Map<String, Object> ui = op("skop.ops.toy:scale").param("factor").ui();
		assertThrows(UnsupportedOperationException.class, () -> ui.put("min", 1));
	}

	@Test
	public void testMultipleOutputs() {
		OpSpec scale = op("skop.ops.toy:scale");
		// The names come from a NamedTuple's fields, which do not cross the
		// boundary -- so they have to arrive as data, not be derived here.
		assertEquals(Arrays.asList("scaled", "total"), scale.outputs());
		assertEquals(TypeSpec.Kind.NDARRAY, scale.outputSpecs().get(0).type().kind());
		assertEquals(TypeSpec.Kind.FLOAT, scale.outputSpecs().get(1).type().kind());
	}

	@Test
	public void testRoles() {
		OpSpec findNothing = op("skop.ops.toy:find_nothing");
		assertEquals(Role.IMAGE, findNothing.param("image").role());
		assertEquals(Role.LABELS, findNothing.outputSpecs().get(0).role());
		assertEquals(Role.POINTS, findNothing.outputSpecs().get(1).role());
	}

	@Test
	public void testAnUnknownRoleCostsOnlyItsOwnDisplayType() {
		assertNull(Role.forWire("holograms"));
		assertNull(Role.forWire(null));
	}

	// -- workflows ---------------------------------------------------------

	@Test
	public void testAWorkflowHasNoEnvironment() {
		// The absence of an environment is the marker: a workflow composes
		// other ops and so runs wherever they do, rather than anywhere itself.
		OpSpec workflow = op("skop.ops.workflows.mask.detect_then_mask:detect_then_mask");
		assertNull(workflow.env());
		assertTrue(workflow.isWorkflow());

		assertFalse(op("skop.ops.toy:add").isWorkflow());
		assertEquals("minimal", op("skop.ops.toy:add").env());
	}

	@Test
	public void testAWorkflowsChoosers() {
		OpSpec workflow = op("skop.ops.workflows.mask.detect_then_mask:detect_then_mask");

		ParamSpec detector = workflow.param("detector");
		assertEquals(2, detector.choices().size());
		assertEquals("object_aware_yolo", detector.choices().get(0).label());
		assertEquals("skop.ops.detect.object_aware_yolo:object_aware_yolo",
			detector.choices().get(0).op());
		// Curated, not discovered: a list means "these have been tested
		// together", where an inventory would mean "these are installed".
		assertNull(detector.paramsFor());
	}

	@Test
	public void testWhatAStageDoesNotHaveToAskFor() {
		ParamSpec args = op("skop.ops.workflows.mask.detect_then_mask:detect_then_mask")
			.param("masker_args");
		assertNotNull(args.paramsFor());
		assertEquals("masker", args.paramsFor().chooser());
		// The workflow supplies the image and the boxes itself, which is what
		// stops two stages that both take an image from asking for it twice.
		assertEquals(java.util.Arrays.asList("image", "boxes"),
			args.paramsFor().binds());
	}

	@Test
	public void testAnOrdinaryParamHasNeither() {
		ParamSpec image = op("skop.ops.threshold:otsu").param("image");
		assertTrue(image.choices().isEmpty());
		assertNull(image.paramsFor());
	}

	// -- one bad op costs only itself ---------------------------------------

	@Test
	public void testAnUnreadableOpDoesNotCostTheOthers() {
		// The rule skop's own discovery holds to, held to at the boundary as
		// well: reading the whole list or nothing is how one new field empties
		// a menu.
		Map<String, Object> data = Wire.asMap(Json.parseJson(
			"{\"package\": \"skop.ops\", \"failures\": [], \"ops\": [" +
			"{\"name\": \"a:a\", \"module\": \"a\", \"function\": \"a\", " +
			"\"env\": \"minimal\", \"form\": \"function\", \"params\": [], " +
			"\"return_type\": {\"name\": \"int\"}, \"outputs\": " +
			"[{\"name\": \"result\", \"type\": {\"name\": \"int\"}}]}," +
			"{\"name\": \"b:b\", \"module\": \"b\"}," +
			"{\"name\": \"c:c\", \"module\": \"c\", \"function\": \"c\", " +
			"\"env\": \"minimal\", \"form\": \"function\", \"params\": [], " +
			"\"return_type\": {\"name\": \"int\"}, \"outputs\": " +
			"[{\"name\": \"result\", \"type\": {\"name\": \"int\"}}]}]}"), "description");

		Description described = Description.fromJson(data);

		assertEquals(2, described.ops().size());
		assertNotNull(described.op("a:a"));
		assertNotNull(described.op("c:c"));

		assertEquals(1, described.unreadable().size());
		Description.ReadFailure failure = described.unreadable().get(0);
		assertEquals("b:b", failure.name());
		assertTrue(failure.error().contains("function"), failure.error());
	}

	@Test
	public void testTheFixtureIsFullyReadable() {
		// If this fails, skop has grown something this reader has not learned.
		assertEquals(java.util.Collections.emptyList(), description.unreadable());
	}

	// -- the forms --------------------------------------------------------

	@Test
	public void testComputerFormHidesItsBuffer() {
		OpSpec scaleInto = op("skop.ops.toy:scale_into");
		assertEquals(Form.COMPUTER, scaleInto.form());

		ParamSpec result = scaleInto.param("result");
		assertEquals(Direction.OUT, result.direction());
		assertFalse(result.harvestable(), "a user is never asked for a buffer");
		assertEquals(Arrays.asList("image", "factor"), names(scaleInto.inputs()));

		// And it is still an output, and still an image.
		assertEquals(Collections.singletonList("result"), scaleInto.outputs());
		assertEquals(Role.IMAGE, scaleInto.outputSpecs().get(0).role());
	}

	// -- enums ------------------------------------------------------------

	@Test
	public void testEnumChoices() {
		ParamSpec shape = op("skop.ops.morphology:dilation").param("shape");
		assertEquals(TypeSpec.Kind.ENUM, shape.type().kind());
		assertEquals("Footprint", shape.type().detail());

		List<TypeSpec.Choice> choices = shape.type().choices();
		assertEquals(3, choices.size());
		assertEquals("ball", choices.get(0).name());
		assertEquals("ball", choices.get(0).value());
		// The default is spelled as a value, because a value is what the worker
		// rebuilds the enum member from.
		assertEquals("ball", shape.defaultValue());
	}

	// -- axes -------------------------------------------------------------

	@Test
	public void testDeclaredAxes() {
		AxesSpec axes = op("skop.ops.toy:quadrants").param("image").axes();
		assertNotNull(axes);
		assertFalse(axes.variadic());
		assertEquals(2, axes.arity());
		assertEquals("y", axes.slots().get(0).name());
		assertEquals("x", axes.slots().get(1).name());
	}

	@Test
	public void testVariadicAxes() {
		AxesSpec axes = op("skop.ops.threshold:otsu").param("image").axes();
		assertNotNull(axes);
		assertTrue(axes.variadic());
		assertEquals(0, axes.arity());
	}

	@Test
	public void testAParameterTakingNoArrayDeclaresNoAxes() {
		assertNull(op("skop.ops.toy:add").param("a").axes());
	}

	// -- the unrenderable case --------------------------------------------

	@Test
	public void testEveryFixtureParamIsRenderable() {
		// Not a law -- UNKNOWN is expected to turn up eventually -- but a
		// tripwire: if this starts failing, a wire type was added and this
		// side has not learned it.
		for (OpSpec op : description.ops()) {
			assertTrue(op.blockingParams().isEmpty(),
				op.name() + " has unrenderable required parameters: " +
					op.blockingParams());
		}
	}

	@Test
	public void testAnUnknownWireTypeDegradesRatherThanFailing() {
		Map<String, Object> data = Wire.asMap(
			Json.parseJson("{\"name\": \"quaternion\", \"detail\": \"Quat\"}"),
			"type");
		TypeSpec type = TypeSpec.fromJson(data);
		assertEquals(TypeSpec.Kind.UNKNOWN, type.kind());
		assertFalse(type.renderable());
		// And it still says what it was, so the message can name it.
		assertEquals("quaternion", type.wireName());
		assertEquals("Quat", type.detail());
	}

	@Test
	public void testNullableIsReadAsSuch() {
		Map<String, Object> data = Wire.asMap(
			Json.parseJson("{\"name\": \"float\", \"nullable\": true}"), "type");
		TypeSpec type = TypeSpec.fromJson(data);
		assertEquals(TypeSpec.Kind.FLOAT, type.kind());
		assertTrue(type.nullable());
	}

	// -- plans ------------------------------------------------------------

	@Test
	public void testPlanIsRead() {
		AdaptationPlan plan = AdaptationPlan.fromJson(Wire.asMap(Json.parseJson(
			"{\"param\": \"image\", \"input_axes\": [\"z\", \"y\", \"x\"], " +
			"\"mapping\": [1, 2], \"select\": [], \"iterate\": [0], " +
			"\"passed\": [], \"transpose\": [0, 1, 2], " +
			"\"output_axes\": [\"z\", \"y\", \"x\"], \"calls\": 3, " +
			"\"uses_all_data\": true, \"warnings\": [], " +
			"\"summary\": \"run 3 times, once per z position\"}"), "plan"));

		assertEquals("image", plan.param());
		assertArrayEquals(new Integer[] { 1, 2 }, plan.mapping());
		assertArrayEquals(new int[] { 0 }, plan.iterate());
		assertEquals(3, plan.calls());
		assertTrue(plan.usesAllData());
		assertTrue(plan.warnings().isEmpty());
	}

	@Test
	public void testAnEmptyOptionalSlotIsNullAndNotZero() {
		// A null in the mapping means "this optional slot was left empty".
		// Reading it as an int would silently make it axis 0.
		AdaptationPlan plan = AdaptationPlan.fromJson(Wire.asMap(Json.parseJson(
			"{\"param\": \"image\", \"input_axes\": [\"y\", \"x\"], " +
			"\"mapping\": [0, 1, null], \"select\": [], \"iterate\": [], " +
			"\"passed\": [], \"transpose\": [0, 1], " +
			"\"output_axes\": [\"y\", \"x\"], \"calls\": 1, " +
			"\"uses_all_data\": true, \"warnings\": [], \"summary\": \"as is\"}"),
			"plan"));
		assertNull(plan.mapping()[2]);
	}

	@Test
	public void testAPlanKeepsItsJsonForTheReturnTrip() {
		String json =
			"{\"param\": \"image\", \"input_axes\": [\"z\", \"y\", \"x\"], " +
			"\"mapping\": [1, 2], \"select\": [[0, 4]], \"iterate\": [], " +
			"\"passed\": [], \"transpose\": [0, 1], " +
			"\"output_axes\": [\"y\", \"x\"], \"calls\": 1, " +
			"\"uses_all_data\": false, \"warnings\": [\"y is being fed the z axis\"], " +
			"\"summary\": \"run at z=4, discarding the rest\", " +
			"\"something_new\": 7}";
		AdaptationPlan plan =
			AdaptationPlan.fromJson(Wire.asMap(Json.parseJson(json), "plan"));

		assertFalse(plan.usesAllData());
		assertEquals(Collections.singletonList("y is being fed the z axis"),
			plan.warnings());
		assertArrayEquals(new int[] { 0, 4 }, plan.select()[0]);
		// A field this Java does not model still survives the round trip.
		assertEquals(7, ((Number) plan.toJson().get("something_new")).intValue());
	}

	// -- the reading itself -----------------------------------------------

	@Test
	public void testAMissingFieldSaysWhichOne() {
		Map<String, Object> data = Wire.asMap(Json.parseJson("{\"module\": \"m\"}"),
			"op");
		IllegalArgumentException exc = assertThrows(IllegalArgumentException.class,
			() -> OpSpec.fromJson(data));
		assertTrue(exc.getMessage().contains("name"), exc.getMessage());
	}

	@Test
	public void testAWrongTypeSaysWhatWasThere() {
		Map<String, Object> data =
			Wire.asMap(Json.parseJson("{\"name\": 7}"), "type");
		IllegalArgumentException exc = assertThrows(IllegalArgumentException.class,
			() -> Wire.string(data, "name"));
		assertTrue(exc.getMessage().contains("a string"), exc.getMessage());
		assertTrue(exc.getMessage().contains("Integer"), exc.getMessage());
	}

	private static List<String> names(List<ParamSpec> params) {
		List<String> result = new java.util.ArrayList<>();
		for (ParamSpec param : params) result.add(param.name());
		return result;
	}

	private static void assertArrayEquals(Integer[] expected, Integer[] actual) {
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
	}

	private static void assertArrayEquals(int[] expected, int[] actual) {
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
	}
}
