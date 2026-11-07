#!/usr/bin/env bash
# release.sh: собрать общий выпуск HYDE TV. Все приложения из apps/ с одним номером версии, подписаны ключом выпуска,
# рядом опись manifest.json (пакет, версия, minSdk, ABI, SHA-256 файла, сертификат, источник, лицензия, зависимости,
# способ возврата) и SHA256SUMS. Результат: out/release/<версия>/.
# Запуск: bash build/release.sh 1.0.0 [--check] [--browser] [--aura]
#   номер X.Y.Z (Y и Z от 0 до 99): versionName у всех приложений X.Y.Z, versionCode X*10000 + Y*100 + Z
#   --check: собрать второй раз в другую папку и сравнить: файлы должны совпасть байт в байт (воспроизводимость)
#   --browser: добавить HYDE Browser (tvbro/build-tvbro.sh: Gradle, JDK 21, SDK 36, долго; исходники TV Bro из TVBRO_REPO)
#   --aura: добавить плеер «Волны» (aura/build-aura.sh, 32-битный ARM; исходники Aura Music из AURA_REPO) и рядом архив его исходников
#           hyde-aura-source-<версия>.tar.gz: GPL-3.0 требует отдавать исходники вместе с файлом
# Ключ выпуска лежит вне репозитория: HYDE_RELEASE_DIR (по умолчанию ~/.hyde-release с release.jks и release.pass,
# создаёт build/release-key.sh). Ключ личных сборок keys/tv.jks выпуск не трогает.
# Настройки сборки нейтральные (config/release.json): город, цвет стекла, курсы, стихи и имена человек выбирает на ТВ,
# а мастер присылает их файлом переноса (settings_load). Свои стихи (verses custom) бывают только в личной сборке.
# Собирается из чистой выгрузки коммита (git archive), а не из рабочей папки: в описи тот коммит, из которого всё собрано,
# и окончания строк такие, какими их получит любой, кто скачает этот коммит. Незакоммиченные правки: отказ
# (проба без коммита: HYDE_RELEASE_DIRTY=1, тогда сборка из рабочей папки и пометка в описи).
set -euo pipefail
source "$(dirname "$0")/env.sh"

ver="${1:-}"; check=0; browser=0; aura=0
for a in "${@:2}"; do
    case "$a" in
        --check) check=1 ;;
        --browser) browser=1 ;;
        --aura) aura=1 ;;
        *) die "Неизвестный параметр: $a" ;;
    esac
done
[[ "$ver" =~ ^([0-9]+)\.([0-9]{1,2})\.([0-9]{1,2})$ ]] || die "Нужен номер выпуска X.Y.Z, например: bash build/release.sh 1.0.0"
code=$(( 10#${BASH_REMATCH[1]} * 10000 + 10#${BASH_REMATCH[2]} * 100 + 10#${BASH_REMATCH[3]} ))
rdir="${HYDE_RELEASE_DIR:-$HOME/.hyde-release}"
[ -f "$rdir/release.jks" ] && [ -f "$rdir/release.pass" ] || die "Нет ключа выпуска в $rdir. Создать: bash build/release-key.sh"
case "$(cd "$rdir" && pwd)/" in "$ROOT"/*) die "Ключ выпуска лежит внутри репозитория ($rdir): перенесите его наружу";; esac
[ -f "$CONFIG_DIR/release.json" ] || die "Нет config/release.json (нейтральные настройки выпуска)"
require_build_env

commit="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || true)"
[ -n "$commit" ] || die "Выпуск собирается только из git-репозитория с коммитом"
dirty=0
if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then
    [ "${HYDE_RELEASE_DIRTY:-0}" = 1 ] || die "Есть незакоммиченные правки: выпуск собирается только из коммита (проба: HYDE_RELEASE_DIRTY=1)"
    dirty=1
    note "Внимание: проба из рабочей папки с незакоммиченными правками, в описи будет пометка"
fi

export SIGN_KS="$rdir/release.jks" SIGN_PASS="$rdir/release.pass"
export HYDE_VERSION_CODE="$code" HYDE_VERSION_NAME="$ver"

# Исходники выпуска: чистая выгрузка коммита в свою папку (при пробе рабочая папка)
source_tree() { # source_tree <папка>: печатает корень исходников
    if [ "$dirty" = 1 ]; then printf '%s' "$ROOT"; return; fi
    rm -rf "$1"; mkdir -p "$1"
    git -C "$ROOT" archive --format=tar "$commit" | tar -x -C "$1"
    printf '%s' "$1"
}

build_from() { # build_from <корень исходников> <папка результата>
    export HYDE_OUT="$2" HYDE_SETTINGS="$1/config/release.json"
    rm -rf "$HYDE_OUT"
    bash "$1/build/build-all.sh" --all
    if [ "$browser" = 1 ]; then bash "$1/tvbro/build-tvbro.sh"; fi
    if [ "$aura" = 1 ]; then bash "$1/aura/build-aura.sh"; fi
}

stage="$ROOT/out/release/$ver"
note "Выпуск $ver (versionCode $code) из коммита ${commit:0:7}, ключ выпуска из $rdir"
src="$(source_tree "$ROOT/out/release-src")"
build_from "$src" "$ROOT/out/release-build"
rm -rf "$stage"; mkdir -p "$stage"
for f in "$ROOT/out/release-build"/*.apk; do cp "$f" "$stage/hyde-$(basename "$f" .apk)-$ver.apk"; done
for f in "$ROOT/out/release-build/tiles"/*.apk; do [ -f "$f" ] && cp "$f" "$stage/hyde-tile-$(basename "$f" .apk)-$ver.apk"; done
# HYDE Browser лежит рядом под своим именем (hyde-browser.apk), в цикл выше попал бы как hyde-hyde-browser
rm -f "$stage/hyde-hyde-browser-$ver.apk"
if [ "$browser" = 1 ]; then
    [ -f "$ROOT/out/release-build/hyde-browser.apk" ] || die "HYDE Browser не собрался (нет out/release-build/hyde-browser.apk)"
    cp "$ROOT/out/release-build/hyde-browser.apk" "$stage/hyde-browser-$ver.apk"
fi
if [ "$aura" = 1 ]; then
    [ -f "$stage/hyde-aura-$ver.apk" ] || die "Плеер «Волны» не собрался (нет out/release-build/aura.apk)"
    # Исходники плеера по GPL-3.0: исходники Aura Music того же коммита с нашими патчами, как они собирались, и наши скрипты сборки.
    # Постоянные даты и порядок, чтобы архив одного выпуска не менялся от сборки к сборке
    asrc="$ROOT/out/release-build/aura-src"
    [ -d "$asrc" ] || die "Нет исходников плеера в $asrc"
    pack="$ROOT/out/release-build/aura-pack"
    rm -rf "$pack"; mkdir -p "$pack/hyde-build/aura" "$pack/hyde-build/build"
    cp "$src/aura/build-aura.sh" "$src/aura/README.md" "$src/aura/LICENSE-Aura.txt" "$pack/hyde-build/aura/"
    cp -r "$src/aura/patches" "$pack/hyde-build/aura/"
    cp "$src/build/env.sh" "$src/build/sign.sh" "$pack/hyde-build/build/"
    tar -C "$ROOT/out/release-build" --sort=name --mtime='1981-01-01 00:00:00' --owner=0 --group=0 --numeric-owner \
        --exclude='aura-src/.git' --exclude='aura-src/*/build' --exclude='aura-src/build' --exclude='aura-src/.gradle' \
        --exclude='aura-src/.kotlin' --exclude='aura-src/local.properties' \
        -cf - aura-src -C "$pack" hyde-build | gzip -n -9 > "$stage/hyde-aura-source-$ver.tar.gz"
    note "Исходники плеера: hyde-aura-source-$ver.tar.gz ($(du -h "$stage/hyde-aura-source-$ver.tar.gz" | cut -f1))"
fi
HYDE_COMMIT="$commit" HYDE_DIRTY="$dirty" $PY -I -X utf8 "$(P "$GT_BUILD_DIR/release-manifest.py")" "$(P "$stage")" "$ver" "$code" \
    "$(P "$AAPT2")" "$(P "$JAVA")" "$(P "$SIGNERJAR")" "$(P "$src")"
# Файл обновлений для «Сторожа»: прошлый выпуск по ближайшей метке v* до этого коммита (1.0.1)
prev="$(git -C "$ROOT" describe --tags --abbrev=0 --match 'v*' "$commit" 2>/dev/null || true)"
[ "$prev" = "v$ver" ] && prev="$(git -C "$ROOT" describe --tags --abbrev=0 --match 'v*' "$commit^" 2>/dev/null || true)"
$PY -I -X utf8 "$(P "$GT_BUILD_DIR/update-json.py")" "$(P "$stage")" "$(P "$src")" "$(P "$ROOT")" ${prev:+--prev "$prev"}

if [ "$check" = 1 ]; then
    note "Проверка воспроизводимости: вторая сборка с нуля из второй выгрузки в другой папке"
    src2="$(source_tree "$ROOT/out/release-src2")"
    build_from "$src2" "$ROOT/out/release-check" > "$ROOT/out/release-check.log" 2>&1 || { tail -20 "$ROOT/out/release-check.log"; die "Вторая сборка не прошла (журнал: out/release-check.log)"; }
    bad=0
    for f in "$ROOT/out/release-build"/*.apk "$ROOT/out/release-build/tiles"/*.apk; do
        [ -f "$f" ] || continue
        g="$ROOT/out/release-check/${f#$ROOT/out/release-build/}"
        if ! cmp -s "$f" "$g"; then echo "НЕ СОВПАЛО: ${f#$ROOT/out/release-build/}"; bad=1; fi
    done
    [ "$bad" = 0 ] || die "Сборка не воспроизводится: файлы второй сборки отличаются"
    note "Воспроизводимость: обе сборки совпали байт в байт"
fi
note "Готово: out/release/$ver/ (manifest.json, SHA256SUMS, hyde-update.json). Выкладывать только по решению владельца проекта."
