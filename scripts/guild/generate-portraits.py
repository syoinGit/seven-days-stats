#!/usr/bin/env python3
"""Build counter-view bust artwork (360 x 440), sharing saved identity with the SD sheet.

The torso, sleeves, hands and counter gestures are drawn for this viewport. Hair
silhouettes/eye families share the SD design, with taller faces and smaller eyes.
Run after generate-people.py; no image service or database is used.
"""
from pathlib import Path
import runpy
import re

ROOT = Path(__file__).resolve().parents[2]
sd = runpy.run_path(str(Path(__file__).with_name('generate-people.py')))
INK, SKIN, HAIR, CLOTH, SECOND, GOLD, CREAM, LEATHER = (sd[key] for key in ('INK','SKIN','HAIR','CLOTH','SECOND','GOLD','CREAM','LEATHER'))
parts = {}

def p(d, fill='none', stroke=INK, width=2.2, extra=''):
    return sd['path'](d,fill,stroke,width,extra).replace('url(#sd-skin)','url(#portrait-skin)')

def e(x,y,rx,ry,fill,stroke='none',width=2,extra=''):
    return sd['ellipse'](x,y,rx,ry,fill,stroke,width,extra).replace('url(#sd-skin)','url(#portrait-skin)')

def r(x,y,w,h,fill,radius=3,stroke=INK):
    return sd['rect'](x,y,w,h,fill,radius,stroke).replace('url(#sd-skin)','url(#portrait-skin)')

def line(d): return p(d,stroke='#fff1d3',width=1.2,extra='opacity=".5"')
def fold(d): return p(d,stroke='#312638',width=1.5,extra='opacity=".35"')
def symbol(name,*art): parts[name]=''.join(art)
def head(art): return '<g transform="translate(74 0) scale(2.2 2.6)">'+art+'</g>'

symbol('body',p('M158 154 Q180 172 202 154 L206 195 L229 208 Q180 250 131 208 L154 195Z',SKIN),
       p('M157 176 Q180 188 203 175 L204 183 Q181 197 155 185Z','#613d4a','none',extra='opacity=".17"'),
       line('M158 192 Q180 207 202 192'),p('M142 212 L159 218 M201 218 L218 212',stroke='#a87560',width=1.2))

# Dedicated adult bust proportions. Identity anchors stay aligned across all layers.
for name in sd['HEADS']:
    symbol('head-'+name,head(sd['parts']['head-'+name]))
for name,art in sd['parts'].items():
    if name.startswith(('eyes-','brows-','face-','expression-','age-','acc-','hat-')):
        if name.startswith('eyes-'):
            art=art.replace('scale(1 1)">','scale(1 1) scale(.79 .76)">').replace('scale(-1 1)">','scale(-1 1) scale(.79 .76)">')
            if name=='eyes-smile':
                art='<g transform="translate(48 44) scale(1 .8) translate(-48 -44)">'+art+'</g>'
        symbol(name,head(art))

hairs=[name.removeprefix('hair-') for name in sd['parts'] if name.startswith('hair-')]
long_hair={'long','wavy','hime','straight','lob','ringlets','halfup','crown','sidelong','feathered'}
for name in hairs:
    back=''
    if name in long_hair:
        if name in ('wavy','ringlets','feathered'):
            back=p('M126 115 Q108 150 121 188 Q104 216 116 242 Q104 266 121 294 L147 306 L147 145Z',HAIR)
            back+=p('M234 115 Q252 150 239 188 Q256 216 244 242 Q256 266 239 294 L213 306 L213 145Z',HAIR)
            back+=line('M123 180 Q135 209 123 234 M237 180 Q225 209 237 234')
        else:
            back=p('M126 115 Q113 177 116 282 L143 310 L148 150Z',HAIR)+p('M234 115 Q247 177 244 282 L217 310 L212 150Z',HAIR)
            back+=line('M125 177 L124 277 M235 177 L236 277')
    symbol('hair-back-'+name,back)
    symbol('hair-'+name,head(sd['parts']['hair-'+name]))

jobs=sd['jobs']

def coat(fill,female):
    waist=110 if female else 101
    return p(f'M132 194 L104 207 Q91 214 87 234 L{waist} 440 H{360-waist} L273 234 Q269 214 256 207 L228 194 Q180 215 132 194Z',fill)+p(f'M239 212 Q266 232 261 278 L{360-waist} 440 L{346-waist} 440 L239 296Z','#221c34','none',extra='opacity=".15"')+fold('M116 254 L135 270 M244 254 L225 270 M130 356 L140 371 M230 356 L220 371')+line(f'M{waist+8} 405 L{waist+10} 435 M{352-waist} 405 L{350-waist} 435')

def collar():
    return p('M133 193 L178 217 L151 244 L123 208Z',CREAM)+p('M227 193 L182 217 L209 244 L237 208Z',CREAM)+line('M134 200 L152 233 M226 200 L208 233')

def badge():
    return p('M180 258 L188 278 L209 284 L188 292 L180 315 L172 292 L151 284 L172 278Z',GOLD,width=1.6)

def apron(fill):
    return p('M144 224 H216 L216 284 L238 427 Q180 442 122 427 L144 284Z',fill)+p('M150 319 H210 L207 358 H153Z',fill)+line('M151 326 V350 H202 M128 421 Q180 433 232 421')+fold('M146 284 L155 295 M214 284 L205 295 M146 365 L139 402 M214 365 L221 402')

for job in jobs:
    for female in (False,True):
        name=('miko' if female else 'miko_m') if job=='miko_m' else job+('_f' if female else '')
        sleeve='#9faeb4' if job=='knight' else CREAM if job=='miko_m' else CLOTH
        art=coat(sleeve,female)+collar()
        if job in ('farmer','cook','crafter'):
            art+=apron(LEATHER if job=='crafter' else CREAM)
            if job=='crafter':
                art+=p('M160 325 V352 M180 325 V352 M200 325 V352',stroke=INK,width=1.4)
                art+=p('M160 332 V312 M178 333 V305 M192 331 L197 310',stroke=GOLD,width=3)
            if job=='cook': art+=p('M156 205 L180 224 L204 205 L188 237 H172Z',SECOND)
            if job=='farmer': art+=p('M145 271 L139 245 M142 254 L131 249 M142 258 L152 250',stroke='#71825b',width=2)
        elif job=='knight':
            art=coat('#9faeb4',female)+p('M127 225 Q180 247 233 225 L229 343 Q180 374 131 343Z',CLOTH)+badge()
            art+=p('M128 225 Q180 251 232 225 M133 340 Q180 362 227 340',stroke=GOLD,width=2.4)
            art+=line('M116 251 L122 325 M244 251 L238 325')
            art+=''.join(e(x,y,2,2,GOLD) for x,y in [(125,242),(235,242),(134,328),(226,328)])
        elif job=='merchant':
            art+=p('M136 201 L175 221 L162 355 L119 351Z',SECOND)+p('M224 201 L185 221 L198 355 L241 351Z',SECOND)
            art+=''.join(e(180,y,3,3,GOLD,INK,1) for y in [249,276,303,330])
            art+=p('M210 278 H229 L226 300 H213Z',LEATHER)+line('M215 282 H225')
        elif job=='cleric':
            art+=p('M134 195 L160 208 L143 428 L114 424Z',CREAM)+p('M226 195 L200 208 L217 428 L246 424Z',CREAM)
            art+=p('M154 211 Q180 309 206 211',stroke=GOLD,width=2)+p('M180 268 L192 286 L180 304 L168 286Z',GOLD)
            art+=p('M132 374 L139 385 L132 396 L125 385Z M228 374 L235 385 L228 396 L221 385Z',GOLD,width=1.2)
        elif job in ('watch','hunter','adventurer'):
            art+=p('M125 196 Q180 230 235 196 L239 238 Q180 270 121 238Z',SECOND)
            art+=p('M211 237 L244 248 L253 319 L220 300Z',SECOND)
            art+=p('M131 252 L227 362',stroke=LEATHER,width=12)+line('M132 248 L229 359')
            art+=r(171,300,16,16,GOLD,2)+r(175,304,8,8,LEATHER,1)
        else:
            art=coat(CREAM if job=='miko_m' else CLOTH,female)
            art+=p('M134 195 L209 292 M226 195 L153 292',stroke=CREAM,width=16)
            art+=line('M136 197 L211 294 M224 197 L151 294')
            art+=p('M130 299 H230 L237 440 H123Z','#ad4c4a' if job=='miko_m' else SECOND)
            art+=r(124,296,112,25,SECOND,2)+fold('M147 326 L139 435 M180 326 V437 M213 326 L221 435')
            if job=='ronin': art+=p('M135 356 L157 350 L163 379 L142 385Z',LEATHER)+line('M141 359 L158 376')
        if job not in ('samurai','ronin','miko_m'):
            art+=p('M120 383 Q180 398 240 383 L239 402 Q180 416 121 402Z',LEATHER)+r(167,388,26,23,GOLD,3)+r(174,394,12,11,LEATHER,1)
        symbol('outfit-'+name,art)
        travel=p('M126 196 Q180 233 234 196 L230 224 Q180 262 130 224Z',SECOND)+p('M213 229 L247 245 L250 290 L218 275Z',SECOND)+e(139,215,5,5,GOLD,INK,1.4)
        formal=p('M130 199 L144 212 L132 254 L116 244Z',SECOND)+p('M230 199 L216 212 L228 254 L244 244Z',SECOND)+p('M131 207 L139 215 L129 245 M229 207 L221 215 L231 245',stroke=GOLD,width=2)
        formal+=p('M150 245 Q180 277 210 245',stroke=GOLD,width=1.6)+e(180,264,4,5,GOLD,INK,1)
        if job in ('samurai','ronin','miko_m'):
            travel=r(125,297,110,25,SECOND)+p('M218 311 L236 330 L217 345 L205 324Z',SECOND)
            formal=p('M136 200 L207 291 M224 200 L153 291',stroke=GOLD,width=2)+r(125,297,110,25,SECOND)+''.join(e(x,310,3,3,GOLD) for x in [140,160,180,200,220])
        symbol('dress-'+name+'-travel',travel)
        symbol('dress-'+name+'-formal',formal)

        def sleeve_path(d): return p(d,sleeve)+line('M95 258 L99 288 M265 258 L261 288')
        def left_rest():
            return sleeve_path('M105 211 Q72 221 65 255 L61 341 Q59 394 142 409 L153 383 L102 365 L113 263Z')+p('M145 382 Q160 380 172 390 L185 394 Q192 402 180 406 L146 410 L138 403Z',SKIN)+p('M153 393 L169 399 M152 399 L166 404',stroke='#9f6c5b',width=1)
        def right_rest():
            return sleeve_path('M255 211 Q288 221 295 255 L299 341 Q301 394 218 409 L207 383 L258 365 L247 263Z')+p('M215 382 Q200 380 188 390 L175 394 Q168 402 180 406 L214 410 L222 403Z',SKIN)+p('M207 393 L191 399 M208 399 L194 404',stroke='#9f6c5b',width=1)
        for pose in sd['POSES']:
            left,right=left_rest(),right_rest()
            if pose=='wave':
                left=sleeve_path('M105 211 Q83 205 74 176 L70 156 L43 163 Q35 215 70 264 L102 270 L117 231Z')
                left+=p('M44 165 L33 129 Q31 120 37 122 L49 138 L40 105 Q39 98 45 102 L59 130 L56 96 Q58 87 63 97 L69 131 L76 106 Q81 99 84 109 L79 146 Q81 162 67 172Z',SKIN)
                left+=fold('M49 143 Q65 136 72 146 M56 153 L65 154')
            elif pose=='crossed':
                left=sleeve_path('M105 211 Q70 226 71 294 Q90 355 226 350 L239 316 L119 287 L117 240Z')+p('M226 316 Q255 300 268 314 Q280 331 242 350 L225 350Z',SKIN)
                right=sleeve_path('M255 211 Q290 225 288 283 Q264 334 134 331 L124 299 L241 275 L241 235Z')+p('M135 299 Q109 281 96 294 Q85 309 124 331 L139 330Z',SKIN)+line('M151 315 L248 292')
            elif pose=='mug':
                right=sleeve_path('M255 211 Q282 224 291 269 L294 326 L245 368 L218 344 L255 311 L240 256Z')
                right+=r(206,300,56,64,'#c2a578',7)+p('M262 312 Q294 311 290 337 Q285 355 262 350',stroke=GOLD,width=5)
                right+=e(234,301,28,7,'#573b32',INK,1.8)+p('M220 336 Q197 328 195 340 Q198 357 221 362 L231 350Z',SKIN)+line('M219 311 V330')
            elif pose=='think':
                left=sleeve_path('M105 211 Q73 223 69 296 Q71 342 99 337 L157 240 L134 216 L96 282 L117 245Z')
                left+=p('M133 237 L132 205 L143 177 Q146 168 152 177 L151 198 L165 193 Q180 192 179 203 L158 242Z',SKIN)+fold('M145 207 L164 205 M145 214 L158 213')
            if job=='knight':
                left=p('M104 211 Q77 203 69 241 L107 259 L124 224Z','#c9d3ce')+left
                right=p('M256 211 Q283 203 291 241 L253 259 L236 224Z','#c9d3ce')+right
            symbol('pose-'+name+'-'+pose,left+right)

# Legacy fallback uses the same dedicated bust artwork and the resident's old tint.
legacy=''.join(parts[key] for key in ['body','head-soft','eyes-round','face-m','brows-natural','outfit-adventurer','pose-adventurer-rest','hair-short'])
for local_id in re.findall(r'\bid="([^"]+)"',legacy):
    legacy=legacy.replace(f'id="{local_id}"',f'id="portrait-legacy-{local_id}"').replace(f'url(#{local_id})',f'url(#portrait-legacy-{local_id})')
symbol('resident','<g style="--cloth:currentColor">'+legacy+'</g>')

finishes=sd['finishes']+sd['gradient']('portrait-skin',[('0','#fff1ce',.18),('.45','#ffe6c0',.03),('1','#653949',.16)],'gradientUnits="userSpaceOnUse" x1="120" y1="140" x2="270" y2="420"').replace('sd-portrait-skin','portrait-skin')
output=['<svg xmlns="http://www.w3.org/2000/svg">','<!-- Counter-view busts. Generated by scripts/guild/generate-portraits.py. -->',f'<defs id="portrait-finishes">{finishes}</defs>']
for name,art in parts.items():
    output.append(f'<symbol id="{name}" viewBox="0 0 360 440"><g stroke-linecap="round" stroke-linejoin="round">{art}</g></symbol>')
output.append('</svg>\n')
(ROOT/'src/main/resources/static/img/guild-portraits.svg').write_text('\n'.join(output))
print(f'Wrote {len(parts)} counter portrait layers.')
