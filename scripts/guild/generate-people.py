#!/usr/bin/env python3
"""Build the guild's layered SD vector artwork. No third-party dependencies.

Coordinates are 96 x 144 for every layer. Keep symbol IDs stable: saved residents
refer to them. Colours inherit from the containing SVG's CSS variables.
Run: python3 scripts/guild/generate-people.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INK = '#352c39'
SKIN = 'var(--skin,#e6b48e)'
HAIR = 'var(--hair,#6e4630)'
CLOTH = 'var(--cloth,#5f7d4a)'
SECOND = 'var(--cloth2,#3a4f6a)'
GOLD = '#d7b46c'
CREAM = '#f5e5c3'
LEATHER = '#76513e'
parts = {}

def path(d, fill='none', stroke=INK, width=2, extra=''):
    return f'<path d="{d}" fill="{fill}" stroke="{stroke}" stroke-width="{width}" {extra}/>'

def ellipse(x, y, rx, ry, fill, stroke='none', width=2, extra=''):
    return f'<ellipse cx="{x}" cy="{y}" rx="{rx}" ry="{ry}" fill="{fill}" stroke="{stroke}" stroke-width="{width}" {extra}/>'

def rect(x, y, w, h, fill, radius=2, stroke=INK):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" fill="{fill}" stroke="{stroke}" stroke-width="2"/>'

def symbol(name, *shapes):
    parts[name] = ''.join(shapes)

def seam(d):
    return path(d, stroke='#fff5dd', width=1.4, extra='opacity=".5"')

# The oversized head, small hands, short boots and soft silhouette are shared.
symbol('body', ellipse(48,136,28,4,'#171322',extra='opacity=".22"'),
    rect(35,108,10,21,SKIN,4),rect(51,108,10,21,SKIN,4),
    path('M34 124 Q40 127 45 124 L45 134 H29 Q27 128 34 124Z',LEATHER),
    path('M51 124 Q57 127 62 124 Q69 128 67 134 H51Z',LEATHER),
    seam('M30 131 H43 M53 131 H66'),rect(41,59,14,15,SKIN,4),
    path('M33 69 Q48 62 63 69 L69 110 Q48 118 27 110Z',SKIN),
    ellipse(23,100,7,9,SKIN,INK),ellipse(73,100,7,9,SKIN,INK),
    ellipse(24,44,5,7,SKIN,INK),ellipse(72,44,5,7,SKIN,INK),
    path('M24 37 Q22 14 48 14 Q74 14 72 37 L71 49 Q68 64 48 66 Q28 64 25 49Z',SKIN),
    path('M27 47 Q31 61 49 62 Q63 61 70 50 Q66 65 48 66 Q30 64 27 47Z','#a85f4b','none',extra='opacity=".18"'),
    ellipse(33,51,5,2.5,'#d27c73',extra='opacity=".3"'),ellipse(63,51,5,2.5,'#d27c73',extra='opacity=".3"'),
    ellipse(36,43,5,6.5,'#fff5e7'),ellipse(60,43,5,6.5,'#fff5e7'),
    ellipse(37,44,3.4,5,'var(--eye,#3f4a41)'),ellipse(59,44,3.4,5,'var(--eye,#3f4a41)'),
    ellipse(37.5,45,1.5,3,INK),ellipse(58.5,45,1.5,3,INK),
    ellipse(38,42,1.2,1.5,'#fff'),ellipse(60,42,1.2,1.5,'#fff'),
    path('M32 39 Q36 36 40 39 M56 39 Q60 36 64 39',width=1.8),
    path('M47 47 L46 50 H49',stroke='#a96f59',width=1.3),
    path('M43 55 Q48 58 53 55',stroke='#935d52',width=1.6))
symbol('face-m',path('M31 35 L40 34 M56 34 L65 35',stroke=HAIR,width=2.6))
symbol('face-f',path('M31 40 L29 38 M65 40 L67 38',width=1.8),path('M32 35 Q36 33 40 35 M56 35 Q60 33 64 35',stroke=HAIR,width=1.7))
# Expression overlays deliberately stay below hair and eyewear.
symbol('expression-bright',ellipse(48,56,7,4,SKIN),path('M42 54 Q48 57 54 54 Q53 62 48 62 Q43 61 42 54Z','#8a474b',width=1.3),path('M44 55 H52 L51 57 H45Z',CREAM,'none'))
symbol('expression-resolute',path('M31 34 L40 37 M56 37 L65 34',stroke=HAIR,width=2.5),ellipse(48,56,7,4,SKIN),path('M44 56 Q48 55 52 56',stroke='#935d52',width=1.7))
symbol('expression-thoughtful',path('M31 35 Q36 31 40 34 M56 34 L64 35',stroke=HAIR,width=2),ellipse(48,56,7,4,SKIN),path('M45 57 Q49 55 53 56',stroke='#935d52',width=1.5))
symbol('expression-gentle',path('M31 35 Q36 32 40 35 M56 35 Q60 32 65 35',stroke=HAIR,width=1.7),ellipse(48,56,7,4,SKIN),path('M42 55 Q48 61 54 55',stroke='#935d52',width=1.7))
symbol('age-mature',path('M28 47 L31 48 M65 48 L68 47 M36 54 L37 56 M59 56 L60 54',stroke='#a56d58',width=1,extra='opacity=".6"'))
symbol('age-elder',path('M28 46 L31 47 M27 49 L31 49 M65 47 L68 46 M65 49 L69 49 M35 53 Q33 56 36 58 M61 53 Q63 56 60 58 M42 61 Q48 63 54 61',stroke='#9d6c58',width=1.2,extra='opacity=".75"'))
symbol('age-young',ellipse(31,51,1,1,'#a96f59'),ellipse(35,53,1,1,'#a96f59'),ellipse(61,53,1,1,'#a96f59'),ellipse(65,51,1,1,'#a96f59'))
symbol('legs-pants',path('M32 103 H64 L61 126 H51 L48 112 L45 126 H35Z',SECOND),seam('M38 110 L39 121 M57 110 L56 121'))
symbol('legs-skirt',path('M32 101 H64 L69 121 Q48 127 27 121Z',SECOND),seam('M38 109 L36 120 M48 109 V122 M58 109 L60 120'))

# Hair silhouettes: clear differences at town size, curved locks at portrait size.
fringe = 'M24 41 Q18 14 39 12 Q62 5 73 24 L73 43 L66 35 Q57 34 52 25 Q45 37 29 35Z'
hairs = {
'short': fringe,
'cropped':'M24 39 L24 25 Q28 13 48 14 Q67 13 72 27 L71 39 L65 29 Q48 24 31 29Z',
'spiky':'M23 42 L20 29 L25 29 L21 20 L34 21 L35 10 L45 15 L54 8 L60 17 L70 15 L70 25 L77 29 L70 43 L64 33 L53 28 L43 33 L34 29 L29 42Z',
'swept':'M24 44 Q14 24 33 15 Q60 1 73 24 L72 44 L66 35 L65 25 Q49 43 27 36Z',
'shaggy':'M23 47 L19 34 L24 25 L22 20 L36 18 L42 10 L50 16 L63 13 L74 25 L72 35 L77 48 L66 43 L62 32 L52 37 L45 30 L34 38 L29 49Z',
'long':'M23 62 Q17 31 24 22 Q30 11 48 12 Q69 10 74 29 L77 78 Q67 82 62 70 L66 35 Q55 33 51 24 Q42 37 29 35 L34 74 Q25 83 18 77Z',
'bob':'M22 57 Q18 30 26 21 Q34 10 49 12 Q69 11 74 30 L75 61 Q68 66 61 60 L66 35 Q52 36 47 25 Q40 35 29 34 L34 60 Q27 64 22 57Z',
'hime':'M21 71 L22 29 Q25 12 48 12 Q71 12 74 29 L75 71 L63 71 L65 34 L31 34 L33 71Z',
'wavy':'M22 75 Q13 69 22 57 Q14 47 22 37 Q17 15 41 12 Q68 7 74 28 Q82 40 74 48 Q83 60 75 67 Q81 79 65 80 L61 69 Q72 58 65 45 L66 33 Q51 34 48 24 Q40 36 29 35 Q25 46 32 53 Q25 62 33 72 Q30 80 22 75Z',
}
for name in ['tied','braid','twintail','bun','topknot','tail']:
    hairs[name] = fringe
extras = {
 'tied':path('M70 26 Q91 23 82 49 L88 63 Q72 60 72 45Z',HAIR),
 'tail':path('M69 29 Q84 33 77 51 L82 58 Q69 57 68 45Z',HAIR),
 'braid': ''.join(ellipse(71+(i%2)*2,49+i*6,5,5,HAIR,INK,1.4) for i in range(6))+path('M69 83 L77 83 L75 90Z',HAIR),
 'twintail':path('M24 29 Q8 26 12 49 L9 70 Q25 72 26 49Z',HAIR)+path('M72 29 Q88 26 84 49 L87 70 Q71 72 70 49Z',HAIR),
 'bun':ellipse(70,19,11,11,HAIR,INK)+seam('M67 13 Q77 18 71 25'),
 'topknot':path('M42 17 Q31 4 47 5 Q64 3 59 15 L54 22Z',HAIR)+rect(42,16,15,4,GOLD),
}
for name, d in hairs.items():
    detail = seam('M30 26 Q36 18 44 18 M58 18 Q65 20 67 26')
    tie = ellipse(73,37,4,3,SECOND,INK,1) if name in ('tied','tail','braid') else ''
    symbol('hair-'+name,extras.get(name,''),path(d,HAIR),detail,tie)

# Clothes share tailoring primitives but every profession has a distinct silhouette/prop.
def coat(female=False, long=False, fill=CLOTH):
    hem = 122 if long else 109
    left,right = (27,69) if female else (29,67)
    return path(f'M34 68 L26 73 Q21 80 18 94 L29 99 L33 87 L{left} {hem} Q48 {hem+6} {right} {hem} L63 87 L67 99 L78 94 Q75 80 70 73 L62 68 Q48 74 34 68Z',fill)+seam(f'M35 91 L34 {hem-3} M61 91 L62 {hem-3}')
def collar():
    return path('M35 68 L47 73 L41 81 L33 72Z',CREAM,width=1.4)+path('M61 68 L49 73 L55 81 L63 72Z',CREAM,width=1.4)
def belt():
    return path('M31 99 Q48 103 65 99 L65 105 Q48 109 31 105Z',LEATHER)+rect(44,100,8,7,GOLD,1)+rect(47,102,3,3,LEATHER,0,'none')
def pouch():
    return path('M62 101 Q77 99 76 111 L75 119 Q67 123 60 117Z',LEATHER)+path('M62 103 L76 105 L70 111Z','#a97a4d',width=1.3)+ellipse(69,111,1.5,1.5,GOLD)
def book():
    return path('M16 89 L31 86 L36 108 L20 112Z','#715573')+path('M20 91 L29 89 L32 105 L23 108Z',CREAM,width=1)+path('M24 95 L29 94 M25 99 L30 98',stroke='#b29369',width=1)
def sword():
    return path('M74 96 L82 126 L79 132 L75 128 L68 98Z','#aebfbd')+path('M66 96 L78 92',stroke=GOLD,width=4)+path('M69 94 L66 85',stroke=LEATHER,width=4)
def lantern():
    return path('M77 95 Q85 91 85 101',width=2)+rect(75,102,15,19,'#f0c46b',3)+path('M76 106 H89 M82 105 V119',stroke=LEATHER,width=2)+ellipse(82,112,3,5,'#ffebad')
def apron(female=False):
    return path('M37 76 H59 L58 94 L65 116 Q48 122 31 116 L38 94Z',CREAM)+path('M39 98 H57 L56 108 H40Z','#d8c29d',width=1.3)+seam('M41 79 V91')

jobs = ['farmer','watch','knight','crafter','merchant','cleric','adventurer','hunter','cook','samurai','ronin','miko_m']
for job in jobs:
  for female in (False,True):
    name = ('miko' if female else 'miko_m') if job=='miko_m' else job+('_f' if female else '')
    long = job in ('farmer','knight','cleric','samurai','ronin','miko_m') or female and job in ('merchant','cook')
    art = coat(female,long,'#f2e6ce' if job=='miko_m' else CLOTH) + collar()
    if job=='farmer':
        art += apron(female)+path('M78 124 L78 78 M71 80 Q78 86 85 80 M71 80 V69 M78 82 V68 M85 80 V69',stroke=LEATHER,width=3)
        art += path('M35 89 L31 81 M34 86 L28 85 M34 85 L36 80',stroke='#778255',width=2)
    elif job=='watch':
        art = path('M32 68 Q48 60 65 68 L78 117 Q49 129 19 117Z',SECOND)+art+belt()+lantern()
        art += ellipse(47,76,3,4,GOLD,INK,1)
    elif job=='knight':
        art = coat(female,True,'#9faeb4')+path('M32 72 Q48 78 64 72 L60 106 Q48 115 36 106Z',CLOTH)+belt()
        art += path('M32 69 Q21 68 20 84 L33 88 L38 75Z','#c9d3ce')+path('M64 69 Q75 68 76 84 L63 88 L58 75Z','#c9d3ce')
        art += path('M48 80 L51 88 L58 90 L51 93 L48 102 L45 93 L38 90 L45 88Z',GOLD,width=1)
        art += sword()+path('M12 90 L28 85 L37 91 L34 113 L24 121 L14 112Z',SECOND)+path('M17 93 L27 90 L32 94 L29 110 L24 114 L19 109Z','#b7c6c1',width=1)
    elif job=='crafter':
        art += path('M37 70 L41 74 L39 86 H59 L56 73 L60 70 L65 115 Q48 122 31 115Z',LEATHER)+rect(39,94,18,14,'#ae7f52')
        art += path('M73 91 L66 118',stroke=LEATHER,width=5)+path('M65 86 L81 92 L78 101 L62 95Z','#91a0a5')+seam('M66 89 L77 93')
    elif job=='merchant':
        art += path('M34 70 L45 76 L42 100 L31 99Z',SECOND)+path('M62 70 L51 76 L54 100 L65 99Z',SECOND)+belt()+pouch()+book()
        art += ellipse(48,86,2,2,GOLD)+ellipse(48,94,2,2,GOLD)
    elif job=='cleric':
        art += path('M34 68 L43 72 L39 119 L30 118Z',CREAM)+path('M62 68 L53 72 L57 119 L66 118Z',CREAM)
        art += path('M41 72 Q48 96 55 72',stroke=GOLD,width=1.5)+path('M48 87 L52 93 L48 99 L44 93Z',GOLD)+book()
        art += path('M79 123 V74',stroke=LEATHER,width=4)+ellipse(79,69,6,7,GOLD,INK)+ellipse(79,69,2,3,'#678b83')
    elif job=='adventurer':
        art = path('M32 68 L63 68 L73 111 L23 116Z',SECOND)+art
        art += path('M31 68 Q48 84 65 68 L61 84 Q47 89 33 82Z',SECOND)+path('M58 79 L67 84 L72 105 L61 98Z',SECOND)
        art += path('M35 82 L61 105',stroke=LEATHER,width=5)+belt()+pouch()+sword()
    elif job=='hunter':
        art = path('M34 68 L63 68 L76 115 L22 113Z',SECOND)+art+belt()
        art += path('M32 70 Q48 84 64 70 L60 87 L36 87Z',SECOND)+path('M78 76 Q96 99 78 125',stroke=LEATHER,width=4)+path('M78 76 V125',stroke=CREAM,width=1)
        art += path('M17 97 L27 69 M22 75 L21 69 L28 70',stroke=GOLD,width=2)+pouch()
    elif job=='cook':
        art += apron(female)+path('M74 118 L74 90',stroke=LEATHER,width=4)+ellipse(74,86,5,8,'#c19a64',INK)
        art += path('M41 71 L48 78 L55 71 L51 82 H45Z',SECOND)
    elif job in ('samurai','ronin','miko_m'):
        art = coat(female,True,'#f2e6ce' if job=='miko_m' else CLOTH)
        art += path('M35 69 L57 95 M61 69 L39 96',stroke=CREAM,width=5)
        art += path('M32 97 H64 L69 122 Q48 128 27 122Z','#ad4c4a' if job=='miko_m' else SECOND)
        art += seam('M39 103 L36 120 M48 103 V123 M57 103 L60 120')+rect(31,95,34,8,GOLD if job=='ronin' else SECOND)
        if job!='miko_m': art+=sword()
        if job=='samurai': art+=path('M26 72 L34 75 L29 88 L20 84Z',SECOND)+path('M70 72 L62 75 L67 88 L76 84Z',SECOND)
        if job=='ronin': art+=path('M34 110 L41 108 L43 116 L36 118Z',LEATHER)+seam('M35 111 L42 114')
        if job=='miko_m': art+=path('M77 115 V79',stroke=LEATHER,width=3)+path('M77 81 L86 85 L80 91 L87 96 L81 103 L74 96 L80 91 L73 85Z',CREAM,width=1)
    symbol('outfit-'+name,art)

# Hats leave eyes and most of the fringe visible.
hats = {
'farmer':path('M29 25 Q30 7 48 8 Q66 7 67 25Z','#d9b979')+ellipse(48,26,33,6,'#dfc28a',INK)+path('M30 20 Q48 25 66 20',stroke=LEATHER,width=4)+seam('M34 12 L33 18 M41 10 L40 20 M54 10 L55 20 M62 13 L63 18'),
'watch':path('M20 43 Q15 8 48 7 Q81 8 76 43 L69 34 Q66 18 48 18 Q30 18 27 34Z',SECOND)+seam('M24 25 Q30 12 46 12'),
'knight':path('M22 31 Q20 8 48 8 Q76 8 74 31 L65 27 Q48 22 31 27Z','#bac9c8')+path('M44 9 H52 L53 25 H43Z',GOLD)+path('M48 8 Q47 0 64 3 L63 11Z',SECOND),
'crafter':path('M25 28 Q24 13 48 14 Q69 12 72 28Z',SECOND)+path('M25 26 Q49 22 73 26 L70 31 Q48 27 26 32Z',LEATHER),
'merchant':path('M23 25 Q18 13 35 12 Q62 4 73 19 L68 27Z',SECOND)+path('M27 25 Q48 20 68 25 L68 30 Q48 25 27 30Z',GOLD)+ellipse(60,25,3,3,'#749b92',INK,1),
'hunter':path('M21 27 L35 10 L54 16 L71 26Z',SECOND)+path('M20 27 Q48 21 76 27 L70 32 Q48 27 24 32Z',CLOTH)+path('M63 23 Q65 8 80 6 Q79 20 63 23Z','#e6cf99',width=1.5)+path('M64 24 L77 10',stroke=LEATHER,width=1),
'cook':path('M28 24 Q17 22 23 11 Q27 5 36 9 Q45 -1 54 8 Q66 4 72 12 Q78 23 68 25 L67 30 H29Z',CREAM)+path('M29 23 H67 V32 H29Z','#e1d2b5')+seam('M34 13 V21 M47 10 V21 M60 13 V21'),
'cleric_f':path('M23 33 Q21 9 48 9 Q74 9 73 33 L68 47 L66 28 Q48 18 30 28 L28 47Z',CREAM)+path('M29 22 Q48 12 67 22',stroke=GOLD,width=2),
'ronin':path('M12 27 L48 7 L84 27 Q48 38 12 27Z','#c9aa71')+path('M48 10 L25 28 M48 10 L38 30 M48 10 L58 30 M48 10 L72 28',stroke='#8b7049',width=1.4),
'miko_m':path('M33 24 L35 7 Q48 2 61 7 L63 24Z','#3c3546')+rect(29,22,38,7,'#514456'),
}
hats['knight_f']=hats['knight']
hats['cook_f']=hats['cook']
hats['crafter_f']=hats['crafter']
for name, art in hats.items(): symbol('hat-'+name,art)

symbol('acc-beard',path('M30 52 Q36 59 42 55 Q48 58 54 55 Q60 59 66 52 Q65 69 48 75 Q31 69 30 52Z',HAIR)+seam('M37 61 L43 68 M57 61 L52 68'))
symbol('acc-mustache',path('M48 53 Q40 49 34 57 Q41 61 48 56 Q55 61 62 57 Q56 49 48 53Z',HAIR,width=1))
symbol('acc-stubble',path('M32 56 L34 58 M37 60 L38 62 M43 62 L44 64 M51 62 L51 64 M58 60 L57 62 M64 56 L62 58',stroke=HAIR,width=1.5))
symbol('acc-eyepatch',path('M26 32 L69 50',stroke=LEATHER,width=2)+path('M53 38 Q59 35 65 39 L64 46 Q59 52 54 46Z','#39323c'))
symbol('acc-scar',path('M63 34 L60 53 M60 39 L64 40 M59 46 L63 47',stroke='#b77365',width=1.5))
symbol('acc-ribbon',path('M25 27 Q12 18 15 34 L25 32 Q34 40 35 24Z',SECOND)+ellipse(25,29,3,3,GOLD,INK,1))
symbol('acc-flower',*(ellipse(x,y,4,5,'#e8b8a6',INK,1) for x,y in [(24,24),(19,28),(21,34),(28,34),(30,28)]),ellipse(25,29,3,3,GOLD))
symbol('acc-earrings',ellipse(24,51,3,4,GOLD,INK,1),ellipse(72,51,3,4,GOLD,INK,1),ellipse(24,56,2,3,'#8dad9e'),ellipse(72,56,2,3,'#8dad9e'))
symbol('acc-hairband',path('M24 31 Q48 9 72 31',stroke=SECOND,width=5),path('M26 29 Q48 12 70 29',stroke=GOLD,width=1))
symbol('acc-glasses',ellipse(36,44,8,7,'none',LEATHER,1.8),ellipse(60,44,8,7,'none',LEATHER,1.8),path('M44 43 Q48 40 52 43 M28 43 L24 41 M68 43 L72 41',stroke=LEATHER,width=1.5),seam('M31 43 L35 39 M55 43 L59 39'))
# Legacy/invalid records also get SD artwork; currentColor retains their old tint.
symbol('resident','<g style="--cloth:currentColor">'+''.join(parts[k] for k in ['body','face-m','legs-pants','outfit-adventurer','hair-short'])+'</g>')

out = ['<svg xmlns="http://www.w3.org/2000/svg">',
       '  <!-- Generated by scripts/guild/generate-people.py. Stable IDs preserve saved residents. -->']
for name, art in parts.items():
    out.append(f'  <symbol id="{name}" viewBox="0 0 96 144"><g stroke-linecap="round" stroke-linejoin="round">{art}</g></symbol>')
out.append('</svg>\n')
(ROOT/'src/main/resources/static/img/guild-people.svg').write_text('\n'.join(out))
print(f'Wrote {len(parts)} SD layers.')
