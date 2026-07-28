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

import java.util.LinkedHashMap;
import java.util.Map;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.Cursor;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

import org.apposed.skop.fiji.wire.Description;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scijava.Context;
import org.scijava.log.LogLevel;
import org.scijava.log.LogService;
import org.scijava.module.Module;
import org.scijava.module.ModuleInfo;
import org.scijava.module.ModuleService;

/**
 * An op, from a menu entry to a result window, with a worker in between.
 * <p>
 * The first shippable thing, tested the way a user meets it: a module is
 * looked up by the identifier a macro would name it by, run through
 * {@code ModuleService} with a {@code Dataset}, and its outputs come back as
 * Datasets. Nothing here reaches for {@link SkopRunner} directly -- that layer
 * has its own test, and the point of this one is everything stacked on top.
 * <p>
 * Needs a scikit-ops checkout, and skips without one.
 *
 * @author Curtis Rueden
 */
public class SkopServiceTest {

	private static Context context;
	private static SkopService skop;
	private static ModuleService modules;
	private static DatasetService datasets;

	@BeforeAll
	public static void setUp() throws Exception {
		assumeTrue(SkopRunner.fromCheckout() != null,
			"no scikit-ops checkout; set -Dskop.checkout");
		context = new Context();
		skop = context.getService(SkopService.class);
		modules = context.getService(ModuleService.class);
		datasets = context.getService(DatasetService.class);
		assertNotNull(skop, "SkopService did not load");
		// Startup already began this; joining it is what a caller wanting the
		// ops before it does anything else should do.
		skop.discover().get();
	}

	@AfterAll
	public static void tearDown() {
		if (context != null) context.dispose();
	}

	/** The registered module for an op, found the way a macro would find it. */
	private static ModuleInfo module(String opName) {
		for (ModuleInfo info : modules.getModules()) {
			if (("skop:" + opName).equals(info.getIdentifier())) return info;
		}
		throw new AssertionError("no registered module for " + opName);
	}

	private static Module run(String opName, Object... pairs) throws Exception {
		Map<String, Object> inputs = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			inputs.put((String) pairs[i], pairs[i + 1]);
		}
		// Note: unprocessed. The preprocessor chain's job is to fill in what a
		// user did not supply, and a test that supplies everything wants to
		// see exactly what it supplied reach the worker.
		return modules.run(module(opName), false, inputs).get();
	}

	private static Dataset image(String name, long[] dims, AxisType[] axes,
		float value)
	{
		Dataset dataset = datasets.create(new FloatType(), dims, name, axes);
		Cursor<RealType<?>> cursor = dataset.cursor();
		while (cursor.hasNext()) cursor.next().setReal(value);
		return dataset;
	}

	// -- registration ------------------------------------------------------

	@Test
	public void testEveryOpBecameACommand() {
		assertFalse(skop.ops().isEmpty());
		assertEquals(skop.ops().size(), skop.registeredModules().size());
		assertNotNull(module("skop.ops.threshold:otsu"));
		assertNotNull(module("skop.ops.toy:add"));
	}

	@Test
	public void testDiscoveryStartsAtStartup() {
		// A second call does not re-describe: the ops are already there.
		assertTrue(skop.discover().isDone());
	}

	@Test
	public void testTheMenuIsPopulated() {
		assertEquals("Plugins > scikit-ops > Threshold > Otsu",
			module("skop.ops.threshold:otsu").getMenuPath().getMenuString());
	}

	@Test
	public void testDiscoveryIsCachedBetweenCalls() throws Exception {
		// The second description comes off disk, and has to be the same one:
		// a cache that drops an op is a menu that drops an op.
		int before = skop.ops().size();
		Description again = skop.discoverNow();
		assertEquals(before, again.ops().size());
		assertEquals(before, skop.registeredModules().size());
	}

	// -- running -----------------------------------------------------------

	@Test
	public void testAScalarOp() throws Exception {
		Module module = run("skop.ops.toy:add", "a", 2L, "b", 3L);
		assertEquals(5L, ((Number) module.getOutput("result")).longValue());
	}

	@Test
	public void testAnImageInAndAnImageOut() throws Exception {
		Dataset image = datasets.create(new FloatType(), new long[] { 32, 32 },
			"blobs", new AxisType[] { Axes.X, Axes.Y });
		int i = 0;
		Cursor<RealType<?>> cursor = image.cursor();
		while (cursor.hasNext()) cursor.next().setReal(i++ < 512 ? 10 : 200);

		Module module = run("skop.ops.threshold:otsu", "image", image,
			"label_objects", false);

		Object result = module.getOutput("result");
		assertTrue(result instanceof Dataset, "expected a Dataset, got " + result);
		Dataset mask = (Dataset) result;
		assertEquals(32, mask.dimension(0));
		assertEquals(32, mask.dimension(1));

		int foreground = 0;
		Cursor<RealType<?>> out = mask.cursor();
		while (out.hasNext()) {
			if (out.next().getRealDouble() > 0) foreground++;
		}
		assertTrue(foreground > 0 && foreground < 1024,
			"threshold kept " + foreground + " of 1024 pixels");
	}

	@Test
	public void testMultipleOutputs() throws Exception {
		Dataset image = image("flat", new long[] { 4, 4 },
			new AxisType[] { Axes.X, Axes.Y }, 2);
		Module module = run("skop.ops.toy:scale", "image", image, "factor", 3.0);

		assertTrue(module.getOutput("scaled") instanceof Dataset);
		assertEquals(96.0, ((Number) module.getOutput("total")).doubleValue(), 1e-6);
	}

	// -- adaptation, which is where the axis order shows up ------------------

	@Test
	public void testA2dOpRunsOverAStack() throws Exception {
		// quadrants is strictly 2-D. Handed an (X, Y, Z) stack, the axes the
		// Dataset carries are read, reversed into numpy order, planned by
		// skop, and the worker iterates z. Nothing about this is asked of the
		// user, and it is most of what makes a 2-D op usable in Fiji.
		Dataset stack = image("stack", new long[] { 8, 8, 3 },
			new AxisType[] { Axes.X, Axes.Y, Axes.Z }, 1);

		Module module = run("skop.ops.toy:quadrants", "image", stack);

		Dataset labels = (Dataset) module.getOutput("result");
		assertEquals(8, labels.dimension(0));
		assertEquals(8, labels.dimension(1));
		assertEquals(3, labels.dimension(2));

		double highest = 0;
		Cursor<RealType<?>> cursor = labels.cursor();
		while (cursor.hasNext()) {
			highest = Math.max(highest, cursor.next().getRealDouble());
		}
		// Four quadrants per plane, renumbered across three planes. If the
		// axis order were reversed, this would be 8 planes of an 8x3 image and
		// the answer would be 32 -- and nothing else would have complained.
		assertEquals(12.0, highest, 0);
	}

	@Test
	public void testTheResultKeepsItsAxisNames() throws Exception {
		Dataset stack = image("stack", new long[] { 8, 8, 3 },
			new AxisType[] { Axes.X, Axes.Y, Axes.Z }, 1);

		Module module = run("skop.ops.toy:quadrants", "image", stack);
		Dataset labels = (Dataset) module.getOutput("result");

		assertEquals(Axes.X, labels.axis(0).type());
		assertEquals(Axes.Y, labels.axis(1).type());
		assertEquals(Axes.Z, labels.axis(2).type());
	}

	@Test
	public void testAChannelAxisSurvivesTheRoundTrip() throws Exception {
		// ImageJ says "Channel"; skop's alias table folds that to c; and it
		// has to come back as Axes.CHANNEL rather than as a second, new axis
		// type spelled differently.
		Dataset stack = image("multichannel", new long[] { 8, 8, 2 },
			new AxisType[] { Axes.X, Axes.Y, Axes.CHANNEL }, 1);

		Module module = run("skop.ops.toy:quadrants", "image", stack);
		Dataset labels = (Dataset) module.getOutput("result");
		assertEquals(Axes.CHANNEL, labels.axis(2).type());
	}

	// -- errors and cancellation --------------------------------------------

	@Test
	public void testAnUnlabelledStackStillAdapts() throws Exception {
		// A plain stack with no axis types says nothing about what its axes
		// are -- and still works, because skop fills the slots right-aligned:
		// the innermost axes are the ones a 2-D imaging op means by y and x.
		// An unnamed axis matches no slot by name, so it draws no warning
		// either. This is the case a user hits by opening a TIFF.
		Dataset stack = image("unlabelled", new long[] { 8, 8, 3 },
			new AxisType[] { Axes.unknown(), Axes.unknown(), Axes.unknown() }, 1);

		Module module = run("skop.ops.toy:quadrants", "image", stack);
		Dataset labels = (Dataset) module.getOutput("result");

		assertFalse(((OpModule) module).isCanceled());
		assertEquals(8, labels.dimension(0));
		assertEquals(8, labels.dimension(1));
		assertEquals(3, labels.dimension(2));
	}

	@Test
	public void testAFailureIsReportedRatherThanThrown() throws Exception {
		// threshold:minimum insists on a genuinely bimodal histogram and
		// raises when it cannot find one. A uniform image cannot have one, so
		// this fails in another interpreter -- and the module records it and
		// stays intact rather than taking the caller down with it.
		Dataset flat = image("flat", new long[] { 16, 16 },
			new AxisType[] { Axes.X, Axes.Y }, 7);

		Module module;
		// The module logs the whole Python traceback, which is exactly what it
		// is supposed to do and exactly what nobody wants to read in the
		// middle of a passing test run. Silenced here and only here, so that
		// an ERROR appearing during these tests still means something.
		LogService log = context.getService(LogService.class);
		int level = log.getLevel();
		log.setLevel(LogLevel.NONE);
		try {
			module = run("skop.ops.threshold:minimum", "image", flat);
		}
		finally {
			log.setLevel(level);
		}
		OpModule op = (OpModule) module;

		assertTrue(op.isCanceled(), "the failure should have been recorded");
		assertTrue(op.getCancelReason().contains("minimum"), op.getCancelReason());
		assertTrue(op.getCancelReason().contains("two maxima"),
			"the reason should carry what the far side actually said: " +
				op.getCancelReason());
		assertNull(module.getOutput("result"));

		// And a failure is tellable from a cancellation, which the Cancelable
		// interface alone cannot say.
		assertNotNull(op.failure());
		assertTrue(op.failure().getMessage().contains("Unable to find two maxima"),
			op.failure().getMessage());
	}

	@Test
	public void testCancellationStopsAnOpThatChecks() throws Exception {
		Dataset image = image("big", new long[] { 64, 64 },
			new AxisType[] { Axes.X, Axes.Y }, 1);

		ModuleInfo info = module("skop.ops.toy:slow_sum");
		OpModule module = (OpModule) info.createModule();
		module.setInput("image", image);
		module.setInput("steps", 40L);

		Thread canceller = new Thread(() -> {
			try {
				Thread.sleep(300);
				module.cancel("because the test said so");
			}
			catch (InterruptedException exc) {
				Thread.currentThread().interrupt();
			}
		});
		canceller.start();
		module.run();
		canceller.join();

		assertTrue(module.isCanceled());
		assertEquals("because the test said so", module.getCancelReason());
		assertNull(module.failure(), "a cancellation is not a failure");
	}
}
