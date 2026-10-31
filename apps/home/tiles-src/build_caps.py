# Значки капсул под рядом («Продолжить Фильмы», «Играет …»): рисунок плитки на тёмном круглом жетоне, 112x112.
# Тот же рисунок, что на плитках (apps/tiles/banners-src), вместо родных значков приложений (у Lampa это белая спираль).
# Запуск из Git Bash: python -I -X utf8 build_caps.py   (нужен Microsoft Edge; результат в apps/home/assets/caps)
import pathlib, re, subprocess, sys

HERE = pathlib.Path(__file__).resolve().parent
SRC = HERE.parent.parent / "tiles" / "banners-src"
OUT = HERE.parent / "assets" / "caps"
TMP = HERE / "_html"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
NAMES = ["lampa", "serials", "youtube", "vk", "twitch", "files", "volna", "browser"]

PAGE = """<!doctype html><html><head><meta charset="utf-8"><style>
html,body{{margin:0;width:112px;height:112px;overflow:hidden;background:transparent}}
.dot{{position:absolute;left:4px;top:4px;width:104px;height:104px;border-radius:50%;
 background:linear-gradient(180deg,#34323c,#1b1a22);
 box-shadow:inset 0 2px 0 rgba(255,255,255,.30),inset 0 0 0 2px rgba(255,255,255,.10)}}
svg{{position:absolute;left:24px;top:24px;width:64px;height:64px;overflow:visible}}
</style></head><body><div class="dot"></div>
<svg viewBox="236 48 168 168" xmlns="http://www.w3.org/2000/svg">{svg}</svg>
</body></html>"""


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    TMP.mkdir(exist_ok=True)
    for name in NAMES:
        t = (SRC / f"{name}.html").read_text(encoding="utf-8")
        svg = re.search(r'<svg class="icon"[^>]*>(.*?)</svg>', t, re.S).group(1).strip()
        html = TMP / f"cap-{name}.html"
        html.write_text(PAGE.format(svg=svg), encoding="utf-8")
        png = OUT / f"{name}.png"
        subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                        "--default-background-color=00000000", "--window-size=112,112",
                        f"--screenshot={png}", html.as_uri()], check=True, capture_output=True)
        print("готово:", png.name)


if __name__ == "__main__":
    sys.exit(main())
