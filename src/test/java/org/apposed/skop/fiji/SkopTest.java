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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Collections;
import java.util.Map;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.Cursor;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scijava.Context;

/** Tests {@link Skop}: ops by name, for scripts. Needs a scikit-ops checkout. */
public class SkopTest {

	private static Context context;
	private static Skop skop;
	private static DatasetService datasets;

	@BeforeAll
	public static void setUp() {
		assumeTrue(SkopRunner.fromCheckout() != null,
			"no scikit-ops checkout; set -Dskop.checkout");
		context = new Context();
		skop = context.getService(Skop.class);
		datasets = context.getService(DatasetService.class);
	}

	@AfterAll
	public static void tearDown() {
		if (context != null) context.dispose();
	}

	/** Half dark, half bright, so Otsu has an easy answer. */
	private static Dataset halves() {
		Dataset image = datasets.create(new FloatType(), new long[] { 32, 32 },
			"halves", new AxisType[] { Axes.X, Axes.Y });
		int i = 0;
		Cursor<RealType<?>> cursor = image.cursor();
		while (cursor.hasNext()) cursor.next().setReal(i++ < 512 ? 10 : 200);
		return image;
	}

	@Test
	public void testEveryWayOfNamingAnOpFindsIt() {
		OpModuleInfo otsu = skop.op("threshold.otsu");
		assertSame(otsu, skop.op("otsu"));
		assertSame(otsu, skop.op("skop.ops.threshold:otsu"));
		assertSame(otsu, skop.op("skop:skop.ops.threshold:otsu"));
		assertTrue(skop.names().contains("threshold.otsu"));
	}

	@Test
	public void testAMisspeltOpSuggestsTheRealOne() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> skop.op("threshold.otsuu"));
		assertTrue(error.getMessage().contains("threshold.otsu"), error.getMessage());
	}

	@Test
	public void testAnImageGoesToTheImageInput() {
		Object labels = skop.run("threshold.otsu", halves(),
			Collections.singletonMap("label_objects", false));
		assertTrue(labels instanceof ImgLabeling, "expected a labeling, got " + labels);
	}

	@Test
	public void testAnUnknownParameterListsTheRealOnes() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> skop.run("threshold.otsu", halves(),
				Collections.singletonMap("lable_objects", false)));
		assertTrue(error.getMessage().contains("label_objects"), error.getMessage());
	}

	@Test
	public void testSeveralOutputsComeBackByName() {
		Map<String, Object> inputs = new java.util.HashMap<>();
		inputs.put("image", halves());
		inputs.put("factor", 3.0);
		Object result = skop.run("toy.scale", inputs);
		assertTrue(result instanceof Map, "expected a map, got " + result);
		Map<?, ?> outputs = (Map<?, ?>) result;
		assertTrue(outputs.get("scaled") instanceof Dataset);
		assertEquals(2, outputs.size());
	}

	@Test
	public void testAnOpWithNoImageTakesItsInputsByName() {
		Map<String, Object> inputs = new java.util.HashMap<>();
		inputs.put("a", 2L);
		inputs.put("b", 3L);
		assertEquals(5L, ((Number) skop.run("toy.add", inputs)).longValue());
	}
}
