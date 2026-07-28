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

import java.util.List;

import net.imagej.Dataset;

import org.apposed.skop.fiji.wire.AdaptationPlan;
import org.apposed.skop.fiji.wire.OpSpec;
import org.apposed.skop.fiji.wire.ParamSpec;
import org.scijava.log.LogService;
import org.scijava.module.Module;
import org.scijava.module.ModuleItem;
import org.scijava.module.process.AbstractPreprocessorPlugin;
import org.scijava.module.process.PreprocessorPlugin;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.InputHarvester;

/**
 * Builds the axis-mapping widgets, just before the dialog is drawn.
 * <p>
 * The mapping items cannot be built when an op is registered, because they
 * depend on the shape and axes of an image nobody has chosen yet. The spec's
 * first suggestion was a callback on the image parameter that adds them once a
 * {@code Dataset} is selected; that fights the input harvester, which has
 * already drawn its panel by then.
 * <p>
 * A preprocessor does not. By the time preprocessing reaches
 * {@link InputHarvester#PRIORITY}, ImageJ's own
 * {@code ActiveImagePreprocessor} has already filled a single image parameter
 * from the active display -- so the image is known, its axes are known, and
 * this can ask skop what it would do and lay that out as widgets. One dialog,
 * drawn once, showing the default and inviting an argument with it.
 * <p>
 * When there is no image to inspect -- nothing open, or several image
 * parameters and no way to guess -- no items are added and the op runs on
 * skop's default plan, which is exactly what it did before any of this
 * existed. The feature degrades to the fallback rather than to an error.
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
		OpModule op = (OpModule) module;
		if (!(op.getInfo() instanceof OpModuleInfo)) return;
		OpModuleInfo info = (OpModuleInfo) op.getInfo();
		OpSpec spec = info.op();

		for (ParamSpec param : spec.params()) {
			if (!param.harvestable() || param.axes() == null) continue;
			Dataset image = resolvedImage(module, param.name());
			if (image == null) continue;
			try {
				addItems(info, spec, param, image);
			}
			catch (Exception exc) {
				// Never block a run over the widgets that were only going to
				// offer a choice: without them, skop's default still applies.
				if (log != null) {
					log.debug("Could not build axis widgets for " + spec.name(), exc);
				}
			}
		}
	}

	private void addItems(OpModuleInfo info, OpSpec spec, ParamSpec param,
		Dataset image) throws Exception
	{
		List<String> labels = Axes.numpyLabelsOf(image);
		List<Integer> shape = numpyShape(image);
		AdaptationPlan plan =
			skop.runner().plan(spec.name(), param.name(), shape, labels);
		AxisMapping.addItems(info, param, labels, shape, plan);
	}

	/**
	 * The image already sitting in a parameter, or null if there is not one.
	 * <p>
	 * Deliberately does not resolve anything itself: an image the user has not
	 * chosen yet is an image whose axes are not known yet, and inventing one
	 * would produce a dialog about the wrong picture.
	 */
	private static Dataset resolvedImage(Module module, String name) {
		ModuleItem<?> item = module.getInfo().getInput(name);
		if (item == null) return null;
		Object value = module.getInput(name);
		return value instanceof Dataset ? (Dataset) value : null;
	}

	private static List<Integer> numpyShape(Dataset image) {
		List<Integer> shape = new java.util.ArrayList<>(image.numDimensions());
		// ImgLib2 is x-fastest and numpy is last-fastest, so this reverses.
		for (int d = image.numDimensions() - 1; d >= 0; d--) {
			shape.add((int) image.dimension(d));
		}
		return shape;
	}
}
