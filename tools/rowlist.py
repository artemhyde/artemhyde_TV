# rowlist.py: напечатать подписи плиток той строки, где стоит фокус, слева направо через "|"; плитка в фокусе помечена звёздочкой.
# Запуск: python -I -X utf8 rowlist.py ui.xml
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
if len(sys.argv) < 2:
    sys.exit(__doc__)
x = open(sys.argv[1], encoding='utf-8').read()
nodes = []
for n in re.findall(r'<node [^>]*>', x):
    m = re.search(r'content-desc="([^"]*)"', n)
    d = m.group(1) if m else ''
    b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]+)"', n).group(1))))
    if d and b[2] - b[0] < 700 and b[3] - b[1] < 300 and b[1] >= 200 and b[2] - b[0] > 20:
        nodes.append((d, b, 'focused="true"' in n))
foc = [t for t in nodes if t[2]]
if not foc:
    sys.exit()
cy = lambda t: (t[1][1] + t[1][3]) // 2
same = sorted([t for t in nodes if abs(cy(t) - cy(foc[0])) < 60], key=lambda t: t[1][0])
print('|'.join(('*' if t[2] else '') + t[0] for t in same))
