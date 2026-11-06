#!/usr/bin/env bash
# make-restore.sh: собрать скрипт отката restore.sh из журнала backup/<метка>/ledger.tsv (обратные команды, последняя запись первой).
# Запуск: bash tools/make-restore.sh backup/<метка> [--dry-run]
#   без параметров: пишет backup/<метка>/restore.sh
#   --dry-run:      только печатает команды отката в нужном порядке, ничего не пишет и не выполняет
# Сам restore.sh тоже понимает --dry-run (печать без выполнения) и --yes (без вопроса); адрес ТВ берёт из TV или --tv.
# Код выхода restore.sh: 0 все команды прошли, 1 часть не прошла, 3 нет связи с ТВ (до начала или посреди: остаток не выполнен).
# Формат журнала: время, что сделано, команда, обратная команда (через табуляцию, команды для оболочки ТВ).
# Записи с пустой обратной командой или «-» в откат не входят: они выводятся списком «делать руками».
# Порядок обратный (последнее изменение отменяется первым) с одним исключением: возврат владельца главного экрана (cmd role add-role-holder)
# идёт после последнего pm enable. Иначе на TCL роль вернётся прежнему главному экрану, пока тот ещё отключён, и команда не пройдёт.
# Перед удалением приложения с правами администратора (dpm set-active-admin в журнале) нужна обратная команда со снятием прав; если её нет, скрипт предупредит.
# После команд журнала restore.sh сверяет со снимком «до» несколько настроек, которые приложения меняют сами, мимо журнала
# (частный DNS, спецвозможности, заставка и тайм-аут экрана), и возвращает те, что отличаются.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

dir=""; dry=0
for a in "$@"; do
    case "$a" in
        --dry-run) dry=1 ;;
        -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) gt_die "Неизвестный параметр: $a" ;;
        *) [ -z "$dir" ] && dir="$a" || gt_die "Лишний аргумент: $a" ;;
    esac
done
[ -n "$dir" ] || gt_die "Нужна папка снимка: bash tools/make-restore.sh backup/<метка> [--dry-run]"
ledger="$dir/ledger.tsv"
[ -f "$ledger" ] || gt_die "Нет журнала: $ledger"

# Разбор журнала: строки без # и без пустых; поля через табуляцию
rows="$(awk -F'\t' 'BEGIN{OFS="\t"} /^#/ || NF==0 || $0 ~ /^[ \t]*$/ {next} {print $1, $2, $3, $4}' "$ledger" | tr -d '\r')"
if [ -z "$rows" ]; then
    gt_say "Журнал пуст: откатывать пока нечего (ТВ ещё не менялся). Скрипт собирается из записей, которые мастер делает при каждом изменении на ТВ. Файл restore.sh не создан."
    exit 0
fi

# Первая колонка нужна только для порядка; обратный порядок без tac (его нет на macOS)
reverse() { awk '{a[NR]=$0} END{for(i=NR;i>=1;i--) print a[i]}'; }
total="$(printf '%s\n' "$rows" | wc -l | tr -d ' ')"
# Возврат роли главного экрана после последнего pm enable (см. шапку)
roles_last() {
    awk -F'\t' '
        { line[NR] = $0; if ($4 ~ /^pm enable /) last = NR }
        END {
            for (i = 1; i <= NR; i++) {
                split(line[i], f, "\t")
                if (f[4] ~ /cmd role add-role-holder/ && last > i) continue
                print line[i]
                if (i == last) for (j = 1; j < i; j++) { split(line[j], g, "\t"); if (g[4] ~ /cmd role add-role-holder/) print line[j] }
            }
        }'
}
undo="$(printf '%s\n' "$rows" | awk -F'\t' '$4 != "" && $4 != "-"' | reverse | roles_last)"
manual="$(printf '%s\n' "$rows" | awk -F'\t' '$4 == "" || $4 == "-"')"
count="$(printf '%s' "$undo" | grep -c . || true)"

# Предупреждения: удаление приложения с правами администратора без снятия прав вешает систему (около 70 секунд), у Key Mapper права снимаются только руками
admins="$(printf '%s\n' "$rows" | awk -F'\t' '{ if (match($3, /set-active-admin [A-Za-z0-9_.]+/)) print substr($3, RSTART + 17, RLENGTH - 17) }' | tr '\n' ' ')"
warns="$(printf '%s\n' "$undo" | awk -F'\t' -v admins="$admins" '
    BEGIN { n = split(admins, a, " "); for (i = 1; i <= n; i++) if (a[i] != "") adm[a[i]] = 1 }
    {
        if ($4 ~ /unadmin/) for (p in adm) if (index($4, p) > 0) done[p] = 1
        if (match($4, /pm uninstall( --user [0-9]+)? [A-Za-z0-9_.]+/)) {
            c = substr($4, RSTART, RLENGTH); k = split(c, w, " "); pkg = w[k]
            if ((pkg in adm) && !(pkg in done)) print "ВНИМАНИЕ: перед удалением " pkg " в откате нет снятия прав администратора (am start ... --ez unadmin true и пауза): система зависнет примерно на 70 секунд."
            if (pkg == "io.github.sds100.keymapper") print "ВНИМАНИЕ: у Key Mapper права администратора снимаются только руками, а удаление при активных правах вешает систему. Уберите его удаление из журнала и снимите права руками (docs/rollback.md)."
        }
    }')"

# Настройки, которые приложения HYDE меняют сами, мимо журнала (проверка на ТВ другой марки 06.11): «Сторож» при поломке DNS
# ставит частный DNS, вписывает службу Key Mapper в спецвозможности, если она слетела, а пока «Дом» главный экран, держит
# заставку Android выключенной и тайм-аут экрана на максимуме. restore.sh сверяет их со снимком «до» (settings-*.txt этой же
# папки) после всех команд журнала, когда приложений уже нет и вернуть своё обратно некому, и возвращает только отличающиеся.
# Сюда же язык штатной клавиатуры и история переключения клавиатур: Android меняет их сам, когда клавиатуру меняют туда
# и обратно (ime set из журнала), и в истории остаётся имя удалённой клавиатуры HYDE.
# Отладку по сети (adb_wifi_enabled) не трогаем: через неё идёт сам откат
RUNTIME_KEYS='secure|selected_input_method_subtype|язык штатной клавиатуры
secure|input_methods_subtype_history|история переключения клавиатур
global|private_dns_mode|частный DNS
global|private_dns_specifier|адрес частного DNS
secure|enabled_accessibility_services|службы спецвозможностей
secure|accessibility_enabled|спецвозможности
secure|screensaver_enabled|заставка Android
system|screen_off_timeout|через сколько гаснет экран'
sq() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
runtime=""; rt_human=""; rt_missing=""
while IFS='|' read -r ns key what; do
    [ -n "$ns" ] || continue
    f="$dir/settings-$ns.txt"
    if [ ! -f "$f" ]; then
        case " $rt_missing " in *" settings-$ns.txt "*) ;; *) rt_missing="$rt_missing settings-$ns.txt" ;; esac
        continue
    fi
    line="$(grep -m1 "^$key=" "$f" | tr -d '\r')"
    if [ -n "$line" ] && [ "${line#*=}" != null ]; then
        had=1; val="${line#*=}"; rt_human="$rt_human  $what ($ns $key): «$val»
"
    else
        had=0; val=""; rt_human="$rt_human  $what ($ns $key): до установки не было, убрать
"
    fi
    runtime="${runtime}back $(sq "$what") $ns $key $(sq "$val") $had
"
done <<EOF
$RUNTIME_KEYS
EOF
rt_warn=""
[ -z "$rt_missing" ] || rt_warn="Внимание: в папке снимка нет$rt_missing. Настройки, которые приложения меняют сами (частный DNS, спецвозможности, заставка), откат не вернёт."

if [ "$dry" = 1 ]; then
    gt_say "Сухой прогон: откат из $ledger (записей в журнале: $total, команд отката: $count). Ничего не выполняется."
    printf '%s\n' "$undo" | awk -F'\t' '$0 != "" {printf "  %s   (отмена: %s)\n", $4, $2}'
    if [ -n "$runtime" ]; then
        gt_say "Потом сверка со снимком «до»: эти настройки вернутся, если приложения их меняли:"
        printf '%s' "$rt_human"
    fi
    if [ -n "$manual" ]; then
        gt_say "Откатом не покрывается, делать руками:"
        printf '%s\n' "$manual" | awk -F'\t' '{printf "  %s\n", $2}'
    fi
    [ -z "$warns" ] || printf '%s\n' "$warns"
    [ -z "$rt_warn" ] || gt_say "$rt_warn"
    exit 0
fi

out="$dir/restore.sh"
{
    cat <<'HEAD'
#!/usr/bin/env bash
# Откат изменений на ТВ. Собран tools/make-restore.sh из ledger.tsv: команды идут в обратном порядке (последнее изменение отменяется первым).
# Запуск: bash restore.sh [--dry-run] [--yes] [--tv адрес:порт]
#   --dry-run  напечатать команды и ничего не выполнять
#   --yes      не спрашивать подтверждение
set -u
DRY=0; YES=0
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY=1 ;;
        --yes) YES=1 ;;
        --tv) TV="${2:?после --tv нужен адрес}"; shift ;;
        *) echo "Неизвестный параметр: $1"; exit 1 ;;
    esac
    shift
done
if [ -z "${ADB:-}" ]; then
    for c in "$(command -v adb 2>/dev/null)" "$(command -v adb.exe 2>/dev/null)" "$HOME/.glasstv/tools/platform-tools/adb" "$HOME/.glasstv/tools/platform-tools/adb.exe"; do
        if [ -n "$c" ] && [ -x "$c" ]; then ADB="$c"; break; fi
    done
fi
[ "$DRY" = 1 ] || [ -n "${ADB:-}" ] || { echo "adb не найден: задайте переменную ADB"; exit 1; }
tvsh() { if [ -n "${TV:-}" ]; then "$ADB" -s "$TV" shell "$1"; else "$ADB" shell "$1"; fi; }
tvstate() { if [ -n "${TV:-}" ]; then "$ADB" -s "$TV" get-state 2>&1; else "$ADB" get-state 2>&1; fi | tr -d '\r'; }
# FAIL: команда не прошла при живой связи. LOST: связь пропала, дальше ничего не выполняется, оставшиеся команды пересчитываются
FAIL=0; LOST=0; LEFT=0
run() {
    echo "  $1"
    echo "    > $2"
    [ "$DRY" = 1 ] && return 0
    if [ "$LOST" = 1 ]; then echo "    не выполнено: нет связи с ТВ"; LEFT=$((LEFT + 1)); return 0; fi
    if ! tvsh "$2"; then
        if [ "$(tvstate)" != device ]; then
            echo "    связь с ТВ пропала: дальше ничего не выполняю"; LOST=1; LEFT=$((LEFT + 1))
        else
            echo "    не вышло, иду дальше"; FAIL=$((FAIL + 1))
        fi
    fi
}
# Настройка, которую приложения меняют сами: вернуть как в снимке «до», только если сейчас иначе.
# back 'что' пространство ключ 'значение до' есть_в_снимке (1; 0 значит «до установки не было», тогда настройка удаляется)
back() {
    local cur q
    if [ "$DRY" = 1 ]; then
        if [ "$5" = 1 ]; then echo "  $1: вернуть «$4», если сейчас иначе"; else echo "  $1: убрать, если появилась (до установки её не было)"; fi
        return 0
    fi
    [ "$LOST" = 1 ] && return 0
    cur="$(tvsh "settings get $2 $3" 2>/dev/null | tr -d '\r')"
    if [ "$5" = 1 ]; then
        [ "$cur" = "$4" ] && return 0
        q="$(printf '%s' "$4" | sed "s/'/'\\\\''/g")"
        run "$1: сейчас «$cur», вернуть «$4»" "settings put $2 $3 '$q'"
    else
        [ "$cur" = null ] && return 0
        run "$1: сейчас «$cur», до установки не было, убрать" "settings delete $2 $3"
    fi
}
HEAD
    printf 'echo "Откат: %s команд, начиная с самой поздней записи."\n' "$count"
    [ -z "$runtime" ] || echo 'echo "Потом сверка со снимком «до»: вернутся настройки, которые приложения меняли сами."'
    if [ -n "$warns" ]; then printf '%s\n' "$warns" | while IFS= read -r w; do printf "echo '%s'\n" "$w"; done; fi
    cat <<'ASK'
if [ "$DRY" = 0 ] && [ "$(tvstate)" != device ]; then
    echo "ТВ не на связи (${TV:-адрес не задан}): откат не начат, на ТВ ничего не менялось. Подключитесь заново (tools/find-tv.sh) и запустите ещё раз."
    exit 3
fi
if [ "$DRY" = 0 ] && [ "$YES" = 0 ]; then
    printf 'Выполнить откат на ТВ? [y/N] '
    read -r ans
    case "$ans" in y|Y|д|Д) ;; *) echo "Отменено."; exit 0 ;; esac
fi
ASK
    # Каждая команда: run 'что отменяем' 'обратная команда' (одинарные кавычки внутри экранируются)
    printf '%s\n' "$undo" | awk -F'\t' -v q="'" '
        function esc(s) { gsub(q, q "\\" q q, s); return s }
        $0 != "" { printf "run %s%s%s %s%s%s\n", q, esc("отмена: " $2), q, q, esc($4), q }'
    if [ -n "$runtime" ]; then
        echo 'echo ""'
        echo 'echo "Сверка со снимком «до»: настройки, которые приложения меняли сами, мимо журнала"'
        printf '%s' "$runtime"
    fi
    if [ -n "$manual" ]; then
        echo 'echo ""'
        echo 'echo "Откатом не покрывается, делайте руками:"'
        printf '%s\n' "$manual" | awk -F'\t' -v q="'" '
            function esc(s) { gsub(q, q "\\" q q, s); return s }
            { printf "echo %s  %s%s\n", q, esc($2), q }'
    fi
    # Итог честный: «Готово» только если все команды прошли. Код выхода 0 готово, 1 часть команд не прошла, 3 связь пропала
    cat <<'TAIL'
echo ""
if [ "$DRY" = 1 ]; then echo "Сухой прогон: ничего не выполнялось."; exit 0; fi
if [ "$LOST" = 1 ]; then
    echo "ОТКАТ НЕ ЗАКОНЧЕН: связь с ТВ пропала, не выполнено команд: $LEFT. Подключитесь заново и запустите restore.sh ещё раз: повтор уже выполненных команд безвреден."
    exit 3
fi
if [ "$FAIL" -gt 0 ]; then
    echo "ОТКАТ ВЫПОЛНЕН НЕ ПОЛНОСТЬЮ: не прошло команд: $FAIL (они отмечены выше «не вышло»). Проверьте пульт, главный экран, HDMI и звук."
    exit 1
fi
echo "Готово: все команды отката прошли."
TAIL
} > "$out"
chmod +x "$out" 2>/dev/null || true
bash -n "$out" || gt_die "Собранный restore.sh не прошёл проверку синтаксиса: $out"
gt_say "Собрано: $out (команд отката: $count, руками: $(printf '%s' "$manual" | grep -c . || true))"
[ -z "$warns" ] || printf '%s\n' "$warns"
[ -z "$rt_warn" ] || gt_say "$rt_warn"
gt_say "Показать без выполнения: bash \"$out\" --dry-run"
