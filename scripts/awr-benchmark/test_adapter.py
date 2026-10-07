#!/usr/bin/env python3
"""Small format/validation checks; optionally exercise a supplied v1-v3 awrlib."""
import argparse
import io
from pathlib import Path
import struct
import sys
import tempfile
from types import SimpleNamespace
import unittest

sys.dont_write_bytecode = True
import run as adapter


def read_normalized(data):
    stream = io.BytesIO(data)

    def take(fmt):
        return struct.unpack(fmt, stream.read(struct.calcsize(fmt)))

    magic, version, real, items, count = take(">iiiii")
    assert (magic, version) == (adapter.NORMALIZED_MAGIC, adapter.NORMALIZED_VERSION)
    recipes = []
    for _ in range(count):
        output, amount, cost, extra = take(">iqii")
        byproducts = [take(">iq") for _ in range(extra)]
        inputs = [take(">iq") for _ in range(take(">i")[0])]
        recipes.append((output, amount, cost, byproducts, inputs))
    assert stream.read() == b""
    return real, items, recipes


def varint(value):
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return out


def awr_fixture(version):
    data = bytearray(b"AWR" + bytes([version]))

    def put(*values):
        for value in values:
            data.extend(varint(value))

    put(3, 2)                  # three real items, two anchor-output groups
    put(2, 1)                  # real output handle 2
    if version >= 3:
        put(7)
    put(3, 1, 1)               # three outputs, workstation handle 1
    if version >= 2:
        put(1, 5, 3)           # also produces five of real handle 3
    put(1, 2, 1)               # consumes two of raw handle 1
    put(2, 1)                  # pseudo output handle 4
    if version >= 3:
        put(0)
    put(1, 0)                  # one tag, no workstation
    if version >= 2:
        put(0)
    put(1, 1, 1)               # consumes one real member
    return bytes(data)


class AdapterChecks(unittest.TestCase):
    reader = None

    def normalize_fixture(self, Graph, content=b"fixture"):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            awr, meta, binary = (directory / name for name in ("test.awr", "test.meta.tsv", "test.bin"))
            awr.write_bytes(content)
            meta.write_text("awr_sha256\t" + adapter.sha256(awr) + "\n", encoding="utf-8")
            adapter.normalize(Graph, awr, meta, binary)
            return read_normalized(binary.read_bytes())

    @staticmethod
    def graph(*recipes):
        class FixtureGraph:
            n_real, n_item = 3, 4

            def __init__(self, path):
                self.recipes = recipes

            def prepare(self):
                return self

            def counts(self):
                return {"n_real": self.n_real, "n_item": self.n_item, "n_recipe": len(self.recipes)}
        return FixtureGraph

    def test_old_reader_keeps_real_one_to_one_paid(self):
        real = SimpleNamespace(out=1, out_amt=1, inputs=[(0, 1)], ws=[0])
        tag = SimpleNamespace(out=3, out_amt=1, inputs=[(0, 1)], ws=[])
        _, _, recipes = self.normalize_fixture(self.graph(real, tag))
        self.assertEqual([r[2] for r in recipes], [1, 0])

    def test_weighted_multi_output_is_one_physical_column(self):
        real = SimpleNamespace(out=1, out_amt=3, inputs=[(0, 2)], ws=[0],
                               byproducts=[(2, 5)], cost=7)
        _, _, recipes = self.normalize_fixture(self.graph(real))
        self.assertEqual(recipes, [(1, 3, 7, [(2, 5)], [(0, 2)])])

    def test_reject_cost_overflow_and_malformed_outputs_or_tags(self):
        for overrides in ({"cost": 1 << 31}, {"cost": 0}, {"cost": True},
                          {"byproducts": [(1, 2)]}, {"byproducts": [(2, 0)]},
                          {"byproducts": [(3, 1)]}, {"extra_effect": True},
                          {"out": 3, "cost": 0, "byproducts": [(2, 1)], "ws": []}):
            values = dict(out=1, out_amt=1, inputs=[(0, 1)], ws=[0], byproducts=[], cost=1)
            values.update(overrides)
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                self.normalize_fixture(self.graph(SimpleNamespace(**values)))

    def test_actual_reader_versions(self):
        if self.reader is None:
            self.skipTest("provide --awrlib to test the actual v1-v3 reader")
        Graph = adapter.load_reader(self.reader)
        for version in (1, 2, 3):
            with self.subTest(version=version):
                real, items, recipes = self.normalize_fixture(Graph, awr_fixture(version))
                self.assertEqual((real, items), (3, 4))
                self.assertEqual(recipes[0], (1, 3, 7 if version == 3 else 1,
                                             [(2, 5)] if version >= 2 else [], [(0, 2)]))
                self.assertEqual(recipes[1], (3, 1, 0, [], [(0, 1)]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--awrlib", type=Path)
    args, unittest_args = parser.parse_known_args()
    AdapterChecks.reader = args.awrlib.resolve() if args.awrlib else None
    unittest.main(argv=[sys.argv[0], *unittest_args])
