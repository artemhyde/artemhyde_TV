#!/usr/bin/env bash
# tv.sh: набор функций для управления ТВ из консоли (кнопки, снимок экрана, разбор экрана, журнал изменений, установка, раскладка Key Mapper).
# Подключение: LEDGER=backup/<метка>/ledger.tsv TV=192.168.0.10:5555 source tools/tv.sh; затем вызывать функции:
#   key, shot, dumpui, tapt, focus_texts, dpad_to, goto_tile, ledger, off, inst, tiles_install, play_install, rc_push, rc_press, km_new_map, km_new_long.
# Переменные задавай в каждом вызове заново: каждый вызов Bash начинается с нового шелла. LEDGER обязателен для всего, что меняет ТВ (off, inst, tiles_install, play_install):
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
    tv_sh "dumpsys activity activities" | tr -d '\r' | grep -m1 topResumedActivity | sed 's/.*u0 //; s/ t[0-9]*}//'
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
# иначе откат включил бы то, что человек отключил сам.
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

# inst файл.apk пакет ["что"]: поставить apk (adb install -r) и записать в журнал обратную команду.
# Обратная pm uninstall пишется только для app.glasstv.* и для приложений, которых до нас на ТВ не было. Что уже стояло у человека (Lampa, SmartTube,
# Key Mapper со своей раскладкой), откат не удаляет: обратная команда пустая, запись попадёт в список «делать руками».
# Key Mapper удаляется только руками всегда: у него права администратора устройства, а удаление при активном админе вешает систему.
inst() {
    local apk="${1:-}" p="${2:-}" what="${3:-}" had=0 out inv act
    if [ ! -f "$apk" ] || [ -z "$p" ]; then echo "inst: нужны файл apk и имя пакета"; return 1; fi
    gt_need_ledger || return 1
    if tv_sh "pm path $p" 2>/dev/null | grep -q package; then had=1; fi
    out="$(tv_adb install -r "$(gt_native "$apk")" 2>&1 | tr -d '\r')"
    if ! printf '%s' "$out" | grep -q 'Success'; then echo "ОШИБКА установки $p: $out"; return 1; fi
    inv="pm uninstall --user 0 $p"
    case "$p" in
        app.glasstv.*) ;;
        io.github.sds100.keymapper) inv="" ;;
        *) [ "$had" = 1 ] && inv="" ;;
    esac
    act="установлен"; [ "$had" = 1 ] && act="обновлён"
    if ! ledger "$act $p${what:+ ($what)}" "adb install -r $(basename "$apk")" "$inv"; then
        echo "ОШИБКА: $p стоит, но запись в журнал не удалась. Допиши её вручную: ledger \"$act $p\" \"adb install -r\" \"$inv\""; return 1
    fi
    if [ -n "$inv" ]; then echo "$act: $p (откат: pm uninstall)"; else echo "$act: $p (откат только руками)"; fi
}

# tiles_install: поставить плитки из out/tiles/*.apk, но только те, чьё приложение-цель уже стоит на ТВ: иначе плитка на главном экране молча ничего не откроет.
# Цель берётся из apps/tiles/tiles.txt (колонки: имя|пакет плитки|пакет цели|подпись|...), тот же файл читает build/build-app.sh.
# Цели плиток «Браузер» и «Файлы» подправь в tiles.txt по выбору человека до сборки плиток, иначе плитка будет ждать приложение, которого нет.
tiles_install() {
    local tf="$GT_ROOT/apps/tiles/tiles.txt" tname pkg target label rest n=0 skip=0
    [ -f "$tf" ] || { echo "нет $tf"; return 1; }
    gt_need_ledger || return 1
    while IFS='|' read -r -u 3 tname pkg target label rest; do
        if [ ! -f "$GT_ROOT/out/tiles/$tname.apk" ]; then echo "пропуск $tname: нет out/tiles/$tname.apk (плитки собираются: bash build/build-app.sh tiles)"; skip=$((skip + 1)); continue; fi
        if tv_sh "pm path $target" 2>/dev/null | grep -q package; then
            if inst "$GT_ROOT/out/tiles/$tname.apk" "$pkg" "плитка $tname"; then n=$((n + 1)); fi
        else
            echo "пропуск плитки $tname: на ТВ нет приложения $target"; skip=$((skip + 1))
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
