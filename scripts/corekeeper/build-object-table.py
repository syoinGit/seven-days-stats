#!/usr/bin/env python3
"""Build objects.tsv (Core Keeper object id -> internal name) from a decompiled ObjectID enum.

The output is derived from game files, so it is not committed; place it on the server in
COREKEEPER_NAMES_DIR (see docs/OCTARINE.md). Usage:

  build-object-table.py <decompiled ObjectID.cs> <output dir>

Columns: id, key (internal name), ja (left empty; fill in by hand if wanted), en (spaced key).
"""
import re
import sys
from pathlib import Path


def spaced(key: str) -> str:
    words = re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ", key)
    return re.sub(r"(?<=[A-Za-z])(?=\d)", " ", words).replace("_", " ").strip()


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    source = Path(sys.argv[1]).read_text(encoding="utf-8")
    body = re.search(r"enum\s+ObjectID\b[^{]*\{(.*?)\n\}", source, re.S)
    if not body:
        print("ObjectID enum not found", file=sys.stderr)
        return 1
    rows = {}
    for name, value in re.findall(r"^\s*(\w+)\s*=\s*(-?\d+)\s*,?", body.group(1), re.M):
        rows.setdefault(int(value), name)
    if len(rows) < 100:
        print(f"only {len(rows)} entries found; is this the right file?", file=sys.stderr)
        return 1
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    with (out / "objects.tsv").open("w", encoding="utf-8") as handle:
        handle.write("id\tkey\tja\ten\n")
        for value in sorted(rows):
            if value <= 0:
                continue
            handle.write(f"{value}\t{rows[value]}\t\t{spaced(rows[value])}\n")
    print(f"wrote {len(rows)} objects to {out / 'objects.tsv'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
