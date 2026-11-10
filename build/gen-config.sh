#!/usr/bin/env bash
# gen-config.sh: превращает config/settings.local.json (ответы человека) в Config.java для «Дома» и «Волны» и, если надо, в файл со своими стихами.
# Запуск: bash build/gen-config.sh [home] [volna]       (без аргументов обе); вызывается и из build-app.sh, отдельно запускать не обязательно
# Читает config/settings.local.json, а если его нет, образец config/settings.example.json (с предупреждением).
# Пишет: out/gen/<приложение>/app/glasstv/<приложение>/Config.java; для «Дома» со стихами custom ещё out/gen/home/res-raw/verses.json.
#
# Поля настроек и что с ними происходит (неуказанное берётся по умолчанию):
#   profile        "tcl-gtv" или "generic-gtv"      > Config.PROFILE   (по умолчанию generic-gtv: какой это ТВ, решает мастер по паспорту)
#   theme          "graphite" или "volume"          > Config.THEME     (по умолчанию graphite; volume принимается, только если светлая тема есть в коде, см. THEMES_IMPLEMENTED ниже)
#   city.name      название города для подписи      > Config.CITY      (пусто: погода без названия города)
#   city.lat/lon   координаты города                > Config.LAT, Config.LON, Config.HAS_LOCATION (false, если город не задан и координаты нулевые: погоду не запрашивать)
#   rates          "none", "auto", банк или crypto  > Config.RATES     (курсы своей страны: auto по стране города, nbrb, cbr, nbu, nbk, ecb; crypto: BTC, ETH, SOL в долларах; по умолчанию none)
#   verses         "quotes", "bible", "custom", "off" > Config.VERSES    (по умолчанию quotes, цитаты о жизни; custom: берётся config/verses.local.json в формате apps/home/res/raw/verses.json: ref, text, slot = morning, day, evening)
#   profile1_name  имя первого вкуса в «Волне»      > Config.PROFILE1  (по умолчанию «Профиль 1»)
#   profile2_name  имя второго вкуса в «Волне»      > Config.PROFILE2  (по умолчанию «Профиль 2»)
#   repo_url       ссылка на репозиторий (по желанию) > Config.USER_AGENT = "GlassTV/1.0 (+ссылка)" для запросов к Википедии и другим сервисам
# Остальные поля (remote, apps, night, tv) на сборку не влияют: их использует мастер при установке.
# Если у приложения лежит шаблон Config.java.in (в apps/<имя>/ или в apps/<имя>/src/app/glasstv/<имя>/), в нём подставляются метки
# @PROFILE@ @THEME@ @CITY@ @LAT@ @LON@ @HAS_LOCATION@ @RATES@ @VERSES@ @PROFILE1@ @PROFILE2@ @USER_AGENT@ (строки уже экранированы, кавычки ставит шаблон).
# Без шаблона создаётся готовый класс Config с теми же полями.
set -euo pipefail
source "$(dirname "$0")/env.sh"

# Какие темы реализованы в коде: graphite (тёмная «Графит») и volume (светлая «Объём»; её читает «Дом», остальным приложениям тема не нужна).
# Новую тему допишите сюда, когда она появится в коде.
THEMES_IMPLEMENTED="graphite volume"

PY="$(find_python || true)"
[ -n "$PY" ] || die "Не найден Python 3 (нужен для разбора настроек)."
apps=("$@")
[ ${#apps[@]} -gt 0 ] || apps=($CONFIG_APPS)

sf="$(settings_file)"
[ -n "$sf" ] || die "Нет ни config/settings.local.json, ни config/settings.example.json."
case "$sf" in *settings.example.json) echo "Внимание: нет config/settings.local.json, беру образец (город пуст, курсов и стихов нет)." >&2 ;; esac

for app in "${apps[@]}"; do
    tmpl=""
    for t in "$ROOT/apps/$app/Config.java.in" "$ROOT/apps/$app/src/app/glasstv/$app/Config.java.in"; do
        [ -f "$t" ] && { tmpl="$t"; break; }
    done
    mkdir -p "$OUT/gen/$app"
    $PY -I -X utf8 - "$(P "$sf")" "$app" "$(P "$OUT/gen/$app")" "${tmpl:+$(P "$tmpl")}" "$THEMES_IMPLEMENTED" "$(P "$CONFIG_DIR")" <<'PY'
import json
import os
import sys

settings_path, app, out_dir, tmpl, themes_ok, config_dir = sys.argv[1:7]
themes_ok = themes_ok.split()


def fail(msg):
    sys.stderr.write('Ошибка настроек: ' + msg + '\n')
    sys.exit(2)


try:
    with open(settings_path, encoding='utf-8') as f:
        s = json.load(f)
except Exception as e:
    fail('не читается %s (%s)' % (settings_path, e))
if not isinstance(s, dict):
    fail('в %s ожидается объект {...}' % settings_path)

profile = s.get('profile') or 'generic-gtv'
if profile not in ('tcl-gtv', 'generic-gtv'):
    fail('profile должен быть tcl-gtv или generic-gtv, а там "%s"' % profile)
theme = s.get('theme') or 'graphite'
if theme not in ('graphite', 'volume'):
    fail('theme должен быть graphite или volume, а там "%s"' % theme)
if theme not in themes_ok:
    fail('светлая тема «Объём» пока не реализована в коде: поставьте theme "graphite" (доступно: %s)' % ', '.join(themes_ok))
city = s.get('city') or {}
if not isinstance(city, dict):
    fail('city должен быть объектом {"name": ..., "lat": ..., "lon": ...}')
name = str(city.get('name') or '').strip()
try:
    lat = float(city.get('lat') or 0.0)
    lon = float(city.get('lon') or 0.0)
except (TypeError, ValueError):
    fail('city.lat и city.lon должны быть числами')
if not (-90.0 <= lat <= 90.0) or not (-180.0 <= lon <= 180.0):
    fail('координаты вне допустимых пределов: lat %s, lon %s' % (lat, lon))
has_loc = bool(name) or lat != 0.0 or lon != 0.0
rates = s.get('rates') or 'none'
if rates not in ('none', 'auto', 'nbrb', 'cbr', 'nbu', 'nbk', 'ecb', 'crypto'):
    fail('rates должен быть none, auto, nbrb, cbr, nbu, nbk, ecb или crypto, а там "%s"' % rates)
verses = s.get('verses') or 'quotes'
if verses not in ('quotes', 'bible', 'custom', 'off'):
    fail('verses должен быть quotes, bible, custom или off, а там "%s"' % verses)
p1 = str(s.get('profile1_name') or '').strip() or 'Профиль 1'
p2 = str(s.get('profile2_name') or '').strip() or 'Профиль 2'
repo = str(s.get('repo_url') or '').strip()
ua = 'GlassTV/1.0' + (' (+%s)' % repo if repo else '')


def jstr(v):
    """Строка для Java-литерала: всё не из ASCII уходит в \\uXXXX, чтобы не зависеть от кодировки."""
    out = []
    for ch in str(v):
        o = ord(ch)
        if ch == '\\':
            out.append('\\\\')
        elif ch == '"':
            out.append('\\"')
        elif ch == '\n':
            out.append('\\n')
        elif ch == '\r':
            out.append('\\r')
        elif ch == '\t':
            out.append('\\t')
        elif 32 <= o < 127:
            out.append(ch)
        else:
            b = ch.encode('utf-16-be')
            for i in range(0, len(b), 2):
                out.append('\\u%02x%02x' % (b[i], b[i + 1]))
    return ''.join(out)


pkg = 'app.glasstv.' + app
values = {
    'PROFILE': jstr(profile), 'THEME': jstr(theme), 'CITY': jstr(name),
    'LAT': repr(lat), 'LON': repr(lon), 'HAS_LOCATION': 'true' if has_loc else 'false',
    'RATES': jstr(rates), 'VERSES': jstr(verses),
    'PROFILE1': jstr(p1), 'PROFILE2': jstr(p2), 'USER_AGENT': jstr(ua),
}

if tmpl:
    with open(tmpl, encoding='utf-8') as f:
        text = f.read()
    for k, v in values.items():
        text = text.replace('@%s@' % k, v)
    import re
    left = re.findall(r'@[A-Z0-9_]+@', text)
    if left:
        fail('в шаблоне %s остались неизвестные метки: %s' % (tmpl, ', '.join(sorted(set(left)))))
else:
    text = (
        'package %s;\n\n'
        '/** Создано build/gen-config.sh из config/settings.local.json. Руками не править: файл пересоздаётся при каждой сборке. */\n'
        'public final class Config {\n'
        '    private Config() {}\n\n'
        '    public static final String PROFILE = "%s";\n'
        '    public static final String THEME = "%s";\n'
        '    public static final String CITY = "%s";\n'
        '    public static final double LAT = %s;\n'
        '    public static final double LON = %s;\n'
        '    public static final boolean HAS_LOCATION = %s;\n'
        '    public static final String RATES = "%s";\n'
        '    public static final String VERSES = "%s";\n'
        '    public static final String PROFILE1 = "%s";\n'
        '    public static final String PROFILE2 = "%s";\n'
        '    public static final String USER_AGENT = "%s";\n'
        '}\n'
    ) % (pkg, values['PROFILE'], values['THEME'], values['CITY'], values['LAT'], values['LON'],
         values['HAS_LOCATION'], values['RATES'], values['VERSES'], values['PROFILE1'],
         values['PROFILE2'], values['USER_AGENT'])

dest_dir = os.path.join(out_dir, *pkg.split('.'))
os.makedirs(dest_dir, exist_ok=True)
with open(os.path.join(dest_dir, 'Config.java'), 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)

# Свои стихи: проверить файл и положить его рядом, build-app.sh подменит им res/raw/verses.json
raw_dir = os.path.join(out_dir, 'res-raw')
if os.path.isdir(raw_dir):
    for fn in os.listdir(raw_dir):
        os.remove(os.path.join(raw_dir, fn))
if app == 'home' and verses == 'custom':
    vp = os.path.join(config_dir, 'verses.local.json')
    if not os.path.isfile(vp):
        fail('verses = custom, но нет файла config/verses.local.json (формат как у apps/home/res/raw/verses.json)')
    try:
        with open(vp, encoding='utf-8') as f:
            data = json.load(f)
    except Exception as e:
        fail('config/verses.local.json не читается (%s)' % e)
    if not isinstance(data, list) or not data:
        fail('config/verses.local.json должен быть непустым списком стихов')
    for i, v in enumerate(data):
        if not isinstance(v, dict) or not all(isinstance(v.get(k), str) and v.get(k) for k in ('ref', 'text', 'slot')):
            fail('стих №%d: нужны непустые поля ref, text, slot' % (i + 1))
        if v['slot'] not in ('morning', 'day', 'evening'):
            fail('стих №%d: slot должен быть morning, day или evening' % (i + 1))
    os.makedirs(raw_dir, exist_ok=True)
    with open(os.path.join(raw_dir, 'verses.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, ensure_ascii=False, indent=1)

print('Config.java для %s: тема %s, город %s, курсы %s, стихи %s' % (
    app, theme, name or 'не задан', rates, verses))
PY
done
