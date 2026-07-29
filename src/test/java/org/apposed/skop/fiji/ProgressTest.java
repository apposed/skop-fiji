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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.Cursor;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scijava.Context;
import org.scijava.event.EventHandler;
import org.scijava.event.EventService;
import org.scijava.event.EventSubscriber;
import org.scijava.module.Module;
import org.scijava.module.ModuleInfo;
import org.scijava.module.ModuleService;
import org.scijava.task.Task;
import org.scijava.task.event.TaskEvent;

/**
 * Progress, as everything that shows progress sees it.
 * <p>
 * A worker reports (message, current, maximum) -- per slice when an op is
 * iterated, and from inside any op that says anything about itself. That has
 * to reach the status bar and the task list, and the Stop button in the task
 * list has to reach back. These tests subscribe to the very events those
 * widgets subscribe to, which is as close to checking a UI as this can get
 * without one.
 *
 * @author Curtis Rueden
 */
public class ProgressTest {

	private static Context context;
	private static ModuleService modules;
	private static DatasetService datasets;
	private static EventService events;

	/** Every task event published during one test. */
	private final List<TaskEvent> seen =
		Collections.synchronizedList(new ArrayList<>());

	private List<EventSubscriber<?>> subscription;

	@BeforeAll
	public static void setUpClass() throws Exception {
		assumeTrue(SkopRunner.fromCheckout() != null,
			"no scikit-ops checkout; set -Dskop.checkout");
		context = new Context();
		modules = context.getService(ModuleService.class);
		datasets = context.getService(DatasetService.class);
		events = context.getService(EventService.class);
		context.getService(SkopService.class).discover().get();
	}

	@AfterAll
	public static void tearDownClass() {
		if (context != null) context.dispose();
	}

	@BeforeEach
	public void listen() {
		seen.clear();
		subscription = events.subscribe(this);
	}

	@AfterEach
	public void stopListening() {
		if (subscription != null) events.unsubscribe(subscription);
	}

	@EventHandler
	public void onTask(TaskEvent event) {
		seen.add(event);
	}

	// -- helpers -------------------------------------------------------------

	private static ModuleInfo info(String opName) {
		for (ModuleInfo info : modules.getModules()) {
			if (("skop:" + opName).equals(info.getIdentifier())) return info;
		}
		throw new AssertionError("no registered module for " + opName);
	}

	private static Dataset image(long[] dims, AxisType[] axes) {
		Dataset dataset = datasets.create(new FloatType(), dims, "test", axes);
		Cursor<RealType<?>> cursor = dataset.cursor();
		while (cursor.hasNext()) cursor.next().setReal(1);
		return dataset;
	}

	private static Module run(String opName, Object... pairs) throws Exception {
		LinkedHashMap<String, Object> inputs = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			inputs.put((String) pairs[i], pairs[i + 1]);
		}
		return modules.run(info(opName), false, inputs).get();
	}

	/** The task this run created, or null if it created none. */
	private Task task() {
		synchronized (seen) {
			for (TaskEvent event : seen) {
				if (event.getTask() != null) return event.getTask();
			}
		}
		return null;
	}

	/** Every status message a widget would have displayed, in order. */
	private List<String> messages() {
		List<String> messages = new ArrayList<>();
		synchronized (seen) {
			for (TaskEvent event : seen) {
				Task task = event.getTask();
				if (task != null && task.getStatusMessage() != null) {
					messages.add(task.getStatusMessage());
				}
			}
		}
		return messages;
	}

	// -- an op's own progress ------------------------------------------------

	@Test
	public void testAnOpsProgressReachesTheTaskList() throws Exception {
		run("skop.ops.toy:slow_sum", "image",
			image(new long[] { 16, 16 }, new AxisType[] { Axes.X, Axes.Y }),
			"steps", 4L);

		assertFalse(seen.isEmpty(), "no task events at all");
		Task task = task();
		assertNotNull(task, "no task was created for the run");
		assertTrue(task.isDone(), "the task should have been finished");

		// slow_sum reports "Summing chunk N of 4" as it goes.
		List<String> messages = messages();
		assertTrue(messages.stream().anyMatch(m -> m.startsWith("Summing chunk")),
			messages.toString());
	}

	@Test
	public void testTheTaskIsNamedForWhatIsRunning() throws Exception {
		run("skop.ops.threshold:otsu", "image",
			image(new long[] { 8, 8 }, new AxisType[] { Axes.X, Axes.Y }));

		Task task = task();
		assertNotNull(task);
		// The op, and the environment it needs -- which is the thing a user
		// waiting four minutes for a first build wants to see named.
		assertTrue(task.getName().contains("Otsu"), task.getName());
		assertTrue(task.getName().contains("skimage"), task.getName());
	}

	@Test
	public void testIteratingAStackReportsPerSlice() throws Exception {
		// The iteration happens in the worker -- eight planes are one task and
		// one trip through shared memory, not eight -- so these events are the
		// only way a user learns it is on plane 3 of 8.
		run("skop.ops.toy:quadrants", "image", image(new long[] { 8, 8, 8 },
			new AxisType[] { Axes.X, Axes.Y, Axes.Z }));

		List<String> messages = messages();
		assertTrue(messages.stream().anyMatch(m -> m.startsWith("Slice ")),
			messages.toString());
		assertTrue(task().getProgressMaximum() >= 8,
			"the maximum should have reached the number of slices");
	}

	@Test
	public void testASilentOpStillStartsAndFinishesATask() throws Exception {
		// add says nothing about itself, and its task still appears and goes
		// away -- otherwise a run that hung would look like no run at all.
		run("skop.ops.toy:add", "a", 2L, "b", 3L);

		Task task = task();
		assertNotNull(task);
		assertTrue(task.isDone());
	}

	@Test
	public void testAFailedRunStillFinishesItsTask() throws Exception {
		// A task left running forever is worse than no task: the widget would
		// go on claiming something is happening.
		//
		// minimum wants a bimodal histogram and a uniform image has none. The
		// module logs the whole Python traceback, which is what it is for and
		// what nobody wants to read in the middle of a passing run.
		Module module = Quiet.run(context, () ->
			run("skop.ops.threshold:minimum", "image",
				image(new long[] { 16, 16 }, new AxisType[] { Axes.X, Axes.Y })));
		assertTrue(((OpModule) module).isCanceled());

		Task task = task();
		assertNotNull(task);
		assertTrue(task.isDone());
	}

	@Test
	public void testStoppingTheTaskStopsTheOp() throws Exception {
		// The Stop button in the task widget and the Cancel in a dialog have
		// to do the same thing: ask the worker to stop, which
		// skop.cancel_requested() reads on the far side.
		OpModule module = (OpModule) info("skop.ops.toy:slow_sum").createModule();
		module.setInput("image",
			image(new long[] { 64, 64 }, new AxisType[] { Axes.X, Axes.Y }));
		module.setInput("steps", 40L);

		Thread stopper = new Thread(() -> {
			try {
				Task task = null;
				for (int i = 0; i < 100 && task == null; i++) {
					Thread.sleep(20);
					task = task();
				}
				assertNotNull(task, "no task appeared to stop");
				task.cancel("from the task widget");
			}
			catch (InterruptedException exc) {
				Thread.currentThread().interrupt();
			}
		});
		stopper.start();
		module.run();
		stopper.join();

		assertTrue(module.isCanceled(), "stopping the task should stop the op");
		assertTrue(task().isDone());
	}

	// -- environment builds --------------------------------------------------

	@Test
	public void testAnEnvironmentBuildGetsATaskOfItsOwn() throws Exception {
		// Nested inside the run's own task, because "Running Otsu" stuck at 0%
		// for four minutes says the wrong thing about what is going on.
		//
		// A context of its own, because a runner reaches for an environment
		// once and holds it: in the shared one, whichever test ran first has
		// already done this and there would be nothing left to watch.
		Context fresh = new Context();
		List<EventSubscriber<?>> subs = null;
		try {
			EventService freshEvents = fresh.getService(EventService.class);
			List<TaskEvent> freshSeen =
				Collections.synchronizedList(new ArrayList<>());
			Object listener = new Object() {
				@EventHandler
				public void onTask(TaskEvent event) {
					freshSeen.add(event);
				}
			};
			subs = freshEvents.subscribe(listener);

			SkopService service = fresh.getService(SkopService.class);
			service.discover().get();
			// Reaching for the environment is what a run does first.
			service.runner().environment("minimal", null);

			Task build = null;
			synchronized (freshSeen) {
				for (TaskEvent event : freshSeen) {
					Task task = event.getTask();
					if (task != null && task.getName() != null && task.getName()
						.startsWith("Building scikit-ops environment"))
					{
						build = task;
					}
				}
			}
			assertNotNull(build, "no build task was published");
			assertTrue(build.getName().contains("minimal"), build.getName());
			assertTrue(build.isDone(), "the build task should have finished");
		}
		finally {
			if (subs != null) fresh.getService(EventService.class).unsubscribe(subs);
			fresh.dispose();
		}
	}
}
