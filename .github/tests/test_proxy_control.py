"""Exercise the proxy_control applet (native/jni/proxy_control.cpp) against a fake service.

The applet is a client of the service's control FIFO: it writes `REQ <reply-fifo> <verb>` and prints the answer
(`<rc>\\n` + `o <stdout>` / `e <stderr>` lines). The fake daemon below speaks that protocol, so nothing here touches
iptables or a device.

Run: python3 -m unittest discover -s .github/tests
A real service (root only, touches the network stack, needs the whole helper) is exercised by LiveServiceTests when
MV_LIVE_SERVICE_TEST=1.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "native"
CXX = shutil.which("g++") or shutil.which("c++")


def build_applet(tmp: Path, data: Path, stub: Path) -> Path:
    exe = tmp / "proxy_control"
    subprocess.run(
        [CXX, "-std=c++17", "-Wall", "-Wextra",
         f'-DMV_DATADIR="{data}"', f'-DMV_STUB_DIR="{stub}"',
         str(NATIVE / "jni/proxy_control.cpp"), str(NATIVE / "tests/pc_main.cpp"), "-o", str(exe)],
        check=True, capture_output=True, text=True)
    return exe


class FakeService:
    """Reads the control FIFO like the service and answers `REQ` lines from a verb -> reply table."""

    def __init__(self, fifo: Path, answers: dict):
        self.answers = answers
        self.seen = []
        self.fd = os.open(fifo, os.O_RDWR)
        self.stop = False
        self.th = threading.Thread(target=self.loop, daemon=True)
        self.th.start()

    def loop(self):
        buf = b""
        while not self.stop:
            try:
                chunk = os.read(self.fd, 4096)
            except OSError:
                return
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                line = line.decode()
                if not line.startswith("REQ "):
                    continue
                _, reply, verb = line.split(" ", 2)
                self.seen.append(verb)
                answer = self.answers.get(verb)
                if answer is None:
                    continue                      # never answers: the client must not hang forever
                w = os.open(reply, os.O_WRONLY | os.O_NONBLOCK)
                os.write(w, answer.encode())
                os.close(w)

    def close(self):
        if self.stop:
            return
        self.stop = True
        try:
            os.write(self.fd, b"\n")              # wake the reader
        except OSError:
            pass
        self.th.join(timeout=2)
        os.close(self.fd)


@unittest.skipUnless(CXX, "needs a C++ compiler")
class ProxyControlTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        root = Path(cls.temp.name)
        cls.data = root / "data"
        cls.stub = root / "stub"
        cls.data.mkdir()
        (cls.stub / "run").mkdir(parents=True)
        cls.exe = build_applet(root, cls.data, cls.stub)
        cls.fifo = cls.stub / "run/control.pipe"

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.addCleanup(lambda: self.fifo.exists() and self.fifo.unlink())
        (self.data / "proxy_control.log").unlink(missing_ok=True)

    def start_service(self, answers):
        os.mkfifo(self.fifo)
        svc = FakeService(self.fifo, answers)
        self.addCleanup(svc.close)
        return svc

    def run_verb(self, *args):
        return subprocess.run([str(self.exe), *args], capture_output=True, text=True, timeout=20)

    def log(self):
        p = self.data / "proxy_control.log"
        return p.read_text() if p.exists() else ""

    def test_status_words_and_exit_codes(self):
        for word, rc in (("running", 0), ("stopped", 1), ("crashed", 2)):
            with self.subTest(word):
                svc = self.start_service({"status": f"{rc}\no {word}\n"})
                r = self.run_verb("status")
                self.assertEqual((r.stdout, r.returncode), (word + "\n", rc), r.stderr)
                self.assertEqual(svc.seen, ["status"])
                svc.close()
                self.fifo.unlink()

    def test_status_without_service_is_stopped(self):
        r = self.run_verb("status")                      # no FIFO at all
        self.assertEqual((r.stdout, r.returncode), ("stopped\n", 1))
        os.mkfifo(self.fifo)                              # FIFO left behind, nobody reads it (ENXIO)
        r = self.run_verb("status")
        self.assertEqual((r.stdout, r.returncode), ("stopped\n", 1))

    def test_other_verbs_without_service_fail_and_log(self):
        r = self.run_verb("restart")
        self.assertEqual((r.stdout, r.returncode), ("", 1))
        self.assertIn("service is not running", self.log())

    def test_stdout_only_carries_the_answer_stderr_lines_go_to_the_log(self):
        self.start_service({"start": "1\ne no config\n"})
        r = self.run_verb("start")
        self.assertEqual((r.stdout, r.stderr, r.returncode), ("", "", 1))
        self.assertIn("start: no config", self.log())

    def test_success_output_and_verb_forwarded(self):
        svc = self.start_service({"start": "0\no started\n", "reload": "0\no reloaded\n", "reapply": "0\n"})
        self.assertEqual(self.run_verb("start").stdout, "started\n")
        self.assertEqual(self.run_verb("reload").stdout, "reloaded\n")
        r = self.run_verb("reapply")
        self.assertEqual((r.stdout, r.returncode), ("", 0))
        self.assertEqual(svc.seen, ["start", "reload", "reapply"])

    def test_unknown_verb_is_a_usage_error_and_sends_nothing(self):
        svc = self.start_service({})
        for args in (("bogus",), ()):
            r = self.run_verb(*args)
            self.assertEqual(r.returncode, 2)
            self.assertEqual(r.stdout, "")
        self.assertEqual(svc.seen, [])

    def test_reply_fifo_is_removed(self):
        self.start_service({"status": "0\no running\n"})
        self.run_verb("status")
        self.assertEqual([p.name for p in self.fifo.parent.iterdir()], ["control.pipe"])

    def test_malformed_answer_is_a_failure(self):
        self.start_service({"stop": "garbage\n"})
        r = self.run_verb("stop")
        self.assertEqual((r.stdout, r.returncode), ("", 1))
        self.assertIn("malformed", self.log())


@unittest.skipUnless(os.environ.get("MV_LIVE_SERVICE_TEST") == "1" and hasattr(os, "geteuid") and os.geteuid() == 0 and CXX,
                     "set MV_LIVE_SERVICE_TEST=1 and run as root (starts the real service)")
class LiveServiceTests(unittest.TestCase):
    """Real service + real applet on a scratch runtime dir. No xray is started (no config), so no firewall change."""

    def test_service_round_trip_and_stop(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        data, stub, legacy = tmp / "data", tmp / "stub", tmp / "legacy"
        (tmp / "mod/bin").mkdir(parents=True)
        data.mkdir()
        (tmp / "mod/module.prop").write_text("debug=0\n")
        defs = [f'-DMV_DATADIR="{data}"', f'-DMV_STUB_DIR="{stub}"', f'-DMV_LEGACY_STUB_DIR="{legacy}"',
                '-DMV_XTABLES_LOCK="/nonexistent"']
        objs = []
        for src in sorted((NATIVE / "jni/service").glob("*.c")):
            o = tmp / (src.name + ".o")
            subprocess.run([shutil.which("gcc"), "-std=gnu11", "-D_GNU_SOURCE", "-w", *defs, "-c", str(src), "-o", str(o)], check=True)
            objs.append(str(o))
        for src in sorted((NATIVE / "jni").glob("*.cpp")):
            o = tmp / (src.name + ".o")
            subprocess.run([CXX, "-std=c++17", "-w", *defs, "-c", str(src), "-o", str(o)], check=True)
            objs.append(str(o))
        helper = tmp / "mod/bin/xhuskydg_helper"
        subprocess.run([CXX, *objs, "-o", str(helper), "-lpthread"], check=True)

        svc = subprocess.Popen([str(helper), "service", "-m", str(tmp / "mod"), "-f"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(lambda: svc.poll() is None and svc.kill())
        pipe = stub / "run/control.pipe"
        for _ in range(100):
            if pipe.exists():
                break
            threading.Event().wait(0.1)
        self.assertTrue(pipe.exists())

        def pc(*a):
            return subprocess.run([str(helper), "proxy_control", *a], capture_output=True, text=True, timeout=30)

        r = pc("status")
        self.assertEqual((r.stdout, r.returncode), ("stopped\n", 1))
        r = pc("start")                                  # no config.v2.json -> refused, nothing started
        self.assertEqual((r.stdout, r.returncode), ("", 1))
        self.assertIn("no config", (data / "proxy_control.log").read_text())
        # The service is our child here, so it stays a zombie (kill(pid, 0) still succeeds) until we wait() for it.
        # A real, daemonized service is reparented to init, which does that by itself.
        stopper = subprocess.Popen([str(helper), "service", "--stop"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(svc.wait(timeout=30), 0)
        out, err = stopper.communicate(timeout=60)
        self.assertEqual(stopper.returncode, 0, err)
        self.assertFalse(stub.exists())


if __name__ == "__main__":
    unittest.main()
