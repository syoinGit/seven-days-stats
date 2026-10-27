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
    path('M47 47 L46 50 H49',stroke='#a96f59',width=1.3),
    path('M43 55 Q48 58 53 55',stroke='#935d52',width=1.6))
symbol('face-m',path('M31 58 Q33 61 36 61 M60 61 Q63 61 65 58',stroke='#a96f59',width=1,extra='opacity=".4"'))
symbol('face-f',ellipse(31,52,4,2,'#d27c73',extra='opacity=".2"'),ellipse(65,52,4,2,'#d27c73',extra='opacity=".2"'))
# Each eye has its own silhouette, clipped iris, upper lash and reflected light.
# Fixed local clipping IDs are unique across the sheet and independent of resident colours.
eye_shapes = {
    'round': ('M-6 0 C-6-9 6-9 6 0 C6 8-6 8-6 0Z', 'M-6-1 Q-1-10 6-1', 4, 5.5, 0),
    'almond': ('M-7 0 Q0-8 7-1 Q1 8-7 0Z', 'M-7 0 Q0-8 7-1', 3.8, 4.8, 0),
    'droop': ('M-7 2 Q-1-7 7-3 Q0 9-7 2Z', 'M-7 2 Q-1-7 7-3', 3.8, 4.6, 1),
    'sharp': ('M-7-6 Q0-3 7 2 Q4 7-1 6 Q-8 4-7-6Z', 'M-7-6 Q0-3 7 2', 3.2, 4.5, 0),
    'sleepy': ('M-7-1 L7-1 Q5 8-2 6 Q-6 5-7-1Z', 'M-7-1 L7-1', 3.7, 4.8, 2),
    'wide': ('M-7 0 C-7-11 7-11 7 0 C7 10-7 10-7 0Z', 'M-7-1 Q0-12 7-1', 4.6, 6.2, 0),
    'narrow': ('M-7 0 Q0-4 7-1 Q1 5-7 0Z', 'M-7 0 Q0-4 7-1', 3, 3.6, 0),
    'sparkle': ('M-7 0 C-7-10 7-10 7 0 C7 10-7 10-7 0Z', 'M-7-1 Q0-11 7-1', 5.1, 6.5, 0),
}
for name,(shape,lid,rx,ry,iy) in eye_shapes.items():
    art = ''
    for x,mirror in [(36,1),(60,-1)]:
        clip = f'eye-{name}-{x}'
        eye = f'<defs><clipPath id="{clip}"><path d="{shape}"/></clipPath></defs>'
        eye += path(shape,'#fff5eb',width=1)
        iris = ellipse(0,iy,rx,ry,'var(--eye,#3f4a41)')
        iris += ellipse(0,iy-2,rx,ry,INK,extra='opacity=".28"')
        iris += path(f'M{-rx*.65} {iy+ry*.55} Q0 {iy+ry} {rx*.65} {iy+ry*.55}',stroke='#f9db9c',width=1.3,extra='opacity=".65"')
        iris += ellipse(0,iy+.5,1.6,ry*.66,INK)
        iris += ellipse(-1.6,iy-2.5,1.8,2,'#fff')+ellipse(2.3,iy+2.7,.9,1,'#fff',extra='opacity=".85"')
        if name=='sparkle': iris+=path('M2-2 L2.5-.5 L4 0 L2.5.5 L2 2 L1.5.5 L0 0 L1.5-.5Z','#fff','none')
        eye += f'<g clip-path="url(#{clip})">{iris}</g>'
        eye += path(lid,stroke=INK,width=2.2)+path('M-6-1 L-8-3',stroke=INK,width=1.5)
        art += f'<g transform="translate({x} 44) scale({mirror} 1)">{eye}</g>'
    symbol('eyes-'+name,art)
symbol('eyes-smile',path('M29 45 Q36 34 43 45 M53 45 Q60 34 67 45',width=2.7),
       path('M30 46 Q36 42 42 46 M54 46 Q60 42 66 46',stroke='#a96f59',width=1,extra='opacity=".5"'))
symbol('brows-natural',path('M30 34 Q36 31 42 34 M54 34 Q60 31 66 34',stroke=HAIR,width=2))
symbol('brows-straight',path('M30 34 L42 35 M54 35 L66 34',stroke=HAIR,width=2.8))
symbol('brows-arched',path('M30 35 Q35 29 42 33 M54 33 Q61 29 66 35',stroke=HAIR,width=1.7))
# Expression overlays deliberately stay below hair and eyewear.
symbol('expression-bright',ellipse(48,56,7,4,SKIN),path('M42 54 Q48 57 54 54 Q53 62 48 62 Q43 61 42 54Z','#8a474b',width=1.3),path('M44 55 H52 L51 57 H45Z',CREAM,'none'))
symbol('expression-resolute',ellipse(48,56,7,4,SKIN),path('M44 56 Q48 55 52 56',stroke='#935d52',width=1.7))
symbol('expression-thoughtful',ellipse(48,56,7,4,SKIN),path('M45 57 Q49 55 53 56',stroke='#935d52',width=1.5))
symbol('expression-gentle',ellipse(48,56,7,4,SKIN),path('M42 55 Q48 61 54 55',stroke='#935d52',width=1.7))
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

# Added cuts retain the face opening; back lengths and bangs both vary.
new_hairs = {
 'sidepart': 'M24 45 Q15 22 35 15 Q61 5 73 26 L70 43 L64 30 Q50 24 28 36Z',
 'curly': 'M23 45 Q14 39 20 30 Q13 21 25 18 Q25 9 36 13 Q44 3 53 12 Q65 7 69 18 Q81 20 74 31 Q80 42 70 47 L65 33 Q57 38 51 29 Q43 38 35 30 L29 46Z',
 'undercut': 'M25 44 L24 25 Q28 14 48 13 Q68 12 73 25 L66 34 L64 25 Q46 33 28 31Z',
 'slicked': 'M25 42 Q17 17 39 13 Q67 9 73 27 L69 43 L64 29 Q48 22 31 29 L29 43Z',
 'messy': 'M23 47 L18 37 L23 29 L19 22 L32 22 L30 13 L43 18 L47 9 L56 17 L69 13 L69 24 L77 27 L71 37 L75 47 L64 42 L63 32 L52 37 L44 29 L33 36 L29 49Z',
 'centerpart': 'M24 45 Q15 19 38 13 Q48 11 48 17 Q53 8 67 17 Q78 27 71 45 L65 33 Q53 31 48 20 Q43 32 30 34 L29 46Z',
 'quiff': 'M24 43 Q17 26 32 22 Q27 9 44 8 Q67 4 73 22 Q76 33 70 45 L65 30 Q48 35 30 31Z',
 'wolf': 'M24 54 L18 58 L21 43 L18 33 L24 22 L23 17 L39 15 L46 9 L53 16 L68 14 L75 30 L72 45 L78 59 L64 53 L64 33 L54 38 L45 28 L33 37 L31 52Z',
 'buzz': 'M24 41 L25 27 Q29 15 48 16 Q67 15 71 27 L72 41 L67 36 L65 27 Q48 22 31 27 L29 36Z',
 'layered': 'M23 49 Q15 28 28 19 Q47 7 65 17 Q79 25 73 47 L67 51 L65 32 L57 38 L51 31 L42 38 L35 31 L29 49Z',
 'windswept': 'M23 45 L20 32 L25 22 L39 19 L36 10 L53 16 L61 10 L66 20 L80 19 L73 30 L76 40 L66 36 L61 28 L45 35 L31 33 L28 45Z',
 'lowtail': 'M23 44 Q18 18 40 13 Q67 8 73 27 L71 44 L66 32 Q49 22 29 33 L29 46Z',
 'highknot': 'M24 44 Q17 21 37 14 Q64 9 73 27 L71 44 L65 32 Q48 24 31 33 L29 46Z',
 'mohawk': 'M25 42 L25 27 Q31 16 41 18 L44 7 L50 11 L55 6 L60 20 Q69 21 71 31 L70 43 L64 30 L53 34 L44 24 L30 33Z',
 'pixie': 'M23 44 L20 30 Q23 14 43 13 Q64 7 73 25 L72 43 L65 35 L60 25 L47 35 L39 28 L28 41Z',
 'lob': 'M22 67 L21 31 Q25 11 47 12 Q70 9 75 31 L75 67 Q68 73 62 65 L66 34 Q51 34 47 24 Q40 35 29 35 L34 65 Q27 72 22 67Z',
 'sidebraid': 'M23 57 Q16 29 25 20 Q43 5 62 15 Q76 21 73 41 L66 35 L64 26 Q44 37 29 35 L32 56Z',
 'doublebraid': 'M23 45 Q17 18 41 12 Q67 8 73 27 L73 45 L66 35 L63 25 L53 33 L46 24 L32 35 L29 46Z',
 'lowbun': 'M23 43 Q17 20 36 15 Q61 6 74 26 L72 44 L65 33 Q49 25 29 34Z',
 'doublebun': 'M24 43 Q17 20 37 14 Q64 8 73 28 L71 44 L65 34 L58 28 L48 34 L38 27 L30 35 L29 44Z',
 'highpony': 'M24 44 Q17 21 37 13 Q64 7 73 27 L71 44 L65 33 Q50 23 29 34Z',
 'sidepony': 'M24 44 Q17 24 35 16 Q57 6 72 24 L72 43 L65 32 Q52 33 45 25 Q39 37 29 36Z',
 'ringlets': 'M21 70 Q14 62 21 53 Q13 44 21 35 Q17 17 37 13 Q65 7 74 28 L73 40 Q82 48 75 56 Q83 64 76 74 L65 74 Q60 66 66 59 Q59 51 65 43 L65 32 L54 34 L46 24 L30 35 Q36 44 29 52 Q36 60 29 68 Q32 77 21 70Z',
 'feathered': 'M23 58 L16 60 L22 47 L18 40 L24 24 Q34 10 52 13 Q70 11 75 29 L73 43 L79 56 L67 59 L64 34 L55 39 L48 28 L37 38 L30 35 L30 55Z',
 'straight': 'M22 82 L22 29 Q25 12 48 12 Q71 12 74 29 L74 82 L63 82 L65 34 L31 34 L33 82Z',
 'halfup': 'M22 73 Q17 30 24 22 Q30 12 48 12 Q70 9 74 28 L76 75 L63 76 L65 35 Q54 33 49 25 Q41 36 29 35 L34 75Z',
 'crown': 'M23 69 Q17 29 25 20 Q44 5 64 16 Q76 24 73 44 L76 70 L64 73 L65 34 L51 31 L46 24 L29 35 L33 71Z',
 'shortwave': 'M22 57 Q13 49 21 40 Q16 29 24 21 Q34 9 51 12 Q70 10 75 30 Q81 41 74 48 Q79 60 65 62 L63 51 L66 35 Q52 35 47 25 Q40 36 29 35 L32 50 L31 60Z',
 'ribbonbraid': 'M23 43 Q17 21 37 14 Q64 9 73 27 L71 44 L65 33 L55 32 L48 25 L37 35 L29 33Z',
 'sidelong': 'M22 75 Q16 28 26 20 Q44 7 63 16 Q77 25 72 44 L66 35 L64 26 Q46 39 29 36 L33 76Z',
}
new_extras = {
 'lowtail':path('M69 43 Q84 45 79 64 L83 73 Q67 71 68 53Z',HAIR),
 'highknot':ellipse(51,9,11,7,HAIR,INK)+rect(41,14,20,4,SECOND),
 'lowbun':ellipse(73,45,10,11,HAIR,INK),
 'doublebun':ellipse(23,19,10,10,HAIR,INK)+ellipse(73,19,10,10,HAIR,INK),
 'highpony':path('M64 16 Q91 10 84 43 L90 70 Q70 74 73 46 L68 28Z',HAIR),
 'sidepony':path('M71 27 Q91 24 84 49 L89 77 Q76 79 72 63 L75 45Z',HAIR),
 'halfup':path('M70 24 Q87 20 80 43 L84 58 Q71 57 71 40Z',HAIR),
 'sidelong':path('M28 51 Q13 59 20 83 Q32 94 37 78 L34 60Z',HAIR),
}
for name in ('sidebraid','doublebraid','ribbonbraid'):
    xs = [24,72] if name=='doublebraid' else [72]
    new_extras[name] = ''.join(ellipse(x+(i%2)*2,48+i*6,5,5,HAIR,INK,1.3) for x in xs for i in range(7))
    new_extras[name] += ''.join(path(f'M{x-3} 87 L{x+5} 87 L{x+2} 94Z',HAIR) for x in xs)
for name,d in new_hairs.items():
    art = new_extras.get(name,'')+path(d,HAIR)
    art += path(d,'#120c20','none',extra='opacity=".05"')
    art += seam('M30 25 Q36 18 42 18 M58 18 Q64 20 67 26')
    if name in ('slicked','undercut','buzz'):
        art += path('M31 26 Q45 17 62 22 M33 23 Q47 15 59 19',stroke='#fff5dd',width=1,extra='opacity=".3"')
    if name=='crown':
        art += ''.join(ellipse(29+i*6,25-((i-3)**2)*-.35,4,3,HAIR,INK,1) for i in range(7))
    if name=='ribbonbraid':
        art += path('M69 52 L77 59 L70 66 L77 73 L70 80',stroke=SECOND,width=2.5)
        art += path('M73 87 L64 81 L64 90 L73 88 L82 91 L82 82Z',SECOND)
    symbol('hair-'+name,art)

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

# Three cuts per profession: classic, a practical travel cut, and ornamented formal dress.
for job in jobs:
  for female in (False,True):
    name = ('miko' if female else 'miko_m') if job=='miko_m' else job+('_f' if female else '')
    travel = path('M32 69 Q48 78 64 69 L62 79 Q48 88 34 79Z',SECOND)
    travel += path('M58 80 L67 82 L71 97 L62 93Z',SECOND)+ellipse(36,76,2,2,GOLD,INK,1)
    travel += path('M34 109 L42 107 L44 116 L36 118Z',LEATHER,width=1.4)+seam('M36 111 L41 114')
    formal = path('M33 70 L39 74 L35 88 L30 84Z',SECOND)+path('M63 70 L57 74 L61 88 L66 84Z',SECOND)
    formal += path('M34 72 L37 75 L33 84 M62 72 L59 75 L63 84',stroke=GOLD,width=1.4)
    formal += path('M25 91 L30 93 M66 93 L71 91',stroke=GOLD,width=2.4)
    if job in ('samurai','ronin','miko_m'):
        travel = rect(31,95,34,8,SECOND)+path('M33 108 L39 105 L42 113 L36 116Z',LEATHER)
        travel += path('M60 98 L66 106 L59 110 L55 102Z',SECOND)
        formal = path('M34 72 L54 94 M62 72 L42 94',stroke=GOLD,width=1.5)+rect(31,95,34,8,SECOND)
        formal += ''.join(ellipse(x,99,1.5,1.5,GOLD) for x in [36,42,48,54,60])
    elif job in ('farmer','cook','crafter'):
        travel += rect(42,90,13,8,LEATHER,1)+path('M45 91 V97 M50 91 V97',stroke=GOLD,width=1)
        formal += path('M39 93 H57 M38 96 H58 M37 111 Q48 115 59 111',stroke=GOLD,width=1.2)
        formal += ellipse(48,85,2.5,3,GOLD,INK,1)
    elif job=='knight':
        travel += path('M37 86 L42 94 M59 86 L54 94',stroke=LEATHER,width=3)
        formal += path('M24 76 Q28 70 33 77 M63 77 Q68 70 72 76',stroke=GOLD,width=2)
        formal += path('M36 109 Q48 115 60 109',stroke=GOLD,width=2)
    else:
        formal += path('M39 81 Q48 94 57 81',stroke=GOLD,width=1.5)+ellipse(48,88,2,3,GOLD)
    symbol('dress-'+name+'-travel',travel)
    symbol('dress-'+name+'-formal',formal)

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
symbol('resident','<g style="--cloth:currentColor">'+''.join(parts[k] for k in ['body','eyes-round','face-m','brows-natural','legs-pants','outfit-adventurer','hair-short'])+'</g>')

out = ['<svg xmlns="http://www.w3.org/2000/svg">',
       '  <!-- Generated by scripts/guild/generate-people.py. Stable IDs preserve saved residents. -->']
for name, art in parts.items():
    out.append(f'  <symbol id="{name}" viewBox="0 0 96 144"><g stroke-linecap="round" stroke-linejoin="round">{art}</g></symbol>')
out.append('</svg>\n')
(ROOT/'src/main/resources/static/img/guild-people.svg').write_text('\n'.join(out))
print(f'Wrote {len(parts)} SD layers.')
