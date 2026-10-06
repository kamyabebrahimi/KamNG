"""Check engine ELF architecture and 16 KiB load alignment before packaging."""
from pathlib import Path
import struct
ROOT = Path(__file__).resolve().parents[1]
for abi, machine in {"arm64-v8a":183, "armeabi-v7a":40, "x86_64":62, "x86":3}.items():
    for library in ["libkamng_awg.so", "libcottendns_client.so"]:
        file = ROOT / "V2rayNG/app/src/main/jniLibs" / abi / library
        data = file.read_bytes()
        assert data[:4] == b"\x7fELF", file
        assert data[5] == 1, file
        assert struct.unpack_from("<H", data, 18)[0] == machine, file
        bits = data[4]
        if bits == 2:
            start = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            alignment_offset, offset_offset, address_offset = 48, 8, 16
            value_format = "<Q"
        else:
            start = struct.unpack_from("<I", data, 28)[0]
            size, count = struct.unpack_from("<HH", data, 42)
            alignment_offset, offset_offset, address_offset = 28, 4, 8
            value_format = "<I"
        loads = 0
        for index in range(count):
            header = start + index * size
            if struct.unpack_from("<I", data, header)[0] != 1:
                continue
            loads += 1
            alignment = struct.unpack_from(value_format, data, header + alignment_offset)[0]
            offset = struct.unpack_from(value_format, data, header + offset_offset)[0]
            address = struct.unpack_from(value_format, data, header + address_offset)[0]
            assert alignment >= 16384 and offset % 16384 == address % 16384, file
        assert loads, file
        print("PASS", abi, library, "16 KiB ELF load alignment")
