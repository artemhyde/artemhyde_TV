#!/usr/bin/env bash
# lib.sh: общие функции скриптов tools (поиск adb и Python, адрес ТВ, вызов adb, журнал изменений). Сам не запускается.
# Подключение: source "$(dirname "$0")/lib.sh". Работает в Git Bash на Windows, в bash на macOS и Linux.
# Переменные: ADB (путь к adb), TV (адрес ТВ вида 192.168.0.10:5555), PY (Python), GLASSTV_TOOLS (куда мастер ставит инструменты).

GT_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GT_ROOT="$(cd "$GT_LIB_DIR/.." && pwd)"
GT_HOME="${GLASSTV_TOOLS:-$HOME/.glasstv/tools}"

# Git Bash не должен «исправлять» пути вроде /sdcard/ui.xml в аргументах adb
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

gt_say() { printf '%s\n' "$*"; }
gt_err() { printf '%s\n' "$*" >&2; }
gt_die() { gt_err "$*"; exit "${GT_EXIT:-1}"; }

# Путь в виде, который понимают программы Windows (Python, Java): C:/... вместо /c/...; на macOS и Linux без изменений
gt_native() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# Старший номер версии adb: «Version 34.0.5» даёт 34, пусто если прочитать не удалось. Беспроводное сопряжение (adb pair) есть с версии 30.
gt_adb_major() { "$1" version 2>/dev/null | tr -d '\r' | sed -n 's/^Version \([0-9][0-9]*\)\..*/\1/p' | head -1; }

# Найти adb: переменная ADB, PATH, папка инструментов мастера, Android SDK. Берётся первый, который не старше версии 30:
# старый adb от Android Studio не умеет adb pair, поэтому его пропускаем, пока есть лучший.
gt_find_adb() {
    local c m old=""
    if [ -n "${ADB:-}" ]; then
        if [ -x "$ADB" ] || command -v "$ADB" >/dev/null 2>&1; then
            m="$(gt_adb_major "$ADB")"
            if [ -n "$m" ] && [ "$m" -lt 30 ]; then gt_err "Внимание: ADB ($ADB) версии $m, а adb pair есть с версии 30. Обновите platform-tools."; fi
            return 0
        fi
        gt_err "Переменная ADB указывает на несуществующий файл: $ADB"; return 1
    fi
    for c in "$(command -v adb 2>/dev/null)" "$(command -v adb.exe 2>/dev/null)" \
             "$GT_HOME/platform-tools/adb" "$GT_HOME/platform-tools/adb.exe" \
             "${ANDROID_HOME:-/nonexistent}/platform-tools/adb" "${ANDROID_HOME:-/nonexistent}/platform-tools/adb.exe"; do
        if [ -n "$c" ] && [ -x "$c" ]; then
            m="$(gt_adb_major "$c")"
            if [ -z "$m" ] || [ "$m" -ge 30 ]; then ADB="$c"; export ADB; return 0; fi
            [ -n "$old" ] || old="$c"
        fi
    done
    if [ -n "$old" ]; then
        gt_err "Найден только старый adb ($old, версия до 30): в нём нет adb pair. Поставьте свежие platform-tools (мастер предложит)."
        ADB="$old"; export ADB; return 0
    fi
    gt_err "adb не найден. Поставьте platform-tools (мастер предложит) или задайте переменную ADB."
    return 1
}

# Найти рабочий Python 3: на Windows python3 бывает заглушкой Microsoft Store, поэтому каждый вариант пробуем запуском.
# Последним проверяется Python, который мог положить мастер: $GT_HOME/python (без прав администратора).
gt_find_python() {
    if [ -n "${PY:-}" ]; then return 0; fi
    local c
    for c in python3 python "py -3"; do
        if $c -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then PY="$c"; export PY; return 0; fi
    done
    for c in "$GT_HOME/python/python.exe" "$GT_HOME/python/bin/python3" "$GT_HOME/python/python3"; do
        if [ -x "$c" ] && "$c" -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then PY="$c"; export PY; return 0; fi
    done
    gt_err "Python 3 не найден. Он нужен только для разбора экрана ТВ и сборки; мастер предложит его поставить."
    return 1
}

# Журнал изменений на ТВ: LEDGER=backup/<метка>/ledger.tsv (колонки: время, что сделано, команда, обратная команда; команды для оболочки ТВ).
# Пустая обратная команда значит «откатом не покрывается». Всё, что меняет ТВ и умеет откат, сначала проверяет gt_need_ledger.
gt_need_ledger() {
    if [ -z "${LEDGER:-}" ]; then
        gt_err "Не задан LEDGER (backup/<метка>/ledger.tsv): без журнала откат невозможен, поэтому на ТВ ничего не меняю."
        return 1
    fi
    if [ ! -d "$(dirname "$LEDGER")" ]; then
        gt_err "Нет папки журнала $(dirname "$LEDGER"). Сначала bash tools/snapshot.sh --label <метка>."
        return 1
    fi
    return 0
}

# ledger "что" "команда" "обратная команда": дописать строку в журнал. Возвращает 1, если записать не удалось.
ledger() {
    gt_need_ledger || return 1
    local what cmd inv
    what="$(printf '%s' "$1" | tr '\t\r\n' '   ')"
    cmd="$(printf '%s' "$2" | tr '\t\r\n' '   ')"
    inv="$(printf '%s' "${3:-}" | tr '\t\r\n' '   ')"
    printf '%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$what" "$cmd" "$inv" >> "$LEDGER" || return 1
}

# Адрес ТВ для adb -s: переменная TV; если пусто, adb сам выберет единственное подключённое устройство
tv_adb() {
    if [ -n "${TV:-}" ]; then "$ADB" -s "$TV" "$@"; else "$ADB" "$@"; fi
}

# Команда в оболочке ТВ одной строкой: tv_sh "settings get global adb_wifi_enabled"
tv_sh() { tv_adb shell "$@"; }

# Состояние связи: device, unauthorized, offline или no-device
tv_state() {
    local s
    s="$(tv_adb get-state 2>&1 | tr -d '\r')" || true
    case "$s" in
        device) echo device ;;
        *unauthorized*) echo unauthorized ;;
        *offline*) echo offline ;;
        *) echo no-device ;;
    esac
}

# Подготовка: найти adb и убедиться, что ТВ отвечает
tv_ready() {
    gt_find_adb || return 1
    local st; st="$(tv_state)"
    case "$st" in
        device) return 0 ;;
        unauthorized) gt_err "ТВ не доверяет этому ПК: на экране ТВ нажмите «Разрешить» в окне отладки."; return 2 ;;
        *) gt_err "ТВ не отвечает (${TV:-адрес не задан}). Сначала tools/find-tv.sh."; return 3 ;;
    esac
}

# Разобрать общие ключи --tv адрес; остальное вернуть в GT_REST
gt_parse_tv() {
    GT_REST=()
    while [ $# -gt 0 ]; do
        case "$1" in
            --tv) TV="${2:?после --tv нужен адрес}"; shift 2 ;;
            *) GT_REST+=("$1"); shift ;;
        esac
    done
    export TV
}
