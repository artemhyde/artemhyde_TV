#!/usr/bin/env bash
# check-apk.sh: проверить подписи собранных apk (apksigner verify --print-certs) и убедиться, что у всех один и тот же ключ.
# Запуск: bash build/check-apk.sh [файл.apk ...]      без аргументов проверяются out/*.apk и out/tiles/*.apk
# Печатает по каждому файлу: пакет, версию и SHA-256 сертификата. Код выхода 1, если подпись не сходится или ключи у файлов разные
# (приложения с разными ключами не обновятся поверх друг друга и не будут доверять друг другу). Если есть keys/tv.jks, сверяет и с ним.
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_build_env

files=("$@")
if [ ${#files[@]} -eq 0 ]; then
    for f in "$OUT"/*.apk "$OUT"/tiles/*.apk; do [ -f "$f" ] && files+=("$f"); done
fi
[ ${#files[@]} -gt 0 ] || die "Нет apk для проверки (искал в out/). Сначала: bash build/build-all.sh"

keyfp=""
if [ -f "$KEYS/tv.jks" ] && [ -f "$KEYS/tv.pass" ]; then
    keyfp="$("$KEYTOOL" -list -keystore "$(P "$KEYS/tv.jks")" -storepass:file "$(P "$KEYS/tv.pass")" 2>/dev/null \
        | grep -i 'SHA-256' | head -1 | sed 's/^[^:]*: *//' | tr -d ':' | tr 'A-F' 'a-f' || true)"
fi

bad=0; first=""
for f in "${files[@]}"; do
    certs="$("$JAVA" -jar "$(P "$SIGNERJAR")" verify --print-certs "$(P "$f")" 2>&1 | tr -d '\r')" || {
        echo "ПЛОХО  ${f#$ROOT/}: подпись не прошла проверку"; printf '%s\n' "$certs" | sed 's/^/       /'; bad=1; continue; }
    fp="$(printf '%s\n' "$certs" | sed -n 's/^Signer #1 certificate SHA-256 digest: *//p' | head -1 | tr 'A-F' 'a-f')"
    badging="$("$AAPT2" dump badging "$(P "$f")" 2>/dev/null | tr -d '\r' | sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1 v\2 (\3)/p" | head -1 || true)"
    status="OK    "
    if [ -z "$fp" ]; then status="ПЛОХО "; bad=1
    else
        [ -n "$first" ] || first="$fp"
        if [ "$fp" != "$first" ]; then status="ПЛОХО "; bad=1; badging="$badging [другой ключ]"; fi
        if [ -n "$keyfp" ] && [ "$fp" != "$keyfp" ]; then status="ПЛОХО "; bad=1; badging="$badging [не ключ keys/tv.jks]"; fi
    fi
    echo "$status${f#$ROOT/}  ${badging:-пакет не прочитан}"
done

echo ""
if [ "$bad" = 0 ]; then
    echo "Все подписи верны, ключ один: $first"
    [ -n "$keyfp" ] && echo "Совпадает с keys/tv.jks."
    exit 0
fi
echo "Есть проблемы: пересоберите проблемные приложения (bash build/build-app.sh <имя>)."
exit 1
