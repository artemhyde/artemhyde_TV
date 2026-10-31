#!/usr/bin/env bash
# rowbench.sh: замер плавности ходьбы по плиткам: 8 нажатий вправо и влево, сводка по шагам между кадрами и времени видеоядра.
# Запуск: TV=192.168.0.10:5555 bash tools/rowbench.sh [--tv адрес] <метка> [пакет]   (пакет по умолчанию app.glasstv.home)
# Условия: ТВ свободен (кнопки уходят в приложение на экране), на экране ряд плиток. Метка нужна для имени файла замера.
# Результат: строка со счётчиком шагов (1 = кадр вовремя, 2 и больше = пропуски) и файл fs_<метка>.txt в рабочей папке (TV_WORK, по умолчанию backup/.tv-work: в git не попадает) для fstats.py.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
gt_parse_tv "$@"; set -- ${GT_REST[@]+"${GT_REST[@]}"}
gt_find_adb; gt_find_python
label="${1:?нужна метка замера, например before или after}"
pkg="${2:-app.glasstv.home}"
W="${TV_WORK:-$GT_ROOT/backup/.tv-work}"; mkdir -p "$W"
out="$W/fs_$label.txt"

tv_sh "dumpsys gfxinfo $pkg reset" >/dev/null
for k in RIGHT RIGHT LEFT LEFT RIGHT LEFT RIGHT LEFT; do
    tv_sh "input keyevent KEYCODE_DPAD_$k"
    sleep 0.25
done
tv_sh "dumpsys gfxinfo $pkg framestats" | tr -d '\r' > "$out"

$PY -I -X utf8 - "$(gt_native "$out")" "$label" <<'PY'
import statistics as st
import sys
from collections import Counter

L = open(sys.argv[1], encoding='utf-8', errors='replace').read().splitlines()
hdr = None
rows = []
for ln in L:
    if ln.startswith('Flags,'):
        hdr = ln.strip().rstrip(',').split(','); continue
    if hdr and ln and ln[0].isdigit():
        v = ln.strip().rstrip(',').split(',')
        if len(v) >= len(hdr):
            rows.append(dict(zip(hdr, map(int, v[:len(hdr)]))))
rows = [r for r in rows if r['Flags'] == 0]
if len(rows) < 2:
    sys.exit('мало кадров: приложение не рисовало или пакет назван неверно')
iv = [r['IntendedVsync'] for r in rows]
c = Counter(round((b - a) / 1e6 / 16.67) for a, b in zip(iv, iv[1:]))
gpu = [(r['GpuCompleted'] - r['SwapBuffers']) / 1e6 for r in rows if r['GpuCompleted'] > 0]
skipped = 100 * sum(v for k, v in c.items() if k > 1) / max(1, sum(c.values()))
print(sys.argv[2], 'кадров', len(rows), 'шаги', dict(c), 'пропуски %.0f%%' % skipped,
      'ГП медиана %.1f' % (st.median(gpu) if gpu else 0))
PY
