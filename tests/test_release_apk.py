import io
import struct
import unittest

from scripts.verify_release_apk import (
    max_version_code,
    verify_native_alignment,
    version_code,
)


class ReleaseVersionTest(unittest.TestCase):
    def test_version_codes_order_patch_minor_and_major(self):
        self.assertEqual(
            [101, 199, 200, 9999, 10000],
            [
                version_code(tag, published=100)
                for tag in ("v0.1.1", "v0.1.99", "v0.2.0", "v0.99.99", "v1.0.0")
            ],
        )

    def test_rejects_old_or_ambiguous_versions(self):
        for tag in (
            "v0.1.0",
            "v0.0.0",
            "v01.0.0",
            "v1.0",
            "v1.1.100",
            "v1.1.1-rc",
            "v210001.0.0",
        ):
            with self.subTest(tag=tag):
                with self.assertRaises(ValueError):
                    version_code(tag, published=100)

    def test_gate_compares_against_published_code(self):
        # 等于已发布上限要拒绝,否则可以重发同一版本覆盖附件
        with self.assertRaises(ValueError):
            version_code("v0.1.2", published=102)
        self.assertEqual(103, version_code("v0.1.3", published=102))
        # 历史包复核时传 0,旧版本仍能解析
        self.assertEqual(100, version_code("v0.1.0", published=0))

    def test_max_published_ignores_unparsable_tags(self):
        tags = ["v0.1.2", "v0.1.10", "nightly", "v1.0.0", "v0.0.0", "v9.9.99"]
        self.assertEqual(90999, max_version_code(tags))
        self.assertEqual(0, max_version_code([]))

    def test_native_load_segment_requires_16_kb_alignment(self):
        header = struct.pack(
            "<16sHHIQQQIHHHHHH",
            b"\x7fELF\x02\x01",
            3,
            183,
            1,
            0,
            64,
            0,
            0,
            64,
            56,
            1,
            0,
            0,
            0,
        )
        for alignment in (4096, 16384):
            segment = struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, 64, 64, alignment)
            stream = io.BytesIO(header + segment)
            if alignment == 4096:
                with self.assertRaisesRegex(ValueError, "16 KB"):
                    verify_native_alignment(stream, "test.so")
            else:
                verify_native_alignment(stream, "test.so")


if __name__ == "__main__":
    unittest.main()
