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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests {@link BundledEnvs}: the recipes a Fiji with no checkout builds from. */
public class BundledEnvsTest {

	@Test
	public void testTheRecipesAreCarried() throws Exception {
		List<String> ids = BundledEnvs.ids();
		assertTrue(ids.contains("minimal"), "no minimal environment in " + ids);
		assertTrue(ids.contains("pytorch"), "no pytorch environment in " + ids);
	}

	@Test
	public void testUnpackingGivesARunnerItsEnvironments() throws Exception {
		SkopRunner runner = SkopRunner.bundled();
		assertNull(runner.root(), "a bundled runner has no checkout to put first");
		assertEquals(BundledEnvs.ids().size(), runner.envIds().size());
		String minimal = new String(Files.readAllBytes(
			runner.envConfig("minimal").toPath()), StandardCharsets.UTF_8);
		assertTrue(minimal.contains("scikit-ops"),
			"the minimal recipe should pin scikit-ops");
	}

	@Test
	public void testUnpackingTwiceReusesTheDirectory() throws Exception {
		File first = BundledEnvs.unpack();
		File second = BundledEnvs.unpack();
		assertEquals(first, second);
	}
}
