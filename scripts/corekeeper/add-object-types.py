#!/usr/bin/env python3
"""Add the game's object type and category tags to objects.tsv (columns 5 and 6).

The dedicated server keeps each object's ObjectInfo (objectID, ..., objectType, tags) in
CoreKeeperServer_Data/StreamingAssets/aa/StandaloneLinux64/defaultlocalgroup_assets_all.bundle.
This decompresses the bundle (UnityFS, LZ4) and reads it as plain bytes; nothing from it is
executed. The output is derived from game files, so it is not committed (docs/OCTARINE.md).

  add-object-types.py <objects.tsv> <defaultlocalgroup_assets_all.bundle> <output objects.tsv>

Columns: id, key, ja, en, type (ObjectType number), tags (ObjectCategoryTag numbers, comma
separated). Objects without an ObjectInfo record get empty type and tags.
"""
import struct
import sys

# The MonoBehaviour that holds ObjectInfo: m_Script path id, then an empty m_Name. If a game
# update changes it, fewer records are found and the script stops instead of guessing.
ANCHOR = struct.pack("<iii", 1001582322, 1424524757, 0)
MIN_RECORDS = 1000
TYPES = {0, 1, 100, 101, 102, 103, 104, 105, 106, 107, 108, 500, 501, 502, 503, 600, 601, 602,
         603, 604, 605, 606, 607, 608, 609, 610, 800, 801, 802, 900, 1100, 1200, 1201, 1250, 1300,
         1400, 1500, 1600, 1700, 6000}


def lz4(src: bytes, size: int) -> bytes:
    dst = bytearray()
    i = 0
    while i < len(src):
        token = src[i]
        i += 1
        literal = token >> 4
        if literal == 15:
            while True:
                extra = src[i]
                i += 1
                literal += extra
                if extra != 255:
                    break
        dst += src[i:i + literal]
        i += literal
        if i >= len(src):
            break
        offset = src[i] | (src[i + 1] << 8)
        i += 2
        match = token & 15
        if match == 15:
            while True:
                extra = src[i]
                i += 1
                match += extra
                if extra != 255:
                    break
        match += 4
        start = len(dst) - offset
        for k in range(match):
            dst.append(dst[start + k])
    if len(dst) != size:
        raise ValueError("LZ4 block size mismatch")
    return bytes(dst)


def unityfs(data: bytes) -> bytes:
    if not data.startswith(b"UnityFS\0"):
        raise ValueError("not a UnityFS bundle")
    p = data.index(b"\0") + 1
    version = struct.unpack_from(">I", data, p)[0]
    p += 4
    p = data.index(b"\0", p) + 1
    p = data.index(b"\0", p) + 1
    _, compressed, uncompressed, flags = struct.unpack_from(">qIII", data, p)
    p += 20
    if version >= 7:
        p += (-p) % 16
    at_end = flags & 0x80
    info = data[len(data) - compressed:] if at_end else data[p:p + compressed]
    start = p if at_end else p + compressed
    if flags & 0x200:
        start += (-start) % 16
    if flags & 0x3F in (2, 3):
        info = lz4(info, uncompressed)
    elif flags & 0x3F:
        raise ValueError("unsupported block info compression")
    q = 16
    blocks = struct.unpack_from(">i", info, q)[0]
    q += 4
    out = bytearray()
    for _ in range(blocks):
        size, csize, bflags = struct.unpack_from(">IIH", info, q)
        q += 10
        chunk = data[start:start + csize]
        start += csize
        if bflags & 0x3F in (2, 3):
            out += lz4(chunk, size)
        elif bflags & 0x3F == 0:
            out += chunk
        else:
            raise ValueError("unsupported block compression")
    return bytes(out)


def object_infos(raw: bytes, known: set) -> dict:
    """objectID -> (objectType, tags) for variation 0 (the base object)."""
    found = {}
    i = raw.find(ANCHOR)
    while i != -1:
        try:
            oid, amount, variation, flag, _, otype, ntags = struct.unpack_from("<7i", raw, i + 12)
            if (oid in known and 1 <= amount <= 9999 and flag in (0, 1) and otype in TYPES
                    and 0 <= ntags <= 32 and (oid not in found or variation == 0)):
                tags = struct.unpack_from(f"<{ntags}i", raw, i + 40)
                if all(0 <= tag < 256 for tag in tags):
                    found[oid] = (otype, sorted(set(tags)))
        except struct.error:
            pass
        i = raw.find(ANCHOR, i + 1)
    return found


def main() -> None:
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    table, bundle, output = sys.argv[1:]
    lines = open(table, encoding="utf-8").read().splitlines()
    rows = [line.split("\t") for line in lines[1:]]
    infos = object_infos(unityfs(open(bundle, "rb").read()), {int(row[0]) for row in rows})
    if len(infos) < MIN_RECORDS:
        sys.exit(f"only {len(infos)} ObjectInfo records found; the bundle layout may have changed")
    out = ["\t".join(["id", "key", "ja", "en", "type", "tags"])]
    for row in rows:
        row = (row + [""] * 4)[:4]
        otype, tags = infos.get(int(row[0]), ("", []))
        out.append("\t".join(row + [str(otype), ",".join(map(str, tags))]))
    with open(output, "w", encoding="utf-8") as file:
        file.write("\n".join(out) + "\n")
    print(f"{len(infos)} of {len(rows)} objects typed", file=sys.stderr)


if __name__ == "__main__":
    main()
