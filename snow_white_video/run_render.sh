#!/bin/bash
# 分段渲染 + 拼接 + 混音；已完成的段落会跳过，便于中断后继续。
set -u
cd "$(dirname "$0")"
TOTAL=$(python3 -c "import film;print(int(round(film.load()['total']*film.FPS)))")
DUR=$(python3 -c "import film;print('%.3f'%film.load()['total'])")
FADE=$(python3 -c "import film;print('%.3f'%(film.load()['total']-2.2))")
SEGS=4
: > out/list.txt
for k in $(seq 0 $((SEGS-1))); do
  S=$(( k*TOTAL/SEGS )); E=$(( (k+1)*TOTAL/SEGS ))
  OUT="out/seg_$k.mp4"
  WANT=$(( E-S ))
  if [ -f "$OUT" ]; then
    N=$(ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "$OUT" 2>/dev/null)
    if [ "$N" = "$WANT" ]; then
      echo "[skip] seg $k ($N frames)"; echo "file 'seg_$k.mp4'" >> out/list.txt; continue
    fi
  fi
  echo "[render] seg $k frames $S..$E"
  python3 compose.py --start "$S" --end "$E" --video-out "$OUT" || exit 1
  echo "file 'seg_$k.mp4'" >> out/list.txt
done

echo "[concat] $(cat out/list.txt | tr '\n' ' ')"
ffmpeg -y -hide_banner -loglevel error -f concat -safe 0 -i out/list.txt -c copy out/video_only.mp4 || exit 1
echo "[mux] audio + fade"
ffmpeg -y -hide_banner -loglevel error -i out/video_only.mp4 -i audio/final.wav -t "$DUR" \
  -af "afade=t=in:st=0:d=1.2,afade=t=out:st=$FADE:d=2.2" \
  -c:v copy -c:a aac -b:a 192k -ar 44100 -movflags +faststart out/snow_white.mp4 || exit 1
echo "DONE out/snow_white.mp4"
