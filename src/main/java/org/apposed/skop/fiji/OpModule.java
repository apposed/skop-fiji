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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.roi.ROITree;
import net.imglib2.roi.labeling.ImgLabeling;

import org.apposed.appose.NDArray;
import org.apposed.appose.Service;
import org.apposed.skop.fiji.wire.AdaptationPlan;
import org.apposed.skop.fiji.wire.Form;
import org.apposed.skop.fiji.wire.OpSpec;
import org.apposed.skop.fiji.wire.OutputSpec;
import org.apposed.skop.fiji.wire.ParamSpec;
import org.apposed.skop.fiji.wire.TypeSpec;
import org.scijava.Cancelable;
import org.scijava.app.StatusService;
import org.scijava.log.LogService;
import org.scijava.module.AbstractModule;
import org.scijava.module.ModuleInfo;

/**
 * One run of one op.
 * <p>
 * Everything a dialog collected is turned into what a worker wants, the op is
 * dispatched, and the results are turned back into things Fiji can show. The
 * three cross-cutting concerns are all handled here because all three are
 * per-run: progress goes to the status bar, cancellation goes to the Appose
 * task (which {@code skop.cancel_requested()} reads on the far side), and a
 * failure goes to the log <em>in full</em>.
 * <p>
 * That last one is a deliberate choice rather than laziness. The interesting
 * part of a failure here is another interpreter's traceback, and a traceback
 * put into a one-line status field is a traceback thrown away.
 * <p>
 * There is no thread plumbing, because SciJava already runs modules off the
 * EDT. This is the whole of skop-napari's {@code _run.py}, deleted.
 *
 * @author Curtis Rueden
 */
public class OpModule extends AbstractModule implements Cancelable {

	private final OpModuleInfo info;
	private final SkopService skop;
	private final DatasetService datasets;
	private final StatusService status;
	private final LogService log;

	private volatile Service.Task task;
	private volatile String cancelReason;
	private volatile Exception failure;

	public OpModule(OpModuleInfo info) {
		this.info = info;
		this.skop = info.context().getService(SkopService.class);
		this.datasets = info.context().getService(DatasetService.class);
		this.status = info.context().getService(StatusService.class);
		this.log = info.context().getService(LogService.class);
	}

	// -- Module methods --

	@Override
	public ModuleInfo getInfo() {
		return info;
	}

	@Override
	public void run() {
		OpSpec op = info.op();
		cancelReason = null;
		failure = null;

		List<ParamSpec> blocking = op.blockingParams();
		if (!blocking.isEmpty()) {
			// One awkward parameter must not cost the whole op -- but a
			// *required* one does, and the only decent thing is to say which.
			fail(op, new IllegalStateException(
				"Cannot run " + op.name() + " from a dialog: no widget for " +
					describe(blocking) + ". Run it from a script instead."));
			return;
		}

		List<NDArray> allocated = new ArrayList<>();
		try {
			Map<String, Object> args = new LinkedHashMap<>();
			Map<String, List<String>> axes = new LinkedHashMap<>();
			encode(op, args, axes, allocated);

			Map<String, AdaptationPlan> plans = plans(op, args, axes);

			Map<String, Object> outputs = skop.runner().run(op, args, plans, null,
				this::report, started -> task = started);

			decode(op, outputs, outputAxes(plans, axes));
		}
		catch (InterruptedException exc) {
			Thread.currentThread().interrupt();
			cancelReason = "Interrupted";
		}
		catch (Exception exc) {
			if (cancelReason == null) fail(op, exc);
		}
		finally {
			task = null;
			// Inputs we allocated are ours to release; outputs are not, and
			// are deliberately left alive. See Images.adopt.
			for (NDArray array : allocated) {
				try {
					array.close();
				}
				catch (Exception exc) {
					log.debug("Could not release an input buffer", exc);
				}
			}
			if (status != null) status.clearStatus();
		}
	}

	// -- Cancelable methods --

	@Override
	public boolean isCanceled() {
		return cancelReason != null;
	}

	@Override
	public void cancel(String reason) {
		cancelReason = reason == null ? "Canceled" : reason;
		Service.Task running = task;
		// The far side polls skop.cancel_requested(), so an op that checks it
		// stops where it is; one that never checks runs to completion and its
		// result is discarded. Nothing here can do better than the op does.
		if (running != null) running.cancel();
	}

	@Override
	public String getCancelReason() {
		return cancelReason;
	}

	// -- OpModule methods --

	/**
	 * What went wrong, or null if nothing did.
	 * <p>
	 * A module that does not finish has exactly one channel to say so --
	 * {@link Cancelable} -- and SciJava uses it for both meanings, so a failed
	 * op and a user pressing Cancel both come back {@linkplain #isCanceled()
	 * canceled}. That is fine for the framework, which only wants to know
	 * whether to display the outputs, and not fine for a script, which wants
	 * to know whether to carry on. This is the difference: non-null means the
	 * op threw, and the {@code TaskException} it returns carries the worker's
	 * whole traceback.
	 *
	 * @return the exception, or null if the op succeeded or was canceled.
	 */
	public Exception failure() {
		return failure;
	}

	// -- Helper methods --

	/** Turns harvested values into what a worker takes. */
	private void encode(OpSpec op, Map<String, Object> args,
		Map<String, List<String>> axes, List<NDArray> allocated)
	{
		for (ParamSpec param : op.params()) {
			if (!param.harvestable()) continue;
			Object value = getInput(param.name());
			if (value == null) {
				// Absent and optional: let skop apply its own default rather
				// than sending a null it would have to interpret.
				continue;
			}
			if (value instanceof Dataset) {
				Dataset dataset = (Dataset) value;
				NDArray array = Images.toNDArray(dataset.getImgPlus());
				if (!Images.isShared(dataset.getImgPlus())) allocated.add(array);
				args.put(param.name(), array);
				// Recorded for every image, not only the ones with declared
				// axes: an op that adapts nothing still hands back a result
				// whose axes are worth naming. See outputAxes.
				axes.put(param.name(), Axes.numpyLabelsOf(dataset));
			}
			else if (value instanceof ImgLabeling) {
				// A labeling that came back from an op is already the block the
				// worker wrote, so chaining a segmentation costs nothing.
				ImgLabeling<?, ?> labeling = (ImgLabeling<?, ?>) value;
				NDArray array = Labelings.toNDArray(labeling);
				if (!Labelings.isShared(labeling)) allocated.add(array);
				args.put(param.name(), array);
			}
			else if (value instanceof ROITree) {
				// Every op taking ROIs today takes boxes; a ROI that is not one
				// contributes its bounds, which is what "boxes" means anyway.
				NDArray array = Rois.toBoxArray(Rois.flatten((ROITree) value));
				allocated.add(array);
				args.put(param.name(), array);
			}
			else {
				args.put(param.name(), value);
			}
		}
	}

	/**
	 * The adaptation plans for this run: skop's own, unedited.
	 * <p>
	 * A user does not choose here yet -- the mapping UI is a phase away -- and
	 * accepting skop's default is the documented fallback precisely because it
	 * never discards data. What it does do is make a strictly 2-D op work on a
	 * stack, by iterating it, which is most of the value and none of the
	 * interface.
	 * <p>
	 * Its warnings go to the log rather than being swallowed: "y is being fed
	 * the z axis" is the sentence that explains a result someone is about to
	 * be confused by.
	 */
	private Map<String, AdaptationPlan> plans(OpSpec op, Map<String, Object> args,
		Map<String, List<String>> axes) throws Exception
	{
		Map<String, AdaptationPlan> plans = new LinkedHashMap<>();
		if (axes.isEmpty() || op.form() != Form.FUNCTION) {
			// A computer- or inplace-form op writes into a buffer shaped like
			// the whole input, so iterating it is not implemented -- on either
			// side. Pass the arrays through as they are.
			return plans;
		}

		int iterating = 0;
		for (Map.Entry<String, List<String>> entry : axes.entrySet()) {
			ParamSpec param = op.param(entry.getKey());
			// Only a parameter that declared what it consumes can be fitted to
			// anything; the rest are passed through as they are.
			if (param == null || param.axes() == null) continue;
			NDArray array = (NDArray) args.get(entry.getKey());
			List<String> labels = entry.getValue();
			// What the user chose, if they were offered the choice at all.
			// Absent everywhere, this is exactly skop's own default plan.
			AdaptationPlan plan = skop.runner().plan(op.name(), entry.getKey(),
				Axes.numpyShape(array), labels,
				AxisMapping.positions(this, entry.getKey(), labels),
				AxisMapping.mapping(this, entry.getKey(),
					param.axes().slots().size(), labels),
				AxisMapping.dispositions(this, entry.getKey(), labels));
			for (String warning : plan.warnings()) {
				log.warn(op.name() + ": " + warning);
			}
			if (plan.iterate().length > 0) {
				iterating++;
				log.info(op.name() + ": " + plan.summary());
			}
			plans.put(entry.getKey(), plan);
		}

		if (iterating > 1) {
			// skop refuses this, and rightly: two iterated parameters is a
			// nested loop nobody asked for. Better to run the op once on
			// everything and let it complain than to invent a traversal.
			log.warn(op.name() + ": more than one parameter would be iterated, " +
				"so the arrays are passed through unadapted.");
			return new LinkedHashMap<>();
		}
		return plans;
	}

	/**
	 * What to call the axes of a result, or null to leave them unnamed.
	 * <p>
	 * A plan knows, because it worked out where every axis went. Without one,
	 * the only defensible answer is the input's own labels, and only when
	 * there was exactly one image to take them from -- an op given two arrays
	 * gives no clue which the result resembles, and
	 * {@link Images#toImgPlus} drops the names anyway if the count is wrong.
	 * Naming an axis incorrectly is worse than leaving it unnamed, so this
	 * declines wherever it would be guessing.
	 */
	private static List<String> outputAxes(Map<String, AdaptationPlan> plans,
		Map<String, List<String>> axes)
	{
		if (plans.size() == 1) return plans.values().iterator().next().outputAxes();
		if (plans.isEmpty() && axes.size() == 1) {
			return axes.values().iterator().next();
		}
		return null;
	}

	/** Turns a worker's results into things Fiji can show. */
	private void decode(OpSpec op, Map<String, Object> outputs,
		List<String> outputAxes)
	{
		for (OutputSpec output : op.outputSpecs()) {
			Object value = outputs.get(output.name());
			if (value instanceof NDArray) {
				// The block outlives this call: whoever takes the result owns
				// it. See Images.adopt.
				Images.adopt((NDArray) value);
				setOutput(output.name(), Results.convert(datasets, value, output,
					name(op, output), outputAxes, log));
			}
			else if (value != null &&
				Params.outputType(output) == String.class &&
				!(value instanceof String))
			{
				setOutput(output.name(), String.valueOf(value));
			}
			else {
				setOutput(output.name(), value);
			}
		}
	}

	/** What a result window should be called. */
	private String name(OpSpec op, OutputSpec output) {
		String base = Params.label(op.function());
		return op.outputSpecs().size() == 1 ? base
			: base + " (" + output.name() + ")";
	}

	private void report(org.apposed.appose.TaskEvent event) {
		if (status == null || event.message == null) return;
		status.showStatus((int) event.current, (int) event.maximum, event.message);
	}

	private void fail(OpSpec op, Exception exc) {
		// In full, and to the log: the interesting part of this failure
		// happened in another interpreter, and a traceback squeezed into a
		// status field is a traceback lost.
		log.error("Op " + op.name() + " failed", exc);
		failure = exc;
		cancelReason = "Op " + op.name() + " failed: " + exc.getMessage();
	}

	private static String describe(List<ParamSpec> params) {
		List<String> parts = new ArrayList<>();
		for (ParamSpec param : params) {
			TypeSpec type = param.type();
			parts.add("'" + param.name() + "' (" +
				(type.detail() == null ? type.wireName() : type.detail()) + ")");
		}
		return String.join(", ", parts);
	}

}
