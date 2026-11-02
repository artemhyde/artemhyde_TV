#!/usr/bin/env bash
# env.sh: находит инструменты сборки (JDK 17, Android build-tools 34, android.jar 34, Python 3) и задаёт общие переменные для скриптов build/. Сам ничего не собирает.
# Подключение: source build/env.sh, затем вызвать require_build_env (проверка и экспорт переменных).
# Переопределить поиск можно переменными: JDK17_HOME, JDK21_HOME, ANDROID_HOME (или ANDROID_SDK_ROOT), BT (папка build-tools с aapt2), ANDROID_JAR, GLASSTV_TOOLS, PY.
# Где ищет: переменные выше, ~/.glasstv/tools (сюда ставит мастер), стандартные места JDK и Android SDK на Windows, macOS и Linux.
# Точные имена папок в ~/.glasstv/tools: platform-tools/, jdk-17*/, jdk-21*/, android-sdk/ (корень SDK: sdkmanager --sdk_root=...), python/ (Python без админа).
# Windows (Git Bash): у программ .exe, а пути для java.exe переводятся функцией P в вид C:/... (cygpath -m), иначе java.exe не находит файлы.

GT_BUILD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$GT_BUILD_DIR/.." && pwd)"
GT_HOME="${GLASSTV_TOOLS:-$HOME/.glasstv/tools}"
OUT="$ROOT/out"
KEYS="$ROOT/keys"
CONFIG_DIR="$ROOT/config"
# Приложения, которым нужен сгенерированный Config.java (настройки города, курсов, стихов, профилей, темы)
CONFIG_APPS="home volna"

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) GT_WIN=1; EXE=.exe ;;
    *) GT_WIN=0; EXE= ;;
esac

die() { echo "Ошибка: $*" >&2; exit 1; }
note() { echo "$*"; }

# Путь в виде, который понимают программы Windows; на macOS и Linux без изменений
P() {
    if [ "$GT_WIN" = 1 ] && command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# Версия java по JAVA_HOME: число (17, 21) или пусто
java_major() {
    local j="$1/bin/java$EXE"
    [ -x "$j" ] || return 1
    "$j" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1
}

# Папка JDK нужной версии: find_jdk 17 [явный путь]. Печатает JAVA_HOME или ничего.
find_jdk() {
    local want="$1" c h m
    for c in "${2:-}" "$(eval echo "\${JDK${want}_HOME:-}")" \
             "$GT_HOME"/jdk-"$want"* "$GT_HOME"/jdk"$want"* "$GT_HOME"/jdk/jdk-"$want"* "$GT_HOME"/*/jdk-"$want"* \
             "${JAVA_HOME:-}" \
             /usr/lib/jvm/*"$want"* /usr/lib/jvm/*/"$want" \
             "/c/Program Files/Eclipse Adoptium"/jdk-"$want"* "/c/Program Files/Java"/jdk-"$want"* \
             "/c/Program Files/Microsoft"/jdk-"$want"* "/c/Program Files/Zulu"/zulu-"$want"* \
             /Library/Java/JavaVirtualMachines/*/Contents/Home; do
        [ -n "$c" ] || continue
        for h in "$c" "$c/Contents/Home"; do
            [ -d "$h" ] || continue
            m="$(java_major "$h" 2>/dev/null || true)"
            if [ "$m" = "$want" ]; then printf '%s' "$h"; return 0; fi
        done
    done
    if [ -x /usr/libexec/java_home ]; then
        h="$(/usr/libexec/java_home -v "$want" 2>/dev/null || true)"
        if [ -n "$h" ] && [ "$(java_major "$h" 2>/dev/null || true)" = "$want" ]; then printf '%s' "$h"; return 0; fi
    fi
    c="$(command -v java 2>/dev/null || true)"
    if [ -n "$c" ]; then
        h="$(cd "$(dirname "$c")/.." 2>/dev/null && pwd || true)"
        if [ -n "$h" ] && [ "$(java_major "$h" 2>/dev/null || true)" = "$want" ]; then printf '%s' "$h"; return 0; fi
    fi
    return 1
}

# Корни Android SDK, которые стоит проверить
sdk_roots() {
    local c
    for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$GT_HOME/android-sdk" "$GT_HOME/sdk" "$GT_HOME/android" \
             "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "${LOCALAPPDATA:-/nonexistent}/Android/Sdk"; do
        [ -n "$c" ] && [ -d "$c" ] && printf '%s\n' "$c"
    done
}

# Первая версия из списка, которая не меньше минимальной (сортировка по версиям); предпочтение точному совпадению
# pick_version <минимум> <имена построчно с stdin> : печатает выбранное имя
pick_version() {
    local min="$1" line major exact="" best=""
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        major="${line%%[!0-9]*}"
        [ -n "$major" ] || continue
        if [ "$major" -eq "$min" ]; then exact="$line"; break; fi
        if [ "$major" -gt "$min" ] && [ -z "$best" ]; then best="$line"; fi
    done < <(sort -V)
    if [ -n "$exact" ]; then printf '%s' "$exact"; elif [ -n "$best" ]; then printf '%s' "$best"; fi
}

# Найти build-tools (папка с aapt2): BT из окружения или SDK, версия 34 или ближайшая выше
find_build_tools() {
    local r v
    if [ -n "${BT:-}" ] && [ -x "$BT/aapt2$EXE" ]; then printf '%s' "$BT"; return 0; fi
    while IFS= read -r r; do
        [ -d "$r/build-tools" ] || continue
        v="$(ls "$r/build-tools" 2>/dev/null | pick_version 34)"
        if [ -n "$v" ] && [ -x "$r/build-tools/$v/aapt2$EXE" ]; then printf '%s' "$r/build-tools/$v"; return 0; fi
    done < <(sdk_roots)
    return 1
}

# Найти android.jar платформы 34 (или ближайшей выше)
find_android_jar() {
    local r v
    if [ -n "${ANDROID_JAR:-}" ] && [ -f "$ANDROID_JAR" ]; then printf '%s' "$ANDROID_JAR"; return 0; fi
    while IFS= read -r r; do
        [ -d "$r/platforms" ] || continue
        v="$(ls "$r/platforms" 2>/dev/null | sed 's/^android-//' | pick_version 34)"
        if [ -n "$v" ] && [ -f "$r/platforms/android-$v/android.jar" ]; then printf '%s' "$r/platforms/android-$v/android.jar"; return 0; fi
    done < <(sdk_roots)
    return 1
}

# Python 3: на Windows python3 бывает заглушкой Microsoft Store, поэтому каждый вариант пробуем запуском
find_python() {
    local c
    if [ -n "${PY:-}" ]; then printf '%s' "$PY"; return 0; fi
    for c in python3 python "py -3"; do
        if $c -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then printf '%s' "$c"; return 0; fi
    done
    # Python, который мог положить мастер без прав администратора: $GT_HOME/python
    for c in "$GT_HOME/python/python.exe" "$GT_HOME/python/bin/python3" "$GT_HOME/python/python3"; do
        if [ -x "$c" ] && "$c" -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then printf '%s' "$c"; return 0; fi
    done
    return 1
}

# Проверить окружение и экспортировать переменные. Вызывать в начале каждого скрипта сборки.
require_build_env() {
    local bad=0
    JAVA_HOME="$(find_jdk 17 || true)"
    if [ -z "$JAVA_HOME" ]; then echo "Не найден JDK 17 (нужна именно 17: на JDK 21 d8 падает). Мастер ставит его в $GT_HOME." >&2; bad=1; fi
    BT="$(find_build_tools || true)"
    if [ -z "$BT" ]; then echo "Не найдены Android build-tools 34 (или новее). Мастер ставит их через sdkmanager." >&2; bad=1; fi
    JAR="$(find_android_jar || true)"
    if [ -z "$JAR" ]; then echo "Не найден android.jar платформы 34 (или новее). Мастер ставит его через sdkmanager." >&2; bad=1; fi
    PY="$(find_python || true)"
    if [ -z "$PY" ]; then echo "Не найден Python 3 (нужен для сборки apk и разбора настроек). Мастер предложит его поставить." >&2; bad=1; fi
    [ "$bad" = 0 ] || exit 1
    J="$JAVA_HOME/bin"
    JAVA="$J/java$EXE"; JAVAC="$J/javac$EXE"; JARTOOL="$J/jar$EXE"; KEYTOOL="$J/keytool$EXE"
    AAPT2="$BT/aapt2$EXE"; ZIPALIGN="$BT/zipalign$EXE"
    D8JAR="$BT/lib/d8.jar"; SIGNERJAR="$BT/lib/apksigner.jar"
    [ -f "$D8JAR" ] || die "Нет $D8JAR: в build-tools нет d8.jar"
    [ -f "$SIGNERJAR" ] || die "Нет $SIGNERJAR: в build-tools нет apksigner.jar"
    [ -x "$ZIPALIGN" ] || die "Нет zipalign в $BT"
    export JAVA_HOME BT JAR PY
    mkdir -p "$OUT"
}

# Положить classes.dex внутрь apk (aapt2 его не кладёт): inject_dex base.apk classes.dex
inject_dex() {
    $PY -I -X utf8 -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a'); z.write(sys.argv[2],'classes.dex'); z.close()" "$(P "$1")" "$(P "$2")"
}

# Настройки человека: локальные, иначе образец
settings_file() {
    if [ -f "$CONFIG_DIR/settings.local.json" ]; then printf '%s' "$CONFIG_DIR/settings.local.json"
    elif [ -f "$CONFIG_DIR/settings.example.json" ]; then printf '%s' "$CONFIG_DIR/settings.example.json"
    fi
}
