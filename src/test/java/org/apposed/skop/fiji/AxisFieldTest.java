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
 * The axis-mapping field, as a run actually sees it.
 * <p>
 * {@link AxisSpecTest} checks the language. This checks the wiring: that the
 * field is there, that the preprocessor fills it with what skop would have
 * done, that editing it changes the answer, and that a line that does not
 * describe the image says so instead of running on something else.
 *
 * @author Curtis Rueden
 */
public class AxisFieldTest {

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
		int i = 0;
		while (cursor.hasNext()) cursor.next().setReal(i++ % 7 == 0 ? 200 : 10);
		return dataset;
	}

	/** A module with its image set and the dialog's starting text filled in. */
	private static Module opened(String opName, Dataset image) {
		Module module = modules.createModule(info(opName));
		module.setInput("image", image);
		module.resolveInput("image");
		preprocessor.process(module);
		return module;
	}

	private static String field(Module module) {
		Object value = module.getInput(OpModuleInfo.axisItemName("image"));
		return value == null ? null : String.valueOf(value);
	}

	private static Module run(Module module) throws Exception {
		return modules.run(module, false, new LinkedHashMap<>()).get();
	}

	@SuppressWarnings("unchecked")
	private static ImgLabeling<String, ?> labeling(Module module) {
		Object result = module.getOutput("result");
		assertTrue(result instanceof ImgLabeling, "expected a labeling, got " +
			result + " (" + ((OpModule) module).getCancelReason() + ")");
		return (ImgLabeling<String, ?>) result;
	}

	// -- the field itself ----------------------------------------------------

	@Test
	public void testTheFieldIsThereWithoutAnImage() {
		// A static item, so it exists whether or not anything is open -- which
		// is what makes it survivable by the input harvester, and what lets a
		// macro set it without a display in sight.
		ModuleItem<?> item = info("skop.ops.toy:quadrants")
			.getInput(OpModuleInfo.axisItemName("image"));
		assertNotNull(item);
		assertEquals(String.class, item.getType());
		assertFalse(item.isRequired());
		assertEquals("Image axes", item.getLabel());
		// The tooltip is the only place the syntax is explained, so it has to
		// actually explain it.
		String help = item.getDescription();
		assertTrue(help.contains("One token per axis"), help);
		assertTrue(help.contains("x first"), help);
		assertTrue(help.contains("Leave blank"), help);
	}

	@Test
	public void testAnOpWithNoDeclaredAxesHasNoField() {
		// scale takes a bare ndarray: it consumes no particular axis, so there
		// is no mapping to make and nothing to ask.
		assertNull(info("skop.ops.toy:scale")
			.getInput(OpModuleInfo.axisItemName("image")));
	}

	@Test
	public void testTheDialogOpensShowingWhatWouldHaveHappened() {
		Module module = opened("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));
		assertEquals("x y z!", field(module));
	}

	@Test
	public void testAVariadicOpOpensShowingItsLeftoversPassedThrough() {
		Module module = opened("skop.ops.threshold:otsu",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));
		// Global thresholding is a histogram over whatever it is handed, so
		// skop hands it the whole stack: one threshold, not one per plane.
		assertEquals("x+ y+ z+", field(module));
	}

	@Test
	public void testAPlainPlaneStillGetsALine() {
		Module module = opened("skop.ops.toy:quadrants",
			image(new long[] { 8, 8 }, new AxisType[] { Axes.X, Axes.Y }));
		assertEquals("x y", field(module));
	}

	@Test
	public void testAnUnnamedStack() {
		Module module = opened("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.unknown(), Axes.unknown(), Axes.unknown() }));
		// Nothing to write in front of the modifier, and the slots still fill.
		assertEquals("x y !", field(module));
	}

	@Test
	public void testWhatAUserTypedIsLeftAlone() {
		Module module = modules.createModule(info("skop.ops.toy:quadrants"));
		module.setInput("image", image(new long[] { 8, 8, 3 },
			new AxisType[] { Axes.X, Axes.Y, Axes.Z }));
		module.resolveInput("image");
		module.setInput(OpModuleInfo.axisItemName("image"), "x y z=1");

		preprocessor.process(module);
		assertEquals("x y z=1", field(module), "a macro's value is not a default");
	}

	@Test
	public void testNoImageMeansNoTextAndNoError() {
		Module module = modules.createModule(info("skop.ops.toy:quadrants"));
		preprocessor.process(module);
		assertFalse(preprocessor.isCanceled());
		String text = field(module);
		assertTrue(text == null || text.isEmpty(), "got: " + text);
	}

	// -- what the line does --------------------------------------------------

	@Test
	public void testTheDefaultLineDoesWhatTheDefaultDid() throws Exception {
		Module module = opened("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));

		ImgLabeling<String, ?> labels = labeling(run(module));
		assertEquals(3, labels.dimension(2));
		// Four quadrants per plane, renumbered across three planes.
		assertEquals(12, new LabelRegions<>(labels).getExistingLabels().size());
	}

	@Test
	public void testABlankLineDoesTheSame() throws Exception {
		Module module = modules.createModule(info("skop.ops.toy:quadrants"));
		module.setInput("image", image(new long[] { 8, 8, 3 },
			new AxisType[] { Axes.X, Axes.Y, Axes.Z }));
		module.setInput(OpModuleInfo.axisItemName("image"), "");

		ImgLabeling<String, ?> labels = labeling(run(module));
		assertEquals(12, new LabelRegions<>(labels).getExistingLabels().size());
	}

	@Test
	public void testPinningAnAxisToOnePosition() throws Exception {
		Module module = opened("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));
		module.setInput(OpModuleInfo.axisItemName("image"), "x y z=1");

		ImgLabeling<String, ?> labels = labeling(run(module));
		assertEquals(2, labels.numDimensions(), "one plane, not a stack");
		assertEquals(4, new LabelRegions<>(labels).getExistingLabels().size());
	}

	@Test
	public void testTransposingTheSlots() throws Exception {
		// Read the image the other way round: the op's y slot from Z, and
		// iterate over Y instead. Nothing crashes -- that is the whole danger
		// -- but the shape of the answer changes, which is what makes it
		// visible at all.
		Module module = opened("skop.ops.toy:quadrants",
			image(new long[] { 8, 4, 2 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));
		module.setInput(OpModuleInfo.axisItemName("image"), "x y! y");

		ImgLabeling<String, ?> labels = labeling(run(module));
		// Iterated over Y, which is 4 long: 4 planes of 4 quadrants.
		assertEquals(16, new LabelRegions<>(labels).getExistingLabels().size());
	}

	@Test
	public void testOneThresholdForTheStackOrOnePerPlane() throws Exception {
		// The decision this whole feature exists for.
		Dataset stack = image(new long[] { 8, 8, 2 },
			new AxisType[] { Axes.X, Axes.Y, Axes.Z });

		Module whole = opened("skop.ops.threshold:otsu", stack);
		whole.setInput(OpModuleInfo.axisItemName("image"), "x+ y+ z+");
		whole.setInput("label_objects", false);
		assertEquals(3, labeling(run(whole)).numDimensions());

		Module perPlane = opened("skop.ops.threshold:otsu", stack);
		perPlane.setInput(OpModuleInfo.axisItemName("image"), "x+ y+ z!");
		perPlane.setInput("label_objects", false);
		assertEquals(3, labeling(run(perPlane)).numDimensions());
	}

	// -- what a bad line does ------------------------------------------------

	@Test
	public void testALineThatDoesNotDescribeTheImageIsRefused() throws Exception {
		Module module = opened("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));
		module.setInput(OpModuleInfo.axisItemName("image"), "x y");

		OpModule ran = (OpModule) run(module);
		assertTrue(ran.isCanceled());
		assertTrue(ran.getCancelReason().contains("2 token(s)"),
			ran.getCancelReason());
		assertNull(ran.getOutput("result"), "nothing ran on the wrong axes");
	}

	@Test
	public void testAnImpossibleRequestIsRefusedBySkop() throws Exception {
		// Leaving a required slot unfilled is skop's call, not this side's, and
		// its message is the one worth showing.
		Module module = opened("skop.ops.toy:quadrants",
			image(new long[] { 8, 8, 3 },
				new AxisType[] { Axes.X, Axes.Y, Axes.Z }));
		module.setInput(OpModuleInfo.axisItemName("image"), "x! y! z!");

		OpModule ran = (OpModule) Quiet.run(context, () -> run(module));
		assertTrue(ran.isCanceled());
		assertNotNull(ran.failure());
	}

	private static List<String> names(ModuleInfo info) {
		List<String> names = new ArrayList<>();
		for (ModuleItem<?> item : info.inputs()) names.add(item.getName());
		return names;
	}

	@Test
	public void testTheFieldSitsAfterTheImageItDescribes() {
		List<String> names = names(info("skop.ops.toy:quadrants"));
		assertEquals(names.indexOf("image") + 1,
			names.indexOf(OpModuleInfo.axisItemName("image")));
	}
}
