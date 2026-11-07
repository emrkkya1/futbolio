import importlib.util
from pathlib import Path
import struct
import unittest
import zlib
import xml.etree.ElementTree as ET

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
        start = 35 * (42 * 4 + 1) + 1 + 21 * 4
        self.assertEqual(pixels[start:start + 3], bytes.fromhex("3a8638"))

    def test_readme_wordmark_is_outlined_and_uses_current_brand(self):
        root = Path(__file__).resolve().parents[2]
        svg = ET.parse(root / "docs/assets/futbolio-logo.svg").getroot()
        ns = {"svg": "http://www.w3.org/2000/svg"}
        self.assertEqual(svg.findall("svg:text", ns), [], "Do not rely on a viewer's installed fonts")
        paths = svg.findall("svg:path", ns)
        self.assertEqual(len(paths), 2)
        self.assertTrue(all(path.attrib["fill"].lower() == "#3a8638" for path in paths))
        self.assertIn("Kodchasan", svg.find("svg:desc", ns).text)

    def test_android_launchers_use_current_brand_and_adaptive_resource(self):
        root = Path(__file__).resolve().parents[2] / "android/src/main"
        android = "{http://schemas.android.com/apk/res/android}"
        app = ET.parse(root / "AndroidManifest.xml").getroot().find("application")
        for attribute in ("icon", "roundIcon"):
            self.assertEqual(app.attrib[android + attribute], "@mipmap/ic_futbolio")
        for resource in ("mipmap-anydpi/ic_futbolio.xml", "drawable/ic_futbolio.xml",
                         "drawable/ic_futbolio_foreground.xml", "drawable/tv_banner.xml"):
            paths = ET.parse(root / "res" / resource).getroot().iter("path")
            mark = [path for path in paths if path.attrib.get(android + "fillType") == "evenOdd"]
            self.assertEqual(len(mark), 1)
            self.assertEqual(mark[0].attrib[android + "fillColor"].lower(), "#3a8638")
        adaptive = ET.parse(root / "res/mipmap-anydpi-v26/ic_futbolio.xml").getroot()
        self.assertEqual(adaptive.find("foreground").attrib[android + "drawable"], "@drawable/ic_futbolio_foreground")


if __name__ == "__main__":
    unittest.main()
