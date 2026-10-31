# Содержимое плиток ряда «Дома» (стиль «Графит» с 27.10, до того «Объём»): значок и подпись на прозрачном фоне, 400x224 (плитка 200x112 dp).
# Значки берутся из apps/tiles/banners-src (тот же рисунок, что на старых тёмных плитках), подпись Inter SemiBold.
# Стекло плитки рисует сам «Дом», здесь только то, что лежит на стекле, с запечёнными тенями.
# Запуск из Git Bash: python -I -X utf8 build_tiles.py   (нужен Microsoft Edge; результат в apps/home/assets/tiles)
# Светлая тема (Config.THEME = "volume") берёт готовые картинки из assets/tiles-volume: они остались от сборки «Объём» (до перехода на «Графит» 27.10), этот скрипт их не пересобирает.
import pathlib, re, subprocess, sys

HERE = pathlib.Path(__file__).resolve().parent
SRC = HERE.parent.parent / "tiles" / "banners-src"
OUT = HERE.parent / "assets" / "tiles"
TMP = HERE / "_html"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

# пакет плитки в «Доме» -> исходник значка, подпись (None: взять из исходника)
TILES = [
    ("app.glasstv.tile.browser", "browser", None),
    ("app.glasstv.tile.vk", "vk", None),
    ("app.glasstv.tile.youtube", "youtube", None),
    ("app.glasstv.tile.lampa", "lampa", None),
    ("app.glasstv.tile.serials", "serials", None),
    ("app.glasstv.tile.files", "files", None),
    ("app.glasstv.tile.twitch", "twitch", None),
    ("app.glasstv.volna", "volna", None),
    ("app.glasstv.nightmode", "night", None),
    ("app.glasstv.tile.settings", "settings", None),
    ("all", None, "Все приложения"),
]

# Значок «Все приложения»: девять точек, как было, в той же системе координат 640x360
ALL_SVG = "".join(
    f'<circle cx="{320 + i * 28}" cy="{132 + j * 28}" r="8.4" fill="#F2F2F4"/>'
    for i in (-1, 0, 1) for j in (-1, 0, 1))

PAGE = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><style>
html,body{{margin:0;width:400px;height:224px;overflow:hidden;background:transparent}}
body{{position:relative;font-family:'Inter',sans-serif;-webkit-font-smoothing:antialiased}}
.ico{{position:absolute;left:0;top:0;width:400px;height:225px;overflow:visible;
 filter:drop-shadow(0 -1px 0 rgba(255,255,255,.22)) drop-shadow(0 9px 9px rgba(0,0,0,.50))}}
.lbl{{position:absolute;left:0;top:137px;width:400px;height:45px;line-height:45px;text-align:center;
 font-size:37.5px;font-weight:600;letter-spacing:-.01em;color:rgba(255,255,255,.90);white-space:nowrap;
 text-shadow:0 1px 1px rgba(0,0,0,.40),0 5px 16px rgba(0,0,0,.50)}}
</style></head><body>
<svg class="ico" viewBox="0 0 640 360" xmlns="http://www.w3.org/2000/svg">{svg}</svg>
<div class="lbl">{label}</div>
</body></html>"""


def source(name):
    t = (SRC / f"{name}.html").read_text(encoding="utf-8")
    svg = re.search(r'<svg class="icon"[^>]*>(.*?)</svg>', t, re.S).group(1).strip()
    label = re.search(r'<div class="label">(.*?)</div>', t, re.S).group(1).strip()
    return svg, label


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    TMP.mkdir(exist_ok=True)
    for pkg, name, label in TILES:
        svg, own = source(name) if name else (ALL_SVG, None)
        html = TMP / f"{pkg}.html"
        html.write_text(PAGE.format(svg=svg, label=label or own), encoding="utf-8")
        png = OUT / f"{pkg}.png"
        subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                        "--default-background-color=00000000", "--window-size=400,224",
                        f"--screenshot={png}", html.as_uri()], check=True, capture_output=True)
        print("готово:", png.name, "|", label or own)


if __name__ == "__main__":
    sys.exit(main())
