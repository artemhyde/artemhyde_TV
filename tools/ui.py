# ui.py: разбор дерева экрана ТВ (файл uiautomator dump): печатает подписи с рамками или координаты центра нужного элемента.
# Запуск: python -I -X utf8 ui.py ui.xml            (все подписи, рамки, [x] у отмеченных, FOCUS у элемента в фокусе)
#         python -I -X utf8 ui.py ui.xml "Текст"    (центр элемента с этим text или content-desc: "x y", пусто если нет)
#         python -I -X utf8 ui.py ui.xml --focus    (тексты внутри элемента, на котором стоит фокус, по одному в строке; пусто если фокуса нет)
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
if len(sys.argv) < 2:
    sys.exit(__doc__)
x = open(sys.argv[1], encoding='utf-8').read()
want = sys.argv[2] if len(sys.argv) > 2 else None


def attr(n, a):
    m = re.search(a + r'="([^"]*)"', n)
    return m.group(1) if m else ''


if want == '--focus':
    nodes = re.findall(r'<node [^>]*>', x)
    foc = [n for n in nodes if attr(n, 'focused') == 'true']
    if foc:
        fx1, fy1, fx2, fy2 = map(int, re.findall(r'-?\d+', attr(foc[-1], 'bounds')))
        for n in nodes:
            a = list(map(int, re.findall(r'-?\d+', attr(n, 'bounds'))))
            if len(a) == 4 and a[0] >= fx1 and a[1] >= fy1 and a[2] <= fx2 and a[3] <= fy2:
                t = attr(n, 'text') or attr(n, 'content-desc')
                if t:
                    print(t)
    sys.exit(0)

for n in re.findall(r'<node [^>]*>', x):
    t, d, b = attr(n, 'text'), attr(n, 'content-desc'), attr(n, 'bounds')
    if want is not None:
        if want in (t, d):
            a = list(map(int, re.findall(r'\d+', b)))
            print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2)
            break
    elif t or d:
        print(repr(t), repr(d), b, '[x]' if attr(n, 'checked') == 'true' else '',
              'FOCUS' if attr(n, 'focused') == 'true' else '')
