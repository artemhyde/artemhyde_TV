#!/usr/bin/env bash
# release.sh: собрать общий выпуск HYDE TV. Все приложения из apps/ с одним номером версии, подписаны ключом выпуска,
# рядом опись manifest.json (пакет, версия, minSdk, ABI, SHA-256 файла, сертификат, источник, лицензия, зависимости,
# способ возврата) и SHA256SUMS. Результат: out/release/<версия>/.
# Запуск: bash build/release.sh 1.0.0 [--check]
#   номер X.Y.Z (Y и Z от 0 до 99): versionName у всех приложений X.Y.Z, versionCode X*10000 + Y*100 + Z
#   --check: собрать второй раз в другую папку и сравнить: файлы должны совпасть байт в байт (воспроизводимость)
# Ключ выпуска лежит вне репозитория: HYDE_RELEASE_DIR (по умолчанию ~/.hyde-release с release.jks и release.pass,
# создаёт build/release-key.sh). Ключ личных сборок keys/tv.jks выпуск не трогает.
# Настройки сборки нейтральные (config/release.json): город, цвет стекла, курсы, стихи и имена человек выбирает на ТВ,
# а мастер присылает их файлом переноса (settings_load). Свои стихи (verses custom) бывают только в личной сборке.
set -euo pipefail
source "$(dirname "$0")/env.sh"

ver="${1:-}"; check=0
[ "${2:-}" = "--check" ] && check=1
[[ "$ver" =~ ^([0-9]+)\.([0-9]{1,2})\.([0-9]{1,2})$ ]] || die "Нужен номер выпуска X.Y.Z, например: bash build/release.sh 1.0.0"
code=$(( 10#${BASH_REMATCH[1]} * 10000 + 10#${BASH_REMATCH[2]} * 100 + 10#${BASH_REMATCH[3]} ))
rdir="${HYDE_RELEASE_DIR:-$HOME/.hyde-release}"
[ -f "$rdir/release.jks" ] && [ -f "$rdir/release.pass" ] || die "Нет ключа выпуска в $rdir. Создать: bash build/release-key.sh"
case "$(cd "$rdir" && pwd)/" in "$ROOT"/*) die "Ключ выпуска лежит внутри репозитория ($rdir): перенесите его наружу";; esac
[ -f "$CONFIG_DIR/release.json" ] || die "Нет config/release.json (нейтральные настройки выпуска)"
require_build_env

export SIGN_KS="$rdir/release.jks" SIGN_PASS="$rdir/release.pass" HYDE_SETTINGS="$CONFIG_DIR/release.json"
export HYDE_VERSION_CODE="$code" HYDE_VERSION_NAME="$ver"

build_into() { # build_into <папка>: собрать все приложения с результатом в этой папке
    export HYDE_OUT="$1"
    rm -rf "$HYDE_OUT"
    bash "$GT_BUILD_DIR/build-all.sh" --all
}

stage="$ROOT/out/release/$ver"
note "Выпуск $ver (versionCode $code), ключ выпуска из $rdir"
build_into "$ROOT/out/release-build"
rm -rf "$stage"; mkdir -p "$stage"
for f in "$ROOT/out/release-build"/*.apk; do cp "$f" "$stage/hyde-$(basename "$f" .apk)-$ver.apk"; done
for f in "$ROOT/out/release-build/tiles"/*.apk; do [ -f "$f" ] && cp "$f" "$stage/hyde-tile-$(basename "$f" .apk)-$ver.apk"; done
$PY -I -X utf8 "$(P "$GT_BUILD_DIR/release-manifest.py")" "$(P "$stage")" "$ver" "$code" \
    "$(P "$AAPT2")" "$(P "$JAVA")" "$(P "$SIGNERJAR")" "$(P "$ROOT")"

if [ "$check" = 1 ]; then
    note "Проверка воспроизводимости: вторая сборка с нуля"
    build_into "$ROOT/out/release-check" > "$ROOT/out/release-check.log" 2>&1         || { tail -20 "$ROOT/out/release-check.log"; die "Вторая сборка не прошла (журнал: out/release-check.log)"; }
    bad=0
    for f in "$ROOT/out/release-build"/*.apk "$ROOT/out/release-build/tiles"/*.apk; do
        [ -f "$f" ] || continue
        g="$ROOT/out/release-check/${f#$ROOT/out/release-build/}"
        if ! cmp -s "$f" "$g"; then echo "НЕ СОВПАЛО: ${f#$ROOT/out/release-build/}"; bad=1; fi
    done
    [ "$bad" = 0 ] || die "Сборка не воспроизводится: файлы второй сборки отличаются"
    note "Воспроизводимость: обе сборки совпали байт в байт"
fi
note "Готово: out/release/$ver/ (manifest.json, SHA256SUMS). Выкладывать только по решению владельца проекта."
