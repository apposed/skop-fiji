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
import java.util.List;
import java.util.Map;

import org.apposed.skop.fiji.wire.OpSpec;
import org.apposed.skop.fiji.wire.OutputSpec;
import org.apposed.skop.fiji.wire.ParamSpec;
import org.scijava.Context;
import org.scijava.MenuPath;
import org.scijava.module.DefaultMutableModuleInfo;
import org.scijava.module.Module;
import org.scijava.module.ModuleException;
import org.scijava.module.MutableModuleItem;

/**
 * One op, as one SciJava module.
 * <p>
 * This is the class that makes the headline true: an op gets its own menu
 * entry, its own generated dialog, a place in the search bar for free, and a
 * recordable macro call, because SciJava registers modules at runtime and does
 * not need a static manifest. skop-napari settled for a single panel with a
 * picker inside it precisely because npe2 does; that compromise is not needed
 * here.
 * <p>
 * <strong>{@link #getIdentifier()} is load-bearing.</strong> It is what the
 * macro recorder writes down, so {@code skop:skop.ops.threshold:otsu} ends up
 * in someone's saved macro and may not be reworded casually. It is derived
 * from the op ID, which skop already treats as an opaque public string.
 *
 * @author Curtis Rueden
 */
public class OpModuleInfo extends DefaultMutableModuleInfo {

	/** Where every op lands. */
	public static final String MENU_ROOT = "Plugins";

	/** The submenu under it. */
	public static final String MENU_GROUP = "scikit-ops";

	private final OpSpec op;
	private final Context context;
	private final List<ParamSpec> unrenderable = new ArrayList<>();

	public OpModuleInfo(Context context, OpSpec op) {
		this.context = context;
		this.op = op;
		setModuleClass(OpModule.class);
		build();
	}

	/** The op this module runs. */
	public OpSpec op() {
		return op;
	}

	/** The context the module will run in. */
	public Context context() {
		return context;
	}

	/**
	 * The parameters that got no widget, and are optional enough not to matter.
	 * <p>
	 * Each one runs at its skop-side default. Worth logging once at
	 * registration and never mentioning again -- and worth <em>not</em>
	 * hiding, because a user wondering why a knob is missing deserves an
	 * answer.
	 *
	 * @return the parameters left at their defaults.
	 */
	public List<ParamSpec> unrenderableParams() {
		return unrenderable;
	}

	// -- ModuleInfo methods --

	@Override
	public Module createModule() throws ModuleException {
		// Note: the inherited implementation calls a no-argument constructor,
		// which cannot work here -- one class serves every op, so an instance
		// is useless without knowing which one it is.
		return new OpModule(this);
	}

	@Override
	public String getIdentifier() {
		return "skop:" + op.name();
	}

	@Override
	public String getVersion() {
		// Note: the delegate class's JAR version would be this plugin's, which
		// says nothing about the op. Until skop reports its own version, the
		// honest answer is none.
		return null;
	}

	@Override
	public String getDelegateClassName() {
		return OpModule.class.getName();
	}

	@Override
	public boolean canCancel() {
		return true;
	}

	@Override
	public boolean canRunHeadless() {
		return true;
	}

	// -- Helper methods --

	private void build() {
		setName(op.name());
		setLabel(displayName());
		setMenuPath(menuPath());
		String summary = Docs.summary(op.doc());
		if (summary != null) setDescription(summary);

		Map<String, String> docs = Docs.args(op.doc());
		for (ParamSpec param : op.params()) {
			if (!param.harvestable()) continue;
			MutableModuleItem<?> item =
				Params.item(this, param, docs.get(param.name()));
			if (item == null) {
				// No widget for this one. Required or not is the module's
				// problem, at run time, where it can say so out loud.
				unrenderable.add(param);
				continue;
			}
			addInput(item);
		}

		for (OutputSpec output : op.outputSpecs()) {
			addOutput(Params.output(this, output));
		}
	}

	/**
	 * The op's menu path: {@code Plugins ▸ scikit-ops ▸ <namespace> ▸ <op>}.
	 * <p>
	 * The namespace is the op's Python module with {@code skop.ops} stripped
	 * off, so {@code skop.ops.segment.cellpose} becomes
	 * {@code Segment ▸ Cellpose}. A module sitting directly in the collection
	 * -- {@code skop.ops.threshold} -- gives one level, which is what keeps
	 * eight thresholding methods together without burying them.
	 */
	private MenuPath menuPath() {
		List<String> parts = new ArrayList<>();
		parts.add(MENU_ROOT);
		parts.add(MENU_GROUP);
		for (String segment : namespace()) parts.add(Params.label(segment));
		parts.add(displayName());
		return new MenuPath(String.join(">", parts));
	}

	private String[] namespace() {
		String module = op.module();
		String prefix = "skop.ops.";
		if (!module.startsWith(prefix)) return new String[0];
		return module.substring(prefix.length()).split("\\.");
	}

	/** The op's own name, as a menu entry should read it. */
	private String displayName() {
		return Params.label(op.function());
	}
}
