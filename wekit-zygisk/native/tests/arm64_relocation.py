#!/usr/bin/env python3
"""Execute original and relocated A64 functions with Unicorn and compare registers.

From the repository root, with uv and rustc on PATH, run:
    uv run --locked --project wekit-zygisk --group test python wekit-zygisk/native/tests/arm64_relocation.py
This tests instruction semantics, not Android's cache/BTI enforcement or ART.
"""
import pathlib
import random
import struct
import subprocess
import tempfile
import unittest

from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import *

SOURCE = 0x1000000
STOP = 0x1800000
DATA = SOURCE + 0x40000
CALLEE = SOURCE + 0x8000
STACK = 0x7000000
NEAR = SOURCE + 0x200000
FAR = 0x900000000
RET = 0xD65F03C0
NOP = 0xD503201F
REGS = [globals()[f"UC_ARM64_REG_X{i}"] for i in range(31)]
VREGS = [globals()[f"UC_ARM64_REG_Q{i}"] for i in range(32)]


def words(*code):
    return struct.pack("<" + "I" * len(code), *code)


def branch(pc, target, link=False):
    return (0x94000000 if link else 0x14000000) | (((target - pc) // 4) & 0x3FFFFFF)


def literal(opcode, target, register):
    return opcode | ((((target - SOURCE) // 4) & 0x7FFFF) << 5) | register


def execute(start, segments, values):
    machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    pages = set()
    for address, data in segments + [(STOP, words(NOP)), (STACK, bytes(0x20000))]:
        for page in range(address & ~4095, (address + len(data) + 4095) & ~4095, 4096):
            if page not in pages:
                machine.mem_map(page, 4096)
                pages.add(page)
        machine.mem_write(address, data)
    for register, value in zip(REGS + VREGS, values):
        machine.reg_write(register, value)
    machine.reg_write(UC_ARM64_REG_X30, STOP)
    machine.reg_write(UC_ARM64_REG_SP, STACK + 0x10000)
    machine.reg_write(UC_ARM64_REG_NZCV, values[-1])
    # Enable floating point/SIMD in the emulated execution context.
    machine.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
    machine.emu_start(start, STOP, count=10000)
    assert machine.reg_read(UC_ARM64_REG_PC) == STOP, "function failed to return"
    return [machine.reg_read(r) for r in REGS + VREGS + [UC_ARM64_REG_SP, UC_ARM64_REG_NZCV]]


class RelocationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.driver = pathlib.Path(cls.temp.name) / "relocate"
        source = pathlib.Path(__file__).with_name("relocation_driver.rs")
        subprocess.run(["rustc", "--edition=2024", str(source), "-o", str(cls.driver)], check=True)
        cls.rng = random.Random(173)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def generate(self, code, count, destination, replacement=None):
        args = [str(self.driver), f"{SOURCE:x}", f"{destination:x}", str(count), code.hex()]
        if replacement is not None:
            args.append(f"{replacement:x}")
        result = subprocess.run(args, check=True, capture_output=True, text=True)
        return [(int(address, 16), bytes.fromhex(data))
                for address, data in (line.split() for line in result.stdout.splitlines())]

    def compare(self, code, count, extra=(), registers=None):
        for destination in [NEAR, FAR]:
            relocated = self.generate(code, count, destination)
            for iteration in range(5):
                values = [self.rng.getrandbits(64) for _ in REGS]
                values += [self.rng.getrandbits(128) for _ in VREGS]
                values += [self.rng.randrange(16) << 28]
                for reg, value in (registers or {}).items():
                    values[reg] = value
                segments = [(SOURCE, code), *extra]
                original = execute(SOURCE, segments, values)
                # Poison the overwritten bytes: internal branches and overlapping
                # literal loads must not accidentally rely on the pristine entry.
                patched = [(SOURCE, bytes(count * 4) + code[count * 4:]), *extra, *relocated]
                actual = execute(destination, patched, values)
                with self.subTest(destination=hex(destination), iteration=iteration):
                    for reg, expected, got in zip(REGS + VREGS + [UC_ARM64_REG_SP, UC_ARM64_REG_NZCV], original, actual):
                        self.assertEqual(expected, got, f"register {reg}")

    def test_arithmetic_stack_and_flags(self):
        self.compare(words(0xA9BF7BFD, 0x910003FD, 0xAB010000, 0xA8C17BFD, RET), 4)

    def test_adr_and_adrp_both_directions(self):
        for page in [False, True]:
            for imm in [-0x2345, -1, 0, 0x12345]:
                bits = imm & 0x1FFFFF
                op = (0x90000000 if page else 0x10000000) | ((bits & 3) << 29) | ((bits >> 2) << 5) | 0
                self.compare(words(op, RET), 1)

    def test_live_integer_simd_and_prefetch_literals(self):
        for opcode in [0x18000000, 0x58000000, 0x98000000, 0x1C000000, 0x5C000000, 0x9C000000, 0xD8000000]:
            for register in [0, 17, 31]:
                with self.subTest(opcode=hex(opcode), register=register):
                    code = words(literal(opcode, DATA, register), RET)
                    self.compare(code, 1, [(DATA, bytes.fromhex("efcdab89674523018182838485868788"))])

    def test_conditional_branches_and_internal_destinations(self):
        for op in [0xB4000000, 0xB5000000, 0xB6080000, 0xB7080000, 0x54000000, 0x54000001, 0x5400000E, 0x5400000F]:
            code = words(op | (3 << 5), 0x91000442, branch(SOURCE + 8, SOURCE + 16), 0x91000842, RET)
            for value in [0, 2, 1 << 33]:
                self.compare(code, 4, registers={0: value})

    def test_backward_internal_loop(self):
        self.compare(words(0xF1000400, 0x54FFFFE1, 0x91000821, RET), 3, registers={0: 4})

    def test_always_taken_branch_skips_inline_data(self):
        for condition in [0xE, 0xF]:  # AL and NV are both always taken in A64.
            for prefix in [(), (0xD503245F,)]:
                with self.subTest(condition=condition, landing_pad=bool(prefix)):
                    code = words(*prefix, 0x54000000 | (2 << 5) | condition,
                                 0, 0xD2800540, RET)
                    self.compare(code, 4 + len(prefix))

    def test_always_taken_branch_preserves_other_incoming_paths(self):
        for condition in [0xE, 0xF]:
            # CBZ can still reach the instruction after the always-taken branch.
            code = words(0xB4000000 | (2 << 5),
                         0x54000000 | (3 << 5) | condition,
                         0x91000821, branch(SOURCE + 12, SOURCE + 16), RET)
            for value in [0, 1]:
                self.compare(code, 5, registers={0: value})

    def test_external_branch_preserves_ip_registers(self):
        self.compare(words(branch(SOURCE, CALLEE), NOP, RET), 1,
                     [(CALLEE, words(0x91000821, RET))])

    def test_existing_absolute_entry_veneer(self):
        code = words(0x58000051, 0xD61F0220) + struct.pack("<Q", CALLEE) + words(RET)
        self.compare(code, 4, [(CALLEE, words(0x91000821, RET))])

    def test_external_call_returns_to_backup(self):
        self.compare(words(0xA9BF7BFD, 0x910003FD, branch(SOURCE + 8, CALLEE, True),
                           0x91000400, 0xA8C17BFD, RET), 4,
                     [(CALLEE, words(0x91000800, RET))])

    def test_get_pc_idiom(self):
        self.compare(words(0xAA1E03E9, branch(SOURCE + 4, SOURCE + 8, True),
                           0xAA1E03E0, 0xAA0903FE, RET), 4)

    def test_overlapping_literal_and_embedded_data(self):
        for opcode, data in [(0x18000000, words(0, 0)), (0x58000000, words(0x12345678, 0)),
                             (0x98000000, words(0x80000001, 0)),
                             (0x9C000000, words(0xDEADBEEF, 0, 0, 0))]:
            code = words(literal(opcode, SOURCE + 8, 0), branch(SOURCE + 4, SOURCE + 8 + len(data))) + data + words(RET)
            self.compare(code, 4)

    def test_entry_redirect_and_backup_with_landing_instructions(self):
        for pad in [NOP, 0xD503245F, 0xD503249F, 0xD50324DF, 0xD503233F, 0xD503237F]:
            for replacement in [CALLEE, FAR]:
                code = words(pad, NOP, NOP, NOP, NOP, RET)
                generated = self.generate(code, 5, NEAR, replacement)
                values = [0] * (len(REGS) + len(VREGS) + 1)
                output = execute(SOURCE, [(SOURCE, code), *generated,
                                 (replacement, words(0xD2800540, RET))], values)
                self.assertEqual(output[0], 42)
                if pad != NOP:
                    self.assertEqual(generated[-1][1][:4], words(0xD50324DF))


if __name__ == "__main__":
    unittest.main(verbosity=2)
