#!/usr/bin/env python3
"""Regression tests for the Termux dashboard (standard library only).

Run from the repo root or termux/:

    python3 -m unittest termux/test_dashboard.py -v

These tests drive the real CLI in a throwaway CHILDGUARD_HOME so they check
the same code path a parent uses, not a reimplementation.
"""

import importlib.util
import json
import os
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
DASHBOARD = os.path.join(HERE, "dashboard.py")


def run_dashboard(home, *args, umask=0o022):
    env = dict(os.environ)
    env["CHILDGUARD_HOME"] = home
    env.pop("CHILDGUARD_SERVER", None)
    env.pop("CHILDGUARD_TOKEN", None)

    def _set_umask():
        os.umask(umask)

    return subprocess.run(
        [sys.executable, DASHBOARD, *args],
        env=env,
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        check=False,
        preexec_fn=_set_umask if os.name == "posix" else None,
    )


class ConfigPermissionTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp(prefix="cg-test-home-")

    def tearDown(self):
        for root, dirs, files in os.walk(self.home, topdown=False):
            for name in files:
                os.unlink(os.path.join(root, name))
            for name in dirs:
                os.rmdir(os.path.join(root, name))
        os.rmdir(self.home)

    def test_fresh_config_is_owner_only_even_with_permissive_umask(self):
        proc = run_dashboard(
            self.home, "--configure",
            "--server", "http://127.0.0.1:38080",
            "--token", "supersecret-token",
        )
        self.assertEqual(0, proc.returncode, proc.stderr)

        cfg_path = os.path.join(self.home, "config.json")
        mode = stat.S_IMODE(os.stat(cfg_path).st_mode)
        self.assertEqual(0o600, mode, f"config.json is {oct(mode)}, expected 0o600")
        self.assertEqual(
            0o700,
            stat.S_IMODE(os.stat(self.home).st_mode),
            "home directory must be owner-only",
        )
        with open(cfg_path, encoding="utf-8") as f:
            cfg = json.load(f)
        self.assertEqual("supersecret-token", cfg["token"])
        self.assertFalse(
            os.path.exists(cfg_path + ".tmp"),
            "atomic write must not leave a temp file behind",
        )

    def test_reconfigure_keeps_mode_and_normalizes_trailing_slash(self):
        run_dashboard(self.home, "--configure",
                      "--server", "http://127.0.0.1:38080/",
                      "--token", "first")
        proc = run_dashboard(self.home, "--configure",
                             "--server", "http://127.0.0.1:38081///",
                             "--token", "second")
        self.assertEqual(0, proc.returncode, proc.stderr)

        cfg_path = os.path.join(self.home, "config.json")
        self.assertEqual(0o600, stat.S_IMODE(os.stat(cfg_path).st_mode))
        with open(cfg_path, encoding="utf-8") as f:
            cfg = json.load(f)
        self.assertEqual("http://127.0.0.1:38081", cfg["server"])
        self.assertEqual("second", cfg["token"])

    def _load_dashboard(self):
        os.environ["CHILDGUARD_HOME"] = self.home
        try:
            spec = importlib.util.spec_from_file_location("cg_dashboard_test", DASHBOARD)
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            return mod
        finally:
            os.environ.pop("CHILDGUARD_HOME", None)

    def test_idle_gap_is_not_counted_as_screen_time(self):
        dd = self._load_dashboard()
        day = 24 * 3600 * 1000
        # Newest first: this is the order sync inserts, and the old query had
        # no ORDER BY, so every gap was negative and screen time was always 0.
        rows = [
            ("com.whatsapp", "screen_text", "", 30_000 + day),
            ("com.whatsapp", "screen_text", "", 30_000),
            ("com.whatsapp", "screen_text", "", 0),
        ]
        total, duration = dd.compute_screen_durations(rows)
        self.assertEqual(3, total["com.whatsapp"])
        self.assertEqual(
            30_000 + dd.MAX_SESSION_GAP_MS,
            duration["com.whatsapp"],
            "an overnight gap must not be credited as 24 h of use",
        )

        _, tight = dd.compute_screen_durations(rows, max_gap_ms=60_000)
        self.assertEqual(30_000 + 60_000, tight["com.whatsapp"])

    def test_sync_without_a_tty_exits_cleanly(self):
        run_dashboard(self.home, "--configure",
                      "--server", "http://127.0.0.1:1",
                      "--token", "x")
        proc = run_dashboard(self.home, "--sync")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertNotIn("Traceback", proc.stderr)
        self.assertIn("non-interactive terminal", proc.stdout)

    def test_cleanup_rejects_non_numeric_days(self):
        # `do_cleanup` does int(days); argparse must reject the value instead
        # of letting a traceback escape to the terminal.
        proc = run_dashboard(self.home, "--cleanup", "soon")
        self.assertNotEqual(0, proc.returncode)
        self.assertNotIn("Traceback", proc.stderr)
        self.assertIn("invalid int value", proc.stderr)

    def test_terminal_escape_sequences_are_neutralized(self):
        dd = self._load_dashboard()
        attacks = [
            "hello\x1b[2Jworld",              # clear screen
            "clip\x1b]52;c;ZWNobw==\x07",     # OSC 52 clipboard write
            "bidi\u202eevil\u202c",           # right-to-left override
            "bell\x07 and tab\t",              # other controls
        ]
        for raw in attacks:
            out = dd.safe_text(raw)
            for bad in ("\x1b", "\x07", "\t", "\u202e"):
                self.assertNotIn(bad, out, f"control survived in {out!r}")
        self.assertEqual("hello", dd.safe_text("hello", 5))
        self.assertNotIn("\x1b", dd.pkg_name("evil\x1b[2J.app"))

    def test_export_bundle_never_contains_the_token(self):
        run_dashboard(self.home, "--configure",
                      "--server", "http://127.0.0.1:38080",
                      "--token", "token-that-must-not-leak")
        proc = run_dashboard(self.home, "--export")
        self.assertEqual(0, proc.returncode, proc.stderr)

        exports = os.path.join(self.home, "exports")
        bundles = [n for n in os.listdir(exports) if n.endswith(".tar.gz")]
        self.assertEqual(1, len(bundles), bundles)
        bundle = os.path.join(exports, bundles[0])
        self.assertEqual(0o600, stat.S_IMODE(os.stat(bundle).st_mode))

        leaked = []
        unsafe = []
        with tarfile.open(bundle, "r:gz") as tar:
            for member in tar.getmembers():
                if not member.isfile():
                    continue
                data = tar.extractfile(member).read()
                if b"token-that-must-not-leak" in data:
                    leaked.append(member.name)
                if stat.S_IMODE(member.mode) != 0o600:
                    unsafe.append((member.name, oct(member.mode)))
        self.assertEqual([], leaked, "export must not include the parent token")
        self.assertEqual([], unsafe, "archive members must extract owner-only")

        leftovers = [n for n in os.listdir(exports) if n != bundles[0]]
        self.assertEqual([], leftovers, f"temp export files left behind: {leftovers}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
