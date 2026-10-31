#!/usr/bin/env bash
# build-app.sh: собрать одно приложение без Gradle (aapt2, javac, d8, zipalign, apksigner) и подписать вашим ключом: out/<имя>.apk.
# Запуск: bash build/build-app.sh <имя или папка>     например: bash build/build-app.sh home     или     bash build/build-app.sh apps/guard
# Для «плиток» (apps/tiles, есть tiles.txt и AndroidManifest.template.xml) собирается по одному apk на строку tiles.txt: out/tiles/<имя>.apk.
# Что делает: настройки в Config.java (для home и volna, gen-config.sh), шрифты из shared/fonts в assets (список: apps/<имя>/fonts.txt,
# а если его нет, шрифты, названные в исходниках как fonts/Имя.ttf), ресурсы, javac --release 8, d8, classes.dex внутрь apk, zipalign, подпись.
# Рабочие файлы: out/work/<имя>. Нужны: JDK 17, build-tools 34, android.jar 34, Python 3 (build/env.sh их ищет), ключ (build/new-key.sh).
set -euo pipefail
source "$(dirname "$0")/env.sh"

arg="${1:-}"
[ -n "$arg" ] || die "Какое приложение собрать? Например: bash build/build-app.sh home"
if [ -d "$arg" ]; then appdir="$(cd "$arg" && pwd)"
elif [ -d "$ROOT/apps/$arg" ]; then appdir="$ROOT/apps/$arg"
else die "Нет папки приложения: $arg (ожидается apps/<имя>)"; fi
name="$(basename "$appdir")"

require_build_env
[ -f "$KEYS/tv.jks" ] || die "Нет ключа подписи. Сначала: bash build/new-key.sh"
work="$OUT/work/$name"
rm -rf "$work"
mkdir -p "$work/gen" "$work/classes" "$work/dex" "$OUT"

# Список java-файлов в аргументный файл для javac (кавычки: в пути могут быть пробелы)
make_sources() { # make_sources <куда> <папка...>
    local list="$1" d; shift
    : > "$list"
    for d in "$@"; do
        [ -d "$d" ] || continue
        find "$d" -name '*.java' | sort | while IFS= read -r f; do printf '"%s"\n' "$(P "$f")"; done >> "$list"
    done
    [ -s "$list" ] || die "В $* нет ни одного .java"
}

# javac, затем d8 по jar с классами (так не упираемся в длину командной строки Windows): результат $work/dex/classes.dex
compile_dex() { # compile_dex <файл со списком исходников>
    "$JAVAC" -nowarn -Xlint:-options -encoding UTF-8 --release 8 -classpath "$(P "$JAR")" \
        -d "$(P "$work/classes")" "@$(P "$1")"
    "$JARTOOL" --create --file "$(P "$work/classes.jar")" -C "$(P "$work/classes")" .
    "$JAVA" -cp "$(P "$D8JAR")" com.android.tools.r8.D8 --release --min-api 31 --lib "$(P "$JAR")" \
        --output "$(P "$work/dex")" "$(P "$work/classes.jar")"
    [ -f "$work/dex/classes.dex" ] || die "d8 не создал classes.dex"
}

# ----- Плитки: один код на все ярлыки, на каждый ярлык свой манифест и картинка -----
if [ -f "$appdir/tiles.txt" ] && [ -f "$appdir/AndroidManifest.template.xml" ]; then
    make_sources "$work/sources.txt" "$appdir/src"
    compile_dex "$work/sources.txt"
    mkdir -p "$OUT/tiles"
    n=0
    while IFS='|' read -r -u 3 tname pkg target label lampa component; do
        d="$work/$tname"
        mkdir -p "$d/res/drawable-xhdpi"
        [ -f "$appdir/banners/$tname.png" ] || die "Нет картинки apps/$name/banners/$tname.png"
        cp "$appdir/banners/$tname.png" "$d/res/drawable-xhdpi/banner.png"
        # Подстановка питоном: кавычки JSON для XML экранируются
        $PY -I -X utf8 - "$(P "$appdir/AndroidManifest.template.xml")" "$(P "$d/AndroidManifest.xml")" "$pkg" "$target" "$label" "$lampa" "$component" <<'PYT'
import html
import sys

t = open(sys.argv[1], encoding='utf-8').read()
for k, v in zip(['@PKG@', '@TARGET@', '@LABEL@', '@LAMPA@', '@COMPONENT@'], sys.argv[3:8]):
    t = t.replace(k, html.escape(v))
open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write(t)
PYT
        "$AAPT2" compile --dir "$(P "$d/res")" -o "$(P "$d/res.zip")"
        "$AAPT2" link -I "$(P "$JAR")" --manifest "$(P "$d/AndroidManifest.xml")" \
            --min-sdk-version 31 --target-sdk-version 34 -o "$(P "$d/base.apk")" "$(P "$d/res.zip")"
        inject_dex "$d/base.apk" "$work/dex/classes.dex"
        bash "$GT_BUILD_DIR/sign.sh" "$d/base.apk" "$OUT/tiles/$tname.apk"
        n=$((n + 1))
    done 3< <(grep -v '^[[:space:]]*#' "$appdir/tiles.txt" | grep -v '^[[:space:]]*$' | tr -d '\r')
    [ "$n" -gt 0 ] || die "В tiles.txt нет ни одной строки ярлыка"
    note "Готово: $n ярлыков в out/tiles/"
    exit 0
fi

# ----- Обычное приложение -----
[ -f "$appdir/AndroidManifest.xml" ] || die "В $appdir нет AndroidManifest.xml"

# Настройки в Config.java (если приложению они нужны: оно в CONFIG_APPS или у него есть шаблон Config.java.in)
gen_dirs=()
need_cfg=0
case " $CONFIG_APPS " in *" $name "*) need_cfg=1 ;; esac
if [ -n "$(find "$appdir" -name 'Config.java.in' 2>/dev/null | head -n 1)" ]; then need_cfg=1; fi
if [ "$need_cfg" = 1 ]; then
    if [ -n "$(find "$appdir/src" -name 'Config.java' 2>/dev/null | head -n 1)" ]; then
        note "В исходниках $name уже есть свой Config.java: gen-config.sh не используется."
    else
        bash "$GT_BUILD_DIR/gen-config.sh" "$name"
        gen_dirs+=("$OUT/gen/$name/app")
    fi
fi

# Ресурсы: копия, чтобы подменить файл стихов
if [ -d "$appdir/res" ]; then
    cp -R "$appdir/res" "$work/res"
    if [ -f "$OUT/gen/$name/res-raw/verses.json" ]; then
        mkdir -p "$work/res/raw"
        cp "$OUT/gen/$name/res-raw/verses.json" "$work/res/raw/verses.json"
        note "Стихи: подставлен config/verses.local.json"
    fi
fi

# Ассеты и шрифты
if [ -d "$appdir/assets" ]; then mkdir -p "$work/assets"; cp -R "$appdir/assets/." "$work/assets/"; fi
fonts=""
if [ -f "$appdir/fonts.txt" ]; then
    fonts="$(grep -v '^[[:space:]]*#' "$appdir/fonts.txt" | tr -d '\r' | sed 's/[[:space:]]*$//' | grep -v '^$' || true)"
else
    fonts="$(grep -rhoE 'fonts/[A-Za-z0-9_.-]+\.(ttf|otf)' "$appdir/src" 2>/dev/null | sed 's#^fonts/##' | sort -u || true)"
fi
if [ -n "$fonts" ]; then
    mkdir -p "$work/assets/fonts"
    while IFS= read -r f; do
        if [ -f "$ROOT/shared/fonts/$f" ]; then cp "$ROOT/shared/fonts/$f" "$work/assets/fonts/$f"
        elif [ -f "$work/assets/fonts/$f" ]; then :
        else die "Шрифт $f нужен приложению $name, но его нет в shared/fonts"; fi
    done <<< "$fonts"
    if [ -f "$ROOT/shared/fonts/OFL.txt" ]; then cp "$ROOT/shared/fonts/OFL.txt" "$work/assets/fonts/OFL.txt"; fi
fi

# Ресурсы и манифест
link_args=(-I "$(P "$JAR")" --manifest "$(P "$appdir/AndroidManifest.xml")" --java "$(P "$work/gen")"
           --min-sdk-version 31 --target-sdk-version 34)
if [ -d "$work/assets" ]; then link_args+=(-A "$(P "$work/assets")"); fi
link_args+=(-o "$(P "$work/base.apk")")
if [ -d "$work/res" ]; then
    "$AAPT2" compile --dir "$(P "$work/res")" -o "$(P "$work/res.zip")"
    link_args+=("$(P "$work/res.zip")")
fi
"$AAPT2" link "${link_args[@]}"

# Код
make_sources "$work/sources.txt" "$appdir/src" "$work/gen" ${gen_dirs[@]+"${gen_dirs[@]}"}
compile_dex "$work/sources.txt"
inject_dex "$work/base.apk" "$work/dex/classes.dex"

bash "$GT_BUILD_DIR/sign.sh" "$work/base.apk" "$OUT/$name.apk"
