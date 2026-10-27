#!/usr/bin/env python3
"""Create a local visual review page (no database / AI calls).
Run this, then: python3 -m http.server 18765 --bind 127.0.0.1 --directory target/guild-sd-preview
"""
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
out = root/'target/guild-sd-preview'
out.mkdir(parents=True, exist_ok=True)
for name in ('img/guild-people.svg', 'css/guild.css'):
    target = out/name
    target.parent.mkdir(exist_ok=True)
    shutil.copyfile(root/'src/main/resources/static'/name, target)
ids = [element.attrib['id'] for element in ET.parse(out/'img/guild-people.svg').getroot()]
hairs = [name.removeprefix('hair-') for name in ids if name.startswith('hair-')]
eyes = [name.removeprefix('eyes-') for name in ids if name.startswith('eyes-')]
eye_names = ['丸目','アーモンド','たれ目','つり目','半目','ぱっちり','細目','きらめき','笑い目']
jobs = [('farmer','農家'),('watch','夜警'),('knight','騎士'),('crafter','職人'),('merchant','商人'),('cleric','聖職者'),('adventurer','冒険者'),('hunter','猟師'),('cook','料理人'),('samurai','侍'),('ronin','浪人'),('miko_m','神職')]
hats = {'farmer','watch','knight','knight_f','crafter','crafter_f','merchant','hunter','cook','cook_f','cleric_f','ronin','miko_m'}
full = {'farmer','knight','knight_f','cleric','cleric_f','farmer_f','merchant_f','cook_f','samurai','samurai_f','ronin','ronin_f','miko','miko_m'}
palettes = [('#6e4630','#e6b48e','#668276','#415653'),('#d6a85a','#f3d2b3','#596f8a','#3a405c'),('#2b2224','#a8704c','#a05b56','#603b47'),('#8c8c96','#c48a62','#887454','#535e70')]

def figure(outfit='adventurer_f',hair='bob',eye='round',brow='natural',variant='classic',female=True,palette=0,hat=False,face=False):
    layers = ['body','eyes-'+eye,'face-f' if female else 'face-m','brows-'+brow]
    if outfit not in full: layers+=['legs-skirt' if female else 'legs-pants']
    layers+=['outfit-'+outfit]
    if variant!='classic': layers+=['dress-'+outfit+'-'+variant]
    layers+=['hair-'+hair]
    if hat and outfit in hats: layers+=['hat-'+outfit]
    assert all(layer in ids for layer in layers), layers
    hc,skin,c1,c2 = palettes[palette%4]
    style=f'--hair:{hc};--skin:{skin};--cloth:{c1};--cloth2:{c2};--eye:#4d6455'
    uses=''.join(f'<use width="96" height="144" href="img/guild-people.svg?v=5#{layer}"/>' for layer in layers)
    return f'<svg class="guild-sprite" viewBox="{"16 8 64 64" if face else "0 0 96 144"}" style="{style}" aria-hidden="true">{uses}</svg>'

def cards(items, scales=True):
    html='<div class="gallery">'
    for label,svg in items:
        html+=f'<article class="sample"><h3>{label}</h3><div class="portrait">{svg}</div>'
        if scales: html+=f'<div class="scales"><span>{svg}</span><span class="tiny">{svg}</span></div>'
        html+='</article>'
    return html+'</div>'

html='''<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Guild SD visual review</title><link rel="stylesheet" href="css/guild.css"><style>
body{margin:0;background:#201c27;color:#f5e5c3;font:15px system-ui}main{max-width:1200px;margin:auto;padding:28px}h1{font-size:26px}p{color:#c5b7ae;line-height:1.8}.gallery{display:grid;grid-template-columns:repeat(auto-fit,minmax(175px,1fr));gap:14px}.sample{border:1px solid #65534d;border-radius:12px;background:radial-gradient(ellipse at 50% 50%,#55463e,#302932);text-align:center;padding:16px 4px}.portrait .guild-sprite{width:144px;height:216px}.scales{display:flex;gap:12px;justify-content:center;align-items:end;border-top:1px solid #ffffff18;padding-top:10px}.tiny .guild-sprite{width:32px;height:48px}.town-room{height:230px;position:relative;background:repeating-linear-gradient(0deg,#534039 0 40px,#43322e 40px 43px);border:6px solid #80634c;margin:24px 0;overflow:hidden}.town-room .room-person{bottom:25px}.sample h3{font-size:14px;font-weight:500;margin:4px}.legacy{display:flex;flex-wrap:wrap;gap:30px;align-items:center;margin:24px 0}.legacy .guild-sprite{width:96px;height:144px}.faces .portrait .guild-sprite{width:144px;height:144px}
</style><main><h1>GUILD · 顔で見分けるSDキャラクター</h1><p>目9種類・眉3種類・髪45種類・職業別24衣装 × 3差分。比較用の架空データです。</p>'''
html+='<section class="faces"><h2>目の形（髪・肌・瞳の色は共通）</h2><p>形の差を比べるため、基本表情と装飾品は付けていません。</p>'
html+=cards([(label,figure(eye=eye,face=True)) for eye,label in zip(eyes,eye_names)],False)+'</section>'
html+='<h2>眉の形</h2>'+cards([(brow,figure(brow=brow,face=True)) for brow in ['natural','straight','arched']],False)
html+='<h2>街での表示</h2><div class="town-room">'+''.join(f'<div class="room-person" style="left:{8+i*12}%">{figure(hair=hairs[i*3],eye=eyes[i],variant=["classic","travel","formal"][i%3],palette=i)}<span class="person-name">住人{i+1}</span></div>' for i in range(8))+'</div>'
html+='<h2>髪型45種類</h2>'+cards([(hair,figure(hair=hair,eye=eyes[i%9],brow=['natural','straight','arched'][i%3],palette=i)) for i,hair in enumerate(hairs)])
figures=[]
for i,(job,label) in enumerate(jobs):
    for female in (False,True):
        outfit = ('miko' if female else 'miko_m') if job=='miko_m' else job+('_f' if female else '')
        for variant,cut in [('classic','普段着'),('travel','旅装'),('formal','晴れ着')]:
            figures.append((label+'・'+cut,figure(outfit,hairs[i%len(hairs)],eyes[i%9],variant=variant,female=female,palette=i,hat=True)))
html+='<h2>衣装72パターン</h2>'+cards(figures)
html+='<div class="legacy">旧データの表示：'+''.join(f'<svg class="guild-sprite {tint}" viewBox="0 0 96 144"><use href="img/guild-people.svg?v=5#resident"/></svg>' for tint in ['ember','moss','violet'])+'</div></main></html>'
(out/'index.html').write_text(html)
print(out/'index.html')
