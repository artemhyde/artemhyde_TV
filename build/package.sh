#!/usr/bin/env bash
# package.sh: архивы выпуска HYDE TV для людей (план Codex, этап 2: «на чистой Windows без Git, Java, Python и SDK»).
# По одному архиву на систему: Windows, macOS (Apple и Intel), Linux (x86-64 и ARM64). В каждом установщик
# hyde-install, файлы выпуска, README.txt, CLAUDE.md для пути с Claude и лицензии. adb не кладётся (условия Android SDK
# запрещают его раздавать): установщик качает его с сайта Google по согласию человека.
# Запуск: bash build/package.sh 1.0.0 [--release <папка выпуска>]
#   папка выпуска по умолчанию out/release/<версия> (её делает build/release.sh); установщик собирается заново
#   (installer/build.sh, нужен Go 1.27). Результат: out/dist/<версия>/ и SHA256SUMS архивов.
# Архивы повторяемые: тот же выпуск и тот же коммит установщика дают те же байты. Выкладка только по решению
# владельца проекта.
set -euo pipefail
source "$(dirname "$0")/env.sh"

ver="${1:-}"; rel=""
shift || true
while [ $# -gt 0 ]; do
    case "$1" in
        --release) rel="${2:-}"; shift 2 ;;
        *) die "Неизвестный параметр: $1" ;;
    esac
done
[[ "$ver" =~ ^[0-9]+\.[0-9]{1,2}\.[0-9]{1,2}$ ]] || die "Нужен номер выпуска X.Y.Z, например: bash build/package.sh 1.0.0"
rel="${rel:-$ROOT/out/release/$ver}"
[ -f "$rel/manifest.json" ] && [ -f "$rel/SHA256SUMS" ] || die "Нет выпуска в $rel (manifest.json и SHA256SUMS): сначала bash build/release.sh $ver"
PY="$(find_python || true)"
[ -n "$PY" ] || die "Не найден Python 3"
# Установщик собирается из рабочей папки, поэтому она должна совпадать с коммитом: он записывается в VERSION.txt архива
icommit="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || true)"
[ -n "$icommit" ] || die "Архивы собираются только в git-репозитории с коммитом"
if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then
    [ "${HYDE_RELEASE_DIRTY:-0}" = 1 ] || die "Есть незакоммиченные правки: установщик для людей собирается только из коммита (проба: HYDE_RELEASE_DIRTY=1)"
    icommit="$icommit (+ незакоммиченные правки, только для проб)"
fi

bash "$ROOT/installer/build.sh"
out="$ROOT/out/dist/$ver"
rm -rf "$out"
$PY -I -X utf8 "$(P "$GT_BUILD_DIR/package.py")" "$(P "$rel")" "$(P "$ROOT/out/installer")" "$(P "$out")" "$ver" "$(P "$ROOT")" "$icommit"
note "Готово: $out. Выкладывать только по решению владельца проекта."
