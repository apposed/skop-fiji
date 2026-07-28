# skop-fiji

[scikit-ops](https://github.com/apposed/scikit-ops) in Fiji.

The plan is one command per op: every op skop can find gets its own entry
under `Plugins ▸ scikit-ops`, its own generated dialog, a place in Fiji's
search bar, and a recordable macro call. See
[the spec](https://github.com/apposed/scikit-ops/blob/main/docs/spec/fiji-front-end.md)
for what that means and why it is shaped this way.

## Where this is

**Phase 3 of five: every role, as the thing it means.** Every op skop can find
is registered as its own SciJava module at startup, with a generated dialog, a
menu entry, a search-bar hit and a recordable identifier. A segmentation comes
back as an `ImgLabeling`, detections as ROIs, tracks as a table; progress
reaches the status bar, Cancel reaches the worker, and a failure reaches the
log with its Python traceback intact.

| | | |
| --- | --- | --- |
| P0 | in scikit-ops: `OpSpec` as JSON, `describe`/`plan` tasks, a pinned skop in each environment | done |
| P1 | `SkopRunner`, and a headless test running `toy:add` and `threshold:otsu` on a `ShmImg` from Java | done |
| P2 | dynamic module registration, image in and image out, progress, cancel, errors | done |
| P3 | the rest of the roles: `ImgLabeling`, ROIs, tables, masks in the ROI Manager | done |
| P4 | axis-mapping UI (done), environment manager, update site, macro-recording polish | in progress |

| Role | Becomes |
| --- | --- |
| `image`, none | `Dataset` |
| `labels` | `ImgLabeling` |
| `masks`, `points`, `shapes` | `ROITree` (imagej-legacy takes it from there to the ROI Manager) |
| `tracks` | `Table` |
| `vectors`, `surface` | `Dataset`, for now -- no op produces either yet |

## What is here

```
SkopService.java    finds the ops, registers one command each, owns the runner
OpModuleInfo.java   OpSpec -> ModuleInfo: menu path, items, the macro identifier
OpModule.java       one run: encode, dispatch, decode; progress, cancel, errors
Params.java         ParamSpec -> MutableModuleItem
Roles.java          Role -> Fiji type; the only Fiji-specific lookup table
Images.java         Dataset <-> NDArray, without a copy where there needn't be one
Results.java        one output -> the Fiji thing its role means
Labelings.java      label array <-> ImgLabeling, over the worker's own block
Rois.java           boxes, points and masks <-> ROIs; coordinates reverse here too
Tables.java         an (N, C) array -> a Table
Docs.java           a Google-style docstring -> the text a dialog shows
AxisMapping.java    the per-axis decisions, as widgets
AxisPreprocessor    builds them, just before the dialog is drawn
Axes.java           ImgLib2 axis labels -> skop's; the order flip lives here
SkopRunner.java     a port of skop's runner.py: build, one service per environment, invoke
wire/               reading the JSON skop sends
```

`SkopRunner` and `wire/` know about Appose and about skop's wire format, and
about nothing else -- no ImgLib2 type and no SciJava service in either. That
is what makes the boundary testable without a running Fiji.

## What a user gets

- **One command per op.** `Plugins ▸ scikit-ops ▸ Threshold ▸ Otsu`, and the
  same thing in the search bar, because `ModuleSearcher` indexes
  `ModuleService`. No op-picker panel: the search bar *is* the browse view.
- **A dialog generated from the op's signature**, with the docstring's
  `Args:` entries as tooltips, sliders where skop asked for sliders, and
  choice lists for enums.
- **2-D ops that work on stacks, and an argument with how.** An `ImgPlus`
  says what its axes are, so they are read, handed to `skop.plan`, and a
  strictly 2-D op is iterated over a stack. The dialog then shows that
  decision and lets you change it: which of the image's axes fills each slot
  the op consumes, and for each axis left over, whether to iterate it, take a
  single position, or hand it through whole. The default is what skop would
  have done unasked, and it never discards data.
- **A stable macro identifier**, `skop:skop.ops.threshold:otsu`.
- **Failures that say what happened.** A worker's traceback reaches the log
  whole. A script can tell a failure from a user-pressed Cancel with
  `OpModule.failure()`, which SciJava's `Cancelable` alone cannot.

## What it does not do yet

- No environment manager, no update site.
- The mapping widgets need an image already in the parameter when the dialog
  opens, which ImageJ's `ActiveImagePreprocessor` handles for the usual case
  of one image parameter and an open image. Without one, the op still runs on
  skop's default plan.
- `vectors` and `surface` still fall back to `Dataset` -- lossless, but not
  an Overlay of arrows or a mesh. No op produces either yet.
- A result's shared memory is deliberately never released. See
  `Images.adopt`.

## Architecture, in one paragraph

Java is the host. It builds each environment with appose-java's `PixiBuilder`,
keeps one worker per environment, encodes arguments, posts the same
`skop_invoke(...)` task skop's own runner posts, and reads the outputs back. A
Java `NDArray` goes straight into the worker's task inputs: one shared memory
block, no copies. The two jobs that need a Python interpreter -- listing the
ops, which works by *importing* them, and planning an axis mapping -- run as
tasks in one long-lived service in the `minimal` environment.

## The trap

**ImgLib2 is x-fastest; numpy is last-fastest.** An image whose ImgLib2 axes
are `(X, Y, Z)` is a numpy array of shape `(z, y, x)`, so the axis labels
handed to skop are the ImgLib2 order reversed.

Get this wrong and nothing crashes. Every label still matches a slot, the plan
still looks reasonable, and the op runs on transposed data and returns a
plausible, wrong answer. `Axes` is a class of its own, with tests of its own,
for exactly that reason.

## Building and testing

Java 11+ and Maven:

```sh
mvn test
```

`AxesTest`, `WireTest`, `DocsTest` and `ModulesTest` run anywhere.
`SkopRunnerTest` and `SkopServiceTest` need a scikit-ops checkout and will
build environments -- which, the first time, means a download. The checkout is
looked for in this order:

1. `-Dskop.checkout=/path/to/scikit-ops`
2. `$SKOP_CHECKOUT`
3. `../scikit-ops`

and those tests skip rather than fail if there is none. `-Dskop.noAutoDiscover=true`
stops `SkopService` from describing the ops at startup, which is occasionally
what you want in a test.

Environments land in Appose's shared directory rather than anywhere under a
Fiji installation, which means **skop-napari and skop-fiji share them**: a
user who has already paid for the `stardist-tf` build in napari does not pay
again here.
