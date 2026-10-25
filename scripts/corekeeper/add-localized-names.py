#!/usr/bin/env python3
"""Fill the ja and en columns of objects.tsv from the game's localization (I2 Localization).

The game keeps its translations in CoreKeeperServer_Data/resources.assets as terms such as
"Items/CopperShovel" with one string per language. This reads the file as plain bytes (nothing
from it is executed) and writes a new table; the output is derived from game files, so it is
not committed (see docs/OCTARINE.md). Usage:

  add-localized-names.py <objects.tsv> <resources.assets> <output objects.tsv>

Rows without a term keep their current names. Hand-written ja values are kept.
"""
import struct
import sys

# Order of the game's language list: English, German, French, Portuguese (Brazil), Italian,
# Japanese, Korean, Russian, Spanish, Ukrainian, Chinese (Simplified), Chinese (Traditional), Thai.
LANGUAGES = 13
EN, JA = 0, 5
# Items first; bosses and minions are named elsewhere.
PREFIXES = ("Items/", "Names/", "WeaponSecondary/")


def terms(data: bytes) -> dict:
    """Term name -> language strings, for every array of LANGUAGES strings after a term name."""
    marker = struct.pack("<i", LANGUAGES)
    found = {}
    i = data.find(marker)
    while i != -1:
        try:
            if i % 4 == 0 and 0 <= struct.unpack_from("<i", data, i - 4)[0] < 16:
                term = term_before(data, i - 4)
                if term is not None:
                    found.setdefault(term, strings(data, i + 4))
        except (ValueError, UnicodeDecodeError, struct.error):
            pass
        i = data.find(marker, i + 1)
    return found


def term_before(data: bytes, end: int):
    """The aligned string that ends at end (before the term type), if it looks like a term."""
    for length in range(3, 160):
        start = end - ((length + 3) // 4) * 4
        if start < 4:
            return None
        if struct.unpack_from("<i", data, start - 4)[0] == length:
            term = data[start:start + length].decode("utf-8")
            return term if "/" in term and term.isprintable() else None
    return None


def strings(data: bytes, at: int) -> list:
    result = []
    for _ in range(LANGUAGES):
        length = struct.unpack_from("<i", data, at)[0]
        if not 0 <= length < 4000:
            raise ValueError("implausible string length")
        at += 4
        result.append(data[at:at + length].decode("utf-8"))
        at += length + (-(at + length)) % 4
    return result


def main() -> None:
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    table, assets, output = sys.argv[1:]
    found = terms(open(assets, "rb").read())
    lines = open(table, encoding="utf-8").read().splitlines()
    out = [lines[0]]
    named = 0
    for line in lines[1:]:
        row = line.split("\t")
        while len(row) < 4:
            row.append("")
        langs = next((found[p + row[1]] for p in PREFIXES if p + row[1] in found), None)
        if langs is not None:
            named += 1
            row[2] = row[2] or langs[JA].strip()
            row[3] = langs[EN].strip() or row[3]
        out.append("\t".join(cell.replace("\t", " ").replace("\n", " ") for cell in row))
    with open(output, "w", encoding="utf-8") as file:
        file.write("\n".join(out) + "\n")
    print(f"{named} of {len(lines) - 1} objects named; {len(found)} terms read", file=sys.stderr)


if __name__ == "__main__":
    main()
