# tvui.py: управление меню настроек ТВ из Python: нажать кнопку, снять дерево экрана, найти пункты, прочитать пункт в фокусе.
# Запуск: TV=192.168.0.10:5555 python -I -X utf8 tvui.py            (все подписи на экране)
#         TV=192.168.0.10:5555 python -I -X utf8 tvui.py focused    (тексты внутри пункта с фокусом)
# Как модуль: добавить папку tools в sys.path, затем import tvui; tvui.key("KEYCODE_DPAD_DOWN"); tvui.texts().
# adb берётся из переменной ADB или из PATH. Адрес ТВ: переменная TV (если устройство одно, можно не задавать).
import os
import re
import shutil
import subprocess
import sys
import time

ADB = os.environ.get("ADB") or shutil.which("adb") or shutil.which("adb.exe")
DEV = os.environ.get("TV", "")


def sh(cmd):
    if not ADB:
        sys.exit("adb не найден: задайте переменную ADB или добавьте adb в PATH")
    args = [ADB] + (["-s", DEV] if DEV else []) + ["shell", cmd]
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8").stdout


def key(*names, pause=0.35):
    for n in names:
        sh("input keyevent " + n)
        time.sleep(pause)


def nodes():
    sh("uiautomator dump /sdcard/u.xml >/dev/null")
    x = sh("cat /sdcard/u.xml")
    out = []
    for m in re.finditer(r'<node [^>]*>', x):
        a = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', m.group(0)))
        out.append(a)
    return out


def texts():
    return [(n.get("text"), n.get("focused"), n.get("bounds")) for n in nodes() if n.get("text")]


def box(b):
    return list(map(int, re.findall(r"-?\d+", b)))


# Тексты внутри пункта, на котором стоит фокус; у переключателей добавляется [вкл] или [выкл]
def focused():
    ns = nodes()
    f = [n for n in ns if n.get("focused") == "true"]
    if not f:
        return []
    x1, y1, x2, y2 = box(f[-1]["bounds"])
    out = []
    for n in ns:
        a, b, c, d = box(n["bounds"])
        if a >= x1 and b >= y1 and c <= x2 and d <= y2:
            if n.get("text"):
                out.append(n["text"])
            elif n.get("checkable") == "true":
                out.append("[вкл]" if n.get("checked") == "true" else "[выкл]")
    return out


if __name__ == "__main__":
    if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
        sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) > 1 and sys.argv[1] == "focused":
        for t in focused():
            print(t)
    else:
        for t in texts():
            print(t)
