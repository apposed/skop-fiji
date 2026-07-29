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
 * One op, as skop describes it.
 * <p>
 * This is the whole contract between skop and a front end: a front end may
 * read an {@code OpSpec} and nothing else. Everything a dialog needs is here
 * -- what to ask for, what kind of widget, what it means, and where the
 * results go.
 * <p>
 * {@link #name()} deserves particular care. It is
 * {@code skop.ops.threshold:otsu}, and in Fiji it ends up in someone's saved
 * macro, which makes it a public string rather than an implementation
 * detail. Treat it as opaque -- it happens to be {@code module:function}, but
 * only skop is entitled to know that -- and do not reword it casually.
 *
 * @author Curtis Rueden
 */
public class OpSpec {

	private final String name;
	private final String module;
	private final String function;
	private final String env;
	private final boolean mainThread;
	private final boolean exclusive;
	private final Form form;
	private final String doc;
	private final List<ParamSpec> params;
	private final TypeSpec returnType;
	private final Role returnRole;
	private final List<String> outputs;
	private final List<OutputSpec> outputSpecs;
	private final Map<String, Object> raw;

	public OpSpec(String name, String module, String function, String env,
		boolean mainThread, boolean exclusive, Form form, String doc,
		List<ParamSpec> params, TypeSpec returnType, Role returnRole,
		List<String> outputs, List<OutputSpec> outputSpecs)
	{
		this(name, module, function, env, mainThread, exclusive, form, doc,
			params, returnType, returnRole, outputs, outputSpecs,
			java.util.Collections.emptyMap());
	}

	private OpSpec(String name, String module, String function, String env,
		boolean mainThread, boolean exclusive, Form form, String doc,
		List<ParamSpec> params, TypeSpec returnType, Role returnRole,
		List<String> outputs, List<OutputSpec> outputSpecs,
		Map<String, Object> raw)
	{
		this.raw = Wire.copy(raw);
		this.name = name;
		this.module = module;
		this.function = function;
		this.env = env;
		this.mainThread = mainThread;
		this.exclusive = exclusive;
		this.form = form;
		this.doc = doc;
		this.params = Collections.unmodifiableList(new ArrayList<>(params));
		this.returnType = returnType;
		this.returnRole = returnRole;
		this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));
		this.outputSpecs = Collections.unmodifiableList(new ArrayList<>(outputSpecs));
	}

	/** This op's ID, and the string a recorded macro will contain. */
	public String name() {
		return name;
	}

	/** The Python module the op lives in; also, the menu path it earns. */
	public String module() {
		return module;
	}

	public String function() {
		return function;
	}

	/**
	 * ID of the environment this op runs in, naming {@code envs/<id>/}, or
	 * null if it has none.
	 * <p>
	 * Null means a <em>workflow</em>: an op that composes other ops and so
	 * runs wherever they do rather than anywhere itself. See
	 * {@link #isWorkflow()}.
	 */
	public String env() {
		return env;
	}

	/**
	 * Whether this op composes other ops rather than computing anything.
	 * <p>
	 * The absence of an environment is the marker -- {@code @op()} with no
	 * {@code env} -- so there is no flag to read and nothing to keep in step.
	 * A workflow's body calls {@code skop.run} on the ops a caller chose,
	 * which means it needs a Python runner to be ambient around it, which is
	 * the one thing this front end does not have.
	 */
	public boolean isWorkflow() {
		return env == null;
	}

	/** Whether the op must run on the worker's main thread. */
	public boolean mainThread() {
		return mainThread;
	}

	/** Whether the op needs a worker to itself. */
	public boolean exclusive() {
		return exclusive;
	}

	public Form form() {
		return form;
	}

	/** The op's docstring, Google-style: a summary, then {@code Args:}. */
	public String doc() {
		return doc;
	}

	/** Every parameter, output buffers included. */
	public List<ParamSpec> params() {
		return params;
	}

	/** The parameters a user is asked for: everything but the OUT buffers. */
	public List<ParamSpec> inputs() {
		List<ParamSpec> result = new ArrayList<>();
		for (ParamSpec param : params) {
			if (param.harvestable()) result.add(param);
		}
		return result;
	}

	public TypeSpec returnType() {
		return returnType;
	}

	public Role returnRole() {
		return returnRole;
	}

	/** Names of this op's outputs, in declaration order. */
	public List<String> outputs() {
		return outputs;
	}

	/** This op's outputs, with their types and roles. */
	public List<OutputSpec> outputSpecs() {
		return outputSpecs;
	}

	/** The parameter of the given name, or null if there is none. */
	public ParamSpec param(String paramName) {
		for (ParamSpec param : params) {
			if (param.name().equals(paramName)) return param;
		}
		return null;
	}

	/**
	 * The parameters no dialog can render, and which are required anyway.
	 * <p>
	 * Empty for all but a handful of ops. When it is not empty, the op cannot
	 * be run from a generated dialog, and the honest thing is to say which
	 * parameter stopped it rather than to hide the op or to guess a value.
	 */
	public List<ParamSpec> blockingParams() {
		List<ParamSpec> result = new ArrayList<>();
		for (ParamSpec param : params) {
			if (param.harvestable() && param.blocking()) result.add(param);
		}
		return result;
	}

	/** The first line of the docstring: what a menu tooltip should say. */
	public String summary() {
		if (doc == null) return null;
		int end = doc.indexOf('\n');
		return end < 0 ? doc : doc.substring(0, end);
	}

	/**
	 * The JSON this was read from, ready to be written back out.
	 * <p>
	 * A description is cached on disk between launches, and a cache written
	 * from a re-serialization of what this class happens to model would lose
	 * whatever it does not model yet. Keeping the original means the cache is
	 * exactly as good as the answer that produced it.
	 *
	 * @return the original JSON, or an empty map if this spec was built by hand.
	 */
	public Map<String, Object> toJson() {
		return raw;
	}

	@Override
	public String toString() {
		return name;
	}

	public static OpSpec fromJson(Map<String, Object> data) {
		List<ParamSpec> params = new ArrayList<>();
		for (Map<String, Object> param : Wire.maps(data, "params")) {
			params.add(ParamSpec.fromJson(param));
		}
		List<OutputSpec> outputSpecs = new ArrayList<>();
		for (Map<String, Object> output : Wire.maps(data, "output_specs")) {
			outputSpecs.add(OutputSpec.fromJson(output));
		}
		return new OpSpec(
			Wire.string(data, "name"),
			Wire.string(data, "module"),
			Wire.string(data, "function"),
			Wire.optionalString(data, "env"),
			Wire.bool(data, "main_thread", false),
			Wire.bool(data, "exclusive", false),
			Form.forWire(Wire.string(data, "form")),
			Wire.optionalString(data, "doc"),
			params,
			TypeSpec.fromJson(Wire.map(data, "return_type")),
			Role.forWire(Wire.optionalString(data, "return_role")),
			java.util.Arrays.asList(Wire.strings(data, "outputs")),
			outputSpecs,
			data);
	}
}
