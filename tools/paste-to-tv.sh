#!/usr/bin/env bash
# paste-to-tv.sh: вставить на ТВ текст с ПК (любой, русский тоже) в поле ввода, которое сейчас в фокусе, и нажать Enter.
# Запуск: bash tools/paste-to-tv.sh [--tv адрес] [--no-enter] "текст"     текст из аргументов
#         echo "текст" | bash tools/paste-to-tv.sh                        текст из потока
#         bash tools/paste-to-tv.sh --clipboard                           текст из буфера обмена ПК (pbpaste, wl-paste, xclip или PowerShell)
# Условие: на ТВ стоит мини-приложение «Вставка с ПК» (app.glasstv.clip), а на экране ТВ курсор уже стоит в поле ввода.
# На Windows буфер читается через PowerShell с принудительным UTF-8 (иначе русские буквы приходят в кодировке консоли и ломаются); проще всего windows/tv-paste.ps1.
# Текст едет в base64: через adb так проходят кириллица, кавычки и любые знаки. Приложение кладёт его в буфер ТВ, затем жмётся «Вставить».
set -uo pipefail
source "$(dirname "$0")/lib.sh"

enter=1; use_clip=0
gt_parse_tv "$@"
words=()
for a in ${GT_REST[@]+"${GT_REST[@]}"}; do
    case "$a" in
        --no-enter) enter=0 ;;
        --clipboard) use_clip=1 ;;
        -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) words+=("$a") ;;
    esac
done

if [ "$use_clip" = 1 ]; then
    if command -v pbpaste >/dev/null 2>&1; then text="$(pbpaste)"
    elif command -v wl-paste >/dev/null 2>&1; then text="$(wl-paste)"
    elif command -v xclip >/dev/null 2>&1; then text="$(xclip -selection clipboard -o)"
    elif command -v powershell.exe >/dev/null 2>&1; then text="$(powershell.exe -NoProfile -Command '[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); Get-Clipboard -Raw' | tr -d '\r')"
    else gt_die "Не нашёл способа прочитать буфер обмена (pbpaste, wl-paste, xclip, PowerShell). Передайте текст аргументом."; fi
elif [ ${#words[@]} -gt 0 ]; then
    text="${words[*]}"
elif [ ! -t 0 ]; then
    text="$(cat)"
else
    gt_die "Нет текста: передайте его аргументом, через поток или ключом --clipboard."
fi
text="${text%$'\n'}"
[ -n "$text" ] || gt_die "Текст пустой."

tv_ready || exit $?
b64="$(printf '%s' "$text" | base64 | tr -d '\n\r')"
res="$(tv_sh "am start -W -n app.glasstv.clip/.Paste --es b64 '$b64'" 2>&1 | tr -d '\r')"
if printf '%s' "$res" | grep -qi 'error'; then
    gt_die "На ТВ нет приложения «Вставка с ПК» (app.glasstv.clip). Его ставит мастер: bash build/build-app.sh clip."
fi
sleep 0.4
tv_sh "input keyevent KEYCODE_PASTE" >/dev/null 2>&1
if [ "$enter" = 1 ]; then
    sleep 0.3
    tv_sh "input keyevent KEYCODE_ENTER" >/dev/null 2>&1
fi
gt_say "Вставлено на ТВ: $text"
