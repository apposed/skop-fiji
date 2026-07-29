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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.apposed.appose.Appose;
import org.apposed.appose.BuildException;
import org.apposed.appose.Builder;
import org.apposed.appose.Environment;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service;
import org.apposed.appose.TaskException;
import org.apposed.appose.TaskEvent;
import org.apposed.appose.builder.PixiBuilder;
import org.apposed.appose.util.Json;
import org.apposed.skop.fiji.wire.AdaptationPlan;
import org.apposed.skop.fiji.wire.Description;
import org.apposed.skop.fiji.wire.Form;
import org.apposed.skop.fiji.wire.OpSpec;
import org.apposed.skop.fiji.wire.ParamSpec;
import org.apposed.skop.fiji.wire.Wire;

/**
 * Builds environments, keeps workers warm, and dispatches op calls.
 * <p>
 * A port of skop's own {@code Runner}, and deliberately a port rather than a
 * client of one. The alternative -- Java driving a Python control process
 * which drives the workers -- was rejected on copies: skop's codec copies any
 * plain {@code numpy.ndarray} into a fresh shared memory block, so an array
 * crossing Java to a control process to a worker would be copied on the way
 * in and again on the way out, for data that was in shared memory to begin
 * with. Here a Java {@link NDArray} goes straight into the worker's task
 * inputs: one block, no copies, and the worker sees exactly what skop's own
 * runner would have handed it.
 * <p>
 * Two jobs cannot be ported, because they need a Python interpreter to do
 * their work: describing the ops (which works by <em>importing</em> them) and
 * planning an axis mapping. Both are metadata-only, so both run as tasks in
 * one long-lived service in the {@code minimal} environment -- see
 * {@link #describe()} and
 * {@link #plan(String, String, List, List, Map, List, Map)}.
 * <p>
 * <strong>This class is not Fiji-shaped.</strong> It knows about Appose and
 * about skop's wire format, and about nothing else; there is no ImgLib2 type
 * and no SciJava service in it. That is on purpose: it is the layer whose
 * correctness can be tested headlessly, before any UI exists.
 *
 * @author Curtis Rueden
 */
public class SkopRunner implements Closeable {

	/**
	 * The one script this class knows the text of rather than reading from
	 * skop.
	 * <p>
	 * Everything else -- how to start an op worker, what task to post at it --
	 * is {@code skop.host} data, fetched once by {@link #constants()}, so that
	 * changing how a worker runs changes one file in one language. This much
	 * cannot be: reading data out of skop needs a service, and starting a
	 * service needs a script. So the bootstrap is transcribed, and kept as
	 * small as a bootstrap can be.
	 * <p>
	 * The {@code %s} is the checkout's {@code src} directory. It goes in at
	 * position 0, so a checkout shadows the copy of skop the environment
	 * installs -- which is what lets someone edit an op and see the edit
	 * without bumping the pin that every environment's identity depends on.
	 */
	private static final String BOOTSTRAP =
		"import sys\n" +
		"sys.path.insert(0, %s)\n" +
		"import skop.host\n" +
		"skop_describe = skop.host.describe\n" +
		"skop_plan = skop.host.plan\n" +
		"skop_constants = skop.host.constants\n";

	/** The environment the metadata service runs in. */
	private static final String METADATA_ENV = "minimal";

	private static final String DESCRIBE = "skop_describe(package)";
	private static final String PLAN =
		"skop_plan(op, param, shape, axes, position, mapping, dispositions)";
	private static final String CONSTANTS = "skop_constants()";

	/** Default collection of ops to describe. */
	public static final String OPS_PACKAGE = "skop.ops";

	private final File root;
	private final File envsDir;

	private final Map<String, Environment> envs = new ConcurrentHashMap<>();
	private final Map<String, Service> services = new ConcurrentHashMap<>();
	private final List<Builder.ProgressConsumer> buildProgress = new ArrayList<>();
	private final List<Consumer<String>> buildOutput = new ArrayList<>();
	private final List<Consumer<String>> buildError = new ArrayList<>();
	private final List<BuildListener> buildListeners = new ArrayList<>();

	private boolean debug;
	private Map<String, Object> constants;
	private String callScript;
	private String initScript;

	/**
	 * Creates a runner over a scikit-ops checkout.
	 *
	 * @param checkout the scikit-ops checkout: the directory holding
	 *          {@code src} and {@code envs}.
	 */
	public SkopRunner(File checkout) {
		this(new File(checkout, "src"), new File(checkout, "envs"));
	}

	/**
	 * Creates a runner over an explicit source root and environment directory.
	 *
	 * @param root the directory holding the {@code skop} package, prepended to
	 *          every worker's {@code sys.path}.
	 * @param envsDir the directory holding {@code <env-id>/pixi.toml}.
	 */
	public SkopRunner(File root, File envsDir) {
		this.root = root;
		this.envsDir = envsDir;
	}

	// -- configuration ---------------------------------------------------

	/** The directory prepended to every worker's search path. */
	public File root() {
		return root;
	}

	/** The directory the environment definitions are read from. */
	public File envsDir() {
		return envsDir;
	}

	/** Logs what the build tool and the workers say, to stdout. */
	public SkopRunner debug(boolean debug) {
		this.debug = debug;
		return this;
	}

	/**
	 * Hears about environment build progress, as (title, current, maximum).
	 * <p>
	 * Building an environment is by far the slowest thing a first run does --
	 * minutes, for a TensorFlow or PyTorch stack -- and it happens inside
	 * {@link #run}, with nothing else to show for it. Titles name the phase:
	 * "Solving", "Installing conda packages", and so on.
	 * <p>
	 * Note that subscribing here is what switches the monitor on: it runs pixi
	 * under {@code -vv} to read its phase transitions, so without a progress
	 * subscriber a build reports only its final summary line.
	 *
	 * @param subscriber called as each phase advances.
	 */
	public SkopRunner subscribeBuildProgress(Builder.ProgressConsumer subscriber) {
		buildProgress.add(subscriber);
		return this;
	}

	/**
	 * Hears when an environment build begins and ends.
	 * <p>
	 * The progress subscriptions say how a build is going; this says
	 * <em>that</em> one is going, which is what anything with a progress bar
	 * needs in order to put one up and take it down again. Appose reports
	 * phases, not a beginning and an end, so these bracket the build here.
	 * <p>
	 * Fires once per environment per runner, not once per op: the second op
	 * to want the same environment finds it in hand and does not wait for
	 * anything, so there is nothing to put a progress bar up for. The first
	 * one fires whether or not the environment turns out to be already built
	 * on disk, because that is not known until it is checked -- so a listener
	 * that finds itself starting and finishing in the same millisecond should
	 * say nothing at all.
	 *
	 * @param listener called around each build.
	 */
	public SkopRunner subscribeBuild(BuildListener listener) {
		buildListeners.add(listener);
		return this;
	}

	/** Hears the build tool's standard output, in raw chunks. */
	public SkopRunner subscribeBuildOutput(Consumer<String> subscriber) {
		buildOutput.add(subscriber);
		return this;
	}

	/**
	 * Hears the build tool's standard error, in raw chunks.
	 * <p>
	 * Note: this is the stderr <em>stream</em>, not a failure report. Pixi
	 * writes its ordinary status there, success message included. A build that
	 * actually fails throws from {@link #environment}.
	 */
	public SkopRunner subscribeBuildError(Consumer<String> subscriber) {
		buildError.add(subscriber);
		return this;
	}

	// -- environments ----------------------------------------------------

	/** The pixi.toml defining an environment. */
	public File envConfig(String envId) {
		File config = new File(new File(envsDir, envId), "pixi.toml");
		if (!config.exists()) {
			throw new IllegalArgumentException("No environment '" + envId + "' at " +
				config + ". Known environments: " + String.join(", ", envIds()));
		}
		return config;
	}

	/** IDs of every environment defined under {@link #envsDir()}. */
	public List<String> envIds() {
		File[] children = envsDir.listFiles();
		if (children == null) return Collections.emptyList();
		// Sorted, because this ends up in an error message and in a menu.
		TreeSet<String> ids = new TreeSet<>();
		for (File child : children) {
			if (new File(child, "pixi.toml").exists()) ids.add(child.getName());
		}
		return new ArrayList<>(ids);
	}

	/**
	 * Builds (or reuses) the Appose environment for an env ID.
	 * <p>
	 * Appose keys environments by name in its shared environments directory,
	 * so several ops declaring the same env ID land on the same installation
	 * -- and so do skop-napari and this front end. A user who has already paid
	 * for the {@code stardist-tf} build in napari does not pay again here.
	 *
	 * @param envId the environment's ID, naming {@code envs/<id>/}.
	 * @param variant a named pixi sub-environment such as {@code "cuda"}, or
	 *          null for the default one.
	 * @return the built environment.
	 * @throws BuildException if the environment could not be built.
	 */
	public Environment environment(String envId, String variant)
		throws BuildException
	{
		String key = envId + "/" + variant;
		Environment cached = envs.get(key);
		if (cached != null) return cached;

		for (BuildListener listener : buildListeners) listener.started(envId);
		try {
			Environment built = build(envId, variant);
			envs.put(key, built);
			for (BuildListener listener : buildListeners) {
				listener.finished(envId, null);
			}
			return built;
		}
		catch (BuildException | RuntimeException exc) {
			for (BuildListener listener : buildListeners) {
				listener.finished(envId, exc);
			}
			throw exc;
		}
	}

	private Environment build(String envId, String variant) throws BuildException {
		PixiBuilder builder = Appose.pixi(envConfig(envId)).name("skop-" + envId);
		for (Builder.ProgressConsumer subscriber : buildProgress) {
			builder = builder.subscribeProgress(subscriber);
		}
		for (Consumer<String> subscriber : buildOutput) {
			builder = builder.subscribeOutput(subscriber);
		}
		for (Consumer<String> subscriber : buildError) {
			builder = builder.subscribeError(subscriber);
		}
		if (debug) builder = builder.logDebug();

		Environment env = builder.build();
		if (variant != null) env = env.activate(variant);
		return env;
	}

	/** Hears when an environment build begins and ends. */
	public interface BuildListener {

		/** An environment is about to be built, or confirmed already built. */
		void started(String envId);

		/**
		 * A build has ended.
		 *
		 * @param envId the environment.
		 * @param error what went wrong, or null if nothing did.
		 */
		void finished(String envId, Exception error);
	}

	// -- metadata --------------------------------------------------------

	/**
	 * Every op skop can find, and every op module that would not load.
	 * <p>
	 * This is the slow call: it builds the {@code minimal} environment if it
	 * is missing and imports every module under {@code skop.ops}. A front end
	 * calls it off the UI thread and caches what it gets.
	 *
	 * @return the description of {@link #OPS_PACKAGE}.
	 */
	public Description describe()
		throws BuildException, InterruptedException, IOException, TaskException
	{
		return describe(OPS_PACKAGE);
	}

	/**
	 * Every op in a collection, and every module in it that would not load.
	 *
	 * @param opsPackage the Python package to search, e.g. {@code skop.ops}.
	 * @return what was found, failures included.
	 */
	public Description describe(String opsPackage)
		throws BuildException, InterruptedException, IOException, TaskException
	{
		Map<String, Object> inputs = new LinkedHashMap<>();
		inputs.put("package", opsPackage);
		return Description.fromJson(metadata(DESCRIBE, inputs));
	}

	/**
	 * Works out how an array should be fed to one of an op's parameters.
	 * <p>
	 * The arithmetic happens in skop rather than here, so that there is one
	 * implementation of it rather than one per front end. What this side owns
	 * is the <em>decision</em>: skop supplies a default plan and the warnings
	 * against it, never a veto, and a caller is free to hand back an edited
	 * mapping or a different disposition for a leftover axis.
	 *
	 * @param op the op's ID, as {@link OpSpec#name()} gives it.
	 * @param param name of the parameter the array is destined for.
	 * @param shape the array's shape, <strong>in numpy order</strong>.
	 * @param axes one label per axis, <strong>in numpy order</strong>; a null
	 *          element is an axis with no name. See {@link Axes}: an
	 *          ImgLib2-shaped caller reverses both of these first, and nothing
	 *          downstream can tell if it forgot.
	 * @param position current position along each axis, keyed by axis name or
	 *          by axis index, for any axis indexed down to a single plane. May
	 *          be null.
	 * @param mapping one input-axis index (or null) per declared slot,
	 *          overriding skop's own assignment. May be null.
	 * @param dispositions what to do with each leftover axis, keyed by axis
	 *          index: {@link AdaptationPlan#ITERATE}, {@link
	 *          AdaptationPlan#SELECT} or {@link AdaptationPlan#PASS}. May be
	 *          null.
	 * @return the plan, warnings included.
	 */
	public AdaptationPlan plan(String op, String param, List<Integer> shape,
		List<String> axes, Map<String, Integer> position, List<Integer> mapping,
		Map<Integer, String> dispositions)
		throws BuildException, InterruptedException, IOException, TaskException
	{
		Map<String, Object> inputs = new LinkedHashMap<>();
		inputs.put("op", op);
		inputs.put("param", param);
		inputs.put("shape", shape);
		inputs.put("axes", axes);
		inputs.put("position", position);
		inputs.put("mapping", mapping);
		inputs.put("dispositions", stringKeyed(dispositions));
		return AdaptationPlan.fromJson(metadata(PLAN, inputs));
	}

	/** Convenience over {@link #plan}, for the common case of no overrides. */
	public AdaptationPlan plan(String op, String param, List<Integer> shape,
		List<String> axes)
		throws BuildException, InterruptedException, IOException, TaskException
	{
		return plan(op, param, shape, axes, null, null, null);
	}

	/**
	 * The scripts and vocabularies skop says this side should be using.
	 * <p>
	 * Fetched once and kept. Reading them rather than transcribing them is
	 * what keeps the worker protocol in one language: if skop changes how a
	 * worker is initialized, nothing here has to change with it.
	 */
	public Map<String, Object> constants()
		throws BuildException, InterruptedException, IOException, TaskException
	{
		if (constants == null) {
			constants = Wire.copy(metadata(CONSTANTS, Collections.emptyMap()));
			callScript = Wire.string(constants, "call");
			initScript = Wire.string(constants, "init");
		}
		return constants;
	}

	// -- running ---------------------------------------------------------

	/**
	 * Runs an op in its environment and returns its outputs.
	 *
	 * @param op the op to run.
	 * @param args the op's arguments, by parameter name. An array argument is
	 *          an {@link NDArray}, which goes to the worker without a copy.
	 * @return the op's outputs, by output name -- {@link OpSpec#outputs()}
	 *          names them, and in that order.
	 */
	public Map<String, Object> run(OpSpec op, Map<String, Object> args)
		throws BuildException, InterruptedException, IOException, TaskException
	{
		return run(op, args, null, null, null, null);
	}

	/**
	 * Runs an op in its environment and returns its outputs.
	 *
	 * @param op the op to run.
	 * @param args the op's arguments, by parameter name.
	 * @param plans adaptation plans, by parameter name, for the array
	 *          arguments whose axes were named. May be null, in which case
	 *          every array is passed through untouched -- which is exactly
	 *          what a caller who has not worked out its axes should want.
	 * @param variant a named pixi sub-environment, or null.
	 * @param onProgress called with each task event as it arrives, or null.
	 * @param onStart called with the task once it has been submitted, or null.
	 *          This method blocks until the op finishes, so a caller wanting
	 *          to cancel one needs a handle on it from another thread; waiting
	 *          for the first progress event instead would leave a silent op
	 *          uncancellable.
	 * @return the op's outputs, by output name.
	 */
	public Map<String, Object> run(OpSpec op, Map<String, Object> args,
		Map<String, AdaptationPlan> plans, String variant,
		Consumer<TaskEvent> onProgress, Consumer<Service.Task> onStart)
		throws BuildException, InterruptedException, IOException, TaskException
	{
		validate(op, args);

		List<Map<String, Object>> planJson = new ArrayList<>();
		if (plans != null) {
			for (AdaptationPlan plan : plans.values()) planJson.add(plan.toJson());
		}

		Map<String, Object> inputs = new LinkedHashMap<>();
		inputs.put("module", op.module());
		inputs.put("function", op.function());
		inputs.put("kwargs", new LinkedHashMap<>(args));
		inputs.put("plans", planJson);

		Service service = service(op, variant);
		Service.Task task = service.task(call(), inputs,
			op.mainThread() ? "main" : null);
		if (onProgress != null) task.listen(onProgress);
		if (onStart != null) onStart.accept(task);
		task.waitFor();

		return outputs(op, args, task);
	}

	/**
	 * Gets the worker serving an op, starting one if needed.
	 * <p>
	 * Ops sharing an environment share a worker, unless one of them asks to be
	 * exclusive.
	 *
	 * @param op the op wanting a worker.
	 * @param variant a named pixi sub-environment, or null.
	 * @return the running service.
	 */
	public Service service(OpSpec op, String variant)
		throws BuildException, InterruptedException, IOException, TaskException
	{
		String key = op.env() + "/" + variant + "/" +
			(op.exclusive() ? op.name() : "");
		Service cached = services.get(key);
		if (cached != null) return cached;

		Service service = environment(op.env(), variant).python();
		service.init(initScript(op.env()));
		if (debug) service.debug(line -> System.out.println("[" + op.env() + "] " + line));
		service.start();
		services.put(key, service);
		return service;
	}

	/** Shuts down every worker this runner started. */
	@Override
	public void close() {
		for (Service service : services.values()) {
			// A worker that already died is still closed, as far as we care.
			try {
				service.close();
			}
			catch (Exception exc) {
				// Ignore: closing is best effort.
			}
		}
		services.clear();
	}

	// -- helpers ---------------------------------------------------------

	/**
	 * The metadata service: one worker in the minimal environment, answering
	 * the two questions that need a Python interpreter but no op dependencies.
	 */
	private synchronized Service metadataService()
		throws BuildException, IOException
	{
		Service cached = services.get(METADATA_ENV + "/metadata");
		if (cached != null) return cached;

		Service service = environment(METADATA_ENV, null).python();
		service.init(String.format(BOOTSTRAP, pythonString(root.getAbsolutePath())));
		if (debug) service.debug(line -> System.out.println("[metadata] " + line));
		service.start();
		services.put(METADATA_ENV + "/metadata", service);
		return service;
	}

	private Map<String, Object> metadata(String script, Map<String, Object> inputs)
		throws BuildException, InterruptedException, IOException, TaskException
	{
		Service.Task task = metadataService().task(script, inputs);
		task.waitFor();
		// A task whose script evaluates to a dict has that dict *as* its
		// outputs, rather than under a "result" key -- which is exactly how
		// skop's own op calls travel, and why every metadata function here
		// returns one.
		return Wire.copy(task.outputs);
	}

	private String call()
		throws BuildException, InterruptedException, IOException, TaskException
	{
		constants();
		return callScript;
	}

	/**
	 * The init script for an op worker: what skop says, plus whatever the
	 * environment adds for itself.
	 */
	private String initScript(String envId)
		throws BuildException, InterruptedException, IOException, TaskException
	{
		constants();
		// skop's INIT is a Python format template with a {root!r} field; the
		// !r is a repr, which for a path string is a quoted Python literal.
		String script = initScript.replace("{root!r}",
			pythonString(root.getAbsolutePath()));
		File extra = new File(new File(envsDir, envId), "init.py");
		if (extra.exists()) {
			script = script + "\n" + new String(
				java.nio.file.Files.readAllBytes(extra.toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
		}
		return script;
	}

	/**
	 * A Java string as a Python string literal.
	 * <p>
	 * Windows paths are full of backslashes, and a backslash in a Python
	 * string literal is an escape. Going through JSON gets the quoting right
	 * -- JSON string escaping is a subset of Python's -- rather than
	 * approximately right.
	 */
	private static String pythonString(String value) {
		return Json.toJson(value);
	}

	private static void validate(OpSpec op, Map<String, Object> args) {
		List<String> known = new ArrayList<>();
		for (ParamSpec param : op.params()) known.add(param.name());
		List<String> unknown = new ArrayList<>(args.keySet());
		unknown.removeAll(known);
		if (!unknown.isEmpty()) {
			Collections.sort(unknown);
			throw new IllegalArgumentException("Op " + op.name() +
				" has no parameter(s): " + String.join(", ", unknown));
		}
		List<String> missing = new ArrayList<>();
		for (ParamSpec param : op.params()) {
			if (param.required() && !args.containsKey(param.name())) {
				missing.add(param.name());
			}
		}
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("Op " + op.name() +
				" is missing required argument(s): " + String.join(", ", missing));
		}
	}

	/**
	 * An op's outputs, whichever end of the wire they came back on.
	 * <p>
	 * A function-form op returns its results as task outputs. The other two
	 * forms write into buffers the caller supplied, and return nothing --
	 * which on this side means the caller's own {@link NDArray}s already hold
	 * the answer, because they were in shared memory the whole time. skop's
	 * Python runner has to copy back out of shared memory at this point; here
	 * there is nothing to copy.
	 */
	private static Map<String, Object> outputs(OpSpec op, Map<String, Object> args,
		Service.Task task)
	{
		Map<String, Object> results = new LinkedHashMap<>();
		for (String name : op.outputs()) {
			results.put(name, op.form() == Form.FUNCTION
				? task.outputs.get(name)
				: args.get(name));
		}
		return results;
	}

	private static Map<String, Object> stringKeyed(Map<Integer, String> dispositions) {
		if (dispositions == null) return null;
		// JSON object keys are strings; skop converts the numeric ones back.
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<Integer, String> entry : dispositions.entrySet()) {
			result.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return result;
	}

	/**
	 * A runner over the scikit-ops checkout named by a system property or an
	 * environment variable, whichever is set.
	 * <p>
	 * Only useful until skop ships inside the plugin: a released front end
	 * knows where its own environment definitions are, and needs no checkout
	 * at all.
	 *
	 * @return the runner, or null if no checkout was found.
	 */
	public static SkopRunner fromCheckout() {
		// Last resort is a sibling checkout, which is where a developer working
		// on both at once will have one.
		for (String value : Arrays.asList(System.getProperty("skop.checkout"),
			System.getenv("SKOP_CHECKOUT"), "../scikit-ops"))
		{
			if (value == null || value.isEmpty()) continue;
			File checkout = new File(value);
			if (new File(checkout, "envs").isDirectory()) {
				return new SkopRunner(checkout.getAbsoluteFile());
			}
		}
		return null;
	}
}
