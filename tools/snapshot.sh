#!/usr/bin/env bash
# snapshot.sh: снимок ТВ «до изменений» (только чтение) в папку backup/<метка>/: пакеты, свойства, настройки, роль HOME, заставка, память, диск.
# Запуск: TV=192.168.0.10:5555 bash tools/snapshot.sh [--tv адрес] [--label метка] [--full] [--out папка]
#   --label: имя снимка (буквы, цифры, точка, минус, подчёркивание), по умолчанию дата и время вида 20261027-1530
#   --full:  добавить dumpsys package (около 2 МБ, долго), нужен для глубокого разбора
#   --out:   куда класть папки снимков (по умолчанию backup/ в корне репозитория; переменная GLASSTV_BACKUP делает то же)
# Внутри снимка есть серийный номер, MAC-адреса и android_id: папка остаётся на этом ПК, backup/ внесён в .gitignore.
# Рядом создаётся пустой журнал ledger.tsv: в него мастер пишет каждое изменение, из него make-restore.sh собирает откат.
# Папка может уже существовать, если в ней лежат только progress.txt, device.json, caps.json (их мастер пишет до снимка, метка у них та же).
set -uo pipefail
source "$(dirname "$0")/lib.sh"

label=""; full=0
out_root="$GT_ROOT/backup"
[ -n "${GLASSTV_BACKUP:-}" ] && out_root="$GLASSTV_BACKUP"
while [ $# -gt 0 ]; do
    case "$1" in
        --tv) TV="${2:?после --tv нужен адрес}"; export TV; shift 2 ;;
        --label) label="${2:?после --label нужна метка}"; shift 2 ;;
        --full) full=1; shift ;;
        --out) out_root="${2:?после --out нужна папка}"; shift 2 ;;
        -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) gt_die "Неизвестный параметр: $1 (справка: --help)" ;;
    esac
done
[ -n "$label" ] || label="$(date +%Y%m%d-%H%M)"
case "$label" in *[!A-Za-z0-9._-]*|"") gt_die "Метка только из латинских букв, цифр, точки, минуса и подчёркивания: $label" ;; esac

tv_ready || exit $?
dir="$out_root/$label"
# Папка с той же меткой может уже быть: мастер ведёт в ней progress.txt (шаг 0), device.json и caps.json (шаг 3) ещё до снимка.
# Эти три файла снимку не мешают. Если там есть что-то ещё (прежний снимок, журнал), папка считается занятой.
if [ -e "$dir" ]; then
    extra="$(ls -A "$dir" 2>/dev/null | grep -vxE 'progress\.txt|device\.json|caps\.json' || true)"
    if [ -n "$extra" ]; then gt_die "Папка уже занята: $dir (там есть: $(printf '%s' "$extra" | tr '\n' ' ')). Укажите другую метку (--label)."; fi
fi
mkdir -p "$dir" || gt_die "Не удалось создать $dir"

gt_say "Снимок «до» в $dir"
gt_say "Внимание: внутри серийный номер, MAC и android_id. Файлы остаются только на этом ПК."

# grab файл "команда в оболочке ТВ": результат в файл, без символов возврата каретки
grab() {
    local f="$1"; shift
    if tv_sh "$*" 2>&1 | tr -d '\r' > "$dir/$f"; then :; fi
    printf '  %-28s %s строк\n' "$f" "$(wc -l < "$dir/$f" | tr -d ' ')"
}

grab packages-all.txt        "pm list packages -f"
grab packages-disabled.txt   "pm list packages -d"
grab packages-enabled.txt    "pm list packages -e"
grab packages-system.txt     "pm list packages -s"
grab packages-third.txt      "pm list packages -3"
grab getprop.txt             "getprop"
grab settings-global.txt     "settings list global"
grab settings-secure.txt     "settings list secure"
grab settings-system.txt     "settings list system"
grab meminfo-head.txt        "dumpsys meminfo | head -40"
grab df.txt                  "df"
grab deviceidle-whitelist.txt "dumpsys deviceidle whitelist"
grab libraries.txt           "pm list libraries"
grab features.txt            "pm list features"

# Состояние, которое мастер потом меняет и должен уметь вернуть: одним файлом «ключ: значение»
{
    echo "home_role_holder: $(tv_sh "h=\$(cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null) || h=''; [ -n \"\$h\" ] || h=\$(dumpsys role 2>/dev/null | grep -A1 'name=android.app.role.HOME\$' | sed -n 's/^ *holders=//p'); [ -n \"\$h\" ] || h=\$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1 | grep / | cut -d/ -f1); [ \"\$h\" = android ] && h=''; echo \"\$h\"" 2>&1 | tr -d '\r')"
    for k in screensaver_components screensaver_enabled screensaver_activate_on_sleep screensaver_activate_on_dock \
             enabled_accessibility_services accessibility_enabled default_input_method; do
        echo "secure.$k: $(tv_sh "settings get secure $k" 2>&1 | tr -d '\r')"
    done
    for k in private_dns_mode private_dns_specifier window_animation_scale transition_animation_scale animator_duration_scale \
             adb_enabled adb_wifi_enabled stay_on_while_plugged_in; do
        echo "global.$k: $(tv_sh "settings get global $k" 2>&1 | tr -d '\r')"
    done
    for k in screen_brightness screen_off_timeout; do
        echo "system.$k: $(tv_sh "settings get system $k" 2>&1 | tr -d '\r')"
    done
    echo "device_admins:"
    tv_sh "dumpsys device_policy" 2>&1 | tr -d '\r' | grep -A8 "Enabled Device Admins" | sed 's/^/  /'
} > "$dir/state.txt"
printf '  %-28s %s строк\n' state.txt "$(wc -l < "$dir/state.txt" | tr -d ' ')"

if [ "$full" = 1 ]; then
    gt_say "dumpsys package (долго)..."
    grab dumpsys-package.txt "dumpsys package"
fi

{
    echo "label: $label"
    echo "time: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "tv: ${TV:-единственное устройство adb}"
    echo "adb: $("$ADB" version 2>&1 | head -1 | tr -d '\r')"
    echo "full: $full"
} > "$dir/meta.txt"

# Журнал изменений: формат читает make-restore.sh
{
    printf '# GlassTV: журнал изменений на ТВ. Колонки через табуляцию: время, что сделано, команда, обратная команда.\n'
    printf '# Обе команды идут в оболочку ТВ (adb shell). Пустая обратная команда значит «откатом не покрывается».\n'
} > "$dir/ledger.tsv"

if [ -f "$GT_ROOT/.gitignore" ] && ! grep -qE '^/?backup/?$' "$GT_ROOT/.gitignore"; then
    gt_say "Внимание: в .gitignore нет строки backup/. Добавьте её, прежде чем создавать репозиторий."
fi
gt_say "Готово. Журнал изменений: $dir/ledger.tsv. Откат собирается так: bash tools/make-restore.sh \"$dir\""
