#!/usr/bin/env python3
"""Read the locked QCOW2 ESP without mounting or changing the guest image."""
import struct


class QcowReader:
    def __init__(self, path):
        self.file = open(path, "rb")
        header = self.file.read(112)
        assert header[:4] == b"QFI\xfb"
        assert struct.unpack_from(">Q", header, 8)[0] == 0, "backing files unsupported"
        assert struct.unpack_from(">I", header, 32)[0] == 0, "encrypted images unsupported"
        self.cluster_size = 1 << struct.unpack_from(">I", header, 20)[0]
        count = struct.unpack_from(">I", header, 36)[0]
        self.file.seek(struct.unpack_from(">Q", header, 40)[0])
        self.l1 = struct.unpack(">" + str(count) + "Q", self.file.read(count * 8))
        self.tables = {}

    def read(self, offset, count):
        output = bytearray()
        mask = 0x00FFFFFFFFFFFE00
        size = self.cluster_size
        while count:
            index, within = divmod(offset, size)
            amount = min(count, size - within)
            table = self.l1[index // (size // 8)] & mask
            physical = 0
            if table:
                if table not in self.tables:
                    self.file.seek(table)
                    self.tables[table] = self.file.read(size)
                entry = struct.unpack_from(">Q", self.tables[table], index % (size // 8) * 8)[0]
                assert not entry & (1 << 62), "compressed clusters unsupported"
                physical = entry & mask
            if physical:
                self.file.seek(physical + within)
                output.extend(self.file.read(amount))
            else:
                output.extend(bytes(amount))
            offset += amount
            count -= amount
        return bytes(output)


class EspReader:
    def __init__(self, qcow):
        self.qcow = qcow
        gpt = qcow.read(512, 512)
        assert gpt[:8] == b"EFI PART"
        table = struct.unpack_from("<Q", gpt, 72)[0] * 512
        first = qcow.read(table, struct.unpack_from("<I", gpt, 84)[0])
        self.base = struct.unpack_from("<Q", first, 32)[0] * 512
        boot = qcow.read(self.base, 512)
        self.sector = struct.unpack_from("<H", boot, 11)[0]
        self.cluster_sectors = boot[13]
        reserved = struct.unpack_from("<H", boot, 14)[0]
        assert struct.unpack_from("<H", boot, 22)[0] == 0, "expected FAT32"
        self.fat = self.base + reserved * self.sector
        fat_size = struct.unpack_from("<I", boot, 36)[0]
        self.data = self.base + (reserved + boot[16] * fat_size) * self.sector
        self.root = struct.unpack_from("<I", boot, 44)[0]

    def chain(self, cluster):
        output = bytearray()
        seen = set()
        size = self.cluster_sectors * self.sector
        while 2 <= cluster < 0x0FFFFFF8:
            assert cluster not in seen, "FAT chain loop"
            seen.add(cluster)
            output.extend(self.qcow.read(self.data + (cluster - 2) * size, size))
            cluster = struct.unpack("<I", self.qcow.read(self.fat + cluster * 4, 4))[0] & 0x0FFFFFFF
        return bytes(output)

    def entries(self, cluster):
        data = self.chain(cluster)
        long_name = []
        for offset in range(0, len(data), 32):
            entry = data[offset:offset + 32]
            if entry[0] == 0:
                break
            if entry[0] == 0xE5:
                long_name = []
                continue
            if entry[11] == 15:
                long_name.insert(0, (entry[1:11] + entry[14:26] + entry[28:32]).decode("utf-16le"))
                continue
            short = entry[:8].decode("ascii", errors="replace").strip()
            extension = entry[8:11].decode("ascii", errors="replace").strip()
            name = ("".join(long_name).split("\0")[0].replace("\uffff", "") if long_name
                    else short + ("." + extension if extension else ""))
            long_name = []
            if name in (".", "..") or entry[11] & 8:
                continue
            cluster_number = (struct.unpack_from("<H", entry, 26)[0]
                              | struct.unpack_from("<H", entry, 20)[0] << 16)
            yield name, cluster_number, struct.unpack_from("<I", entry, 28)[0], bool(entry[11] & 16)

    def read_file(self, path):
        cluster = self.root
        parts = path.strip("/").split("/")
        for index, part in enumerate(parts):
            item = next(item for item in self.entries(cluster) if item[0].lower() == part.lower())
            cluster = item[1]
            if index == len(parts) - 1:
                assert not item[3]
                return self.chain(cluster)[:item[2]]
            assert item[3]


