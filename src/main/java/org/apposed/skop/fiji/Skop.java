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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import net.imagej.Dataset;

import org.apposed.skop.fiji.wire.OpSpec;
import org.scijava.Cancelable;
import org.scijava.convert.ConvertService;
import org.scijava.module.Module;
import org.scijava.module.ModuleItem;
import org.scijava.module.ModuleService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.SciJavaService;
import org.scijava.service.Service;

/**
 * scikit-ops for scripts: an op by name, run in one call.
 * <p>
 * Everything a script otherwise spells out -- waiting for discovery, finding
 * the command, converting an {@code ImagePlus}, telling a failure from a
 * success -- happens here, so a script reads like the Python it stands in for:
 *
 * <pre>
 * #@ org.apposed.skop.fiji.Skop skop
 * labels = skop.run("segment.cellpose4", imp)
 * labels = skop.run("segment.cellpose4", imp, {"diameter": 30})
 * </pre>
 * <p>
 * An op is named the way the napari panel lists it, its namespace and its
 * function: {@code segment.cellpose4}, {@code threshold.otsu}. The function
 * alone works when no other op shares it, and the full
 * {@code skop.ops.segment.cellpose4:cellpose4} always does.
 * <p>
 * One output comes back as itself; several come back as a map from output
 * name to value, so {@code result.nuclei} works in Groovy and
 * {@code result["nuclei"]} in Jython. The op runs through
 * {@link ModuleService}, so it converts its inputs and outputs, reports
 * progress and can be canceled exactly as it does from its menu entry.
 */
@Plugin(type = Service.class)
public class Skop extends AbstractService implements SciJavaService {

	@Parameter
	private SkopService skop;

	@Parameter
	private ModuleService modules;

	@Parameter
	private ConvertService convert;

	/** The most recent one, for code that has no context to ask: skop_fiji.py. */
	private static volatile Skop current;

	// -- Service methods --

	@Override
	public void initialize() {
		current = this;
	}

	@Override
	public void dispose() {
		if (current == this) current = null;
	}

	// -- Skop methods --

	/**
	 * The running Fiji's {@code Skop}, for code with no SciJava context to ask
	 * -- the {@code skop_fiji} Jython module, which a script imports rather
	 * than receives as a parameter.
	 *
	 * @throws IllegalStateException if no context has started one.
	 */
	public static Skop current() {
		Skop skop = current;
		if (skop == null) {
			throw new IllegalStateException("scikit-ops is not running: no SciJava " +
				"context has started its Skop service.");
		}
		return skop;
	}

	/** Every op's short name, sorted. Waits for discovery. */
	public List<String> names() {
		return new ArrayList<>(byShortName().keySet());
	}

	/**
	 * The command for an op.
	 *
	 * @param name short ({@code segment.cellpose4}), bare ({@code cellpose4})
	 *          or full ({@code skop.ops.segment.cellpose4:cellpose4}).
	 * @throws IllegalArgumentException if no op, or more than one, is named
	 *           so; the message suggests what was probably meant.
	 */
	public OpModuleInfo op(String name) {
		Map<String, OpModuleInfo> all = byShortName();
		String wanted = name.startsWith("skop:") ? name.substring(5) : name;
		if (all.containsKey(wanted)) return all.get(wanted);

		List<OpModuleInfo> matches = new ArrayList<>();
		for (OpModuleInfo info : all.values()) {
			OpSpec op = info.op();
			if (op.name().equals(wanted) || op.function().equals(wanted)) {
				matches.add(info);
			}
		}
		if (matches.size() == 1) return matches.get(0);
		if (matches.size() > 1) {
			List<String> names = new ArrayList<>();
			for (OpModuleInfo info : matches) names.add(shortName(info.op()));
			throw new IllegalArgumentException("More than one op is called '" +
				name + "': " + String.join(", ", names) + ". Say which.");
		}
		throw new IllegalArgumentException("No op called '" + name + "'." +
			suggest(wanted, all.keySet()));
	}

	/** Runs an op on an image, with every other parameter at its default. */
	public Object run(String name, Object image) {
		return run(name, image, Collections.emptyMap());
	}

	/** Runs an op on an image, with some parameters set by name. */
	public Object run(String name, Object image, Map<String, ?> args) {
		OpModuleInfo info = op(name);
		Map<String, Object> all = new LinkedHashMap<>(args);
		all.put(imageInput(info), image);
		return run(info, all);
	}

	/** Runs an op with every input given by name, the image included. */
	public Object run(String name, Map<String, ?> args) {
		return run(op(name), args);
	}

	/** Groovy's named arguments: {@code skop.run("x", imp, diameter: 30)}. */
	public Object run(Map<String, ?> args, String name, Object image) {
		return run(name, image, args);
	}

	/** Groovy's named arguments, with the image among them. */
	public Object run(Map<String, ?> args, String name) {
		return run(name, args);
	}

	/**
	 * An op's short name: its namespace and its function, as the napari panel
	 * lists it. {@code skop.ops.segment.cellpose4:cellpose4} is
	 * {@code segment.cellpose4}.
	 */
	public static String shortName(OpSpec op) {
		String module = op.module();
		String prefix = "skop.ops.";
		if (!module.startsWith(prefix)) return op.name();
		String namespace = module.substring(prefix.length()).split("\\.")[0];
		return namespace + "." + op.function();
	}

	// -- Helper methods --

	private Object run(OpModuleInfo info, Map<String, ?> args) {
		Map<String, Object> inputs = new LinkedHashMap<>();
		for (Map.Entry<String, ?> entry : args.entrySet()) {
			ModuleItem<?> item = info.getInput(entry.getKey());
			if (item == null) {
				throw new IllegalArgumentException(shortName(info.op()) +
					" has no parameter '" + entry.getKey() + "'. It has: " +
					String.join(", ", inputNames(info)) + ".");
			}
			inputs.put(entry.getKey(), converted(info, item, entry.getValue()));
		}

		Module module;
		try {
			module = modules.run(info, false, inputs).get();
		}
		catch (InterruptedException exc) {
			Thread.currentThread().interrupt();
			throw new CancellationException("Interrupted");
		}
		catch (ExecutionException exc) {
			throw new IllegalStateException(shortName(info.op()) + " failed",
				exc.getCause());
		}
		if (module instanceof OpModule && ((OpModule) module).failure() != null) {
			throw new IllegalStateException(shortName(info.op()) + " failed",
				((OpModule) module).failure());
		}
		if (module instanceof Cancelable && ((Cancelable) module).isCanceled()) {
			throw new CancellationException(((Cancelable) module).getCancelReason());
		}

		List<String> names = info.op().outputs();
		if (names.size() == 1) return module.getOutput(names.get(0));
		Map<String, Object> outputs = new LinkedHashMap<>();
		for (String output : names) outputs.put(output, module.getOutput(output));
		return outputs;
	}

	/** A value as the item wants it: an ImagePlus as a Dataset, say. */
	private Object converted(OpModuleInfo info, ModuleItem<?> item, Object value) {
		if (value == null) return null;
		Class<?> type = item.getType();
		if (!type.isInstance(value) && convert.supports(value, type)) {
			value = convert.convert(value, type);
		}
		List<?> choices = item.getChoices();
		if (choices != null && !choices.isEmpty() && !choices.contains(value)) {
			throw new IllegalArgumentException(shortName(info.op()) + ": '" +
				value + "' is not a choice for " + item.getName() + ". The choices: " +
				choices + ".");
		}
		return value;
	}

	/** The op's first image input: where a lone positional image goes. */
	private String imageInput(OpModuleInfo info) {
		for (ModuleItem<?> item : info.inputs()) {
			if (Dataset.class.isAssignableFrom(item.getType())) return item.getName();
		}
		throw new IllegalArgumentException(shortName(info.op()) +
			" takes no image. Give its inputs by name: " +
			String.join(", ", inputNames(info)) + ".");
	}

	private List<String> inputNames(OpModuleInfo info) {
		List<String> names = new ArrayList<>();
		for (ModuleItem<?> item : info.inputs()) names.add(item.getName());
		return names;
	}

	/** Every registered op by short name, once discovery has finished. */
	private Map<String, OpModuleInfo> byShortName() {
		try {
			skop.discover().get();
		}
		catch (InterruptedException exc) {
			Thread.currentThread().interrupt();
			throw new CancellationException("Interrupted waiting for discovery");
		}
		catch (ExecutionException exc) {
			throw new IllegalStateException("scikit-ops' ops could not be listed",
				exc.getCause());
		}
		Map<String, OpModuleInfo> all = new TreeMap<>();
		for (OpModuleInfo info : skop.registeredModules()) {
			all.put(shortName(info.op()), info);
		}
		return all;
	}

	/** Up to five names close to the one asked for, as a sentence. */
	private static String suggest(String wanted, Iterable<String> names) {
		String bare = wanted.substring(wanted.lastIndexOf('.') + 1).toLowerCase();
		List<String> close = new ArrayList<>();
		for (String name : names) {
			String function = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
			if (function.contains(bare) || bare.contains(function) ||
				distance(function, bare) <= 2)
			{
				close.add(name);
			}
		}
		if (close.isEmpty()) return " Skop.names() lists them all.";
		return " Did you mean " +
			String.join(", ", close.subList(0, Math.min(5, close.size()))) + "?";
	}

	/** Levenshtein distance; names are short, so the plain version will do. */
	private static int distance(String a, String b) {
		int[] row = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) row[j] = j;
		for (int i = 1; i <= a.length(); i++) {
			int diagonal = row[0];
			row[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int above = row[j];
				row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1),
					diagonal + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
				diagonal = above;
			}
		}
		return row[b.length()];
	}
}
