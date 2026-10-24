"""Build Terraria id -> name tables (items, NPCs, prefixes, containers, map objects).

The output is game text, so it is NOT committed: generate it from your own install and copy it to
the server directory named by TERRARIA_NAMES_DIR. See docs/LUMINITE.md for the full steps.

Usage: python3 build-name-tables.py <decompiled ID classes> <extracted resources> <output dir>
"""
import json, re, sys
from pathlib import Path

decomp, res, out = map(Path, sys.argv[1:4])

def consts(cs, types):
    pat = re.compile(r'public const (?:%s) (\w+) = (-?\d+);' % '|'.join(types))
    found = {}
    for name, value in pat.findall(cs.read_text(encoding='utf-8')):
        found.setdefault(int(value), name)
    return found

def load(lang, part):
    suffix = f'.{part}' if part else ''
    text = (res / f'Terraria.Localization.Content.{lang}{suffix}.json').read_text(encoding='utf-8-sig')
    return json.loads(re.sub(r',(\s*[}\]])', r'\1', text))

def table(lang):
    merged = {}
    for part in ('Items', 'NPCs', None, 'Game'):
        for section, values in load(lang, part).items():
            if isinstance(values, dict):
                merged.setdefault(section, {}).update(values)
    ref = re.compile(r'\{\$(\w+)\.(\w+)\}')
    def text(section, key, depth=0):
        value = merged.get(section, {}).get(key)
        if value is None or depth > 5:
            return None
        return ref.sub(lambda m: text(m.group(1), m.group(2), depth + 1) or m.group(0), value).strip()
    return text

ja, en = table('ja-JP'), table('en-US')

def clean(value):
    return None if value is None else value.replace('\t', ' ').replace('\n', ' ')

def write(name, ids, section, skip):
    rows = []
    for value, key in sorted(ids.items()):
        if skip(value, key):
            continue
        rows.append((value, key, clean(ja(section, key)) or '', clean(en(section, key)) or ''))
    (out / name).write_text('id\tkey\tja\ten\n' + ''.join(f'{a}\t{b}\t{c}\t{d}\n' for a, b, c, d in rows), encoding='utf-8')
    print(name, len(rows), sum(1 for r in rows if not r[2]), 'without ja')

out.mkdir(parents=True, exist_ok=True)
write('items.tsv', consts(decomp / 'ItemID.cs', ['short']), 'ItemName', lambda v, k: v <= 0)
write('npcs.tsv', consts(decomp / 'NPCID.cs', ['short']), 'NPCName', lambda v, k: k == 'NegativeIDCount' or v == 0)
write('prefixes.tsv', consts(decomp / 'PrefixID.cs', ['int', 'byte']), 'Prefix', lambda v, k: v <= 0 or k == 'Count')

# Container tile styles: chests (21), chests 2 (467), dressers (88).
legacy = {}
for lang in ('ja-JP', 'en-US'):
    text = (res / f'Terraria.Localization.Content.{lang}.Legacy.json').read_text(encoding='utf-8-sig')
    legacy[lang] = json.loads(re.sub(r',(\s*[}\]])', r'\1', text))
ref = re.compile(r'\{\$(\w+)\.(\w+)\}')
rows = []
for tile, section in ((21, 'LegacyChestType'), (467, 'LegacyChestType2'), (88, 'LegacyDresserType')):
    for style in sorted(legacy['ja-JP'][section], key=int):
        names = []
        for lang, lookup in (('ja-JP', ja), ('en-US', en)):
            value = legacy[lang][section].get(style, '')
            names.append(clean(ref.sub(lambda m: lookup(m.group(1), m.group(2)) or m.group(0), value)))
        rows.append((tile, int(style), *names))
(out / 'containers.tsv').write_text('tile\tstyle\tja\ten\n' + ''.join('\t'.join(map(str, r)) + '\n' for r in rows), encoding='utf-8')
print('containers.tsv', len(rows))

# Map legend names for objects that have no item of their own (e.g. Plantera's Bulb).
game = {}
for lang in ('ja-JP', 'en-US'):
    text = (res / f'Terraria.Localization.Content.{lang}.Game.json').read_text(encoding='utf-8-sig')
    game[lang] = json.loads(re.sub(r',(\s*[}\]])', r'\1', text))['MapObject']
rows = [(key, clean(game['ja-JP'].get(key, '')), clean(game['en-US'].get(key, ''))) for key in sorted(game['en-US'])]
(out / 'mapobjects.tsv').write_text('key\tja\ten\n' + ''.join('\t'.join(r) + '\n' for r in rows), encoding='utf-8')
print('mapobjects.tsv', len(rows))
