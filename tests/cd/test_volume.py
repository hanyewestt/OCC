"""Resolve retained EBS identity independently of NVMe enumeration order."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class VolumeIdentityTests(unittest.TestCase):
    def test_adoption_never_authorizes_formatting_existing_filesystems(self):
        spec = importlib.util.spec_from_file_location("occ_volume", ROOT / "scripts/cd/volume.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertFalse(module.initialization_required("ext4", "verified", "verified", True))
        for kind, actual, expected, initialize in (("ext4", "old", "", True),
                                                   ("ext4", "wrong", "verified", True),
                                                   ("", "", "verified", True),
                                                   ("", "", "", False)):
            with self.assertRaises(ValueError):
                module.initialization_required(kind, actual, expected, initialize)
        self.assertTrue(module.initialization_required("", "", "", True))

    def test_volume_id_selects_the_matching_serial_not_nvme_order(self):
        spec = importlib.util.spec_from_file_location("occ_volume", ROOT / "scripts/cd/volume.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            sys_block = Path(directory)
            for device, serial in (("nvme1n1", "vol00000000000000001"),
                                   ("nvme3n1", "vol0123456789abcdef0")):
                path = sys_block / device / "device"
                path.mkdir(parents=True)
                (path / "serial").write_text(serial, encoding="ascii")
            self.assertEqual(module.resolve_device("vol-0123456789abcdef0", sys_block),
                             Path("/dev/nvme3n1"))
            with self.assertRaises(ValueError):
                module.resolve_device("vol-fffffffffffffffff", sys_block)

    def test_data_volume_has_its_own_retained_lifecycle(self):
        import yaml
        template = yaml.load((ROOT / "infra/aws/ec2-cd.yaml").read_text(encoding="utf-8"),
                             Loader=yaml.BaseLoader)
        resources = template["Resources"]
        self.assertIn("OccDataVolume", resources)
        self.assertEqual(resources["OccDataVolume"]["DeletionPolicy"], "Retain")
        self.assertEqual(resources["OccDataVolume"]["UpdateReplacePolicy"], "Retain")
        mappings = resources["OccHost"]["Properties"]["BlockDeviceMappings"]
        self.assertEqual([m["DeviceName"] for m in mappings], ["/dev/xvda"])


if __name__ == "__main__":
    unittest.main()
