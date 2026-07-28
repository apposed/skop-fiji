# skop-fiji

[scikit-ops](https://github.com/apposed/scikit-ops) in Fiji.

The plan is one command per op: every op skop can find gets its own entry
under `Plugins ▸ scikit-ops`, its own generated dialog, a place in Fiji's
search bar, and a recordable macro call. See
[the spec](https://github.com/apposed/scikit-ops/blob/main/docs/spec/fiji-front-end.md)
for what that means and why it is shaped this way.

## Where this is

**Phase 1 of five.** There is no UI yet. What exists is the boundary
underneath one: Java builds the environments, drives the workers, reads the op
specs, and runs ops on shared memory it allocated itself.

| | | |
| --- | --- | --- |
| P0 | in scikit-ops: `OpSpec` as JSON, `describe`/`plan` tasks, a pinned skop in each environment | done |
| P1 | `SkopRunner`, and a headless test running `toy:add` and `threshold:otsu` on a `ShmImg` from Java | done |
| P2 | dynamic module registration, image in and image out, progress, cancel, errors | next |
| P3 | the rest of the roles: `ImgLabeling`, ROIs, tables, masks in the ROI Manager | |
| P4 | axis-mapping UI, environment manager, update site, macro-recording polish | |

## What is here

```
SkopRunner.java   a port of skop's runner.py: build, one service per environment, invoke
Axes.java         ImgLib2 axis labels -> skop's; the order flip lives here
wire/             reading the JSON skop sends
```

`SkopRunner` knows about Appose and about skop's wire format, and about
nothing else -- there is no ImgLib2 type and no SciJava service in it. That is
what makes it testable without a running Fiji, which is the whole point of
doing it first.

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

`AxesTest` and `WireTest` run anywhere. `SkopRunnerTest` needs a scikit-ops
checkout and will build environments -- which, the first time, means a
download. It looks for the checkout in this order:

1. `-Dskop.checkout=/path/to/scikit-ops`
2. `$SKOP_CHECKOUT`
3. `../scikit-ops`

and skips rather than fails if it finds none.

Environments land in Appose's shared directory rather than anywhere under a
Fiji installation, which means **skop-napari and skop-fiji share them**: a
user who has already paid for the `stardist-tf` build in napari does not pay
again here.
