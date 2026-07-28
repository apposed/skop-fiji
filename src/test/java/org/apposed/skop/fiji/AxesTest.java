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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.apposed.appose.NDArray;
import org.junit.jupiter.api.Test;

/**
 * The order flip, on its own.
 * <p>
 * These need no environment and no worker, which is the point: the flip is
 * the one piece of this front end that is wrong silently, so it gets tests
 * that run every time rather than tests that run when an environment happens
 * to be built.
 *
 * @author Curtis Rueden
 */
public class AxesTest {

	@Test
	public void testToNumpyReverses() {
		assertEquals(Arrays.asList("z", "y", "x"), Axes.toNumpy("x", "y", "z"));
	}

	@Test
	public void testToImgLib2Reverses() {
		assertEquals(Arrays.asList("x", "y", "z"),
			Axes.toImgLib2(Arrays.asList("z", "y", "x")));
	}

	@Test
	public void testRoundTrip() {
		List<String> original = Arrays.asList("x", "y", "c", "z", "t");
		assertEquals(original, Axes.toImgLib2(Axes.toNumpy(original)));
	}

	@Test
	public void testDoesNotMutateItsArgument() {
		List<String> original = Arrays.asList("x", "y", "z");
		Axes.toNumpy(original);
		assertEquals(Arrays.asList("x", "y", "z"), original);
	}

	@Test
	public void testShapeIsCOrder() {
		// An ImgLib2-order (F_ORDER) shape of 32x64x3 is a numpy shape of
		// (3, 64, 32) -- the same array, described the other way round.
		NDArray array = new NDArray(NDArray.DType.FLOAT32,
			new NDArray.Shape(NDArray.Shape.Order.F_ORDER, 32, 64, 3));
		try {
			assertEquals(Arrays.asList(3, 64, 32), Axes.numpyShape(array));
		}
		finally {
			array.close();
		}
	}

	@Test
	public void testShapeAndLabelsAgree() {
		// The invariant that matters: for an image of ImgLib2 dimensions
		// (x=32, y=64, z=3) labelled (X, Y, Z), the shape and the labels handed
		// to skop line up element for element.
		NDArray array = new NDArray(NDArray.DType.FLOAT32,
			new NDArray.Shape(NDArray.Shape.Order.F_ORDER, 32, 64, 3));
		try {
			List<Integer> shape = Axes.numpyShape(array);
			List<String> labels = Axes.toNumpy("x", "y", "z");
			assertEquals(shape.size(), labels.size());
			assertEquals("z", labels.get(0));
			assertEquals(3, shape.get(0).intValue());
			assertEquals("x", labels.get(2));
			assertEquals(32, shape.get(2).intValue());
		}
		finally {
			array.close();
		}
	}

	@Test
	public void testFlip() {
		assertEquals(2, Axes.flip(0, 3));
		assertEquals(1, Axes.flip(1, 3));
		assertEquals(0, Axes.flip(2, 3));
		assertEquals(0, Axes.flip(0, 1));
	}

	@Test
	public void testFlipRejectsAnAxisThatIsNotThere() {
		assertThrows(IndexOutOfBoundsException.class, () -> Axes.flip(3, 3));
		assertThrows(IndexOutOfBoundsException.class, () -> Axes.flip(-1, 3));
	}
}
