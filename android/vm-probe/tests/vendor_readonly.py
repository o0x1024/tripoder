"""Read the pinned image's logical vendor partition and ext4 files without mounting."""
import hashlib
import struct


class VendorReader:
    def __init__(self, image):
        self.image = image
        self.super_base = 269484032
        header = self.image.read(self.super_base + 12288, 4096)
        assert header[:4] == b"0PLA"
        self.header_size = struct.unpack_from("<I", header, 8)[0]
        self.tables_size = struct.unpack_from("<I", header, 44)[0]
        tables = self.image.read(self.super_base + 12288 + self.header_size, self.tables_size)
        assert hashlib.sha256(tables).digest() == header[48:80]
        po, pn, ps = struct.unpack_from("<III", header, 80)
        eo, en, es = struct.unpack_from("<III", header, 92)
        self.vendor = None
        for i in range(pn):
            record = tables[po + i * ps:po + (i + 1) * ps]
            if record[:36].split(b"\0")[0] == b"vendor":
                first, count = struct.unpack_from("<II", record, 40)
                assert count == 1
                self.sectors, kind, sector, source = struct.unpack_from("<QIQI", tables, eo + first * es)
                assert kind == 0 and source == 0
                self.vendor = self.super_base + sector * 512
        assert self.vendor is not None
        self.sb = self.read(1024, 1024)
        assert self.sb[56:58] == b"\x53\xef"
        self.block_size = 1024 << struct.unpack_from("<I", self.sb, 24)[0]
        self.inodes_per_group = struct.unpack_from("<I", self.sb, 40)[0]
        self.inode_size = struct.unpack_from("<H", self.sb, 88)[0]
        assert self.block_size == 4096 and self.inode_size == 256
        assert not struct.unpack_from("<I", self.sb, 96)[0] & 0x80, "64-bit descriptors not implemented"

    def read(self, offset, count):
        assert 0 <= offset <= self.sectors * 512 - count
        return self.image.read(self.vendor + offset, count)

    def inode(self, number):
        group, index = divmod(number - 1, self.inodes_per_group)
        desc = self.read(self.block_size + group * 32, 32)
        table = struct.unpack_from("<I", desc, 8)[0]
        return self.read(table * self.block_size + index * self.inode_size, self.inode_size)

    def extents(self, node):
        magic, count, capacity, depth = struct.unpack_from("<HHHH", node)
        assert magic == 0xf30a and count <= capacity and depth < 6
        result = []
        for i in range(count):
            entry = node[12 + i * 12:24 + i * 12]
            if depth:
                logical, low, high = struct.unpack_from("<IIH", entry)
                result.extend(self.extents(self.read(((high << 32) | low) * self.block_size, self.block_size)))
            else:
                logical, length, high, low = struct.unpack("<IHHI", entry)
                assert 0 < length <= 32768, "Unwritten extent not supported"
                result.append((logical, length, (high << 32) | low))
        return result

    def contents(self, number):
        node = self.inode(number)
        size = struct.unpack_from("<I", node, 4)[0]
        assert 0 <= size < 16 * 1024 * 1024
        assert struct.unpack_from("<I", node, 32)[0] & 0x80000
        output = bytearray(size)
        for logical, length, physical in self.extents(node[40:100]):
            offset = logical * self.block_size
            count = min(length * self.block_size, size - offset)
            assert count > 0
            output[offset:offset + count] = self.read(physical * self.block_size, count)
        return bytes(output)

    def find_file(self, path):
        current = 2
        for component in path.strip("/").split("/"):
            data = self.contents(current)
            offset = 0
            entries = {}
            while offset < len(data):
                number, length, name_length, kind = struct.unpack_from("<IHBB", data, offset)
                assert length >= 8 and offset + length <= len(data)
                if number:
                    entries[data[offset + 8:offset + 8 + name_length].decode()] = number
                offset += length
            current = entries[component]
        return self.contents(current)
