# -*- coding: utf-8 -*-
"""生成 storyboard.html：分镜 + 旁白 + 时间码，便于在预览器里翻阅。"""
import html
import json
import os

import film

tl = film.load()
rows = []
for sc in tl["scenes"]:
    img = f"frames/scene_{sc['index'] + 1:02d}_{sc['kind']}.png"
    assert os.path.exists(img), img
    t = int(round(sc["vstart"]))
    rows.append(f"""
    <figure class="card">
      <img src="{img}" alt="{html.escape(sc['subtitle'])}">
      <figcaption>
        <div class="meta"><span class="no">{sc['index'] + 1:02d}</span>
          <span class="time">{t // 60:02d}:{t % 60:02d}</span></div>
        <h3>{html.escape(sc['subtitle'] or '—')}</h3>
        <p>{html.escape(sc.get('narr', ''))}</p>
      </figcaption>
    </figure>""")

# 旁白文案从 story.py 读取
import story
narr = {s["key"]: s["narration"] for s in story.SCENES}
for i, sc in enumerate(tl["scenes"]):
    rows[i] = rows[i].replace("<p></p>", f"<p>{html.escape(narr.get(sc['key'], '') or '（无旁白，仅音乐）')}</p>")

doc = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>白雪公主与七个小矮人 · 分镜</title>
<style>
  :root {{ --ink:#f3ece0; --dim:#b9af9f; --bg:#12100f; --gold:#d8b57a; }}
  * {{ box-sizing:border-box; }}
  body {{ margin:0; background:radial-gradient(1200px 600px at 50% -10%,#241d1c,#0d0b0b 70%);
         color:var(--ink); font-family:"Songti SC","Source Han Serif SC",Georgia,serif; }}
  header {{ padding:56px 24px 28px; text-align:center; }}
  header h1 {{ font-size:44px; letter-spacing:.12em; margin:0 0 10px; font-weight:600; }}
  header .sub {{ color:var(--dim); letter-spacing:.28em; font-size:13px; text-transform:uppercase; }}
  header .info {{ margin-top:18px; color:var(--dim); font-size:14px; letter-spacing:.06em; }}
  header .info b {{ color:var(--gold); font-weight:600; }}
  .rule {{ width:120px; height:1px; background:linear-gradient(90deg,transparent,var(--gold),transparent);
           margin:22px auto; }}
  main {{ display:grid; grid-template-columns:repeat(auto-fill,minmax(340px,1fr)); gap:26px;
          max-width:1280px; margin:0 auto; padding:10px 24px 72px; }}
  .card {{ margin:0; background:#191514; border:1px solid #2c2422; border-radius:14px; overflow:hidden;
           box-shadow:0 18px 40px rgba(0,0,0,.45); }}
  .card img {{ display:block; width:100%; aspect-ratio:16/9; object-fit:cover; }}
  figcaption {{ padding:16px 18px 20px; }}
  .meta {{ display:flex; justify-content:space-between; align-items:center; font-size:12px;
           letter-spacing:.18em; color:var(--gold); }}
  .no {{ border:1px solid #4a3b30; border-radius:6px; padding:2px 8px; }}
  .time {{ color:var(--dim); font-variant-numeric:tabular-nums; }}
  h3 {{ font-size:17px; margin:10px 0 8px; font-weight:600; letter-spacing:.04em; }}
  p {{ margin:0; color:#c3b9ab; font-size:13.5px; line-height:1.85; }}
  footer {{ text-align:center; color:#6d6358; font-size:12px; padding:0 0 48px; letter-spacing:.1em; }}
</style></head>
<body>
<header>
  <div class="sub">Snow White and the Seven Dwarfs</div>
  <h1>白雪公主与七个小矮人</h1>
  <div class="rule"></div>
  <div class="info">14 个镜头 · 片长 <b>{int(tl['total'])//60} 分 {int(tl['total'])%60} 秒</b> ·
    1920×1080 / 30fps · 中文旁白 + 八音盒配乐</div>
</header>
<main>{''.join(rows)}</main>
<footer>剧本、插画、配音、配乐、剪辑均由本地脚本生成</footer>
</body></html>
"""

open("storyboard.html", "w").write(doc)
print("wrote storyboard.html", len(doc), "bytes")
