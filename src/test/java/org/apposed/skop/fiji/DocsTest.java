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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Reading a docstring for the text a dialog shows.
 *
 * @author Curtis Rueden
 */
public class DocsTest {

	/** A real op's docstring, copied verbatim from skop.ops.threshold. */
	private static final String OTSU =
		"Threshold an image by Otsu's method.\n" +
		"\n" +
		"Chooses the threshold that minimizes the variance within the two classes\n" +
		"it creates, which is the same as maximizing the variance between them.\n" +
		"The standard first thing to try, and what it assumes -- two comparably\n" +
		"sized modes -- is also what it fails on.\n" +
		"\n" +
		"Args:\n" +
		"    image: Image to threshold. A trailing RGB(A) axis is collapsed.\n" +
		"    invert: Whether to keep what falls below the threshold instead.\n" +
		"    label_objects: Whether to label connected components, rather than\n" +
		"        returning a plain binary mask.\n" +
		"\n" +
		"Returns:\n" +
		"    A label image, or a 0/1 mask when ``label_objects`` is off.";

	@Test
	public void testSummaryIsTheFirstLine() {
		assertEquals("Threshold an image by Otsu's method.", Docs.summary(OTSU));
	}

	@Test
	public void testSummaryOfNothing() {
		assertNull(Docs.summary(null));
		assertNull(Docs.summary("   \n  "));
	}

	@Test
	public void testArgs() {
		Map<String, String> args = Docs.args(OTSU);
		assertEquals(3, args.size());
		assertEquals("Image to threshold. A trailing RGB(A) axis is collapsed.",
			args.get("image"));
		assertEquals("Whether to keep what falls below the threshold instead.",
			args.get("invert"));
	}

	@Test
	public void testAContinuationLineJoinsItsEntry() {
		// The line break is there for an 88-column source file, not for a
		// tooltip, so it becomes a space.
		assertEquals(
			"Whether to label connected components, rather than returning a " +
				"plain binary mask.",
			Docs.args(OTSU).get("label_objects"));
	}

	@Test
	public void testTheReturnsSectionIsNotAnArgument() {
		assertTrue(Docs.args(OTSU).containsKey("label_objects"));
		assertEquals(3, Docs.args(OTSU).size());
	}

	@Test
	public void testADocstringWithNoArgsSection() {
		assertTrue(Docs.args("Add two numbers. The smallest op there is.").isEmpty());
		assertTrue(Docs.args(null).isEmpty());
	}

	@Test
	public void testAColonInsideADescriptionIsNotANewEntry() {
		String doc =
			"Do a thing.\n" +
			"\n" +
			"Args:\n" +
			"    mode: How to do it. Note: this matters.\n" +
			"    other: Something else.";
		Map<String, String> args = Docs.args(doc);
		assertEquals(2, args.size());
		assertEquals("How to do it. Note: this matters.", args.get("mode"));
	}

	@Test
	public void testATypedArgsEntry() {
		// Some docstrings write `name (type): description`. The name is still
		// the first token, and what follows is still the description.
		String doc = "Do a thing.\n\nArgs:\n    sigma: Standard deviation.\n";
		assertEquals("Standard deviation.", Docs.args(doc).get("sigma"));
	}
}
