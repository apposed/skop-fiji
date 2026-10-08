"""scikit-ops' ops as Python functions, for Fiji's Jython scripts.

    from skop_fiji import skops

    labels = skops.segment.cellpose4(imp)
    labels = skops.segment.cellpose4(imp, diameter=30, model="cpsam")

    result = skops.segment.instanseg(imp, target="nuclei")
    result.nuclei
    nuclei, cells = skops.segment.instanseg(imp)

``skops.segment.cellpose4(imp, diameter=30)`` is
``Skop.run("segment.cellpose4", imp, {"diameter": 30})`` and nothing more:
the first argument is the op's image, everything else goes by name, and the
op runs exactly as it does from its menu entry. An op with several outputs
returns them by name, and they unpack in order, as a Python op's NamedTuple
does.

``dir(skops)`` lists the namespaces and ``dir(skops.segment)`` the ops in one. A
misspelt name or parameter raises ValueError, saying what was probably
meant; an op that fails raises RuntimeError with the worker's message.
"""

from java.lang import IllegalArgumentException, IllegalStateException
from java.util import HashMap, Map
from org.apposed.skop.fiji import Skop

__all__ = ["skops"]


def _skop():
    return Skop.current()


def _call(run, *args):
    """Run a Skop call, with its Java errors as Python ones."""
    try:
        return run(*args)
    except IllegalArgumentException, error:
        raise ValueError(error.getMessage())
    except IllegalStateException, error:
        cause = error.getCause()
        detail = cause.getMessage() if cause is not None else ""
        raise RuntimeError("%s: %s" % (error.getMessage(), detail))


class Outputs(object):
    """Several outputs, by name (``result.nuclei``) and in order."""

    def __init__(self, outputs):
        self._outputs = outputs

    def __getattr__(self, name):
        if name.startswith("_") or not self._outputs.containsKey(name):
            raise AttributeError("no output %r; there are: %s"
                                 % (name, ", ".join(self._outputs.keySet())))
        return self._outputs.get(name)

    def __getitem__(self, name):
        return self.__getattr__(name)

    def __iter__(self):
        return iter(list(self._outputs.values()))

    def __len__(self):
        return self._outputs.size()

    def __dir__(self):
        return list(self._outputs.keySet())

    def __repr__(self):
        return "Outputs(%s)" % ", ".join(self._outputs.keySet())


class Op(object):
    """One op, callable: ``op(image, **parameters)``."""

    def __init__(self, name):
        self.__name__ = name

    def __call__(self, *args, **kwargs):
        if len(args) > 1:
            raise TypeError("%s takes the image, then everything else by name"
                            % self.__name__)
        named = HashMap()
        for key, value in kwargs.items():
            named.put(key, value)
        skop = _skop()
        if args:
            result = _call(skop.run, self.__name__, args[0], named)
        else:
            result = _call(skop.run, self.__name__, named)
        return Outputs(result) if isinstance(result, Map) else result

    def __repr__(self):
        return "<scikit-ops op %s>" % self.__name__


class Namespace(object):
    """The ops in one namespace: ``skops.segment``."""

    def __init__(self, name):
        self._name = name

    def _ops(self):
        prefix = self._name + "."
        return [name[len(prefix):] for name in _skop().names()
                if name.startswith(prefix)]

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        full = self._name + "." + name
        if name not in self._ops():
            _call(_skop().op, full)  # raises, suggesting the op meant
        return Op(full)

    def __dir__(self):
        return self._ops()

    def __repr__(self):
        return "<scikit-ops namespace %s: %s>" % (self._name, ", ".join(self._ops()))


class Ops(object):
    """Every op, by namespace: ``skops.segment.cellpose4``."""

    def _namespaces(self):
        return sorted(set(name.split(".")[0] for name in _skop().names()))

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        if name not in self._namespaces():
            raise AttributeError("no scikit-ops namespace %r; there are: %s"
                                 % (name, ", ".join(self._namespaces())))
        return Namespace(name)

    def __dir__(self):
        return self._namespaces()

    def __repr__(self):
        return "<scikit-ops: %s>" % ", ".join(self._namespaces())


skops = Ops()
