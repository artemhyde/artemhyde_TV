#!/usr/bin/env bash
# build-all.sh: собрать все приложения, которые вы выбрали (config/settings.local.json, раздел apps), и показать итог по каждому.
# Запуск: bash build/build-all.sh [--all] [--keep-going] [имя ...]
#   имена:        собрать только перечисленные (home guard nightmode sound picture volna tiles clip keyboard)
#   --all:        собрать все приложения из папки apps/, не глядя на выбор в настройках
#   --keep-going: не останавливаться на первой ошибке (в конце всё равно будет код выхода 1)
# Без имён: берутся приложения с true в разделе apps настроек; нет файла настроек: все. Порядок сборки фиксирован.
# Результат: out/<имя>.apk и out/tiles/*.apk; проверка подписи отдельно: bash build/check-apk.sh.
set -euo pipefail
source "$(dirname "$0")/env.sh"

all=0; keep=0; names=()
for a in "$@"; do
    case "$a" in
        --all) all=1 ;;
        --keep-going) keep=1 ;;
        -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) die "Неизвестный параметр: $a" ;;
        *) names+=("$a") ;;
    esac
done

if [ ! -d "$ROOT/apps" ] || [ -z "$(ls -A "$ROOT/apps" 2>/dev/null)" ]; then
    die "Репозиторий неполный: нет папки apps/ с исходниками приложений (или она пуста). Скачайте репозиторий заново целиком (Code, Download ZIP) и ничего не ставьте на ТВ, пока папка не появится."
fi

if [ ${#names[@]} -eq 0 ]; then
    order="guard nightmode home sound picture volna clip keyboard tiles"
    present=""
    for n in $order; do [ -d "$ROOT/apps/$n" ] && present="$present $n"; done
    # приложения, которых нет в порядке выше, но они лежат в apps/ (с манифестом)
    for d in "$ROOT"/apps/*/; do
        n="$(basename "$d")"
        case " $order " in *" $n "*) continue ;; esac
        case "$n" in _*|.*) continue ;; esac
        if [ -f "$d/AndroidManifest.xml" ] || [ -f "$d/tiles.txt" ]; then present="$present $n"; fi
    done
    sel=""
    if [ "$all" = 0 ] && [ -f "$CONFIG_DIR/settings.local.json" ]; then
        PY="$(find_python || true)"
        [ -n "$PY" ] || die "Не найден Python 3."
        sel="$($PY -I -X utf8 - "$(P "$CONFIG_DIR/settings.local.json")" <<'PYS'
import json
import sys

s = json.load(open(sys.argv[1], encoding='utf-8'))
apps = s.get('apps') or {}
print(' '.join(k for k, v in apps.items() if v is True))
PYS
)"
        for n in $present; do case " $sel " in *" $n "*) names+=("$n") ;; esac; done
    else
        for n in $present; do names+=("$n"); done
    fi
fi
[ ${#names[@]} -gt 0 ] || die "Нечего собирать: в config/settings.local.json нет включённых приложений или папка apps/ пуста."

ok=(); bad=()
for n in "${names[@]}"; do
    echo "=== $n"
    if bash "$GT_BUILD_DIR/build-app.sh" "$n"; then ok+=("$n")
    else
        bad+=("$n")
        echo "Не собралось: $n" >&2
        [ "$keep" = 1 ] || break
    fi
done

echo ""
echo "Собрано: ${ok[*]:-ничего}"
if [ ${#bad[@]} -gt 0 ]; then echo "С ошибкой: ${bad[*]}"; exit 1; fi
echo "Проверить подписи: bash build/check-apk.sh"
