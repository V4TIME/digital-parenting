#!/usr/bin/env python3
"""Regression tests for the Termux dashboard (standard library only).

Run from the repo root or termux/:

    python3 -m unittest termux/test_dashboard.py -v

These tests drive the real CLI in a throwaway CHILDGUARD_HOME so they check
the same code path a parent uses, not a reimplementation.
"""

import contextlib
import importlib.util
import io
import json
import os
import stat
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import unittest
import unittest.mock

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

    def test_foreground_switch_credits_only_the_app_on_screen(self):
        dd = self._load_dashboard()
        # A 0-100 s, B 100-300 s. The old per-package gap credited WhatsApp
        # with the whole 300 s span *and* Instagram with its 100 s: 400 s of
        # screen time inside a 300 s window.
        rows = [
            ("com.whatsapp", "screen_active", "", 0),
            ("com.instagram.android", "screen_active", "", 100_000),
            ("com.instagram.android", "screen_active", "", 200_000),
            ("com.whatsapp", "screen_active", "", 300_000),
        ]
        total, duration = dd.compute_screen_durations(rows)
        self.assertEqual(2, total["com.whatsapp"])
        self.assertEqual(2, total["com.instagram.android"])
        self.assertEqual(100_000, duration["com.whatsapp"], "0-100 s belongs to WhatsApp")
        self.assertEqual(200_000, duration["com.instagram.android"], "100-300 s belongs to Instagram")
        self.assertEqual(
            300_000,
            sum(duration.values()),
            "attributed time must equal the timeline, never more",
        )

    def test_package_labels_skip_platform_segments(self):
        dd = self._load_dashboard()
        self.assertEqual("Instagram", dd.pkg_name("com.instagram.android"))
        self.assertEqual("Whatsapp", dd.pkg_name("com.whatsapp"))
        self.assertEqual("Messaging", dd.pkg_name("com.google.android.apps.messaging"))
        self.assertEqual("(system)", dd.pkg_name(""))

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


    def test_sync_sanitizes_zero_negative_and_future_timestamps(self):
        dd = self._load_dashboard()
        con = dd.ensure_env()
        now = int(time.time() * 1000)
        payload = {"events": [
            {"id": "zero", "ts": 0, "type": "keylog", "package": "p", "data": "zero"},
            {"id": "negative", "ts": -5, "type": "keylog", "package": "p", "data": "negative"},
            {"id": "future", "ts": now + 10 * 3600 * 1000,
             "type": "keylog", "package": "p", "data": "future"},
            {"id": "inf", "ts": float("inf"), "type": "keylog", "package": "p", "data": "inf"},
        ]}

        def fake_get(url, token, timeout=10):
            return payload if "/api/events" in url else {"alerts": []}

        original = dd.http_get_json
        dd.http_get_json = fake_get
        try:
            added = dd.sync_from_server(con, {"server": "http://x", "token": "t"},
                                        verbose=False)
        finally:
            dd.http_get_json = original
        self.assertEqual(4, added, "every row must still be stored")

        rows = dict(con.execute("SELECT id, ts FROM events").fetchall())
        after = int(time.time() * 1000)
        # Both collectors treat 0/negative/non-finite as "now"; the dashboard
        # used to store 1970 for 0 and crash the row loop on Infinity.
        for key in ("zero", "negative", "inf"):
            self.assertGreater(rows[key], now - 5000, f"{key} was filed at {rows[key]}")
            self.assertLessEqual(rows[key], after + 1000)
        # A phone one hour ahead is accepted; more than that is clock skew.
        self.assertGreater(rows["future"], after, "future ts was reset instead of clamped")
        self.assertLessEqual(rows["future"], after + dd.TS_FUTURE_ALLOWANCE_MS)
        con.close()

    def test_two_exports_in_the_same_second_do_not_overwrite(self):
        dd = self._load_dashboard()
        con = dd.ensure_env()
        con.execute(
            "INSERT INTO events(id,ts,type,package,data,child) VALUES(?,?,?,?,?,?)",
            ("e1", int(time.time() * 1000), "keylog", "com.whatsapp", "hello", "c"),
        )
        con.commit()

        # The bundle name only has second resolution. Two exports in one
        # second used to truncate the first bundle (O_TRUNC, same name).
        with unittest.mock.patch.object(dd.time, "strftime",
                                        return_value="20260101-000000"):
            with contextlib.redirect_stdout(io.StringIO()):
                dd.do_export(con)
                dd.do_export(con)
        con.close()

        exports = os.path.join(self.home, "exports")
        bundles = sorted(n for n in os.listdir(exports) if n.endswith(".tar.gz"))
        self.assertEqual(2, len(bundles), f"same-second export overwrote: {bundles}")
        self.assertIn("childguard_logs_20260101-000000.tar.gz", bundles)
        self.assertIn("childguard_logs_20260101-000000-2.tar.gz", bundles)
        for name in bundles:
            path = os.path.join(exports, name)
            self.assertEqual(0o600, stat.S_IMODE(os.stat(path).st_mode))
            with tarfile.open(path, "r:gz") as tar:
                self.assertIn("events.jsonl", tar.getnames())
                self.assertIn(b"hello", tar.extractfile("events.jsonl").read())
        leftovers = [n for n in os.listdir(exports) if not n.endswith(".tar.gz")]
        self.assertEqual([], leftovers, f"staging files left behind: {leftovers}")

    def _start_fake_server(self, routes):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                path = self.path.split("?")[0]
                raw = json.dumps(routes.get(path, {})).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def log_message(self, *args):
                pass

        srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        return srv.server_address[1]

    def _write_server_config(self, port, token="x"):
        with open(os.path.join(self.home, "config.json"), "w", encoding="utf-8") as f:
            json.dump({"server": f"http://127.0.0.1:{port}", "token": token}, f)

    def test_sync_survives_malformed_rows_and_response_shapes(self):
        port = self._start_fake_server({
            "/api/events": {"events": [None, 1, {
                "id": None, "ts": "x", "type": "keylog",
                "package": "p", "data": "kept",
            }]},
            "/api/alerts": {"alerts": [None, {
                "id": "a1", "ts": None, "severity": None,
                "reason": None, "detail": None,
            }]},
        })
        self._write_server_config(port)

        proc = run_dashboard(self.home, "--sync")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertNotIn("Traceback", proc.stderr + proc.stdout)
        self.assertIn("Sync complete: 2 new records", proc.stdout)

        logs = run_dashboard(self.home, "--logs", "kept")
        self.assertEqual(0, logs.returncode, logs.stderr)
        self.assertIn("kept", logs.stdout)

    def test_sync_survives_a_bare_list_response(self):
        # Not a dict at all: the old code crashed on ev_resp.get(...).
        port = self._start_fake_server({
            "/api/events": [],
            "/api/alerts": [],
        })
        self._write_server_config(port)
        proc = run_dashboard(self.home, "--sync")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertNotIn("Traceback", proc.stderr + proc.stdout)
        self.assertIn("Sync complete: 0 new records", proc.stdout)

    def test_doctor_survives_non_dict_children(self):
        port = self._start_fake_server({
            "/health": {"ok": True, "service": "fake"},
            "/api/stats": {
                "ok": True, "totalEvents": 1, "totalAlerts": 0,
                "children": ["x", None, {"id": "c", "online": True}],
            },
        })
        self._write_server_config(port)
        proc = run_dashboard(self.home, "--doctor")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertNotIn("Traceback", proc.stderr + proc.stdout)
        self.assertIn("1 child(ren), 1 online", proc.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
