"""Exercise the real control front-end against a temporary service FIFO.

Run: python3 -m unittest discover -s .github/tests
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class ProxyControlStartTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.data = root / "data"
        self.stub = root / "stub"
        self.data.mkdir()
        (self.stub / "run").mkdir(parents=True)
        self.fifo = self.stub / "run/control.pipe"
        os.mkfifo(self.fifo)
        self.fd = os.open(self.fifo, os.O_RDWR | os.O_NONBLOCK)
        self.addCleanup(os.close, self.fd)
        (self.stub / "run/control_loop.pid").write_text(str(os.getpid()))
        original = (Path(__file__).resolve().parents[2] / "proxy_control.sh").read_text()
        original = original.replace('DATADIR="/data/adb/magic_v2ray"', f'DATADIR="{self.data}"')
        original = original.replace('STUB_DIR=/dev/sysctl_stubs', f'STUB_DIR="{self.stub}"')
        self.script = root / "proxy_control.sh"
        self.script.write_text(original)
        (root / "module.prop").write_text("debug=0\n")

    def run_start(self):
        return subprocess.run(
            [shutil.which("sh"), str(self.script), "start"],
            capture_output=True, text=True, timeout=5,
        )

    def commands(self):
        try:
            return os.read(self.fd, 4096).decode().splitlines()
        except BlockingIOError:
            return []

    def test_current_config_without_legacy_file_sends_start(self):
        (self.data / "config.v2.json").write_text("{}")
        result = self.run_start()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "started\n")
        self.assertEqual(self.commands(), ["start", "wait"])

    def test_missing_current_config_rejects_legacy_file(self):
        (self.data / "config.json").write_text("{}")
        result = self.run_start()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("no config", (self.data / "proxy_control.log").read_text())
        self.assertEqual(self.commands(), [])

    def test_empty_current_config_is_rejected(self):
        (self.data / "config.v2.json").touch()
        result = self.run_start()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.commands(), [])

    def test_running_engine_does_not_send_duplicate_start(self):
        tracked = self.stub / "proc/xray"
        tracked.mkdir(parents=True)
        (tracked / "exe").symlink_to(shutil.which("sh"))
        result = self.run_start()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "running\n")
        self.assertEqual(self.commands(), [])


if __name__ == "__main__":
    unittest.main()
