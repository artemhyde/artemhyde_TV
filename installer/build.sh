#!/usr/bin/env bash
# build.sh: собрать установщик HYDE TV одним файлом под Windows, macOS (Apple и Intel) и Linux.
# Запуск: bash installer/build.sh   Результат: out/installer/hyde-install-<система>-<процессор>[.exe] и SHA256SUMS
# Нужен Go 1.27 (go.dev): ищется в GO, в PATH и в $GLASSTV_TOOLS/go (по умолчанию ~/.glasstv/tools/go). Сторонних
# библиотек нет, сеть при сборке не нужна (GOPROXY=off). Перед сборкой проверки: go vet и go test.
# Сборка повторяемая: -trimpath, без идентификатора сборки, без cgo; один и тот же коммит даёт те же байты
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
TOOLS="${GLASSTV_TOOLS:-$HOME/.glasstv/tools}"

GO="${GO:-}"
for c in "$GO" "$(command -v go 2>/dev/null || true)" "$TOOLS/go/bin/go" "$TOOLS/go/bin/go.exe"; do
    if [ -n "$c" ] && [ -x "$c" ]; then GO="$c"; break; fi
done
[ -n "$GO" ] && [ -x "$GO" ] || { echo "Go не найден: поставьте Go 1.27 с go.dev в $TOOLS/go или задайте GO"; exit 1; }
export GOTOOLCHAIN=local GOPROXY=off GOFLAGS=-trimpath CGO_ENABLED=0
[ -n "${GLASSTV_TOOLS:-}" ] && export GOCACHE="${GOCACHE:-$TOOLS/gocache}" GOPATH="${GOPATH:-$TOOLS/gopath}"

cd "$HERE"
echo "Go: $("$GO" version)"
"$GO" vet ./...
"$GO" test ./...
OUT="$ROOT/out/installer"
rm -rf "$OUT"; mkdir -p "$OUT"
for t in windows/amd64 darwin/arm64 darwin/amd64 linux/amd64 linux/arm64; do
    os="${t%/*}"; arch="${t#*/}"; ext=""; [ "$os" = windows ] && ext=".exe"
    GOOS="$os" GOARCH="$arch" "$GO" build -ldflags="-s -w -buildid=" -o "$OUT/hyde-install-$os-$arch$ext" .
    echo "собрано: hyde-install-$os-$arch$ext"
done
(cd "$OUT" && sha256sum hyde-install-* > SHA256SUMS)
echo "Готово: $OUT"
