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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.Cursor;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.roi.labeling.LabelRegions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scijava.Context;
import org.scijava.module.Module;
import org.scijava.module.ModuleInfo;
import org.scijava.module.ModuleItem;
import org.scijava.module.ModuleService;
import org.scijava.module.process.PreprocessorPlugin;
import org.scijava.plugin.PluginService;

/**
 * Per-axis decisions, offered rather than assumed.
 * <p>
 * skop supplies a default plan and never a veto: whether a stack is processed
 * plane by plane or as a volume is a property of the experiment rather than of
 * the op, and only the person who acquired it knows which. Up to now this front
 * end took the default silently. These tests are about the dialog that lets
 * someone disagree with it -- built through the preprocessor rather than through
 * a UI, so that the whole thing can be checked without one.
 *
 * @author Curtis Rueden
 */
public class AxisMappingTest {

	private static Context context;
	private static SkopService skop;
	private static ModuleService modules;
	private static DatasetService datasets;
	private static AxisPreprocessor preprocessor;

	@BeforeAll
	public static void setUp() throws Exception {
		assumeTrue(SkopRunner.fromCheckout() != null,
			"no scikit-ops checkout; set -Dskop.checkout");
		context = new Context();
		skop = context.getService(SkopService.class);
		modules = context.getService(ModuleService.class);
		datasets = context.getService(DatasetService.class);
		skop.discover().get();

		for (PreprocessorPlugin plugin : context.getService(PluginService.class)
			.createInstancesOfType(PreprocessorPlugin.class))
		{
			if (plugin instanceof AxisPreprocessor) {
				preprocessor = (AxisPreprocessor) plugin;
			}
		}
		assertNotNull(preprocessor, "AxisPreprocessor did not load");
	}

	@AfterAll
	public static void tearDown() {
		if (context != null) context.dispose();
	}

	private static ModuleInfo info(String opName) {
		for (ModuleInfo info : modules.getModules()) {
			if (("skop:" + opName).equals(info.getIdentifier())) return info;
		}
		throw new AssertionError("no registered module for " + opName);
	}

	private static Dataset image(long[] dims, AxisType[] axes) {
		Dataset dataset = datasets.create(new FloatType(), dims, "test", axes);
		Cursor<RealType<?>> cursor = dataset.cursor();
		while (cursor.hasNext()) cursor.next().setReal(1);
		return dataset;
	}

	/**
	 * A module with its image set and the mapping widgets built, exactly as a
	 * dialog would have them the moment it is drawn.
	 */
	private static Module harvested(String opName, Dataset image)
		throws Exception
	{
		Module module = modules.createModule(info(opName));
		module.setInput("image", image);
		module.resolveInput("image");
		preprocessor.process(module);
		return module;
	}

	private static List<String> itemNames(Module module) {
		List<String> names = new ArrayList<>();
		for (ModuleItem<?> item : module.getInfo().inputs()) {
			names.add(item.getName());
		}
		return names;
	}

	private static ModuleItem<?> item(Module module, String name) {
		ModuleItem<?> item = module.getInfo().getInput(name);
		assertNotNull(item, "no item '" + name + "' among " + itemNames(module));
		return item;
	}

	private static Module run(Module module) throws Exception {
		return modules.run(module, false, new LinkedHashMap<>()).get();
	}

	// -- the widgets ---------------------------------------------------------

	@Test
	public void testAStackGetsASlotPerAxisTheOpConsumes() throws Exception {
		// quadrants declares Axes("y", "x"): two slots, and z left over.
		Module module = harvested("skop.ops.toy:quadrants",
			image(new long[] { 8, 16, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));

		ModuleItem<?> ySlot = item(module, AxisMapping.slotName("image", 0));
		ModuleItem<?> xSlot = item(module, AxisMapping.slotName("image", 1));

		// The choices are the image's own axes, with their extents.
		assertEquals(Arrays.asList("Z (3)", "Y (16)", "X (8)"), ySlot.getChoices());
		// And the default is what skop would have done unasked.
		assertEquals("Y (16)", ySlot.getDefaultValue());
		assertEquals("X (8)", xSlot.getDefaultValue());
	}

	@Test
	public void testALeftoverAxisGetsADisposition() throws Exception {
		Module module = harvested("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));

		// z is axis 0 in numpy order.
		ModuleItem<?> disposition =
			item(module, AxisMapping.dispositionName("image", 0));
		assertEquals(AxisMapping.ITERATE, disposition.getDefaultValue(),
			"skop's default never discards data");
		assertTrue(disposition.getChoices().contains(AxisMapping.SELECT));
		assertFalse(disposition.getChoices().contains(AxisMapping.PASS),
			"quadrants is not variadic, so it cannot be handed extra axes");

		ModuleItem<?> position = item(module, AxisMapping.positionName("image", 0));
		assertEquals(0L, position.getDefaultValue());
		assertEquals(2L, position.getMaximumValue());
	}

	@Test
	public void testAVariadicOpMayBeHandedItsLeftovers() throws Exception {
		// Global thresholding is a histogram over whatever it is given, so
		// passing a stack through whole is a real option -- one threshold for
		// the stack, rather than one per plane.
		Module module = harvested("skop.ops.threshold:otsu",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));

		ModuleItem<?> disposition =
			item(module, AxisMapping.dispositionName("image", 0));
		assertTrue(disposition.getChoices().contains(AxisMapping.PASS));
		assertEquals(AxisMapping.PASS, disposition.getDefaultValue(),
			"a variadic op is handed its leftovers by default");
	}

	@Test
	public void testAPlainPlaneNeedsNoDecisions() throws Exception {
		// Nothing is left over, so there is nothing to ask about -- and the
		// dialog does not grow a section that says so.
		Module module = harvested("skop.ops.toy:quadrants",
			image(new long[] { 8, 8 }, new AxisType[] { Axes.X, Axes.Y }));

		assertNull(module.getInfo().getInput(AxisMapping.dispositionName("image", 0)));
		assertNotNull(module.getInfo().getInput(AxisMapping.slotName("image", 0)));
	}

	@Test
	public void testAnOpWithNoDeclaredAxesGetsNoWidgets() throws Exception {
		// scale takes a bare ndarray: it consumes no particular axis, so there
		// is no mapping to make.
		Module module = harvested("skop.ops.toy:scale",
			image(new long[] { 8, 8 }, new AxisType[] { Axes.X, Axes.Y }));

		for (String name : itemNames(module)) {
			assertFalse(name.startsWith(AxisMapping.PREFIX),
				"unexpected mapping item: " + name);
		}
	}

	@Test
	public void testNoImageMeansNoWidgetsAndNoError() throws Exception {
		// The op still runs; it just runs on skop's default plan, exactly as
		// it did before any of this existed.
		Module module = modules.createModule(info("skop.ops.toy:quadrants"));
		preprocessor.process(module);
		assertFalse(preprocessor.isCanceled());
		for (String name : itemNames(module)) {
			assertFalse(name.startsWith(AxisMapping.PREFIX));
		}
	}

	@Test
	public void testTwoRunsGetIndependentWidgets() throws Exception {
		// Two people thresholding two differently shaped stacks must not be
		// editing the same list of items.
		Module first = harvested("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));
		Module second = harvested("skop.ops.toy:quadrants",
			image(new long[] { 4, 4 }, new AxisType[] { Axes.X, Axes.Y }));

		assertNotNull(first.getInfo().getInput(
			AxisMapping.dispositionName("image", 0)));
		assertNull(second.getInfo().getInput(
			AxisMapping.dispositionName("image", 0)));
		// And the registered info is untouched by either.
		assertNull(info("skop.ops.toy:quadrants").getInput(
			AxisMapping.dispositionName("image", 0)));
	}

	// -- what the choices actually do ----------------------------------------

	@Test
	public void testTheDefaultIsWhatItAlwaysWas() throws Exception {
		Module module = harvested("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));

		Module ran = run(module);
		ImgLabeling<String, ?> labels = labeling(ran);
		assertEquals(3, labels.dimension(2));
		// Four quadrants per plane, renumbered across three planes.
		assertEquals(12, new LabelRegions<>(labels).getExistingLabels().size());
	}

	@Test
	public void testChoosingASinglePosition() throws Exception {
		// "Use a single position" is the one choice that discards data, which
		// is why skop never picks it on its own and a person has to.
		Module module = harvested("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));
		module.setInput(AxisMapping.dispositionName("image", 0), AxisMapping.SELECT);
		module.setInput(AxisMapping.positionName("image", 0), 1L);

		ImgLabeling<String, ?> labels = labeling(run(module));
		assertEquals(2, labels.numDimensions(), "one plane, not a stack");
		assertEquals(4, new LabelRegions<>(labels).getExistingLabels().size());
	}

	@Test
	public void testSwappingTwoSlotsTransposesTheResult() throws Exception {
		// The op is told to read the image the other way round. Nothing
		// crashes -- that is the whole danger -- but the result is a different
		// shape, which is what makes the change visible at all.
		Module module = harvested("skop.ops.toy:quadrants",
			image(new long[] { 8, 4, 2 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));

		// Default: y <- Y, x <- X, iterate z. Instead: y <- Z, x <- X, and
		// iterate Y.
		module.setInput(AxisMapping.slotName("image", 0), "Z (2)");
		module.setInput(AxisMapping.slotName("image", 1), "X (8)");

		ImgLabeling<String, ?> labels = labeling(run(module));
		assertEquals(3, labels.numDimensions());
		// Iterated over Y, which is 4 long, so 4 planes of 4 quadrants.
		assertEquals(16, new LabelRegions<>(labels).getExistingLabels().size());
	}

	@Test
	public void testPassingAStackThroughGivesOneThresholdForItAll() throws Exception {
		// The decision this whole feature exists for: one threshold for the
		// stack, or one per plane? Only the experiment knows.
		Dataset stack = image(new long[] { 8, 8, 2 },
			new AxisType[] { Axes.X, Axes.Y, Axes.Z });
		Cursor<RealType<?>> cursor = stack.cursor();
		int i = 0;
		while (cursor.hasNext()) cursor.next().setReal(i++ < 64 ? 10 : 200);

		Module module = harvested("skop.ops.threshold:otsu", stack);
		module.setInput(AxisMapping.dispositionName("image", 0), AxisMapping.ITERATE);
		module.setInput("label_objects", false);

		ImgLabeling<String, ?> perPlane = labeling(run(module));
		assertEquals(3, perPlane.numDimensions());

		Module whole = harvested("skop.ops.threshold:otsu", stack);
		whole.setInput(AxisMapping.dispositionName("image", 0), AxisMapping.PASS);
		whole.setInput("label_objects", false);
		ImgLabeling<String, ?> together = labeling(run(whole));
		assertEquals(3, together.numDimensions());
	}

	// -- what a user was told ------------------------------------------------

	@Test
	public void testTheChoicesAreDescribed() throws Exception {
		Module module = harvested("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));

		ModuleItem<?> slot = item(module, AxisMapping.slotName("image", 0));
		assertEquals("Image: Y", slot.getLabel());
		assertNotNull(slot.getDescription());

		ModuleItem<?> disposition =
			item(module, AxisMapping.dispositionName("image", 0));
		assertEquals("What to do with Z", disposition.getLabel());
		assertTrue(disposition.getDescription().contains("never discards"));
	}

	@Test
	public void testAnUnnamedAxisIsStillOfferable() throws Exception {
		// A plain TIFF has no axis types at all, and its axes still have to be
		// mappable -- by number, since they have no names.
		Module module = harvested("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.unknown(), Axes.unknown(), Axes.unknown() }));

		ModuleItem<?> slot = item(module, AxisMapping.slotName("image", 0));
		assertEquals(Arrays.asList("axis 0 (3)", "axis 1 (8)", "axis 2 (8)"),
			slot.getChoices());
		assertEquals("axis 1 (8)", slot.getDefaultValue());
	}

	@SuppressWarnings("unchecked")
	private static ImgLabeling<String, ?> labeling(Module module) {
		Object result = module.getOutput("result");
		assertTrue(result instanceof ImgLabeling,
			"expected a labeling, got " + result + " (" +
				((OpModule) module).getCancelReason() + ")");
		return (ImgLabeling<String, ?>) result;
	}

}
