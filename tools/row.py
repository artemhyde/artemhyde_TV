# row.py: на экране с рядами плиток (у плиток есть content-desc) вычислить, как дойти кнопками от фокуса до плитки с нужным именем.
# Запуск: python -I -X utf8 row.py ui.xml "Имя"  ->  "<вниз 1, вверх -1, в той же строке 0> <сдвиг вправо или влево в строке>"; пусто, если плитки или фокуса нет.
# Если плиток с таким именем несколько, берётся ближайшая к фокусу по вертикали. Размеры плиток рассчитаны на экран 1920x1080.
import re
import sys

if len(sys.argv) < 3:
    sys.exit(__doc__)
x = open(sys.argv[1], encoding='utf-8').read()
want = sys.argv[2]
nodes = []
for n in re.findall(r'<node [^>]*>', x):
    m = re.search(r'content-desc="([^"]*)"', n)
    d = m.group(1) if m else ''
    b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]+)"', n).group(1))))
    f = 'focused="true"' in n
    if d and b[2] - b[0] < 700 and b[3] - b[1] < 300 and b[1] >= 200:
        nodes.append((d, b, f))
foc = [t for t in nodes if t[2]]
if not foc:
    sys.exit()
cy = lambda t: (t[1][1] + t[1][3]) // 2
fy = cy(foc[0])
tgt = sorted([t for t in nodes if t[0] == want], key=lambda t: abs(cy(t) - fy))
if not tgt:
    sys.exit()
ty = cy(tgt[0])
same = sorted([t for t in nodes if abs(cy(t) - ty) < 60], key=lambda t: t[1][0])
if abs(fy - ty) < 60:
    fi = [i for i, t in enumerate(same) if t[2]][0]
    ti = [i for i, t in enumerate(same) if t is tgt[0]][0]
    print(0, ti - fi)
else:
    print(1 if ty > fy else -1, 0)
