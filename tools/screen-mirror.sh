#!/usr/bin/env bash
# screen-mirror.sh: показать экран ТВ в окне на ПК через scrcpy (смотреть, кликать мышкой, печатать с клавиатуры ПК); scrcpy должен уже стоять.
# Запуск: TV=192.168.0.10:5555 bash tools/screen-mirror.sh [--tv адрес] [--keep-ime]
# Мышь: левая кнопка нажимает, правая «Назад», средняя «Домой», колёсико листает. Закрыть: закрыть окно.
# Клавиатура ПК работает как клавиатура ТВ (--keyboard=uhid); на время сеанса включается клавиатура Google (Gboard), если она есть,
# потому что с клавиатурой производителя ТВ русские и английские буквы с подключённой клавиатуры могут не работать. По окончании прежняя клавиатура возвращается
# при любом выходе, и Ctrl+C тоже (trap); если задан LEDGER, смена пишется в журнал с обратной командой (--keep-ime: клавиатуру не трогать).
# Звук остаётся на ТВ, кадров 30 в секунду (меню хватает, ТВ не нагружается). Путь к scrcpy: переменная SCRCPY, PATH или ~/.glasstv/tools/scrcpy.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

keep_ime=0
gt_parse_tv "$@"
for a in ${GT_REST[@]+"${GT_REST[@]}"}; do
    case "$a" in
        --keep-ime) keep_ime=1 ;;
        -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) gt_die "Неизвестный параметр: $a" ;;
    esac
done

tv_ready || exit $?

scrcpy_bin=""
if [ -n "${SCRCPY:-}" ]; then scrcpy_bin="$SCRCPY"
else
    for c in "$(command -v scrcpy 2>/dev/null)" "$(command -v scrcpy.exe 2>/dev/null)" "$GT_HOME/scrcpy/scrcpy" "$GT_HOME/scrcpy/scrcpy.exe"; do
        if [ -n "$c" ] && [ -x "$c" ]; then scrcpy_bin="$c"; break; fi
    done
fi
if [ -z "$scrcpy_bin" ]; then
    gt_say "scrcpy не найден. Это необязательная программа (показ экрана ТВ в окне на ПК): проект Genymobile/scrcpy на GitHub."
    gt_say "Без неё всё остальное работает: снимок экрана делает tools/tv.sh (функция shot)."
    exit 1
fi

# scrcpy должен работать через тот же adb, что и мы: два разных adb выбивают друг друга
export ADB

# Прежняя клавиатура возвращается при любом выходе: закрытие окна, Ctrl+C, сигнал TERM, падение scrcpy (trap на EXIT, INT, TERM)
old_ime=""; ime_changed=0
restore_ime() {
    if [ "$ime_changed" = 1 ]; then
        ime_changed=0
        if [ -n "$old_ime" ] && [ "$old_ime" != "null" ]; then tv_sh "settings put secure default_input_method $old_ime" >/dev/null 2>&1
        else tv_sh "settings delete secure default_input_method" >/dev/null 2>&1; fi
        gt_say "Прежняя клавиатура ТВ возвращена."
    fi
}
trap restore_ime EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if [ "$keep_ime" = 0 ]; then
    old_ime="$(tv_sh "settings get secure default_input_method" | tr -d '\r')"
    gboard="$(tv_sh "ime list -s" | tr -d '\r' | grep -m1 'com.google.android.inputmethod.latin' || true)"
    if [ -n "$gboard" ]; then
        # Смена клавиатуры идёт в журнал (если задан LEDGER), чтобы откат вернул прежнюю, даже если этот скрипт убили
        if [ -n "${LEDGER:-}" ] && [ -f "$LEDGER" ]; then
            if [ -n "$old_ime" ] && [ "$old_ime" != "null" ]; then inv="settings put secure default_input_method $old_ime"; else inv="settings delete secure default_input_method"; fi
            ledger "клавиатура ТВ временно заменена на Gboard (зеркало экрана)" "ime set $gboard" "$inv" || gt_err "Журнал не записан: прежнюю клавиатуру этот скрипт вернёт сам."
        fi
        ime_changed=1
        tv_sh "ime set $gboard" >/dev/null 2>&1
    fi
fi
tv_sh "input keyevent KEYCODE_WAKEUP" >/dev/null 2>&1

gt_say "Окно с экраном открывается. Закройте его, когда закончите."
serial_args=()
[ -n "${TV:-}" ] && serial_args=(--serial "$TV")
"$scrcpy_bin" ${serial_args[@]+"${serial_args[@]}"} --no-audio --max-size 1920 --max-fps 30 \
    --window-title 'GlassTV' --shortcut-mod lalt --keyboard=uhid
rc=$?
exit $rc
