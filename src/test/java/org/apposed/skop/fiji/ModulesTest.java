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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.imagej.Dataset;

import org.apposed.appose.util.Json;
import org.apposed.skop.fiji.wire.Description;
import org.apposed.skop.fiji.wire.OpSpec;
import org.apposed.skop.fiji.wire.Wire;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scijava.Context;
import org.scijava.ItemIO;
import org.scijava.module.ModuleInfo;
import org.scijava.module.ModuleItem;
import org.scijava.module.ModuleService;
import org.scijava.widget.NumberWidget;

/**
 * Turning op specs into SciJava modules.
 * <p>
 * Everything here runs against the captured description, with no worker and no
 * environment: building a module out of a spec is pure translation, and it is
 * worth being able to check it in a second rather than a minute.
 *
 * @author Curtis Rueden
 */
public class ModulesTest {

	private static Context context;
	private static Description description;

	@BeforeAll
	public static void setUp() throws Exception {
		context = new Context();
		try (InputStream in = ModulesTest.class.getResourceAsStream(
			"/org/apposed/skop/fiji/wire/describe.json"))
		{
			assertNotNull(in, "describe.json is missing from the test resources");
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			for (int n; (n = in.read(chunk)) > 0;) buffer.write(chunk, 0, n);
			description = Description.fromJson(Wire.asMap(Json.parseJson(
				new String(buffer.toByteArray(), StandardCharsets.UTF_8)),
				"description"));
		}
	}

	@AfterAll
	public static void tearDown() {
		if (context != null) context.dispose();
	}

	private static OpModuleInfo info(String name) {
		OpSpec op = description.op(name);
		assertNotNull(op, "no such op in the fixture: " + name);
		return new OpModuleInfo(context, op);
	}

	private static List<String> names(Iterable<ModuleItem<?>> items) {
		List<String> result = new ArrayList<>();
		for (ModuleItem<?> item : items) result.add(item.getName());
		return result;
	}

	// -- the module as a whole --------------------------------------------

	@Test
	public void testMenuPath() {
		assertEquals("Plugins > scikit-ops > Threshold > Otsu",
			info("skop.ops.threshold:otsu").getMenuPath().getMenuString());
	}

	@Test
	public void testANestedNamespaceGetsANestedMenu() {
		assertEquals("Plugins > scikit-ops > Morphology > Dilation",
			info("skop.ops.morphology:dilation").getMenuPath().getMenuString());
	}

	@Test
	public void testIdentifierCarriesTheOpId() {
		// This is what a recorded macro contains. Changing it breaks saved
		// macros, so it is a public string and this test says so.
		assertEquals("skop:skop.ops.threshold:otsu",
			info("skop.ops.threshold:otsu").getIdentifier());
	}

	@Test
	public void testDescriptionIsTheSummaryLine() {
		assertEquals("Threshold an image by Otsu's method.",
			info("skop.ops.threshold:otsu").getDescription());
	}

	@Test
	public void testCancelableAndHeadless() {
		OpModuleInfo info = info("skop.ops.toy:add");
		assertTrue(info.canCancel());
		assertTrue(info.canRunHeadless());
	}

	// -- inputs ------------------------------------------------------------

	@Test
	public void testScalarInputs() {
		OpModuleInfo info = info("skop.ops.toy:add");
		assertEquals(java.util.Arrays.asList("a", "b"), names(info.inputs()));
		for (ModuleItem<?> item : info.inputs()) {
			assertEquals(Long.class, item.getType());
			assertTrue(item.isRequired());
			assertEquals(ItemIO.INPUT, item.getIOType());
		}
	}

	@Test
	public void testAnArrayInputIsADataset() {
		ModuleItem<?> image = info("skop.ops.threshold:otsu").getInput("image");
		assertEquals(Dataset.class, image.getType());
		assertTrue(image.isRequired());
	}

	@Test
	public void testDefaultsAndOptionality() {
		ModuleItem<?> invert = info("skop.ops.threshold:otsu").getInput("invert");
		assertEquals(Boolean.class, invert.getType());
		assertFalse(invert.isRequired(), "skop already has an answer for this");
		assertEquals(Boolean.FALSE, invert.getDefaultValue());
	}

	@Test
	public void testLabelsAreReadable() {
		// label_objects is a Python name; "Label objects" is a user's.
		assertEquals("Label objects",
			info("skop.ops.threshold:otsu").getInput("label_objects").getLabel());
	}

	@Test
	public void testDocumentationBecomesTooltips() {
		assertEquals("Whether to keep what falls below the threshold instead.",
			info("skop.ops.threshold:otsu").getInput("invert").getDescription());
	}

	@Test
	public void testUiHintsBecomeWidgetSettings() {
		ModuleItem<?> factor = info("skop.ops.toy:scale").getInput("factor");
		assertEquals(Double.class, factor.getType());
		assertEquals(NumberWidget.SLIDER_STYLE, factor.getWidgetStyle());
		assertEquals(0.0, ((Number) factor.getMinimumValue()).doubleValue(), 0);
		assertEquals(10.0, ((Number) factor.getMaximumValue()).doubleValue(), 0);
		assertEquals(0.1, factor.getStepSize().doubleValue(), 1e-12);
		assertEquals(2.0, ((Number) factor.getDefaultValue()).doubleValue(), 0);
	}

	@Test
	public void testAnEnumBecomesAChoiceList() {
		ModuleItem<?> shape = info("skop.ops.morphology:dilation").getInput("shape");
		assertEquals(String.class, shape.getType());
		assertEquals(java.util.Arrays.asList("ball", "box", "diamond"),
			shape.getChoices());
		assertEquals("ball", shape.getDefaultValue());
	}

	@Test
	public void testAnOutputBufferIsNeverHarvested() {
		OpModuleInfo info = info("skop.ops.toy:scale_into");
		assertEquals(java.util.Arrays.asList("image", "factor"), names(info.inputs()));
		assertNull(info.getInput("result"));
		// And it is still an output.
		assertNotNull(info.getOutput("result"));
	}

	// -- outputs -----------------------------------------------------------

	@Test
	public void testOutputsAreTypedByWhatTheyAre() {
		OpModuleInfo info = info("skop.ops.toy:scale");
		assertEquals(java.util.Arrays.asList("scaled", "total"), names(info.outputs()));
		assertEquals(Dataset.class, info.getOutput("scaled").getType());
		assertEquals(Double.class, info.getOutput("total").getType());
		assertEquals(ItemIO.OUTPUT, info.getOutput("scaled").getIOType());
	}

	@Test
	public void testAStringOutput() {
		assertEquals(String.class, info("skop.ops.toy:probe").getOutput("result")
			.getType());
	}

	// -- registration ------------------------------------------------------

	@Test
	public void testEveryFixtureOpBuildsAModule() {
		for (OpSpec op : description.ops()) {
			OpModuleInfo info = new OpModuleInfo(context, op);
			assertNotNull(info.getMenuPath());
			assertTrue(info.unrenderableParams().isEmpty(),
				op.name() + " lost a parameter: " + info.unrenderableParams());
		}
	}

	@Test
	public void testModulesRegisterAndUnregister() {
		ModuleService modules = context.getService(ModuleService.class);
		List<OpModuleInfo> infos = new ArrayList<>();
		for (OpSpec op : description.ops()) infos.add(new OpModuleInfo(context, op));

		int before = modules.getModules().size();
		modules.addModules(infos);
		assertEquals(before + infos.size(), modules.getModules().size());

		ModuleInfo found = null;
		for (ModuleInfo info : modules.getModules()) {
			if ("skop:skop.ops.threshold:otsu".equals(info.getIdentifier())) {
				found = info;
			}
		}
		assertNotNull(found, "the registered op is not in the module index");

		modules.removeModules(infos);
		assertEquals(before, modules.getModules().size());
	}

	@Test
	public void testAModuleKnowsItsOwnInfo() throws Exception {
		// The registered one, shared by every run, as an ordinary SciJava
		// command's is. Nothing mutates it: the axis mapping is one static text
		// field whose *value* a run sets, not a list of items a run builds.
		OpModuleInfo info = info("skop.ops.toy:add");
		org.scijava.module.Module module = info.createModule();

		assertTrue(module instanceof OpModule);
		assertSame(info, module.getInfo());
	}

	// -- the cache round trip ----------------------------------------------

	@Test
	public void testADescriptionSurvivesBeingCached() {
		// The service writes the JSON that arrived rather than a
		// re-serialization of what it models, so a field this side does not
		// know about still survives a launch.
		Map<String, Object> json = description.toJson();
		Description again = Description.fromJson(
			Wire.asMap(Json.parseJson(Json.toJson(json)), "cache"));
		assertEquals(description.ops().size(), again.ops().size());
		assertEquals(description.failures().size(), again.failures().size());
		assertEquals("skop.ops.threshold:otsu",
			again.op("skop.ops.threshold:otsu").name());
	}
}
