"""Run the production detector against netd rule fixtures; no device changes.

Run: python3 -m unittest discover -s .github/tests
"""
from pathlib import Path
import shutil
import subprocess
import unittest


class DefaultNetworkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = (Path(__file__).resolve().parents[2] / "service.sh").read_text()
        start = source.index("detect_auto_table() {\n")
        end = source.index("\n}", start) + len("\n}")
        cls.detector = source[start:end]

    def detect(self, rules):
        # Only the detector is evaluated, so service startup/iptables never run.
        script = "fixture_ip() { cat; }\nip=fixture_ip\n" + self.detector + "\ndetect_auto_table\n"
        result = subprocess.run(
            [shutil.which("sh"), "-c", script], input=rules,
            capture_output=True, text=True, timeout=5,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def test_full_range_wifi_default(self):
        rules = "29998: from all fwmark 0x0/0xffff iif lo uidrange 0-2147483647 lookup wlan0 \n"
        self.assertEqual(self.detect(rules), "wlan0")

    def test_full_range_mobile_default(self):
        rules = "29998: from all fwmark 0x0/0xffff iif lo uidrange 0-2147483647 lookup rmnet_data2\n"
        self.assertEqual(self.detect(rules), "rmnet_data2")

    def test_narrow_uid_routes_do_not_override_global_default(self):
        rules = (
            "29040: from all fwmark 0x0/0xffff iif lo uidrange 10147-10147 lookup other0\n"
            "29040: from all fwmark 0x0/0xffff iif lo uidrange 10000-19999 lookup tun0\n"
            "29998: from all fwmark 0x0/0xffff iif lo uidrange 0-2147483647 lookup wlan0\n"
        )
        self.assertEqual(self.detect(rules), "wlan0")

    def test_legacy_default_with_trailing_whitespace(self):
        rules = "31000: from all fwmark 0x0/0xffff iif lo lookup wlan0 \t\n"
        self.assertEqual(self.detect(rules), "wlan0")

    def test_numeric_table_is_returned_for_existing_resolver(self):
        rules = "29998: from all fwmark 0x0/0xffff iif lo uidrange 0-2147483647 lookup 1026\n"
        self.assertEqual(self.detect(rules), "1026")

    def test_only_narrow_uid_routes_yield_no_default(self):
        rules = "29040: from all fwmark 0x0/0xffff iif lo uidrange 10147-10147 lookup tun0\n"
        self.assertEqual(self.detect(rules), "")

    def test_unrelated_marks_are_ignored(self):
        rules = (
            "15998: from all fwmark 0x10064/0x1ffff iif lo uidrange 0-2147483647 lookup other0\n"
            "29998: from all fwmark 0x0/0xffff iif lo uidrange 0-2147483647 lookup wlan0\n"
        )
        self.assertEqual(self.detect(rules), "wlan0")

    def test_empty_rules_yield_no_default(self):
        self.assertEqual(self.detect(""), "")


if __name__ == "__main__":
    unittest.main()
