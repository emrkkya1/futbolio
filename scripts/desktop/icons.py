"""Generate deterministic, transparent brand PNG/ICO icons using the stdlib only."""
import argparse
from pathlib import Path
import struct
import zlib


def png(size=256):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    pixels = bytearray()
    for y in range(size):
        pixels.append(0)
        for x in range(size):
            covered = 0
            for sy in range(4):
                for sx in range(4):
                    u, v = (x + (sx + .5) / 4) * 42 / size, (y + (sy + .5) / 4) * 42 / size
                    diamond = abs(u - 21) + abs(v - 21) <= 21
                    cutout = (13 <= u <= 19 and 10 <= v <= 32 or
                              19 <= u <= 29 and 10 <= v <= 16 or
                              19 <= u <= 27 and 20 <= v <= 26)
                    covered += diamond and not cutout
            pixels.extend((0x3A, 0x86, 0x38, round(255 * covered / 16)))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(bytes(pixels), 9)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    image = png()
    (args.output / "futbolio.png").write_bytes(image)
    header = struct.pack("<HHH", 0, 1, 1) + struct.pack("<BBBBHHII", 0, 0, 0, 0, 1, 32, len(image), 22)
    (args.output / "futbolio.ico").write_bytes(header + image)


if __name__ == "__main__":
    main()
