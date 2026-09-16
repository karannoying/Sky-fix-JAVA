#!/usr/bin/env python3
"""Regenerate data/reference/ussa1976.csv, the T-V1 oracle (DS-3).

This script is NOT part of the build and is never run by a test: SKYFIX itself is
pure Java and offline (CLAUDE.md rules 1 and 2). It exists only so the reference
table is reproducible rather than hand-typed from memory.

It evaluates two independent third-party implementations of the standard at the
same 25 geometric altitudes and reports their worst disagreement, so the table
carries a measured corroboration figure rather than an assertion of correctness:

    pip install ambiance pyatmos
    python3 scripts/reference/gen_ussa1976.py

verify: the committed CSV must ultimately be re-transcribed from the primary
source (NOAA-S/T 76-1562, Table I). See the header of the CSV.
"""
import importlib.util
import os
import sys
import types

PYATMOS = os.path.join(os.path.dirname(sys.executable), '..', 'lib')


def load_coesa76():
    """Import pyatmos' COESA-76 module directly.

    pyatmos.__init__ downloads IERS earth-orientation data on import, which the
    offline rule forbids; the standard-atmosphere module itself needs no network.
    """
    import pyatmos  # noqa: F401  (located via the normal import path)
    base = os.path.dirname(pyatmos.__file__)
    os.chdir(base)  # coesa76.py resolves its coefficient file relative to cwd
    pkg = types.ModuleType('pyatmos')
    pkg.__path__ = [base]
    sys.modules['pyatmos'] = pkg
    sub = types.ModuleType('pyatmos.standardatmos')
    sub.__path__ = [os.path.join(base, 'standardatmos')]
    sys.modules['pyatmos.standardatmos'] = sub
    spec = importlib.util.spec_from_file_location(
        'pyatmos.standardatmos.coesa76', os.path.join(base, 'standardatmos', 'coesa76.py'))
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod.coesa76


# 25 geometric altitudes spanning 0-47 km, including every USSA layer boundary.
ALTITUDES_KM = [0, 1, 2, 3, 4, 5, 7, 9, 10, 11, 12, 14, 16, 18, 20,
                22, 25, 28, 30, 32, 35, 40, 44, 46, 47]


def main():
    out_path = os.path.abspath(os.path.join(
        os.path.dirname(__file__), '..', '..', 'data', 'reference', 'ussa1976.csv'))
    coesa76 = load_coesa76()
    from ambiance import Atmosphere

    rows, worst = [], (0.0, None)
    for h_km in ALTITUDES_KM:
        primary = coesa76([h_km])                       # geometric altitude, km
        check = Atmosphere(h_km * 1000.0)               # geometric altitude, m
        triple = (float(primary.T[0]), float(primary.P[0]), float(primary.rho[0]))
        other = (float(check.temperature[0]), float(check.pressure[0]),
                 float(check.density[0]))
        for name, a, b in zip(('T', 'p', 'rho'), triple, other):
            rel = abs(a - b) / b
            if rel > worst[0]:
                worst = (rel, f'{name} at {h_km} km')
        rows.append((h_km * 1000.0,) + triple)

    for h, t, p, d in rows:
        print(f'{h:.1f},{t:.4f},{p:.6g},{d:.6g}')
    print(f'\n# max disagreement between the two implementations: '
          f'{worst[0] * 100:.5f} % ({worst[1]})', file=sys.stderr)
    print(f'# paste the rows above under the header of {out_path}', file=sys.stderr)


if __name__ == '__main__':
    main()
