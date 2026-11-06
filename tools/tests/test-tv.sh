#!/usr/bin/env bash
# test-tv.sh: проверки без телевизора. Функции tools/tv.sh (inst, off, home_switch) и собранный restore.sh гоняются против
# подставного ТВ: его состояние (пакеты, отключённые, владелец главного экрана, верхнее окно, связь) живёт в переменных этого скрипта.
# Списки пакетов настоящие (data/tcl-packages.tsv и data/tcl-never-touch.txt). Ничего не подключается к настоящему ТВ.
# Запуск: bash tools/tests/test-tv.sh   Код выхода 0: все проверки прошли, иначе число провалов.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Подставной adb нужен, чтобы tv.sh подключился; вызовы ТВ из функций ниже подменены
printf '#!/bin/sh\nexit 0\n' > "$TMP/adb"
chmod +x "$TMP/adb"
export ADB="$TMP/adb" PY=true TV_WORK="$TMP/work" HOME_WAIT=0
# shellcheck source=../tv.sh
source "$HERE/../tv.sh" || { echo "tv.sh не подключился"; exit 1; }

PASS=0; FAILN=0
ok() { PASS=$((PASS + 1)); echo "  ок: $1"; }
bad() { FAILN=$((FAILN + 1)); echo "  ПРОВАЛ: $1"; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
has() { case " $1 " in *" $2 "*) return 0 ;; esac; return 1; }
# Вызов в этом же шелле (не в подоболочке $(...)): иначе состояние подставного ТВ после вызова не видно. Вывод в $out, код в $rc
env_off() { OFF_ALLOW_UNLISTED=1 off "$@"; }
call() { "$@" > "$TMP/out" 2>&1; rc=$?; out="$(cat "$TMP/out")"; }

# ===== Подставной ТВ =====
# Состояние в файлах, а не в переменных: функции tv.sh зовут ТВ и внутри подоболочек $(...), изменения оттуда иначе терялись бы.
# Настройки сценария (ROLE_STICKS и другие ниже) только читаются, поэтому остаются переменными
ST="$TMP/state"
sg() { cat "$ST/$1" 2>/dev/null; }
ss() { printf '%s' "$2" > "$ST/$1"; }
reset_tv() {
    rm -rf "$ST"; mkdir -p "$ST"
    ss pkgs "com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith app.glasstv.home app.glasstv.nightmode com.android.tv.settings"
    ss disabled ""; ss holder com.google.android.apps.tv.launcherx; ss top com.google.android.apps.tv.launcherx; ss online 1; ss calls ""
    HOMECAP="com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith app.glasstv.home com.android.tv.settings"
    ADMINS=""; ROLE_STICKS=1; HOME_LAUNCHES=1; LOSE_ON_SETHOME=0
    INSTALL_RESULT=Success; NEWVER=2.0
    mkdir -p "$TMP/backup/t"; : > "$TMP/backup/t/ledger.tsv"
    LEDGER="$TMP/backup/t/ledger.tsv"
}
ver() { if [ -f "$ST/new_$1" ]; then echo "$NEWVER"; else case "$1" in app.glasstv.home|app.glasstv.nightmode) echo 1.0 ;; esac; fi; }

tv_state() { if [ "$(sg online)" = 1 ]; then echo device; else echo no-device; fi; }

tv_adb() {
    echo "adb $*" >> "$ST/calls"
    [ "$(sg online)" = 1 ] || { echo "error: device offline"; return 1; }
    [ "$1" = pull ] && printf '{"hyde":"settings","v":1}' > "$3"
    if [ "$1" = install ]; then
        echo "$INSTALL_RESULT"
        if [ "$INSTALL_RESULT" = Success ]; then
            has "$(sg pkgs)" "$INST_PKG" || ss pkgs "$(sg pkgs) $INST_PKG"
            : > "$ST/new_$INST_PKG"
        fi
    fi
    return 0
}

tv_sh() {
    local c="$*" p a
    echo "$c" >> "$ST/calls"
    [ "$(sg online)" = 1 ] || return 1
    case "$c" in
        "pm path "*) p="${c#pm path }"; has "$(sg pkgs)" "$p" && echo "package:/data/app/$p/base.apk" ;;
        "pm list packages -d") for p in $(sg disabled); do echo "package:$p"; done ;;
        "pm disable-user --user 0 "*) p="${c##* }"; ss disabled "$(sg disabled) $p"; echo "Package $p new state: disabled-user" ;;
        "pm enable --user 0 "*) p="${c##* }"; ss disabled "$(printf '%s\n' $(sg disabled) | grep -vx "$p" | tr '\n' ' ')"; echo "Package $p new state: enabled" ;;
        "dumpsys package "*) p="${c#dumpsys package }"; a="$(ver "$p")"; [ -n "$a" ] && echo "    versionName=$a" ;;
        "dumpsys device_policy")
            if [ -n "$ADMINS" ]; then echo "  Enabled Device Admins (User 0, provisioningState: 0):"; for a in $ADMINS; do echo "    $a/.Admin:"; done; fi ;;
        "cmd package query-activities"*)
            for p in $HOMECAP; do has "$(sg disabled)" "$p" || echo "    $p/.Home"; done ;;
        "cmd package resolve-activity"*)
            p="${c##* }"; if has "$HOMECAP" "$p" && ! has "$(sg disabled)" "$p"; then echo "priority=0"; echo "$p/.Home"; fi ;;
        "cmd role get-role-holders"*) sg holder; echo ;;
        "cmd role add-role-holder"*) ss holder "${c##* }" ;;
        "am start -n "*) p="${c#am start -n }"; p="${p%%/*}"; [ "$HOME_LAUNCHES" = 1 ] && ss top "$p" ;;
        "cmd package set-home-activity "*)
            p="${c#cmd package set-home-activity }"; p="${p%%/*}"
            [ "$ROLE_STICKS" = 1 ] && ss holder "$p"
            [ "$LOSE_ON_SETHOME" = 1 ] && ss online 0
            echo "Success" ;;
        "input keyevent KEYCODE_HOME")
            # HIJACK: прежний главный экран перехватывает «Домой», пока он включён (так ведёт себя Google TV)
            if [ -n "${HIJACK:-}" ] && ! has "$(sg disabled)" "$HIJACK"; then ss top "$HIJACK"
            elif has "$(sg disabled)" "$(sg holder)"; then ss top com.android.tv.settings; else ss top "$(sg holder)"; fi ;;
        "dumpsys activity activities") echo "  topResumedActivity=ActivityRecord{1a u0 $(sg top)/.Main t7}" ;;
        "am broadcast -n app.glasstv.home/.SetupCmd --es do export")
            if [ "${NO_HOME:-0}" = 1 ]; then echo "Broadcast completed: result=0"
            else echo "Broadcast completed: result=0, data=\"$EXPORT_PATH (части: «Сторож» «Волна» «Дом»)\""; fi ;;
        "am broadcast -n app.glasstv.home/.SetupCmd --es do import"*)
            echo 'Broadcast completed: result=0, data="Загружено настроек. «Дом»: 10, «Сторож»: 16, «Волна»: 3"' ;;
    esac
    return 0
}
nodis() { [ -z "$(sg disabled | tr -d ' ')" ]; }

last_inv() { tail -1 "$LEDGER" | awk -F'\t' '{print $4}'; }
last_what() { tail -1 "$LEDGER" | awk -F'\t' '{print $2}'; }
lines() { grep -c . "$LEDGER" || true; }

echo "== inst: установка и обновление"
reset_tv; echo apk > "$TMP/s.apk"
INST_PKG=app.glasstv.sound; call inst "$TMP/s.apk" app.glasstv.sound "звук"
check "новое приложение: откат удаляет его" '[ "$(last_inv)" = "pm uninstall --user 0 app.glasstv.sound" ]'

reset_tv; INST_PKG=app.glasstv.home; call inst "$TMP/s.apk" app.glasstv.home "дом"
check "обновление нашего приложения: обратной команды нет (данные не стираются)" '[ -z "$(last_inv)" ]'
check "обновление: в журнале версии было и стало" 'case "$(last_what)" in *"1.0 → 2.0"*) true ;; *) false ;; esac'

reset_tv; ADMINS=app.glasstv.nightmode; INST_PKG=app.glasstv.nightmode
call inst "$TMP/s.apk" app.glasstv.nightmode
check "обновление при правах администратора: отказ" '[ "$rc" = 1 ]'
check "обновление при правах администратора: установка не запускалась" '! grep -q "^adb install" "$ST/calls"'
check "обновление при правах администратора: журнал пуст" '[ "$(lines)" = 0 ]'

reset_tv; INST_PKG=app.glasstv.home; INSTALL_RESULT="Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]"
call inst "$TMP/s.apk" app.glasstv.home
check "чужая подпись: ошибка" '[ "$rc" = 1 ]'
check "чужая подпись: приложение не удаляется" '! grep -q uninstall "$ST/calls"'
check "чужая подпись: понятное объяснение" 'case "$out" in *"другой подписью"*) true ;; *) false ;; esac'

echo "== off: главный экран отдельно не отключается"
reset_tv
call off com.google.android.apps.tv.launcherx "проба"
check "главный экран Google TV через off: отказ" '[ "$rc" = 1 ] && nodis'
call env_off com.android.tv.settings "проба"
check "пакет с запасным главным экраном: отказ даже вне списка" '[ "$rc" = 1 ] && nodis'

echo "== home_switch: смена главного экрана"
reset_tv
call home_switch app.glasstv.home com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith
check "успех: код 0" '[ "$rc" = 0 ]'
check "успех: владелец сменился" '[ "$(sg holder)" = app.glasstv.home ]'
check "успех: оба пакета отключены" 'has "$(sg disabled)" com.google.android.apps.tv.launcherx && has "$(sg disabled)" com.google.android.tungsten.setupwraith'
check "успех: в журнале два отключения и смена с возвратом роли" '[ "$(lines)" = 3 ] && [ "$(last_inv)" = "cmd role add-role-holder --user 0 android.app.role.HOME com.google.android.apps.tv.launcherx" ]'

reset_tv; ROLE_STICKS=0
call home_switch app.glasstv.home com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith
check "владелец не сменился: код 1" '[ "$rc" = 1 ]'
check "владелец не сменился: отключённое включено обратно" 'nodis'
check "владелец не сменился: роль у прежнего" '[ "$(sg holder)" = com.google.android.apps.tv.launcherx ]'

reset_tv; HOME_LAUNCHES=0
call home_switch app.glasstv.home com.google.android.apps.tv.launcherx
check "новый главный экран не открылся: код 1 и ничего не тронуто" '[ "$rc" = 1 ] && nodis && [ "$(lines)" = 0 ]'

reset_tv; LOSE_ON_SETHOME=1
call home_switch app.glasstv.home com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith
check "связь пропала посреди: код 3" '[ "$rc" = 3 ]'
check "связь пропала посреди: подсказка, что делать после переподключения" 'case "$out" in *"СВЯЗЬ С ТВ ПРОПАЛА"*"pm enable --user 0 com.google.android.apps.tv.launcherx"*) true ;; *) false ;; esac'
check "связь пропала посреди: возврат роли записан в журнал заранее" 'grep -q "add-role-holder" "$LEDGER"'

reset_tv; ss holder app.glasstv.home
call home_switch app.glasstv.home com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith
check "роль уже наша и «Домой» ведёт к нам: код 0, ничего не отключено" '[ "$rc" = 0 ] && nodis && [ "$(lines)" = 0 ]'

reset_tv; ss holder app.glasstv.home; HIJACK=com.google.android.apps.tv.launcherx
call home_switch app.glasstv.home com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith
check "роль уже наша, но «Домой» перехвачен: отключение доделано, код 0" '[ "$rc" = 0 ] && has "$(sg disabled)" com.google.android.apps.tv.launcherx && [ "$(sg top)" = app.glasstv.home ]'
check "роль уже наша, но «Домой» перехвачен: отключения в журнале с возвратом" 'grep -q "pm enable --user 0 com.google.android.apps.tv.launcherx" "$LEDGER"'
reset_tv; ss holder app.glasstv.home; HIJACK=com.google.android.apps.tv.launcherx
call home_switch app.glasstv.home
check "роль уже наша, «Домой» перехвачен, отключать нечего: код 1" '[ "$rc" = 1 ] && nodis'
HIJACK=

echo "== restore.sh: честный итог"
# Свой подставной adb для restore.sh: команды из FAILCMD падают, после команды из LOSECMD связь пропадает
cat > "$TMP/radb" <<'EOF'
#!/usr/bin/env bash
st="$RTMP/online"; [ -f "$st" ] || echo 1 > "$st"
[ "$1" = "-s" ] && shift 2
if [ "$1" = get-state ]; then if [ "$(cat "$st")" = 1 ]; then echo device; exit 0; else echo "error: no devices"; exit 1; fi; fi
[ "$(cat "$st")" = 1 ] || { echo "error: device offline"; exit 1; }
shift
# Чтение настройки: значение из файла cur_<пространство>_<ключ>, нет файла: null (в журнал выполненного не идёт)
case "$*" in "settings get "*) set -- $*; f="$RTMP/cur_$3_$4"; if [ -f "$f" ]; then cat "$f"; echo; else echo null; fi; exit 0 ;; esac
echo "$*" >> "$RTMP/ran"
case "$*" in "${LOSECMD:-@@}") echo 0 > "$st"; exit 1 ;; "${FAILCMD:-@@}") exit 1 ;; esac
exit 0
EOF
chmod +x "$TMP/radb"
mk_ledger() {
    mkdir -p "$TMP/backup/r"
    printf '%s\t%s\t%s\t%s\n' "t1" "отключён a" "pm disable-user --user 0 a" "pm enable --user 0 a" \
        "t2" "отключён b" "pm disable-user --user 0 b" "pm enable --user 0 b" \
        "t3" "отключён c" "pm disable-user --user 0 c" "pm enable --user 0 c" > "$TMP/backup/r/ledger.tsv"
    bash "$HERE/../make-restore.sh" "$TMP/backup/r" >/dev/null
}
runr() { rm -f "$TMP/online" "$TMP/ran"; RTMP="$TMP" ADB="$TMP/radb" TV=x:1 bash "$TMP/backup/r/restore.sh" --yes 2>&1; }
mk_ledger
export RTMP="$TMP"
out="$(FAILCMD=@@ LOSECMD=@@ runr)"; rc=$?
check "всё прошло: код 0 и «Готово»" '[ "$rc" = 0 ] && case "$out" in *"Готово: все команды"*) true ;; *) false ;; esac'
out="$(FAILCMD="pm enable --user 0 b" LOSECMD=@@ runr)"; rc=$?
check "одна команда не прошла: код 1, без «Готово»" '[ "$rc" = 1 ] && case "$out" in *"Готово:"*) false ;; *"НЕ ПОЛНОСТЬЮ"*) true ;; *) false ;; esac'
out="$(FAILCMD=@@ LOSECMD="pm enable --user 0 b" runr)"; rc=$?
check "связь пропала посреди: код 3, остаток не выполнялся" '[ "$rc" = 3 ] && ! grep -q "enable --user 0 a" "$TMP/ran" && case "$out" in *"не выполнено команд: 2"*) true ;; *) false ;; esac'
rm -f "$TMP/online" "$TMP/ran"; echo 0 > "$TMP/online"
out="$(RTMP="$TMP" ADB="$TMP/radb" TV=x:1 bash "$TMP/backup/r/restore.sh" --yes 2>&1)"; rc=$?
check "ТВ не на связи с самого начала: код 3, ничего не выполнялось" '[ "$rc" = 3 ] && [ ! -f "$TMP/ran" ]'

echo "== restore.sh: настройки, которые приложения меняют сами (частный DNS, спецвозможности, заставка)"
gen="$(bash "$HERE/../make-restore.sh" "$TMP/backup/r" 2>&1)"
check "в снимке нет списков настроек: предупреждение" 'case "$gen" in *"откат не вернёт"*) true ;; *) false ;; esac'
# Снимок «до»: DNS автоматический, адреса частного DNS нет, заставка включена, экран гаснет через 10 минут, отладка по сети включена
printf 'private_dns_mode=opportunistic\nadb_wifi_enabled=1\n' > "$TMP/backup/r/settings-global.txt"
printf 'screensaver_enabled=1\naccessibility_enabled=0\n' > "$TMP/backup/r/settings-secure.txt"
printf 'screen_off_timeout=600000\n' > "$TMP/backup/r/settings-system.txt"
gen="$(bash "$HERE/../make-restore.sh" "$TMP/backup/r" 2>&1)"
# Сейчас: «Сторож» сам поставил частный DNS и вписал службу Key Mapper, тайм-аут на максимуме; заставка уже как была
printf 'hostname' > "$TMP/cur_global_private_dns_mode"; printf 'one.one.one.one' > "$TMP/cur_global_private_dns_specifier"
printf 'io.github.sds100.keymapper/x.MyAccessibilityService' > "$TMP/cur_secure_enabled_accessibility_services"
printf '1' > "$TMP/cur_secure_accessibility_enabled"; printf '1' > "$TMP/cur_secure_screensaver_enabled"
printf '2147483647' > "$TMP/cur_system_screen_off_timeout"
out="$(FAILCMD=@@ LOSECMD=@@ runr)"; rc=$?
ran_has() { grep -qxF -- "$1" "$TMP/ran"; }
line_of() { grep -nxF -- "$1" "$TMP/ran" | head -1 | cut -d: -f1; }
t_dns_mode() { ran_has "settings put global private_dns_mode 'opportunistic'"; }
t_dns_host() { ran_has "settings delete global private_dns_specifier"; }
t_a11y() { ran_has "settings delete secure enabled_accessibility_services" && ran_has "settings put secure accessibility_enabled '0'"; }
t_saver() { ! grep -q "screensaver_enabled" "$TMP/ran"; }
t_timeout() { ran_has "settings put system screen_off_timeout '600000'"; }
t_adb() { ! grep -q "adb_wifi_enabled" "$TMP/ran"; }
t_after() { local a d; a="$(line_of "pm enable --user 0 a")"; d="$(line_of "settings put global private_dns_mode 'opportunistic'")"; [ -n "$a" ] && [ -n "$d" ] && [ "$d" -gt "$a" ]; }
check "частный DNS: режим как до установки" t_dns_mode
check "адрес частного DNS убран: до установки его не было" t_dns_host
check "служба Key Mapper убрана, спецвозможности как были" t_a11y
check "заставка не менялась: команды для неё нет" t_saver
check "тайм-аут экрана как до установки" t_timeout
check "отладку по сети откат не трогает: через неё идёт сам откат" t_adb
check "настройки возвращаются после всех команд журнала" t_after
check "итог: код 0 и «Готово»" '[ "$rc" = 0 ] && case "$out" in *"Готово: все команды"*) true ;; *) false ;; esac'
out="$(bash "$TMP/backup/r/restore.sh" --dry-run 2>&1)"
check "сухой прогон показывает и эти настройки" 'case "$out" in *"частный DNS"*"opportunistic"*) true ;; *) false ;; esac'
rm -f "$TMP"/cur_* "$TMP/backup/r"/settings-*.txt

echo "== tiles_install: плитка ставится и при другом варианте приложения"
reset_tv
SAVED_ROOT="$GT_ROOT"; GT_ROOT="$TMP/root"; mkdir -p "$GT_ROOT/apps/tiles" "$GT_ROOT/out/tiles"
printf '%s\n' '# имя|плитка|цель|подпись' 'browser|app.glasstv.tile.browser|app.glasstv.browser|Браузер' \
    'files|app.glasstv.tile.files|me.zhanghai.android.files|Файлы' 'twitch|app.glasstv.tile.twitch|tv.twitch.android.app|Twitch' \
    > "$GT_ROOT/apps/tiles/tiles.txt"
for n in browser files twitch; do echo apk > "$GT_ROOT/out/tiles/$n.apk"; done
ss pkgs "$(sg pkgs) com.phlox.tvwebbrowser com.cxinventor.file.explorer"
INST_PKG=app.glasstv.tile.x; call tiles_install
check "браузер из Google Play вместо HYDE Browser: плитка ставится" 'case "$out" in *"плитка browser: вместо app.glasstv.browser стоит com.phlox.tvwebbrowser,"*) true ;; *) false ;; esac'
check "другой файловый менеджер: плитка ставится" 'case "$out" in *"плитка files: вместо me.zhanghai.android.files стоит com.cxinventor.file.explorer"*) true ;; *) false ;; esac'
check "нет ни приложения, ни варианта: плитка пропущена" 'case "$out" in *"пропуск плитки twitch"*) true ;; *) false ;; esac'
check "итог: две поставлены, одна пропущена" 'case "$out" in *"плиток поставлено: 2, пропущено: 1"*) true ;; *) false ;; esac'
GT_ROOT="$SAVED_ROOT"

echo "== settings_save и settings_load: перенос настроек"
reset_tv
SAVED_ROOT="$GT_ROOT"; GT_ROOT="$TMP/root2"; mkdir -p "$GT_ROOT"
EXPORT_PATH='/sdcard/Download/hyde-settings-20261106-2000 (1).json'
call settings_save "$TMP/copy.json"
check "сохранение: файл забран с ТВ, путь с пробелом цел" '[ "$rc" = 0 ] && [ -s "$TMP/copy.json" ] && grep -qF "adb pull $EXPORT_PATH" "$ST/calls"'
call settings_load "$TMP/copy.json"
check "загрузка: ответ «Дома» показан" '[ "$rc" = 0 ] && case "$out" in *"«Сторож»: 16"*) true ;; *) false ;; esac'
check "загрузка: прежние настройки сохранены до загрузки" 'ls "$GT_ROOT"/backup/hyde-settings-before-*.json >/dev/null 2>&1'
check "загрузка: файл убран с ТВ" 'grep -q "rm -f /data/local/tmp/hyde-settings.json" "$ST/calls"'
reset_tv
NO_HOME=1; call settings_save "$TMP/copy2.json"; NO_HOME=0
check "«Дома» нет: ошибка, файла нет" '[ "$rc" = 1 ] && [ ! -e "$TMP/copy2.json" ]'
call settings_load "$TMP/нет.json"
check "загрузка без файла: отказ, на ТВ ничего не шлётся" '[ "$rc" = 1 ] && ! grep -q "do import" "$ST/calls"'
GT_ROOT="$SAVED_ROOT"

echo
echo "Итог: прошло $PASS, провалов $FAILN"
exit "$FAILN"
