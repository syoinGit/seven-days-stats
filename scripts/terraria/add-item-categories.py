"""Add a site category column to items.tsv from numbers read out of the game.

The input comes from DumpItemTypes.cs (item and tile numbers from your own install). Like the
name tables, neither the input nor the output is committed. See docs/LUMINITE.md.

Usage: python3 add-item-categories.py <itemstats.tsv> <tiles.tsv> <items.tsv>
"""
import csv, sys
from pathlib import Path

stats_path, tiles_path, items_path = map(Path, sys.argv[1:4])
stats = {int(r['id']): r for r in csv.DictReader(stats_path.open(encoding='utf-8'), delimiter='\t')}
tiles = {int(r['tile']): r for r in csv.DictReader(tiles_path.open(encoding='utf-8'), delimiter='\t')}
BARS = 239  # metal bars are placeable tiles but are crafting materials


def category(key, r):
    if r is None:
        return 'other'
    n = lambda field: int(r[field] or 0)
    flag = lambda field: r[field] == '1'
    if key.endswith('Dye') or n('hairDye') >= 0:
        return 'vanity'
    if n('headSlot') >= 0 or n('bodySlot') >= 0 or n('legSlot') >= 0:
        return 'vanity' if flag('vanity') else 'armor'
    if flag('accessory'):
        return 'vanity' if flag('vanity') else 'accessory'
    if key.endswith('Hook') or n('pick') > 0 or n('axe') > 0 or n('hammer') > 0 or n('fishingPole') > 0 or n('mountType') >= 0:
        return 'tool'
    if n('ammo') > 0 and n('damage') > 0:
        return 'ammo'
    if n('damage') > 0 and any(flag(f) for f in ('melee', 'ranged', 'magic', 'summon', 'sentry')):
        return 'weapon'
    if flag('potion') or n('healLife') > 0 or n('healMana') > 0 or (flag('consumable') and n('buffType') > 0):
        return 'potion'
    if n('makeNPC') > 0:
        return 'other'
    if n('createWall') >= 0:
        return 'block'
    tile = n('createTile')
    if tile >= 0:
        info = tiles.get(tile, {})
        if tile == BARS or info.get('ore') == '1':
            return 'ore'
        return 'block' if info.get('solid') == '1' else 'placeable'
    if flag('consumable') or n('useStyle') > 0:
        return 'other'
    return 'material'


lines = items_path.read_text(encoding='utf-8').splitlines()
header = lines[0].split('\t')
keep = header.index('category') if 'category' in header else len(header)
out = ['\t'.join(header[:keep] + ['category'])]
counts = {}
for line in lines[1:]:
    if not line.strip():
        continue
    row = line.split('\t')[:keep]
    value = category(row[1], stats.get(int(row[0])))
    counts[value] = counts.get(value, 0) + 1
    out.append('\t'.join(row + [value]))
items_path.write_text('\n'.join(out) + '\n', encoding='utf-8')
print(items_path, sorted(counts.items(), key=lambda kv: -kv[1]))
