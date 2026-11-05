# Значки внутри сфер стиля «Vision» (05.11): тот же рисунок, что на плитках, но крупнее и без подписи,
# 160x160 на прозрачном фоне (значок 128x128 по центру, по краям запас под тень). Сферы с 05.11 крупнее (116 dp),
# и значок из картинки плитки пришлось бы растягивать: он мылился. Подпись сферы рисует сам «Дом».
# Запуск из Git Bash: python -I -X utf8 build_orbs.py   (нужен Microsoft Edge; результат в apps/home/assets/orbs)
import pathlib, re, subprocess, sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from build_tiles import TILES, ALL_SVG, SRC  # noqa: E402

OUT = HERE.parent / "assets" / "orbs"
TMP = HERE / "_html"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

# Квадрат значка в исходниках: x 236..404, y 48..216 (как у значков капсул, build_caps.py)
PAGE = """<!doctype html><html><head><meta charset="utf-8"><style>
html,body{{margin:0;width:160px;height:160px;overflow:hidden;background:transparent}}
svg{{position:absolute;left:16px;top:16px;width:128px;height:128px;overflow:visible;
 filter:drop-shadow(0 -1px 0 rgba(255,255,255,.22)) drop-shadow(0 7px 8px rgba(0,0,0,.50))}}
</style></head><body>
<svg viewBox="236 48 168 168" xmlns="http://www.w3.org/2000/svg">{svg}</svg>
</body></html>"""


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    TMP.mkdir(exist_ok=True)
    for pkg, name, _ in TILES:
        if name:
            t = (SRC / f"{name}.html").read_text(encoding="utf-8")
            svg = re.search(r'<svg class="icon"[^>]*>(.*?)</svg>', t, re.S).group(1).strip()
        else:
            svg = ALL_SVG
        html = TMP / f"orb-{pkg}.html"
        html.write_text(PAGE.format(svg=svg), encoding="utf-8")
        png = OUT / f"{pkg}.png"
        subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                        "--default-background-color=00000000", "--window-size=160,160",
                        f"--screenshot={png}", html.as_uri()], check=True, capture_output=True)
        print("готово:", png.name)


if __name__ == "__main__":
    sys.exit(main())
