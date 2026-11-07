# Загрузка списка приложений HYDE в ByeByeDPI и включение, без нажатий человека (выполняется на ТВ: adb shell "<текст файла>").
# Проверено на ByeByeDPI 1.8.2. Файл списка заранее кладёт установщик: /sdcard/0 HYDE TV/ByeByeDPI-HYDE.json
# (формат экспорта ByeByeDPI: app, apps, settings; см. docs/byedpi.md). Папка с цифрой в начале стоит первой
# в его выборе файлов. Меню «⋮» в 1.8.2 пультом не достать, поэтому оно открывается кнопкой MENU, а «Импорт»
# берётся вторым пунктом меню по номеру, не по тексту (язык не важен). Каждое нажатие только после проверки экрана;
# на время импорта ByeByeDPI получает доступ к файлам и сразу его теряет.
# Туннель включается только после удачного импорта: без списка через него пошли бы все приложения.
# Код 0: список загружен, ByeByeDPI включён, папка со списком убрана. Не 0: туннель не включался, ничего не сломано.
# ByeByeDPI стоял у человека раньше (файл приложения старше часа): его настройки не трогаем, импорт стёр бы подобранное им.
# Строка do шага в installer/recipe.json это этот файл без строк-комментариев.
P=io.github.romanvht.byedpi; D="/sdcard/0 HYDE TV"; X=/data/local/tmp/hyde-ui.xml
f=$(pm path $P | sed -n 's/^package://p' | head -1)
[ -n "$f" ] && [ $(( $(date +%s) - $(stat -c %Y "$f") )) -gt 3600 ] && { rm -rf "$D"; echo "ByeByeDPI стоял до HYDE: его настройки не трогаю"; exit 0; }
top() { dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity; }
w() { i=0; while [ $i -lt 40 ]; do top | grep -q "$1" && return 0; sleep 0.5; i=$((i+1)); done; echo "нет экрана $1"; return 1; }
ui() { uiautomator dump $X >/dev/null 2>&1; tr '>' '\n' < $X; }
bx() { sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p'; }
tap4() { input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); }
t() { n=0; while [ $n -lt 30 ]; do b=$(ui | grep -m1 "$1" | bx); if [ -n "$b" ]; then sleep 1; tap4 $b; sleep 1.5; [ -z "$(ui | grep -m1 "$1")" ] && return 0; fi; sleep 0.5; n=$((n+1)); done; echo "не вышло нажать: $1"; return 1; }
menu() { n=0; while [ $n -lt 8 ]; do [ "$(ui | grep -c "$P:id/title")" -ge 3 ] && return 0; top | grep -q "$P/.activities.SettingsActivity" || return 1; input keyevent MENU; sleep 1.5; n=$((n+1)); done; echo "меню не открылось"; return 1; }
imp() { b=$(ui | grep "$P:id/title" | sed -n 2p | bx); [ -n "$b" ] && tap4 $b; }
ready() { n=0; while [ $n -lt 20 ]; do [ "$(ui | grep -c 'android:id/title')" -ge 3 ] && return 0; sleep 0.5; n=$((n+1)); done; echo "настройки не открылись"; return 1; }
vpn() { n=0; while [ $n -lt 20 ]; do dumpsys connectivity 2>/dev/null | grep -q 'VPN CONNECTED' && return 0; sleep 0.5; n=$((n+1)); done; echo "туннель не поднялся"; return 1; }
appops set $P MANAGE_EXTERNAL_STORAGE allow
am start -n $P/.activities.SettingsActivity >/dev/null && w "$P/.activities.SettingsActivity" && ready && menu && imp && w "$P/.activities.FileActivity" && { ui | grep -q 'android.widget.EditText' && { echo "открылся не импорт"; input keyevent BACK; false; } || true; } && t 'text="0 HYDE TV"' && t 'text="ByeByeDPI-HYDE.json"' && t 'resource-id="android:id/button1"'
r=$?
appops set $P MANAGE_EXTERNAL_STORAGE default
[ $r -eq 0 ] && rm -rf "$D" && am start -n $P/.activities.ToggleActivity --ez only_start true >/dev/null && vpn; r=$?
rm -f $X; input keyevent BACK
[ $r -eq 0 ] && echo "список загружен, ByeByeDPI включён"
exit $r
