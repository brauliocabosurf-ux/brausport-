import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "dump_nsc", Path(__file__).with_name("dump-network-security.py")
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

MANIFEST = "A: http://schemas.android.com/apk/res/android:networkSecurityConfig(0x01010527)=@0x7f120000"
RESOURCE = "resource 0x7f120000 xml/network_security_config\n  () (file) {path} type=XML\n"


class ResolveNetworkSecurityTest(unittest.TestCase):
    def test_original_and_shortened_paths(self):
        for path in ("res/xml/network_security_config.xml", "res/aB.xml"):
            with self.subTest(path=path):
                self.assertEqual(module.resolve_path(MANIFEST, RESOURCE.format(path=path)), path)

    def test_follows_manifest_id_not_resource_name(self):
        resources = (
            "resource 0x7f120001 xml/network_security_config\n"
            "  () (file) res/wrong.xml type=XML\n"
            "resource 0x7f120000 xml/a\n  () (file) res/right.xml type=XML\n"
        )
        self.assertEqual(module.resolve_path(MANIFEST, resources), "res/right.xml")

    def test_missing_or_ambiguous_reference_is_rejected(self):
        for manifest in ("", MANIFEST + "\n" + MANIFEST):
            with self.subTest(manifest=manifest), self.assertRaises(ValueError):
                module.resolve_path(manifest, RESOURCE.format(path="res/a.xml"))

    def test_wrong_resource_id_is_rejected(self):
        with self.assertRaises(ValueError):
            module.resolve_path(MANIFEST, RESOURCE.replace("0x7f120000", "0x7f120001"))

    def test_configuration_override_is_rejected(self):
        resources = RESOURCE.format(path="res/a.xml") + "  (v28) (file) res/b.xml type=XML\n"
        with self.assertRaises(ValueError):
            module.resolve_path(MANIFEST, resources)

    def test_alias_or_missing_file_is_rejected(self):
        for value in ("@0x7f120001", "(file) res/a.xml type=PNG", ""):
            with self.subTest(value=value), self.assertRaises(ValueError):
                module.resolve_path(MANIFEST, "resource 0x7f120000 xml/nsc\n  () " + value)


if __name__ == "__main__":
    unittest.main()
