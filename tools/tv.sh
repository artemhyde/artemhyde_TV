#!/usr/bin/env bash
# tv.sh: набор функций для управления ТВ из консоли (кнопки, снимок экрана, разбор экрана, журнал изменений, установка, раскладка Key Mapper).
# Подключение: LEDGER=backup/<метка>/ledger.tsv TV=192.168.0.10:5555 source tools/tv.sh; затем вызывать функции:
#   key, shot, dumpui, tapt, focus_texts, dpad_to, goto_tile, ledger, off, inst, home_switch, tiles_install, play_install, settings_save, settings_load,
#   rc_push, rc_press, km_new_map, km_new_long.
# Переменные задавай в каждом вызове заново: каждый вызов Bash начинается с нового шелла. LEDGER обязателен для всего, что меняет ТВ (off, inst, home_switch, tiles_install, play_install):
# без журнала эти функции отказываются работать, потому что откат строится из журнала.
# Нужны adb и Python 3 (для разбора экрана). Рабочая папка для снимков и разбора экрана: TV_WORK (по умолчанию backup/.tv-work: backup/ в git не попадает).
# В разборах экрана бывают названия сетей, аккаунты и адреса: не клади их в папки, которые уходят в репозиторий.

_TV_SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$_TV_SH_DIR/lib.sh"
gt_find_adb || return 1
gt_find_python || return 1

W="${TV_WORK:-$GT_ROOT/backup/.tv-work}"
mkdir -p "$W"
UIPY="$(gt_native "$_TV_SH_DIR/ui.py")"
ROWPY="$(gt_native "$_TV_SH_DIR/row.py")"
ROWLISTPY="$(gt_native "$_TV_SH_DIR/rowlist.py")"
# Журнал изменений (его собирает мастер, откат строится из него): LEDGER=backup/<метка>/ledger.tsv. Функция ledger лежит в lib.sh.
LEDGER="${LEDGER:-}"

# key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER ...: нажать кнопки по очереди, пауза KEY_PAUSE секунд (по умолчанию 0.6)
key() { local k; for k in "$@"; do tv_sh "input keyevent $k"; sleep "${KEY_PAUSE:-0.6}"; done; }

# shot файл.png [пауза]: снимок экрана ТВ и название окна, которое сейчас сверху
shot() {
    sleep "${2:-1.5}"
    tv_adb exec-out screencap -p > "$1"
    tv_sh "dumpsys activity activities" | tr -d '\r' | grep -m1 -E 'topResumedActivity|ResumedActivity:' | sed 's/.*u0 //; s/ t[0-9]*}//'
}

# uidump: дерево экрана ТВ в файл $W/ui.xml
uidump() {
    tv_sh "uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
    tv_adb exec-out cat /sdcard/ui.xml > "$W/ui.xml"
}

# dumpui: напечатать все подписи на экране с рамками и пометкой фокуса
dumpui() { uidump; $PY -I -X utf8 "$UIPY" "$(gt_native "$W/ui.xml")"; }

# focus_texts: тексты внутри элемента, на котором сейчас фокус (по одному в строке; пусто, если фокуса нет)
focus_texts() { uidump; $PY -I -X utf8 "$UIPY" "$(gt_native "$W/ui.xml")" --focus | tr -d '\r'; }

# tapt "Текст": найти на экране элемент с этим текстом или описанием и коснуться его центра. Код возврата 1, если элемента нет.
# Касания переводят ТВ в режим касаний (фокус потом ведёт себя иначе), а в окнах выбора Key Mapper вообще не срабатывают: там dpad_to.
tapt() {
    uidump
    local b; b="$($PY -I -X utf8 "$UIPY" "$(gt_native "$W/ui.xml")" "$1")"
    if [ -z "$b" ]; then echo "не нашёл: $1"; return 1; fi
    tv_sh "input tap $b"
    echo "нажал «$1» в $b"
}

# dpad_to "Текст" [макс шагов]: стрелкой вниз дойти до элемента, внутри которого есть этот текст, проверяя экран после каждого шага.
# Возвращает 0, если фокус на нём; 1, если не дошли (список закончился, фокуса нет или шагов не хватило).
dpad_to() {
    local want="$1" max="${2:-25}" i f prev=""
    for i in $(seq 1 "$max"); do
        f="$(focus_texts)"
        if printf '%s\n' "$f" | grep -qxF "$want"; then return 0; fi
        if [ -n "$f" ] && [ "$f" = "$prev" ]; then return 1; fi
        tv_sh "input keyevent KEYCODE_DPAD_DOWN"; sleep 0.5
        prev="$f"
    done
    return 1
}

# dpad_pick "Текст" [макс шагов]: дойти стрелками до элемента и нажать OK. Возвращает 1, если дойти не удалось (OK не нажимается).
dpad_pick() { dpad_to "$@" || return 1; tv_sh "input keyevent KEYCODE_DPAD_CENTER"; sleep 1.5; }

# Пакет приложения сверху экрана (пусто, если прочитать не удалось)
# Android 12 пишет не topResumedActivity, а «ResumedActivity: ActivityRecord{…}» (проверено 07.11 на виртуальном Google TV 12)
_top_pkg() { tv_sh "dumpsys activity activities" 2>/dev/null | tr -d '\r' | grep -m1 -E 'topResumedActivity|ResumedActivity:' | sed -E 's/.* u[0-9]+ ([^/ ]+)\/.*/\1/'; }

# Владелец главного экрана (роль HOME). cmd role get-role-holders есть только с Android 13, на 10–12 владелец берётся из dumpsys role.
# На Android 9 ролей нет: главный экран тот, к которому система ведёт «Домой» (resolve-activity; окно выбора, пакет android: никого)
_home_holder() { tv_sh "h=\$(cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null) || h=''; [ -n \"\$h\" ] || h=\$(dumpsys role 2>/dev/null | grep -A1 'name=android.app.role.HOME\$' | sed -n 's/^ *holders=//p'); [ -n \"\$h\" ] || h=\$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1 | grep / | cut -d/ -f1); [ \"\$h\" = android ] && h=''; echo \"\$h\"" 2>/dev/null | tr -d '\r' | head -1 | tr -d ' '; }

# Версия приложения на ТВ (versionName), пусто, если его нет
_pkg_ver() { tv_sh "dumpsys package $1" 2>/dev/null | tr -d '\r' | grep -m1 'versionName=' | sed 's/.*versionName=//'; }

# Есть ли у пакета активные права администратора устройства
_is_admin() {
    tv_sh "dumpsys device_policy" 2>/dev/null | tr -d '\r' \
        | awk -v p="$1/" '/Enabled Device Admins/ {s = 1} s && index($1, p) == 1 {f = 1} END {exit !f}'
}

# Отвечает ли пакет за главный экран: уровень home в data/tcl-packages.tsv или у него есть экран категории HOME
# (так у главного экрана Google TV, у страховки и у запасного экрана настроек)
_is_home_pkg() {
    local p="$1"
    if awk -F'\t' -v p="$p" '$1 == p && $2 == "home" {f = 1} END {exit !f}' "$GT_ROOT/data/tcl-packages.tsv" 2>/dev/null; then return 0; fi
    tv_sh "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME" 2>/dev/null | tr -d '\r' \
        | awk -v p="$p/" '{sub(/^[ \t]+/, "")} index($0, p) == 1 {f = 1} END {exit !f}'
}

# Проверка по спискам data/: _pkg_in_list файл пакет. Строка списка: имя пакета целиком или начало имени со звёздочкой в конце,
# дальше через пробел или табуляцию что угодно, после знака решётки пояснение. 0 есть в списке, 1 нет, 2 нет самого файла.
_pkg_in_list() {
    local f="$1" p="$2" line pat
    [ -f "$f" ] || return 2
    while IFS= read -r line; do
        line="${line%%#*}"
        pat="$(printf '%s' "$line" | tr -d '\r' | awk '{print $1}')"
        [ -n "$pat" ] || continue
        case "$pat" in
            *'*') case "$p" in "${pat%\*}"*) return 0 ;; esac ;;
            *) [ "$p" = "$pat" ] && return 0 ;;
        esac
    done < "$f"
    return 1
}

# off пакет "причина": отключить системный пакет для пользователя 0 (pm disable-user) и записать в журнал.
# Откажется, если: не задан LEDGER; пакет в data/tcl-never-touch.txt; пакета нет в data/tcl-packages.tsv (вне списка только по прямому решению
# человека, по одному пакету: OFF_ALLOW_UNLISTED=1 off пакет "причина"). Пакет, который уже был отключён до нас, не трогается и в журнал не пишется,
# иначе откат включил бы то, что человек отключил сам. Пакет, который отвечает за главный экран (уровень home или экран категории HOME),
# off отдельно не отключает: только внутри home_switch, где смена проверяется и при сбое откатывается.
off() {
    local p="${1:-}" why="${2:-}" never="$GT_ROOT/data/tcl-never-touch.txt" known="$GT_ROOT/data/tcl-packages.tsv" r
    [ -n "$p" ] || { echo "off: нужен пакет"; return 1; }
    gt_need_ledger || return 1
    if [ ! -f "$never" ]; then gt_err "ОТКАЗ: нет $never, список «не трогать» проверить нечем."; return 1; fi
    if _pkg_in_list "$never" "$p"; then echo "ОТКАЗ: $p в списке «не трогать»"; return 1; fi
    if ! _pkg_in_list "$known" "$p" && [ "${OFF_ALLOW_UNLISTED:-0}" != 1 ]; then
        echo "ОТКАЗ: $p нет в data/tcl-packages.tsv. Вне списка только по прямому решению человека и по одному пакету: OFF_ALLOW_UNLISTED=1 off $p \"причина\""
        return 1
    fi
    if ! tv_sh "pm path $p" 2>/dev/null | grep -q package; then echo "нет на ТВ: $p"; return 0; fi
    # Главный экран отключается только внутри проверяемой смены главного экрана (home_switch): отдельно отключённый
    # прежний главный экран без готовой замены уводит кнопку «Домой» в тёмный или служебный экран
    if [ "${_GT_HOME_TXN:-0}" != 1 ] && _is_home_pkg "$p"; then
        echo "ОТКАЗ: $p отвечает за главный экран. Отключать его можно только вместе со сменой главного экрана: home_switch <новый главный экран> $p ..."
        return 1
    fi
    if tv_sh "pm list packages -d" 2>/dev/null | tr -d '\r' | grep -qx "package:$p"; then
        echo "уже отключён до нас: $p (в журнал не пишу, откат его не включит)"; return 0
    fi
    r="$(tv_sh "pm disable-user --user 0 $p" 2>&1 | tr -d '\r')"
    if echo "$r" | grep -q "new state: disabled"; then
        if ! ledger "отключён $p${why:+ ($why)}" "pm disable-user --user 0 $p" "pm enable --user 0 $p"; then
            tv_sh "pm enable --user 0 $p" >/dev/null 2>&1
            echo "ОШИБКА: запись в журнал не удалась, $p включён обратно"; return 1
        fi
        echo "отключён: $p"
    else
        echo "ОШИБКА $p: $r"; return 1
    fi
}

# inst файл.apk пакет ["что"]: поставить apk (adb install --user 0 -r) и записать в журнал обратную команду.
# Только основному пользователю: у TCL есть служебный второй пользователь прошивки, и приложение, поставленное
# всем, после отката pm uninstall --user 0 осталось бы числиться за ним (07.11, настоящий TCL 65C6K).
# Установка (приложения до этого на ТВ не было): обратная pm uninstall. Обновление (приложение уже стояло, в том числе наше app.glasstv.*):
# обратной команды нет. Удалять ради отката обновления нельзя: вместе с приложением пропадут его данные и настройки, а вернуть
# прежнюю версию поверх Android не даёт. Запись попадёт в список «делать руками» с версиями «было → стало».
# Key Mapper откатом не удаляется никогда: у него права администратора устройства, удаление при активных правах вешает систему.
# Обновление поверх приложения с активными правами администратора inst не делает (система виснет на 70 секунд): сначала права снимают.
# Подпись другая (стоит сборка с другим ключом): установка не идёт, стоящее приложение не удаляется.
inst() {
    local apk="${1:-}" p="${2:-}" what="${3:-}" had=0 out inv act v0="" v1=""
    if [ ! -f "$apk" ] || [ -z "$p" ]; then echo "inst: нужны файл apk и имя пакета"; return 1; fi
    gt_need_ledger || return 1
    if tv_sh "pm path $p" 2>/dev/null | grep -q package; then had=1; v0="$(_pkg_ver "$p")"; fi
    if [ "$had" = 1 ] && _is_admin "$p"; then
        echo "ОТКАЗ: у $p включены права администратора устройства. Сначала сними их (шаг 7 мастера, «Правило обновления»), потом обновляй."
        return 1
    fi
    out="$(tv_adb install --user 0 -r "$(gt_native "$apk")" 2>&1 | tr -d '\r')"
    if ! printf '%s' "$out" | grep -q 'Success'; then
        case "$out" in
            *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
                echo "ОШИБКА $p: на ТВ стоит сборка с другой подписью. Ставить поверх нельзя, а удалять её ради установки не буду: пропадут данные. Переход на другой ключ: docs/rollback.md." ;;
            *INSTALL_FAILED_VERSION_DOWNGRADE*)
                echo "ОШИБКА $p: на ТВ версия новее этого файла. Старую поверх Android не ставит; стоящая версия не тронута." ;;
            *) echo "ОШИБКА установки $p: $out" ;;
        esac
        return 1
    fi
    v1="$(_pkg_ver "$p")"
    if [ "$had" = 1 ]; then
        act="обновлён"; inv=""
        what="${v0:-?} → ${v1:-?}, откат версии не делается, данные на месте${what:+; $what}"
    else
        act="установлен"; inv="pm uninstall --user 0 $p"
        [ "$p" = io.github.sds100.keymapper ] && inv=""
    fi
    if ! ledger "$act $p${what:+ ($what)}" "adb install --user 0 -r $(basename "$apk")" "$inv"; then
        echo "ОШИБКА: $p стоит, но запись в журнал не удалась. Допиши её вручную: ledger \"$act $p\" \"adb install --user 0 -r\" \"$inv\""; return 1
    fi
    if [ -n "$inv" ]; then echo "$act: $p (откат: pm uninstall)"; elif [ "$had" = 1 ]; then echo "$act: $p ($what)"; else echo "$act: $p (откат только руками)"; fi
}

# home_switch пакет [пакеты, которые отключить вместе со сменой]: сменить главный экран одной проверяемой операцией.
#   1. новый главный экран стоит и открывается (иначе ничего не меняется);
#   2. запоминается прежний владелец;
#   3. перечисленные пакеты отключаются функцией off (на TCL: главный экран Google TV и страховка, уровень home в data/tcl-packages.tsv);
#   4. в журнал пишется намерение с обратной командой (возврат роли), затем назначается новый главный экран;
#   5. проверка: владелец роли сменился, а кнопка «Домой» открывает новый главный экран.
# Любой сбой: отключённое включается обратно в обратном порядке, роль возвращается прежнему, код 1. Связь пропала посреди: код 3 и
# точные команды возврата на экран (в журнале они тоже есть, restore.sh их выполнит). Отдельно off эти пакеты не отключает.
home_switch() {
    local cand="${1:-}" act prev top holder p r
    local -a done_pk=()
    [ -n "$cand" ] || { echo "home_switch: нужен пакет нового главного экрана"; return 1; }
    shift
    gt_need_ledger || return 1
    act="$(tv_sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $cand" 2>/dev/null \
        | tr -d '\r' | awk -v p="$cand/" '{sub(/^[ \t]+/, "")} index($0, p) == 1 {c = $0} END {print c}')"
    if [ -z "$act" ]; then echo "ОТКАЗ: у $cand нет главного экрана (или приложения нет на ТВ). Ничего не менял."; return 1; fi
    prev="$(_home_holder)"
    if [ "$prev" = "$cand" ]; then _home_already "$cand" "$@"; return $?; fi
    tv_sh "am start -n $act" >/dev/null 2>&1
    sleep "${HOME_WAIT:-3}"
    top="$(_top_pkg)"
    if [ "$top" != "$cand" ]; then echo "ОТКАЗ: $cand не открылся (сверху ${top:-неизвестно}). Ничего не менял."; return 1; fi
    for p in "$@"; do
        if ! _GT_HOME_TXN=1 off "$p" "вместе со сменой главного экрана на $cand"; then
            echo "Не удалось отключить $p: возвращаю как было."
            _home_undo "$prev" "${done_pk[@]}"; return 1
        fi
        done_pk+=("$p")
    done
    if ! ledger "главный экран: $cand вместо ${prev:-никого}" "cmd package set-home-activity $act" \
            "$(_home_back "$prev")"; then
        echo "Запись в журнал не удалась: главный экран не меняю, возвращаю отключённое."
        _home_undo "$prev" "${done_pk[@]}"; return 1
    fi
    r="$(tv_sh "cmd package set-home-activity $act" 2>&1 | tr -d '\r')"
    sleep "${HOME_WAIT:-3}"
    if [ "$(tv_state)" != device ]; then _home_lost "$prev" "${done_pk[@]}"; return 3; fi
    holder="$(_home_holder)"
    tv_sh "input keyevent KEYCODE_HOME" >/dev/null 2>&1
    sleep "${HOME_WAIT:-3}"
    top="$(_top_pkg)"
    if [ "$holder" = "$cand" ] && [ "$top" = "$cand" ]; then
        echo "главный экран: $cand (владелец сменился, «Домой» открывает его)"; return 0
    fi
    if [ "$(tv_state)" != device ]; then _home_lost "$prev" "${done_pk[@]}"; return 3; fi
    echo "НЕ ВЫШЛО: ответ «${r:-пусто}», владелец ${holder:-никто}, «Домой» открывает ${top:-неизвестно}. Возвращаю как было."
    _home_undo "$prev" "${done_pk[@]}"; return 1
}

# Команда, которая вернёт главным экраном пакет $1. Со службой ролей (Android 10 и новее) роль HOME, как раньше;
# на Android 9 set-home-activity по его экрану «Домой». Пакета нет или экрана нет: пусто
_home_back() {
    local prev="${1:-}" act
    [ -n "$prev" ] || return 0
    if tv_sh "service check role" 2>/dev/null | grep -q ': found'; then
        echo "cmd role add-role-holder --user 0 android.app.role.HOME $prev"; return 0
    fi
    act="$(tv_sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $prev" 2>/dev/null \
        | tr -d '\r' | awk -v p="$prev/" '{sub(/^[ \t]+/, "")} index($0, p) == 1 {c = $0} END {print c}')"
    [ -n "$act" ] && echo "cmd package set-home-activity $act"
    return 0
}

# Возврат после неудачной смены главного экрана: включить отключённое в обратном порядке, роль прежнему, проверить
# Роль «Домой» уже у нового главного экрана (ставили раньше или не через home_switch). Одной роли мало: проверить кнопку.
# «Домой» уводит в другой экран (прежний главный экран включён, так бывает на Google TV): доделать отключение перечисленного
# и проверить снова. Прежний владелец неизвестен, роль не трогаем; не вышло: отключённое включается обратно, код 1
_home_already() {
    local cand="$1" p top; shift
    local -a done_pk=()
    tv_sh "input keyevent KEYCODE_HOME" >/dev/null 2>&1
    sleep "${HOME_WAIT:-3}"
    top="$(_top_pkg)"
    if [ "$top" = "$cand" ]; then echo "$cand уже главный экран («Домой» открывает его)"; return 0; fi
    if [ $# -eq 0 ]; then
        echo "НЕ ВЫШЛО: $cand уже владелец роли, но «Домой» открывает ${top:-неизвестно}, а отключать нечего (на TCL и Google TV нужны launcherx и setupwraith)."
        return 1
    fi
    echo "$cand уже владелец роли, но «Домой» открывает ${top:-неизвестно}: доделываю отключение"
    for p in "$@"; do
        if ! _GT_HOME_TXN=1 off "$p" "вместе со сменой главного экрана на $cand"; then
            echo "Не удалось отключить $p: возвращаю отключённое."
            _home_undo "" "${done_pk[@]}"; return 1
        fi
        done_pk+=("$p")
    done
    tv_sh "input keyevent KEYCODE_HOME" >/dev/null 2>&1
    sleep "${HOME_WAIT:-3}"
    if [ "$(tv_state)" != device ]; then _home_lost "" "${done_pk[@]}"; return 3; fi
    top="$(_top_pkg)"
    if [ "$top" = "$cand" ]; then echo "главный экран: $cand («Домой» открывает его после отключения ${done_pk[*]})"; return 0; fi
    echo "НЕ ВЫШЛО: «Домой» всё равно открывает ${top:-неизвестно}. Возвращаю отключённое."
    _home_undo "" "${done_pk[@]}"; return 1
}

_home_undo() {
    local prev="$1" i n bad=0; shift
    local -a pk=("$@")
    n=${#pk[@]}
    for ((i = n - 1; i >= 0; i--)); do
        tv_sh "pm enable --user 0 ${pk[i]}" >/dev/null 2>&1 || { echo "  не включился ${pk[i]}"; bad=1; }
    done
    if [ -n "$prev" ]; then
        tv_sh "$(_home_back "$prev")" >/dev/null 2>&1 || bad=1
        if [ "$(_home_holder)" != "$prev" ]; then echo "  владелец главного экрана не вернулся к $prev"; bad=1; fi
    fi
    if [ "$bad" = 0 ]; then echo "Возвращено как было: главный экран ${prev:-прежний}."; return 0; fi
    echo "ВНИМАНИЕ: вернуть как было удалось не полностью. Проверь кнопку «Домой»; команды возврата есть в журнале (restore.sh)."
    return 1
}

# Связь пропала посреди смены главного экрана: что выполнить после переподключения (по шагу 2 мастера)
_home_lost() {
    local prev="$1" i n; shift
    local -a pk=("$@")
    n=${#pk[@]}
    echo "СВЯЗЬ С ТВ ПРОПАЛА посреди смены главного экрана. Ничего не утверждаю: состояние ТВ неизвестно."
    echo "После переподключения проверь главный экран и, если «Домой» ведёт не туда, выполни по порядку:"
    for ((i = n - 1; i >= 0; i--)); do echo "  pm enable --user 0 ${pk[i]}"; done
    [ -n "$prev" ] && echo "  $(_home_back "$prev")"
    echo "Эти же команды есть в журнале: restore.sh их выполнит."
}

# tiles_install: поставить плитки из out/tiles/*.apk, но только те, чьё приложение-цель уже стоит на ТВ: иначе плитка на главном экране молча ничего не откроет.
# Цель берётся из apps/tiles/tiles.txt (колонки: имя|пакет плитки|пакет цели|подпись|...), тот же файл читает build/build-app.sh.
# Плитка ставится, если на ТВ есть её приложение или другой вариант того же (список _tile_alts ниже, тот же в «Доме», HomeActivity.ALT):
# TV Bro из Google Play вместо нашей сборки, второй файловый менеджер, официальный YouTube вместо SmartTube. «Дом» сам открывает тот вариант,
# что стоит; своё приложение для плитки человек выбирает потом на ТВ («Настройки «Дома»», «Какие приложения открывают плитки»).
_tile_alts() {
    case "$1" in
        app.glasstv.browser) echo "com.phlox.tvwebbrowser.foss com.phlox.tvwebbrowser" ;;
        com.phlox.tvwebbrowser.foss) echo "app.glasstv.browser com.phlox.tvwebbrowser" ;;
        com.phlox.tvwebbrowser) echo "app.glasstv.browser com.phlox.tvwebbrowser.foss" ;;
        me.zhanghai.android.files) echo "com.cxinventor.file.explorer" ;;
        com.cxinventor.file.explorer) echo "me.zhanghai.android.files" ;;
        org.smarttube.stable) echo "com.liskovsoft.smarttubetv.beta com.google.android.youtube.tv" ;;
    esac
}

tiles_install() {
    local tf="$GT_ROOT/apps/tiles/tiles.txt" tname pkg target label rest n=0 skip=0 have alt
    [ -f "$tf" ] || { echo "нет $tf"; return 1; }
    gt_need_ledger || return 1
    while IFS='|' read -r -u 3 tname pkg target label rest; do
        if [ ! -f "$GT_ROOT/out/tiles/$tname.apk" ]; then echo "пропуск $tname: нет out/tiles/$tname.apk (плитки собираются: bash build/build-app.sh tiles)"; skip=$((skip + 1)); continue; fi
        have=""
        for alt in "$target" $(_tile_alts "$target"); do
            if tv_sh "pm path $alt" 2>/dev/null | grep -q package; then have="$alt"; break; fi
        done
        if [ -n "$have" ]; then
            [ "$have" != "$target" ] && echo "плитка $tname: вместо $target стоит $have, «Дом» откроет его"
            if inst "$GT_ROOT/out/tiles/$tname.apk" "$pkg" "плитка $tname"; then n=$((n + 1)); fi
        else
            echo "пропуск плитки $tname: на ТВ нет приложения $target и его вариантов"; skip=$((skip + 1))
        fi
    done 3< <(grep -v '^[[:space:]]*#' "$tf" | grep -v '^[[:space:]]*$' | tr -d '\r')
    echo "плиток поставлено: $n, пропущено: $skip"
}

# play_install пакет: открыть страницу приложения в Google Play, нажать OK на кнопке «Установить» и ждать появления пакета (до двух минут).
# Только для бесплатных приложений и только после прямого «да» человека. OK жмётся, только если фокус стоит на «Установить» или «Install»
# и на странице нет «Купить»/«Buy» и цены. Иначе функция останавливается (код 2), снимок страницы лежит в $W/play_<пакет>.png:
# покажи его человеку, вход в аккаунт и платные кнопки не нажимай. После установки пишет в журнал обратную pm uninstall (пакета до нас не было).
play_install() {
    local p="${1:-}" i f labels
    [ -n "$p" ] || { echo "play_install: нужен пакет"; return 1; }
    gt_need_ledger || return 1
    if tv_sh "pm path $p" 2>/dev/null | grep -q package; then echo "уже есть: $p"; return 0; fi
    tv_sh "am start -a android.intent.action.VIEW -d 'market://details?id=$p' -p com.android.vending" >/dev/null
    sleep 6
    tv_adb exec-out screencap -p > "$W/play_$p.png"
    uidump
    labels="$($PY -I -X utf8 "$UIPY" "$(gt_native "$W/ui.xml")" | tr '\n' ' ')"
    f="$($PY -I -X utf8 "$UIPY" "$(gt_native "$W/ui.xml")" --focus | tr -d '\r')"
    if printf '%s' "$labels" | grep -qiE 'Купить|Buy |₽|€|£|[$]|BYN'; then
        echo "СТОП: на странице $p есть платная кнопка или цена. Снимок: $W/play_$p.png. Решает человек."; return 2
    fi
    if ! printf '%s\n' "$f" | grep -qxE 'Установить|Install'; then
        echo "СТОП: фокус не на «Установить» (в фокусе: $(printf '%s' "$f" | tr '\n' '|')). Снимок: $W/play_$p.png. Покажи его человеку: возможно, нужен вход в Google или другая страница."; return 2
    fi
    tv_sh "input keyevent KEYCODE_DPAD_CENTER"
    for i in $(seq 1 40); do
        sleep 3
        if tv_sh "pm path $p" 2>/dev/null | grep -q package; then
            ledger "поставлен из Google Play: $p" "market://details?id=$p" "pm uninstall --user 0 $p" \
                || echo "ВНИМАНИЕ: $p установлен, но запись в журнал не удалась: допиши вручную"
            echo "установлен: $p за $((i * 3)) с"; return 0
        fi
    done
    echo "НЕ УСТАНОВИЛСЯ: $p"; return 1
}

# settings_save [файл.json]: копия настроек «Дома», «Сторожа» и «Волны» одним файлом на компьютер (на ТВ то же самое:
# «Настройки «Дома»» > «Перенос настроек»). На ТВ копия ложится в «Загрузки», сюда забирается и там убирается: она была
# только перевозкой, а в «Загрузках» ТВ остались бы город и координаты (06.11: после отката на виртуальном ТВ лежали два
# таких файла). По умолчанию backup/hyde-settings-<время>.json: backup/ в git не попадает. Пароли, история и код записок
# в копию не входят. Настроек ТВ не меняет, журнал не нужен.
settings_save() {
    local out="${1:-$GT_ROOT/backup/hyde-settings-$(date +%Y%m%d-%H%M%S).json}" r p
    r="$(tv_sh "am broadcast -n app.glasstv.home/.SetupCmd --es do export" 2>/dev/null | tr -d '\r' | grep -m1 'data=')"
    p="$(printf '%s' "$r" | sed -nE 's/.*data="(\/sdcard\/[^"]*\.json).*/\1/p')"
    [ -n "$p" ] || { echo "ОШИБКА: «Дом» не сохранил настройки (${r:-нет ответа}). Стоит ли app.glasstv.home?"; return 1; }
    mkdir -p "$(dirname "$out")"
    { tv_adb pull "$p" "$(gt_native "$out")" >/dev/null 2>&1 && [ -s "$out" ]; } || { echo "ОШИБКА: не забрал $p с ТВ"; return 1; }
    tv_sh "rm -f '$p'" >/dev/null 2>&1
    echo "настройки сохранены: $out"
}

# settings_load файл.json: вернуть настройки из копии. Сначала нынешние сохраняются в backup/ (вернуть их: settings_load тот файл),
# потом «Дом» раскладывает копию по своим приложениям и отвечает, сколько настроек приняло каждое. Загрузка дописывает:
# чего нет в копии, не трогает. Режим отладки «выключена» из копии сразу не выключает: два часа обслуживания, потом сам.
settings_load() {
    local f="${1:-}" r before
    [ -f "$f" ] || { echo "settings_load: нужен файл копии"; return 1; }
    before="$GT_ROOT/backup/hyde-settings-before-$(date +%Y%m%d-%H%M%S).json"
    settings_save "$before" >/dev/null || echo "ВНИМАНИЕ: нынешние настройки сохранить не удалось, загружаю без копии «до»"
    tv_adb push "$(gt_native "$f")" /data/local/tmp/hyde-settings.json >/dev/null 2>&1 || { echo "ОШИБКА: файл не дошёл до ТВ"; return 1; }
    tv_sh "chmod 644 /data/local/tmp/hyde-settings.json" >/dev/null 2>&1
    r="$(tv_sh "am broadcast -n app.glasstv.home/.SetupCmd --es do import --es file /data/local/tmp/hyde-settings.json" 2>/dev/null \
        | tr -d '\r' | grep -m1 'data=' | sed -E 's/.*data="(.*)"$/\1/')"
    tv_sh "rm -f /data/local/tmp/hyde-settings.json" >/dev/null 2>&1
    case "$r" in
        Загружено*)
            echo "$r"; [ -s "$before" ] && echo "прежние настройки: $before"
            # Новый цвет стекла: «Дом» гасит свой процесс, здесь он поднимается снова уже с новой темой
            case "$r" in *перезапустится*) sleep 1; tv_sh "am start -a android.intent.action.MAIN -c android.intent.category.HOME" >/dev/null 2>&1 ;; esac ;;
        *) echo "ОШИБКА: ${r:-«Дом» не ответил}"; return 1 ;;
    esac
    return 0
}

# goto_tile "Имя": на главном экране с плитками (у плиток есть подпись) дойти кнопками до плитки по имени.
# Размеры в row.py рассчитаны на экран 1920x1080 и плитки шире 20 и уже 700 пикселей.
goto_tile() {
    local name="$1" i r k n
    for i in 1 2 3 4 5 6 7 8; do
        uidump
        r="$($PY -I -X utf8 "$ROWPY" "$(gt_native "$W/ui.xml")" "$name")"
        if [ -z "$r" ]; then tv_sh "input keyevent KEYCODE_DPAD_DOWN"; sleep 0.7; continue; fi
        set -- "$name" $r
        if [ "$2" = "0" ]; then
            k=KEYCODE_DPAD_RIGHT; n=$3
            [ "$n" -lt 0 ] && { k=KEYCODE_DPAD_LEFT; n=$((-n)); }
            [ "$n" -eq 0 ] && return 0
            tv_sh "for j in \$(seq 1 $n); do input keyevent $k; done"; sleep 0.8
        else
            if [ "$2" = "1" ]; then tv_sh "input keyevent KEYCODE_DPAD_DOWN"; else tv_sh "input keyevent KEYCODE_DPAD_UP"; fi
            sleep 0.8
        fi
    done
    return 1
}

# row_names: подписи всех плиток в строке с фокусом, слева направо
row_names() {
    uidump
    $PY -I -X utf8 "$ROWLISTPY" "$(gt_native "$W/ui.xml")" | tr '|' '\n' | sed 's/^\*//' | grep -v '^$'
}

# rc_push: положить двойников пульта (tools/rc-double/*.json) на ТВ в /data/local/tmp
rc_push() {
    local f
    for f in "$_TV_SH_DIR"/rc-double/*.json; do tv_adb push "$(gt_native "$f")" /data/local/tmp/ >/dev/null; done
    echo "двойники пульта лежат в /data/local/tmp"
}

# rc_press имя: нажать «кнопку» двойника: rc_press okko, rc_press gear_long (файлы rc_okko.json, rc_gear_long.json). Нужен предварительный rc_push.
# Двойник работает через системную команду hid; input keyevent приложение Key Mapper не принимает. Только готовые файлы из rc-double/, свои коды не придумывать.
rc_press() { local n="${1#rc_}"; tv_sh "hid /data/local/tmp/rc_$n.json" >/dev/null 2>&1; }

# Раскладка Key Mapper (интерфейс на английском, версия 4.6.2 foss). Что проверено на деле на TCL 65C6K: касания не срабатывают в окне «Choose an action»
# и в предупреждении («Launch app», «Ignore» выбираются стрелками и OK), кнопки формы и приложение в списке нажимаются касанием, список листается свайпом.
# Функции ниже целиком на живом ТВ НЕ проверялись: после каждого вызова сними экран (shot) и сверь с результатом. При сбое функция жмёт «Назад» один раз,
# пишет, на каком шаге остановилась, и возвращает 1: дальше делай вручную по описанию в CLAUDE.md (шаг 10), вслепую не повторяй.
_km_fail() {
    echo "Key Mapper: остановился на шаге «$1». Нажимаю «Назад» один раз. Недоделанное назначение (если осталось) удали в Key Mapper руками, шаги в CLAUDE.md, шаг 10."
    tv_sh "input keyevent KEYCODE_BACK"
    return 1
}

# _km_pick_app "Название": приложение в списке «Launch app»: листать свайпом, нужное нажать касанием. Не нашли за 8 прокруток: 1.
_km_pick_app() {
    local tries=0
    until tapt "$1" >/dev/null; do
        tv_sh "input swipe 960 800 960 300 300"; sleep 1.5
        tries=$((tries + 1)); [ "$tries" -ge 8 ] && return 1
    done
    return 0
}

# km_new_map двойник "Название приложения": в Key Mapper создать раскладку «кнопка > запуск приложения».
# Шаги: New key map, Tap to record trigger, нажатие двойника, Add action, Launch app (стрелками), Ignore (стрелками, если есть предупреждение), приложение из списка.
km_new_map() {
    local dev="$1" app="$2" trig row
    key KEYCODE_HOME; sleep 1
    tv_sh "monkey -p io.github.sds100.keymapper 1" >/dev/null 2>&1; sleep 2
    tapt "New key map" >/dev/null || { _km_fail "New key map"; return 1; }; sleep 2
    tapt "Tap to record trigger" >/dev/null || { _km_fail "Tap to record trigger"; return 1; }
    rc_press "$dev"; sleep 2
    uidump
    trig="$(grep -oE 'text="Key code [0-9]+"' "$W/ui.xml" | head -1)"
    echo "триггер: $trig"
    [ -n "$trig" ] || { _km_fail "запись триггера (в строке триггера нет «Key code»)"; return 1; }
    tapt "Add action" >/dev/null || { _km_fail "Add action"; return 1; }; sleep 2
    dpad_pick "Launch app" 30 || { _km_fail "«Launch app» стрелками"; return 1; }
    sleep 1; uidump
    if grep -q 'text="Ignore"' "$W/ui.xml"; then
        dpad_pick "Ignore" 8 || { _km_fail "«Ignore» в предупреждении"; return 1; }
        sleep 2
    fi
    _km_pick_app "$app" || { _km_fail "приложение «$app» не найдено в списке"; return 1; }
    sleep 2; uidump
    row="$(grep -oE 'text="Open [^"]+"' "$W/ui.xml" | head -1)"
    echo "${row:-нет строки «Open ...»}"
    [ -n "$row" ] || { _km_fail "под действием нет строки «Open ...»"; return 1; }
    key KEYCODE_BACK; sleep 1.5
}

# km_new_long двойник_долгий "Название приложения": то же для долгого нажатия (rc_gear_long, rc_okko_long), включая переключатель «Long press».
km_new_long() {
    local dev="$1" app="$2" trig row
    key KEYCODE_HOME; sleep 1
    tv_sh "monkey -p io.github.sds100.keymapper 1" >/dev/null 2>&1; sleep 2.5
    tapt "New key map" >/dev/null || { _km_fail "New key map"; return 1; }; sleep 2
    tapt "Tap to record trigger" >/dev/null || { _km_fail "Tap to record trigger"; return 1; }
    rc_press "$dev"; sleep 2
    uidump
    trig="$($PY -I -X utf8 "$UIPY" "$(gt_native "$W/ui.xml")" | grep -E "Notification|Key code" | head -2)"
    echo "триггер: $trig"
    [ -n "$trig" ] || { _km_fail "запись триггера (в строке триггера нет «Key code»)"; return 1; }
    tapt "Long press" >/dev/null || { _km_fail "переключатель «Long press»"; return 1; }; sleep 1.2
    tapt "Add action" >/dev/null || { _km_fail "Add action"; return 1; }; sleep 2
    dpad_pick "Launch app" 30 || { _km_fail "«Launch app» стрелками"; return 1; }
    sleep 1; uidump
    if grep -q 'text="Ignore"' "$W/ui.xml"; then
        dpad_pick "Ignore" 8 || { _km_fail "«Ignore» в предупреждении"; return 1; }
        sleep 2
    fi
    _km_pick_app "$app" || { _km_fail "приложение «$app» не найдено в списке"; return 1; }
    sleep 2; uidump
    row="$($PY -I -X utf8 "$UIPY" "$(gt_native "$W/ui.xml")" | grep -E "Long press|Open " | head -4)"
    echo "$row"
    printf '%s' "$row" | grep -q 'Open ' || { _km_fail "под действием нет строки «Open ...»"; return 1; }
}
