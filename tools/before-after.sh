#!/usr/bin/env bash
# before-after.sh: открывает в браузере интерактивное сравнение «До и После» со шторкой (docs/before-after.html).
# Запуск: bash tools/before-after.sh. Нужен только браузер: ни ТВ, ни интернет, ни инструменты сборки.
f="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docs/before-after.html"
[ -f "$f" ] || { echo "Нет файла $f: репозиторий скачан неполно." >&2; exit 1; }
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) explorer.exe "$(cygpath -w "$f")" || true ;;   # explorer всегда отвечает кодом 1
    Darwin) open "$f" ;;
    *) if command -v xdg-open >/dev/null 2>&1; then xdg-open "$f"; else echo "Откройте в браузере: $f"; fi ;;
esac
