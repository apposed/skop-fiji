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

import org.scijava.Context;
import org.scijava.log.LogLevel;
import org.scijava.log.LogService;

/**
 * Runs something that is supposed to fail, without the noise.
 * <p>
 * A failed op logs the whole Python traceback, which is exactly what it is for
 * -- the interesting part of the failure happened in another interpreter --
 * and exactly what nobody wants to read in the middle of a passing test run.
 * Silencing it around the calls that provoke it deliberately keeps an ERROR
 * appearing during these tests meaningful.
 *
 * @author Curtis Rueden
 */
final class Quiet {

	private Quiet() {
		// Prevent instantiation of utility class.
	}

	interface Body<T> {
		T get() throws Exception;
	}

	/** Runs the body with logging off, and puts it back afterwards. */
	static <T> T run(Context context, Body<T> body) throws Exception {
		LogService log = context.getService(LogService.class);
		int level = log.getLevel();
		log.setLevel(LogLevel.NONE);
		try {
			return body.get();
		}
		finally {
			log.setLevel(level);
		}
	}
}
