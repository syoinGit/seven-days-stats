#!/usr/bin/env python3
"""Create a local visual review page (no database / AI calls).
Run this, then: python3 -m http.server 18765 --bind 127.0.0.1 --directory target/guild-sd-preview
"""
from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[2]
out = root/'target/guild-sd-preview'
out.mkdir(parents=True, exist_ok=True)
for name in ('img/guild-people.svg', 'css/guild.css'):
    target = out/name
    target.parent.mkdir(exist_ok=True)
    shutil.copyfile(root/'src/main/resources/static'/name, target)

jobs = [('farmer','農家'),('watch','夜警'),('knight','騎士'),('crafter','職人'),('merchant','商人'),('cleric','聖職者'),('adventurer','冒険者'),('hunter','猟師'),('cook','料理人'),('samurai','侍'),('ronin','浪人'),('miko_m','神職')]
hats = {'farmer','watch','knight','knight_f','crafter','crafter_f','merchant','hunter','cook','cook_f','cleric_f','ronin','miko_m'}
full = {'farmer','knight','knight_f','cleric','cleric_f','farmer_f','merchant_f','cook_f','samurai','samurai_f','ronin','ronin_f','miko','miko_m'}
mhair = ['short','cropped','spiky','swept','shaggy','topknot','tail']
fhair = ['long','bob','tied','braid','twintail','bun','wavy','hime']
palettes = [('#6e4630','#e6b48e','#668276','#415653'),('#d6a85a','#f3d2b3','#596f8a','#3a405c'),('#2b2224','#a8704c','#a05b56','#603b47'),('#8c8c96','#c48a62','#887454','#535e70')]
expressions = ['gentle','resolute','bright','thoughtful']
figures = []
for i,(job,label) in enumerate(jobs):
  for female in (False,True):
    outfit = ('miko' if female else 'miko_m') if job=='miko_m' else job+('_f' if female else '')
    hair = fhair[i%8] if female else mhair[i%7]
    layers = ['body','face-f' if female else 'face-m','expression-'+expressions[i%4]]
    if i%4==3: layers+=['age-elder']
    if outfit not in full: layers+=['legs-skirt' if female else 'legs-pants']
    layers+=['outfit-'+outfit,'hair-'+hair]
    accessories = ['ribbon','flower','earrings','hairband','glasses'] if female else ['beard','mustache','stubble','eyepatch','scar','glasses']
    layers+=['acc-'+accessories[i%len(accessories)]]
    if outfit in hats: layers+=['hat-'+outfit]
    hc,skin,c1,c2 = palettes[(i+int(female))%4]
    style=f'--hair:{hc};--skin:{skin};--cloth:{c1};--cloth2:{c2};--eye:#4d6455'
    uses=''.join(f'<use href="img/guild-people.svg?v=4#{layer}"/>' for layer in layers)
    svg=f'<svg class="guild-sprite" viewBox="0 0 96 144" style="{style}" aria-hidden="true">{uses}</svg>'
    figures.append((label,svg))
html='''<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Guild SD visual review</title><link rel="stylesheet" href="css/guild.css"><style>
body{margin:0;background:#201c27;color:#f5e5c3;font:15px system-ui}main{max-width:1200px;margin:auto;padding:28px}h1{font-size:26px}p{color:#c5b7ae;line-height:1.8}.gallery{display:grid;grid-template-columns:repeat(auto-fit,minmax(175px,1fr));gap:14px}.sample{border:1px solid #65534d;border-radius:12px;background:radial-gradient(ellipse at 50% 50%,#55463e,#302932);text-align:center;padding:16px 4px}.portrait .guild-sprite{width:144px;height:216px}.scales{display:flex;gap:12px;justify-content:center;align-items:end;border-top:1px solid #ffffff18;padding-top:10px}.tiny .guild-sprite{width:32px;height:48px}.town-room{height:230px;position:relative;background:repeating-linear-gradient(0deg,#534039 0 40px,#43322e 40px 43px);border:6px solid #80634c;margin:24px 0;overflow:hidden}.town-room .room-person{bottom:25px}.sample h2{font-size:14px;font-weight:500;margin:4px}.legacy{display:flex;flex-wrap:wrap;gap:30px;align-items:center;margin:24px 0}.legacy .guild-sprite{width:96px;height:144px}
</style><main><h1>GUILD · SDキャラクター</h1><p>同じ素材を立ち絵・街・一覧のサイズで表示。既存の髪型、衣装、色を引き継ぐ、96 × 144のレイヤー式SVG。</p>'''
html+='<div class="town-room">'+''.join(f'<div class="room-person" style="left:{8+i*12}%">{svg}<span class="person-name">{label}</span></div>' for i,(label,svg) in enumerate(figures[::3][:8]))+'</div><div class="gallery">'
for label,svg in figures:
    html+=f'<article class="sample"><h2>{label}</h2><div class="portrait">{svg}</div><div class="scales"><span>{svg}</span><span class="tiny">{svg}</span></div></article>'
html+='</div><div class="legacy">旧データの表示：'+''.join(f'<svg class="guild-sprite {tint}" viewBox="0 0 96 144"><use href="img/guild-people.svg?v=4#resident"/></svg>' for tint in ['ember','moss','violet'])+'</div></main></html>'
(out/'index.html').write_text(html)
print(out/'index.html')
