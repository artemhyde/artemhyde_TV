# fsum.py: короткая сводка замера кадров одной строкой: синхронизация, выдача команд, весь кадр (медианы в миллисекундах).
# Запуск: adb shell dumpsys gfxinfo app.glasstv.home framestats > fs.txt; python -I -X utf8 fsum.py fs.txt
import statistics as st
import sys

sys.stdout.reconfigure(encoding='utf-8')
if len(sys.argv) < 2:
    sys.exit(__doc__)
L = open(sys.argv[1], encoding='utf-8', errors='replace').read().splitlines()
hdr = None
rows = []
for ln in L:
    if ln.startswith('Flags,'):
        hdr = ln.strip().rstrip(',').split(',')
        continue
    if hdr and ln and ln[0].isdigit():
        v = ln.strip().rstrip(',').split(',')
        if len(v) >= len(hdr):
            rows.append(dict(zip(hdr, map(int, v[:len(hdr)]))))
rows = [r for r in rows if r['Flags'] == 0]
if not rows:
    print('нет кадров')
    sys.exit()
f = lambda a, b: st.median([(r[b] - r[a]) / 1e6 for r in rows if r[b] > 0 and r[a] > 0])
print('синхр %.1f команды %.1f всего %.1f' % (f('DrawStart', 'SyncStart'), f('IssueDrawCommandsStart', 'SwapBuffers'), f('IntendedVsync', 'FrameCompleted')))
