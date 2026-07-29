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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.imglib2.appose.ShmImg;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;

import org.apposed.appose.NDArray;
import org.apposed.skop.fiji.wire.AdaptationPlan;
import org.apposed.skop.fiji.wire.Description;
import org.apposed.skop.fiji.wire.OpSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The whole boundary, end to end: Java builds the environment, Java drives the
 * worker, and an op runs on shared memory Java allocated.
 * <p>
 * This is the test the whole first phase exists to make possible. It needs a
 * scikit-ops checkout ({@code -Dskop.checkout=...}, {@code $SKOP_CHECKOUT}, or
 * a sibling directory) and it will build environments, which the first time
 * means a download. Without a checkout it skips rather than fails, because a
 * CI run that has neither should still be able to run {@link AxesTest} and the
 * wire tests.
 *
 * @author Curtis Rueden
 */
public class SkopRunnerTest {

	private static SkopRunner runner;
	private static Description description;

	@BeforeAll
	public static void setUp() throws Exception {
		runner = SkopRunner.fromCheckout();
		assumeTrue(runner != null, "no scikit-ops checkout; set -Dskop.checkout");
		description = runner.describe();
	}

	@AfterAll
	public static void tearDown() {
		if (runner != null) runner.close();
	}

	private static OpSpec op(String name) {
		OpSpec op = description.op(name);
		assertNotNull(op, "no such op: " + name);
		return op;
	}

	private static Map<String, Object> args(Object... pairs) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			result.put((String) pairs[i], pairs[i + 1]);
		}
		return result;
	}

	// -- discovery --------------------------------------------------------

	@Test
	public void testDescribeFindsTheOps() {
		assertFalse(description.ops().isEmpty());
		assertNotNull(description.op("skop.ops.toy:add"));
		assertNotNull(description.op("skop.ops.threshold:otsu"));
	}

	@Test
	public void testAModuleThatWillNotImportCostsOnlyItself() {
		// Not an assertion that the checkout is tidy -- somebody is always
		// halfway through writing an op, and a half-written one must not empty
		// this front end's menu. What is asserted is that the rest arrive and
		// that the casualty is *reported* rather than swallowed.
		assertTrue(description.ops().size() > 1);
		for (Description.LoadFailure failure : description.failures()) {
			assertNotNull(failure.module());
			assertFalse(failure.error().isEmpty(), failure.module());
			assertTrue(failure.message().contains(failure.module()));
		}
	}

	@Test
	public void testThisSideCanReadEverythingSkopSaid() {
		// A read failure means skop grew a field this Java has not learned,
		// which is a different problem from an op that will not import and has
		// a different fix: here, rather than there.
		assertEquals(Collections.emptyList(), description.unreadable());
	}

	@Test
	public void testOpIdsAreStable() {
		// The ID ends up in a recorded macro, so it is a public string. If this
		// test fails, someone's saved macro just broke.
		assertEquals("skop.ops.threshold:otsu", op("skop.ops.threshold:otsu").name());
	}

	@Test
	public void testConstantsComeFromSkopRatherThanFromHere() throws Exception {
		Map<String, Object> constants = runner.constants();
		assertEquals("skop_invoke(task, module, function, kwargs, plans)",
			constants.get("call"));
		assertTrue(((String) constants.get("init")).contains("skop.worker"));
		// And they are fetched once.
		assertSame(constants, runner.constants());
	}

	@Test
	public void testEnvIdsAreTheDirectoriesOnDisk() {
		List<String> ids = runner.envIds();
		assertTrue(ids.contains("minimal"), ids.toString());
		assertTrue(ids.contains("skimage"), ids.toString());
		assertTrue(new File(runner.envsDir(), "minimal/pixi.toml").exists());
	}

	// -- running ----------------------------------------------------------

	@Test
	public void testScalarRoundTrip() throws Exception {
		Map<String, Object> outputs =
			runner.run(op("skop.ops.toy:add"), args("a", 2, "b", 3));
		assertEquals(5, ((Number) outputs.get("result")).intValue());
	}

	@Test
	public void testMissingArgumentIsCaughtBeforeTheWorkerSeesIt() {
		IllegalArgumentException exc = assertThrows(IllegalArgumentException.class,
			() -> runner.run(op("skop.ops.toy:add"), args("a", 2)));
		assertTrue(exc.getMessage().contains("b"), exc.getMessage());
	}

	@Test
	public void testUnknownArgumentIsRejected() {
		IllegalArgumentException exc = assertThrows(IllegalArgumentException.class,
			() -> runner.run(op("skop.ops.toy:add"), args("a", 2, "b", 3, "c", 4)));
		assertTrue(exc.getMessage().contains("c"), exc.getMessage());
	}

	@Test
	public void testMultipleOutputs() throws Exception {
		ShmImg<FloatType> image = new ShmImg<>(new FloatType(), 4, 4);
		fill(image, 2);
		try {
			Map<String, Object> outputs = runner.run(op("skop.ops.toy:scale"),
				args("image", image.ndArray(), "factor", 3.0));

			assertTrue(outputs.get("scaled") instanceof NDArray);
			// 16 pixels of 2, tripled.
			assertEquals(96.0, ((Number) outputs.get("total")).doubleValue(), 1e-6);
			close(outputs);
		}
		finally {
			image.ndArray().close();
		}
	}

	@Test
	public void testAnArrayCrossesWithoutACopy() throws Exception {
		// Computer form: the op writes into a buffer this side allocated, and
		// the buffer is shared memory, so there is nothing to copy back. The
		// test is that the values are simply there afterwards.
		ShmImg<FloatType> image = new ShmImg<>(new FloatType(), 8, 8);
		ShmImg<FloatType> result = new ShmImg<>(new FloatType(), 8, 8);
		fill(image, 3);
		try {
			Map<String, Object> outputs = runner.run(op("skop.ops.toy:scale_into"),
				args("image", image.ndArray(), "result", result.ndArray(),
					"factor", 2.0));

			// The output is the very buffer that was handed in.
			assertSame(result.ndArray(), outputs.get("result"));
			for (FloatType pixel : result) {
				assertEquals(6.0f, pixel.get(), 1e-6f);
			}
		}
		finally {
			image.ndArray().close();
			result.ndArray().close();
		}
	}

	@Test
	public void testProgressArrives() throws Exception {
		List<String> messages = new ArrayList<>();
		ShmImg<FloatType> image = new ShmImg<>(new FloatType(), 16, 16);
		fill(image, 1);
		try {
			runner.run(op("skop.ops.toy:slow_sum"),
				args("image", image.ndArray(), "steps", 4), null, null,
				event -> {
					if (event.message != null) messages.add(event.message);
				}, null);
			assertFalse(messages.isEmpty(), "no progress events arrived");
			assertTrue(messages.get(0).startsWith("Summing chunk"), messages.toString());
		}
		finally {
			image.ndArray().close();
		}
	}

	@Test
	public void testAFailingOpSurfacesItsTraceback() {
		// quadrants unpacks its input as two axes, so a stack handed to it
		// without a plan fails inside the op -- in another interpreter, which
		// is the interesting part of the failure and the reason it has to
		// arrive here in full rather than as a one-line summary.
		ShmImg<FloatType> image = new ShmImg<>(new FloatType(), 4, 4, 3);
		try {
			Exception exc = assertThrows(Exception.class, () -> runner.run(
				op("skop.ops.toy:quadrants"), args("image", image.ndArray())));
			assertTrue(exc.getMessage().contains("ValueError") ||
				exc.getMessage().contains("unpack"), exc.getMessage());
		}
		finally {
			image.ndArray().close();
		}
	}

	// -- a real op, in a real environment ----------------------------------

	@Test
	public void testThresholdOtsu() throws Exception {
		// Two populations, so Otsu has a valley to find.
		ShmImg<FloatType> image = new ShmImg<>(new FloatType(), 32, 32);
		int i = 0;
		for (FloatType pixel : image) {
			pixel.set(i++ < 512 ? 10f : 200f);
		}
		try {
			Map<String, Object> outputs = runner.run(op("skop.ops.threshold:otsu"),
				args("image", image.ndArray(), "label_objects", false));

			NDArray labels = (NDArray) outputs.get("result");
			assertNotNull(labels);
			// A mask of the same extent, and not everything on one side of it.
			assertArrayEquals(new int[] { 32, 32 },
				labels.shape().toIntArray(NDArray.Shape.Order.F_ORDER));

			int foreground = 0;
			for (UnsignedShortType pixel : new ShmImg<UnsignedShortType>(labels)) {
				if (pixel.get() > 0) foreground++;
			}
			assertTrue(foreground > 0 && foreground < 32 * 32,
				"threshold kept " + foreground + " of 1024 pixels");
			close(outputs);
		}
		finally {
			image.ndArray().close();
		}
	}

	// -- the axis-order trap -----------------------------------------------

	@Test
	public void testPlanIsGivenAxesInNumpyOrder() throws Exception {
		// An ImgLib2 image of (x=32, y=64, z=3), whose axes Fiji reports as
		// (X, Y, Z). skop must be told (z, y, x) -- and it must agree with the
		// shape Appose derives from the very same array.
		ShmImg<FloatType> image = new ShmImg<>(new FloatType(), 32, 64, 3);
		try {
			AdaptationPlan plan = runner.plan("skop.ops.toy:quadrants", "image",
				Axes.numpyShape(image.ndArray()), Axes.toNumpy("x", "y", "z"));

			assertEquals(Arrays.asList("z", "y", "x"), plan.inputAxes());
			// y and x fill the op's two slots; z is what is looped over.
			assertArrayEquals(new Integer[] { 1, 2 }, plan.mapping());
			assertArrayEquals(new int[] { 0 }, plan.iterate());
			assertEquals(3, plan.calls());
			assertTrue(plan.warnings().isEmpty(), plan.warnings().toString());
		}
		finally {
			image.ndArray().close();
		}
	}

	@Test
	public void testForgettingToReverseIsWrongAndSilent() throws Exception {
		// The same call with the labels left in ImgLib2 order. It does not
		// raise. It does not warn. It runs the op 32 times over transposed
		// planes and returns an array of exactly the right shape.
		ShmImg<FloatType> image = new ShmImg<>(new FloatType(), 32, 64, 3);
		try {
			AdaptationPlan wrong = runner.plan("skop.ops.toy:quadrants", "image",
				Axes.numpyShape(image.ndArray()), Arrays.asList("x", "y", "z"));

			assertTrue(wrong.warnings().isEmpty(),
				"nothing warns about this, which is the whole problem");
			assertEquals(32, wrong.calls());
			assertArrayEquals(new int[] { 2 }, wrong.iterate());
		}
		finally {
			image.ndArray().close();
		}
	}

	@Test
	public void testAdaptingA2dOpToAStack() throws Exception {
		// quadrants is strictly 2-D. Handed a 3-plane stack with a plan, the
		// worker iterates it and stacks the results -- renumbering the labels,
		// since each plane numbered its own objects from 1.
		ShmImg<FloatType> image = new ShmImg<>(new FloatType(), 8, 8, 3);
		try {
			AdaptationPlan plan = runner.plan("skop.ops.toy:quadrants", "image",
				Axes.numpyShape(image.ndArray()), Axes.toNumpy("x", "y", "z"));

			Map<String, AdaptationPlan> plans = new HashMap<>();
			plans.put("image", plan);
			Map<String, Object> outputs = runner.run(op("skop.ops.toy:quadrants"),
				args("image", image.ndArray()), plans, null, null, null);

			NDArray labels = (NDArray) outputs.get("result");
			assertArrayEquals(new int[] { 8, 8, 3 },
				labels.shape().toIntArray(NDArray.Shape.Order.F_ORDER));

			int highest = 0;
			for (UnsignedShortType pixel : new ShmImg<UnsignedShortType>(labels)) {
				highest = Math.max(highest, pixel.get());
			}
			// Four quadrants per plane, renumbered across three planes.
			assertEquals(12, highest);
			close(outputs);
		}
		finally {
			image.ndArray().close();
		}
	}

	// -- helpers ----------------------------------------------------------

	private static void fill(Img<FloatType> image, float value) {
		for (FloatType pixel : image) pixel.set(value);
	}

	/** Releases shared memory the worker allocated for results. */
	private static void close(Map<String, Object> outputs) {
		for (Object value : outputs.values()) {
			if (value instanceof NDArray) ((NDArray) value).close();
		}
	}

	private static void assertArrayEquals(int[] expected, int[] actual) {
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
	}

	private static void assertArrayEquals(Integer[] expected, Integer[] actual) {
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
	}
}
