# fstats.py: разбор замера кадров (dumpsys gfxinfo <пакет> framestats): где тратится время кадра, по стадиям.
# Запуск: adb shell dumpsys gfxinfo app.glasstv.home framestats > fs.txt; python -I -X utf8 fstats.py fs.txt
# Перед замером удобно сбросить счётчики: adb shell dumpsys gfxinfo app.glasstv.home reset, затем пройтись по экрану кнопками.
import statistics as st
import sys

sys.stdout.reconfigure(encoding='utf-8')
if len(sys.argv) < 2:
    sys.exit(__doc__)
lines = open(sys.argv[1], encoding='utf-8', errors='replace').read().splitlines()
hdr = None
rows = []
for ln in lines:
    if ln.startswith('Flags,'):
        hdr = ln.strip().rstrip(',').split(',')
        continue
    if hdr and ln and ln[0].isdigit() and ',' in ln:
        v = ln.strip().rstrip(',').split(',')
        if len(v) >= len(hdr):
            rows.append(dict(zip(hdr, map(int, v[:len(hdr)]))))
rows = [r for r in rows if r['Flags'] == 0]
print('кадров', len(rows))
if not rows:
    sys.exit()
ms = lambda a, b: [(r[b] - r[a]) / 1e6 for r in rows if r[a] > 0 and r[b] > 0]
stages = [
    ('ввод+анимации', 'IntendedVsync', 'PerformTraversalsStart'),
    ('обход и запись', 'PerformTraversalsStart', 'SyncQueued'),
    ('синхронизация', 'SyncStart', 'IssueDrawCommandsStart'),
    ('выдача команд', 'IssueDrawCommandsStart', 'SwapBuffers'),
    ('свап', 'SwapBuffers', 'SwapBuffersCompleted'),
    ('ГП', 'SwapBuffers', 'GpuCompleted'),
    ('весь кадр', 'IntendedVsync', 'FrameCompleted'),
]
for name, a, b in stages:
    if a in rows[0] and b in rows[0]:
        d = ms(a, b)
        if d:
            d.sort()
            print(f'{name:16s} медиана {st.median(d):6.1f}  90% {d[int(len(d)*0.9)-1]:6.1f}  макс {d[-1]:6.1f}')
if 'DequeueBufferDuration' in rows[0]:
    d = sorted(r['DequeueBufferDuration'] / 1e6 for r in rows)
    print(f'{"ожидание буфера":16s} медиана {st.median(d):6.1f}  макс {d[-1]:6.1f}')
iv = [r['IntendedVsync'] for r in rows]
gaps = sorted((b - a) / 1e6 for a, b in zip(iv, iv[1:]))
if gaps:
    print('шаг между кадрами: медиана %.1f мс, 90%% %.1f' % (st.median(gaps), gaps[int(len(gaps) * 0.9) - 1]))
