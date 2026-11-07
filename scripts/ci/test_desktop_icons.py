import importlib.util
from pathlib import Path
import struct
import unittest
import zlib

spec = importlib.util.spec_from_file_location("desktop_icons", Path(__file__).parents[1] / "desktop" / "icons.py")
icons = importlib.util.module_from_spec(spec)
spec.loader.exec_module(icons)


class DesktopIconTest(unittest.TestCase):
    def test_icon_is_deterministic_transparent_rgba_png(self):
        data = icons.png(42)
        self.assertEqual(data, icons.png(42))
        self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
        self.assertEqual(struct.unpack(">IIBBBBB", data[16:29]), (42, 42, 8, 6, 0, 0, 0))
        offset = 8
        compressed = bytearray()
        while offset < len(data):
            length = struct.unpack(">I", data[offset:offset + 4])[0]
            kind = data[offset + 4:offset + 8]
            payload = data[offset + 8:offset + 8 + length]
            crc = struct.unpack(">I", data[offset + 8 + length:offset + 12 + length])[0]
            self.assertEqual(crc, zlib.crc32(kind + payload) & 0xFFFFFFFF)
            if kind == b"IDAT":
                compressed.extend(payload)
            offset += 12 + length
        pixels = zlib.decompress(compressed)
        alpha = lambda x, y: pixels[y * (42 * 4 + 1) + 1 + x * 4 + 3]
        self.assertEqual(alpha(0, 0), 0)
        self.assertEqual(alpha(15, 12), 0)  # Transparent F, not background paint.
        self.assertEqual(alpha(21, 35), 255)


if __name__ == "__main__":
    unittest.main()
