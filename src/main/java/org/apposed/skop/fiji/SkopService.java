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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apposed.appose.util.Json;
import org.apposed.skop.fiji.wire.Description;
import org.apposed.skop.fiji.wire.OpSpec;
import org.apposed.skop.fiji.wire.ParamSpec;
import org.apposed.skop.fiji.wire.Wire;
import org.scijava.app.StatusService;
import org.scijava.log.LogService;
import org.scijava.module.ModuleService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.SciJavaService;
import org.scijava.service.Service;

/**
 * Finds the ops, registers one command per op, and owns the runner.
 * <p>
 * <strong>Registration must not block startup.</strong> Describing the ops
 * imports every module under {@code skop.ops}, and on a first launch it must
 * build the {@code minimal} environment before it can do even that -- which is
 * a download. So registration happens on a background thread, and the answer
 * is cached on disk keyed by a hash of the ops tree, so that the second launch
 * populates the menu immediately and only re-describes when the ops have
 * actually changed.
 * <p>
 * A user who has already paid for an environment build in napari does not pay
 * again here: environments land in Appose's shared directory, not anywhere
 * under the Fiji installation, so the two front ends share them.
 *
 * @author Curtis Rueden
 */
@Plugin(type = Service.class)
public class SkopService extends AbstractService implements SciJavaService {

	@Parameter
	private ModuleService modules;

	@Parameter
	private LogService log;

	@Parameter(required = false)
	private StatusService status;

	@Parameter(required = false)
	private org.scijava.task.TaskService tasks;

	private SkopRunner runner;
	private Description description;
	private final List<OpModuleInfo> registered = new ArrayList<>();
	private CompletableFuture<Description> pending;

	// -- Initializable methods --

	@Override
	public void initialize() {
		// A Context instantiates every service it finds, so this runs at Fiji
		// startup -- which is exactly why it must not do any work here. It
		// starts discovery and returns; the menu fills in when it finishes.
		if (Boolean.getBoolean("skop.noAutoDiscover")) return;
		if (!available()) {
			log.debug("scikit-ops: no checkout found; no ops registered.");
			return;
		}
		discover();
	}

	// -- SkopService methods --

	/**
	 * The runner every op goes through.
	 *
	 * @return the runner, built over whatever checkout was found.
	 * @throws IllegalStateException if there is no scikit-ops checkout.
	 */
	public synchronized SkopRunner runner() {
		if (runner == null) {
			runner = SkopRunner.fromCheckout();
			if (runner == null) {
				throw new IllegalStateException(
					"No scikit-ops checkout found. Set -Dskop.checkout=<path>, or " +
						"$SKOP_CHECKOUT, or put one beside this project.");
			}
			runner.subscribeBuild(new BuildTasks());
			runner.subscribeBuildProgress(this::reportBuild);
			runner.subscribeBuildError(chunk -> log.debug(chunk.trim()));
		}
		return runner;
	}

	/** The task service every run and every build reports through, or null. */
	public org.scijava.task.TaskService tasks() {
		return tasks;
	}

	/** Whether a checkout was found, without demanding one. */
	public synchronized boolean available() {
		return runner != null || SkopRunner.fromCheckout() != null;
	}

	/** The ops found so far, or an empty list if discovery has not finished. */
	public synchronized List<OpSpec> ops() {
		return description == null ? Collections.emptyList() : description.ops();
	}

	/** The modules registered for those ops. */
	public synchronized List<OpModuleInfo> registeredModules() {
		return Collections.unmodifiableList(new ArrayList<>(registered));
	}

	/**
	 * Describes the ops and registers a command for each, off this thread.
	 * <p>
	 * Idempotent while it is running: a second call returns the first call's
	 * future rather than starting a second discovery.
	 *
	 * @return a future completing with what was found.
	 */
	public synchronized CompletableFuture<Description> discover() {
		if (pending != null) return pending;
		if (description != null) return CompletableFuture.completedFuture(description);
		// Note: registration happens *inside* the supplied job, not in a
		// whenComplete on it. whenComplete returns a new stage, so a caller
		// waiting on this one could be handed the ops a moment before they
		// were registered -- and find an empty menu, intermittently.
		pending = CompletableFuture.supplyAsync(() -> {
			Description found;
			try {
				found = describe();
			}
			catch (Exception exc) {
				throw new IllegalStateException("Could not describe skop's ops", exc);
			}
			register(found);
			return found;
		});
		pending.whenComplete((found, error) -> {
			synchronized (SkopService.this) {
				pending = null;
			}
			if (error != null) {
				// Note: says what happened, and does not guess why. The
				// previous wording here claimed scikit-ops was unavailable,
				// which sent at least one person looking at their Python
				// environment when the environment was fine and the fault was
				// on this side of the wire.
				log.error("Could not register scikit-ops' ops.", error);
			}
		});
		return pending;
	}

	/**
	 * Describes the ops and registers them again, on this thread.
	 * <p>
	 * What {@link #discover()} does in the background, and unlike it, this
	 * always re-describes -- so it is also how a developer who has just edited
	 * an op picks up the change without restarting.
	 *
	 * @return what was found.
	 */
	public synchronized Description discoverNow() throws Exception {
		Description found = describe();
		register(found);
		return found;
	}

	/** Forgets every registered op, and the cached description with it. */
	public synchronized void unregister() {
		if (!registered.isEmpty()) {
			modules.removeModules(new ArrayList<>(registered));
			registered.clear();
		}
		description = null;
	}

	// -- Disposable methods --

	@Override
	public synchronized void dispose() {
		unregister();
		if (runner != null) {
			runner.close();
			runner = null;
		}
	}

	// -- Helper methods --

	private Description describe() throws Exception {
		File cache = cacheFile();
		Description cached = readCache(cache);
		if (cached != null) {
			log.debug("Read " + cached.ops().size() + " op(s) from " + cache);
			return cached;
		}
		Description found = runner().describe();
		writeCache(cache, found);
		return found;
	}

	private synchronized void register(Description found) {
		unregister();
		description = found;

		List<OpModuleInfo> infos = new ArrayList<>(found.ops().size());
		List<String> workflows = new ArrayList<>();
		for (OpSpec op : found.ops()) {
			if (op.isWorkflow()) {
				// A workflow's body calls skop.run on the ops a caller chose,
				// so it needs a Python runner ambient around it. This front end
				// deliberately has none: it drives the workers itself. Until
				// that is settled, a workflow is listed nowhere rather than
				// listed and broken.
				workflows.add(op.name());
				continue;
			}
			try {
				infos.add(new OpModuleInfo(getContext(), op));
			}
			catch (Exception exc) {
				// One malformed op must not cost the other sixty-two their
				// menu entries -- the same rule discovery itself follows.
				log.error("Could not register op " + op.name(), exc);
			}
		}
		modules.addModules(infos);
		registered.addAll(infos);

		log.info("scikit-ops: registered " + infos.size() + " op(s)");
		for (Description.LoadFailure failure : found.failures()) {
			log.warn(failure.message());
		}
		for (Description.ReadFailure failure : found.unreadable()) {
			// This side is behind skop, which is worth saying loudly, and is
			// not worth costing anyone the other ops.
			log.error("scikit-ops: " + failure);
		}
		if (!workflows.isEmpty()) {
			log.info("scikit-ops: " + workflows.size() + " workflow op(s) not " +
				"registered, because running one needs a Python runner this " +
				"front end does not have: " + String.join(", ", workflows));
		}
		for (OpModuleInfo info : infos) {
			for (ParamSpec param : info.unrenderableParams()) {
				log.debug(info.op().name() + ": no widget for '" + param.name() +
					"' (" + param.type() + "); it runs at its default.");
			}
		}
	}

	private void reportBuild(String title, long current, long maximum) {
		// A first run of a TensorFlow or PyTorch environment is minutes long,
		// and unlike a napari user launched from a terminal, a Fiji user has
		// nowhere else to look.
		log.info("scikit-ops: " + title);
		org.scijava.task.Task task = building.get();
		if (task != null) {
			task.setStatusMessage(title);
			task.setProgressValue(current);
			task.setProgressMaximum(maximum);
		}
		if (status != null) {
			status.showStatus((int) current, (int) maximum,
				"Building scikit-ops environment: " + title);
		}
	}

	/**
	 * A SciJava task per environment build, so that a user can see one
	 * happening and how far along it is.
	 * <p>
	 * A build is the one thing here that can take minutes -- a TensorFlow or
	 * PyTorch stack is a multi-gigabyte download -- and it happens inside an
	 * op run, with nothing else to show for it. It is also nested inside that
	 * run's own task, which is why it gets a task of its own rather than
	 * borrowing one: "Running Otsu" stuck at 0% for four minutes says the
	 * wrong thing about what is going on.
	 * <p>
	 * Note that this is a single slot rather than a map. Appose's progress
	 * callbacks say nothing about which environment they belong to, so
	 * attributing them to anything other than the build currently in flight
	 * would be a guess -- and builds are serialized by
	 * {@code SkopRunner.environment} anyway.
	 */
	private final java.util.concurrent.atomic.AtomicReference<
		org.scijava.task.Task> building =
			new java.util.concurrent.atomic.AtomicReference<>();

	private class BuildTasks implements SkopRunner.BuildListener {

		@Override
		public void started(String envId) {
			if (tasks == null) return;
			org.scijava.task.Task task =
				tasks.createTask("Building scikit-ops environment: " + envId);
			task.setStatusMessage("Checking " + envId);
			task.start();
			building.set(task);
		}

		@Override
		public void finished(String envId, Exception error) {
			org.scijava.task.Task task = building.getAndSet(null);
			if (task == null) return;
			if (error != null) task.setStatusMessage("Failed: " + error.getMessage());
			task.finish();
		}
	}

	// -- the on-disk cache --

	/**
	 * Where the description is cached, keyed by the state of the ops tree.
	 * <p>
	 * The key is a hash over every op module's path, size and modification
	 * time. That is cheap -- a directory walk, no imports -- and it changes
	 * exactly when an op does, which is the point: editing an op must not
	 * leave a stale menu behind, and launching Fiji twice in a row must not
	 * cost two rounds of importing tensorflow's worth of modules.
	 */
	private File cacheFile() {
		File dir = new File(new File(System.getProperty("user.home"), ".skop"),
			"fiji");
		return new File(dir, "ops-" + treeHash() + ".json");
	}

	private String treeHash() {
		StringBuilder sb = new StringBuilder();
		File root = new File(runner().root(), "skop");
		collect(root, sb);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
			return hex.toString();
		}
		catch (Exception exc) {
			// No hash means no cache, which is slow rather than wrong.
			return "nohash";
		}
	}

	private static void collect(File file, StringBuilder into) {
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children == null) return;
			java.util.Arrays.sort(children);
			for (File child : children) collect(child, into);
		}
		else if (file.getName().endsWith(".py")) {
			into.append(file.getPath()).append(':').append(file.length())
				.append(':').append(file.lastModified()).append('\n');
		}
	}

	private Description readCache(File cache) {
		if (!cache.isFile()) return null;
		try {
			String json = new String(Files.readAllBytes(cache.toPath()),
				StandardCharsets.UTF_8);
			Map<String, Object> data = Wire.asMap(Json.parseJson(json), "cache");
			return Description.fromJson(data);
		}
		catch (Exception exc) {
			// A cache that cannot be read is a cache that is not there.
			log.debug("Ignoring unreadable op cache " + cache, exc);
			return null;
		}
	}

	private void writeCache(File cache, Description found) {
		try {
			File dir = cache.getParentFile();
			if (dir != null) dir.mkdirs();
			// Note: what is written is the JSON that arrived, not a
			// re-serialization of what this side happens to model -- so a
			// field a newer skop added survives being cached.
			Files.write(cache.toPath(),
				Json.toJson(found.toJson()).getBytes(StandardCharsets.UTF_8));
			purgeStale(cache);
		}
		catch (IOException exc) {
			log.debug("Could not cache the op description at " + cache, exc);
		}
	}

	/** Removes the descriptions of ops trees that no longer exist. */
	private static void purgeStale(File current) {
		File[] siblings = current.getParentFile().listFiles();
		if (siblings == null) return;
		for (File sibling : siblings) {
			if (sibling.getName().startsWith("ops-") &&
				sibling.getName().endsWith(".json") && !sibling.equals(current))
			{
				sibling.delete();
			}
		}
	}

}
