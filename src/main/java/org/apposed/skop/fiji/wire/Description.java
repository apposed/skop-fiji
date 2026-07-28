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
package org.apposed.skop.fiji.wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Everything skop found in a collection of ops, failures included.
 * <p>
 * Failures are part of the answer rather than an exception, for the same
 * reason skop's own {@code discover} collects them: an op module that will
 * not import in a minimal environment must not cost the other fifty-eight
 * ops their menu entries. A front end shows the ops and logs the failures.
 *
 * @author Curtis Rueden
 */
public class Description {

	/** An op module that could not be imported, and why. */
	public static class LoadFailure {

		private final String module;
		private final String error;
		private final List<String> heavyImports;
		private final String message;

		public LoadFailure(String module, String error, List<String> heavyImports,
			String message)
		{
			this.module = module;
			this.error = error;
			this.heavyImports = Collections.unmodifiableList(
				new ArrayList<>(heavyImports));
			this.message = message;
		}

		public String module() {
			return module;
		}

		public String error() {
			return error;
		}

		/**
		 * Module-scope imports found in the module that would not load.
		 * <p>
		 * Nearly always the explanation: an op is discovered in a minimal
		 * environment, so anything it needs only in order to run belongs
		 * inside the function body.
		 */
		public List<String> heavyImports() {
			return heavyImports;
		}

		/** The whole diagnostic, as skop phrased it. Log this. */
		public String message() {
			return message;
		}

		@Override
		public String toString() {
			return message;
		}

		static LoadFailure fromJson(Map<String, Object> data) {
			return new LoadFailure(
				Wire.string(data, "module"),
				Wire.string(data, "error"),
				java.util.Arrays.asList(Wire.strings(data, "heavy_imports")),
				Wire.string(data, "message"));
		}
	}

	private final String pkg;
	private final List<OpSpec> ops;
	private final List<LoadFailure> failures;

	public Description(String pkg, List<OpSpec> ops, List<LoadFailure> failures) {
		this.pkg = pkg;
		this.ops = Collections.unmodifiableList(new ArrayList<>(ops));
		this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
	}

	/** The collection that was described, e.g. {@code skop.ops}. */
	public String pkg() {
		return pkg;
	}

	public List<OpSpec> ops() {
		return ops;
	}

	public List<LoadFailure> failures() {
		return failures;
	}

	/** The op of the given ID, or null if there is none. */
	public OpSpec op(String name) {
		for (OpSpec op : ops) {
			if (op.name().equals(name)) return op;
		}
		return null;
	}

	@Override
	public String toString() {
		return pkg + ": " + ops.size() + " op(s), " + failures.size() + " failure(s)";
	}

	public static Description fromJson(Map<String, Object> data) {
		List<OpSpec> ops = new ArrayList<>();
		for (Map<String, Object> op : Wire.maps(data, "ops")) {
			ops.add(OpSpec.fromJson(op));
		}
		List<LoadFailure> failures = new ArrayList<>();
		for (Map<String, Object> failure : Wire.maps(data, "failures")) {
			failures.add(LoadFailure.fromJson(failure));
		}
		return new Description(Wire.string(data, "package"), ops, failures);
	}
}
