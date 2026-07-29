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
		private final Map<String, Object> raw;

		public LoadFailure(String module, String error, List<String> heavyImports,
			String message)
		{
			this(module, error, heavyImports, message,
				Collections.<String, Object>emptyMap());
		}

		private LoadFailure(String module, String error, List<String> heavyImports,
			String message, Map<String, Object> raw)
		{
			this.raw = Wire.copy(raw);
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

		/** The JSON this was read from. See {@link OpSpec#toJson()}. */
		public Map<String, Object> toJson() {
			return raw;
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
				Wire.string(data, "message"),
				data);
		}
	}

	/**
	 * An op skop described that this side could not read.
	 * <p>
	 * A different thing from a {@link LoadFailure}, and worth keeping apart:
	 * that one means skop could not import a module, and the fix is in the op.
	 * This one means skop described the op perfectly well and <em>this Java</em>
	 * did not understand the description -- so the fix is here, and usually the
	 * fix is that skop grew a field this reader has not learned.
	 */
	public static class ReadFailure {

		private final String name;
		private final String error;

		public ReadFailure(String name, String error) {
			this.name = name;
			this.error = error;
		}

		/** The op's ID, or a best guess if even that could not be read. */
		public String name() {
			return name;
		}

		public String error() {
			return error;
		}

		@Override
		public String toString() {
			return "Could not read op '" + name + "': " + error;
		}
	}

	private final String pkg;
	private final List<OpSpec> ops;
	private final List<LoadFailure> failures;
	private final List<ReadFailure> unreadable;
	private final Map<String, Object> raw;

	public Description(String pkg, List<OpSpec> ops, List<LoadFailure> failures) {
		this(pkg, ops, failures, Collections.<ReadFailure>emptyList(),
			Collections.<String, Object>emptyMap());
	}

	private Description(String pkg, List<OpSpec> ops, List<LoadFailure> failures,
		List<ReadFailure> unreadable, Map<String, Object> raw)
	{
		this.unreadable = Collections.unmodifiableList(new ArrayList<>(unreadable));
		this.raw = Wire.copy(raw);
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

	/**
	 * The ops this side could not read, and why.
	 * <p>
	 * Empty, nearly always. When it is not, the other ops are still here and
	 * still work -- one op skop describes in a way this reader has not caught
	 * up with must not cost the other sixty-two their menu entries, which is
	 * the same rule skop's own discovery holds to when a module will not
	 * import.
	 */
	public List<ReadFailure> unreadable() {
		return unreadable;
	}

	/** The op of the given ID, or null if there is none. */
	public OpSpec op(String name) {
		for (OpSpec op : ops) {
			if (op.name().equals(name)) return op;
		}
		return null;
	}

	/**
	 * The JSON this was read from, ready to be cached and read back.
	 *
	 * @return the original JSON, or an empty map if built by hand.
	 */
	public Map<String, Object> toJson() {
		return raw;
	}

	@Override
	public String toString() {
		return pkg + ": " + ops.size() + " op(s), " + failures.size() + " failure(s)";
	}

	public static Description fromJson(Map<String, Object> data) {
		List<OpSpec> ops = new ArrayList<>();
		List<ReadFailure> unreadable = new ArrayList<>();
		for (Map<String, Object> op : Wire.maps(data, "ops")) {
			// One op at a time, because one op this reader does not understand
			// must cost only itself. Reading the whole list or nothing is how
			// a single new field empties a menu.
			try {
				ops.add(OpSpec.fromJson(op));
			}
			catch (RuntimeException exc) {
				Object name = op.get("name");
				unreadable.add(new ReadFailure(
					name == null ? "(unnamed)" : String.valueOf(name),
					exc.getMessage()));
			}
		}
		List<LoadFailure> failures = new ArrayList<>();
		for (Map<String, Object> failure : Wire.maps(data, "failures")) {
			failures.add(LoadFailure.fromJson(failure));
		}
		return new Description(Wire.string(data, "package"), ops, failures,
			unreadable, data);
	}
}
