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

    def test_screen_time_skips_non_numeric_timestamps(self):
        dd = self._load_dashboard()
        rows = [
            ("com.whatsapp", "screen_text", "", 20_000),
            ("com.whatsapp", "screen_text", "", "not-a-timestamp"),
            ("com.whatsapp", "screen_text", "", None),
            ("com.whatsapp", "screen_text", "", 10_000),
        ]
        # A hand-edited DB or an old schema can hold text/NULL ts; the sort
        # used to raise TypeError and kill the screen-time view.
        total, duration = dd.compute_screen_durations(rows)
        self.assertEqual(2, total["com.whatsapp"])
        self.assertEqual(10_000, duration["com.whatsapp"])
        # Durations feed integer arithmetic in screen_time(); a float here
        # raised "can't multiply sequence by non-int of type 'float'".
        self.assertIsInstance(duration["com.whatsapp"], int)
        self.assertEqual("#", "#" * (duration["com.whatsapp"] // 60000 + 1))

    def test_show_logs_survives_null_data(self):
        dd = self._load_dashboard()
        con = dd.ensure_env()
        con.execute(
            "INSERT INTO events(id,ts,type,package,data,child) VALUES(?,?,?,?,?,?)",
            ("null-row", 1, "keylog", "com.whatsapp", None, "c"),
        )
        con.commit()
        # `data.lower()` used to raise AttributeError on NULL and abort the log.
        try:
            dd.show_logs(con, None)
        finally:
            con.close()

    def test_sync_coerces_bad_types_from_server(self):
        dd = self._load_dashboard()
        con = dd.ensure_env()
        payload_events = {
            "events": [
                {"id": "bad-ts", "ts": "oops", "type": None,
                 "package": None, "data": None, "childId": None},
                {"ts": None, "type": "keylog", "package": "p", "data": "x"},
            ]
        }
        payload_alerts = {"alerts": [{"id": "bad-alert", "ts": "x",
                                      "severity": None, "reason": None,
                                      "detail": {"nested": 1}}]}

        def fake_get(url, token, timeout=10):
            return payload_alerts if url.endswith("/api/alerts?limit=500") else payload_events

        original = dd.http_get_json
        dd.http_get_json = fake_get
        try:
            added = dd.sync_from_server(con, {"server": "http://x", "token": "t"},
                                        verbose=False)
        finally:
            dd.http_get_json = original
        self.assertEqual(3, added)
        rows = con.execute("SELECT ts,type,package,data,child FROM events "
                           "ORDER BY id").fetchall()
        for ts, typ, pkg, data, child in rows:
            self.assertIsInstance(ts, int, f"ts not coerced: {ts!r}")
            self.assertIsInstance(typ, str)
            self.assertIsInstance(pkg, str)
            self.assertIsInstance(data, str)
            self.assertIsInstance(child, str)
        con.close()

    def test_http_get_json_rejects_an_oversized_response(self):
        dd = self._load_dashboard()

        class FakeResponse:
            def __init__(self, chunks):
                self._chunks = list(chunks)

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self, n=-1):
                return self._chunks.pop(0) if self._chunks else b""

        chunk = b"x" * 65536
        count = (dd.MAX_RESPONSE_BYTES // len(chunk)) + 2
        original = dd.urllib.request.urlopen
        dd.urllib.request.urlopen = lambda req, timeout=10: FakeResponse(
            [chunk] * count
        )
        try:
            with self.assertRaises(ValueError) as ctx:
                dd.http_get_json("http://x/api/events", "t")
            self.assertIn("exceeded", str(ctx.exception))
        finally:
            dd.urllib.request.urlopen = original

    def test_blocklist_rejects_out_of_range_index(self):
        dd = self._load_dashboard()
        con = dd.ensure_env()
        dd.set_cfg(con, dd.BLOCKLIST_KEY, ["com.keep", "com.also-keep"])
        answers = iter(["r", "0", "d"])  # 0 - 1 == -1 would pop the last item

        def fake_input(prompt=""):
            return next(answers)

        import builtins
        saved = builtins.input
        builtins.input = fake_input
        try:
            dd.manage_blocklist(con)
        finally:
            builtins.input = saved
        self.assertEqual(["com.keep", "com.also-keep"],
                         dd.get_cfg(con, dd.BLOCKLIST_KEY, []))
        con.close()

    def test_malformed_config_falls_back_to_defaults(self):
        cfg_path = os.path.join(self.home, "config.json")
        # Each of these parses as valid JSON but is not a config object; the
        # old load_config returned it verbatim and every command crashed in
        # cfg.get("...").
        for payload in ("[]", "null", '"just-a-string"',
                        '{"server": 5, "token": null}'):
            with open(cfg_path, "w", encoding="utf-8") as f:
                f.write(payload)
            proc = run_dashboard(self.home, "--doctor")
            self.assertNotIn(
                "Traceback", proc.stderr,
                f"malformed config {payload!r} crashed: {proc.stderr}",
            )
            # Defaults have an empty token, so the doctor stops at exit 5
            # without touching the network.
            self.assertEqual(5, proc.returncode, payload)

    def test_doctor_sanitizes_untrusted_server_url(self):
        cfg_path = os.path.join(self.home, "config.json")
        with open(cfg_path, "w", encoding="utf-8") as f:
            json.dump({"server": "http://127.0.0.1:1/\x1b[2JEVIL", "token": "x"}, f)
        proc = run_dashboard(self.home, "--doctor")
        # The dashboard's own output uses SGR colour codes; strip those, then
        # assert that the attacker-supplied clear-screen sequence is gone.
        import re
        plain = re.sub(r"\x1b\[[0-9;]*m", "", proc.stdout)
        self.assertNotIn("\x1b", plain, "control sequence reached the terminal")
        self.assertNotIn("\x1b[2J", proc.stdout)

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
