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

import net.imagej.Dataset;

import org.apposed.skop.fiji.wire.AdaptationPlan;
import org.apposed.skop.fiji.wire.OpSpec;
import org.apposed.skop.fiji.wire.ParamSpec;
import org.scijava.log.LogService;
import org.scijava.module.Module;
import org.scijava.module.process.AbstractPreprocessorPlugin;
import org.scijava.module.process.PreprocessorPlugin;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.InputHarvester;

/**
 * Fills in the axis mapping skop would have chosen, before the dialog opens.
 * <p>
 * The field is there whether or not this runs -- blank means skop's own plan,
 * which is what every run got before any of this existed. What this adds is
 * making the default <em>visible</em>: a dialog that opens showing
 * {@code "x y z!"} has told a user what is about to happen, what the syntax
 * looks like, and where to intervene, all without asking them anything.
 * <p>
 * It has to be a preprocessor rather than a callback on the image parameter,
 * because the answer depends on the shape and axes of an image that is not
 * chosen when the op is registered. By {@link InputHarvester#PRIORITY},
 * imagej-common's {@code ActiveImagePreprocessor} has already filled a single
 * image parameter from the active display, so the axes are known before the
 * panel exists.
 * <p>
 * When there is no image to inspect -- nothing open, or several image
 * parameters and no way to guess -- the field is simply left blank and the op
 * runs on skop's default. Nothing here is ever required for a run to work.
 *
 * @author Curtis Rueden
 */
@Plugin(type = PreprocessorPlugin.class, priority = InputHarvester.PRIORITY + 1)
public class AxisPreprocessor extends AbstractPreprocessorPlugin {

	@Parameter(required = false)
	private SkopService skop;

	@Parameter(required = false)
	private LogService log;

	@Override
	public void process(Module module) {
		if (skop == null || !(module instanceof OpModule)) return;
		if (!(module.getInfo() instanceof OpModuleInfo)) return;
		OpSpec spec = ((OpModuleInfo) module.getInfo()).op();

		for (ParamSpec param : spec.params()) {
			if (!param.harvestable() || param.axes() == null) continue;
			String name = OpModuleInfo.axisItemName(param.name());
			Object current = module.getInput(name);
			// Anything already there is a user's, or a macro's, and stays.
			if (current != null && !String.valueOf(current).trim().isEmpty()) continue;

			Dataset image = resolvedImage(module, param.name());
			if (image == null) continue;
			try {
				module.setInput(name, defaultSpec(spec, param, image));
			}
			catch (Exception exc) {
				// A default nobody could compute is a blank field, which still
				// runs. Never block a dialog over a convenience.
				if (log != null) {
					log.debug("Could not describe the default axis mapping for " +
						spec.name(), exc);
				}
			}
		}
	}

	private String defaultSpec(OpSpec spec, ParamSpec param, Dataset image)
		throws Exception
	{
		List<String> labels = Axes.numpyLabelsOf(image);
		AdaptationPlan plan = skop.runner().plan(spec.name(), param.name(),
			numpyShape(image), labels);
		return AxisSpec.format(plan, param.axes());
	}

	/**
	 * The image already sitting in a parameter, or null if there is not one.
	 * <p>
	 * Deliberately does not resolve anything itself: an image the user has not
	 * chosen yet is an image whose axes are not known yet, and inventing one
	 * would describe the wrong picture.
	 */
	private static Dataset resolvedImage(Module module, String name) {
		if (module.getInfo().getInput(name) == null) return null;
		Object value = module.getInput(name);
		return value instanceof Dataset ? (Dataset) value : null;
	}

	private static List<Integer> numpyShape(Dataset image) {
		List<Integer> shape = new ArrayList<>(image.numDimensions());
		// ImgLib2 is x-fastest and numpy is last-fastest, so this reverses.
		for (int d = image.numDimensions() - 1; d >= 0; d--) {
			shape.add((int) image.dimension(d));
		}
		return shape;
	}
}
