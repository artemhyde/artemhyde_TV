#!/usr/bin/env bash
# sign.sh: выровнять (zipalign) и подписать apk вашим ключом keys/tv.jks, затем проверить подпись и показать отпечаток.
# Запуск: bash build/sign.sh <вход.apk> <выход.apk>
# Вход: собранный apk без подписи (с classes.dex внутри). Ключ и пароль: keys/tv.jks и keys/tv.pass (создаёт build/new-key.sh);
# общий выпуск подменяет их ключом выпуска через SIGN_KS и SIGN_PASS (build/release.sh).
# Схемы подписи apksigner выбирает сам по minSdk 28 (v2 и выше); v4 (.idsig) отключена: этот файл не нужен.
set -euo pipefail
source "$(dirname "$0")/env.sh"
[ $# -eq 2 ] || die "Нужно два аргумента: bash build/sign.sh <вход.apk> <выход.apk>"
in="$1"; out="$2"
[ -f "$in" ] || die "Нет файла: $in"
require_build_env
ks="${SIGN_KS:-$KEYS/tv.jks}"; kp="${SIGN_PASS:-$KEYS/tv.pass}"
[ -f "$ks" ] && [ -f "$kp" ] || die "Нет ключа. Сначала: bash build/new-key.sh"

mkdir -p "$(dirname "$out")" "$OUT/work/_sign"
aligned="$OUT/work/_sign/$(basename "${out%.apk}").aligned.apk"
rm -f "$aligned" "$out"
# Строки «header mismatch» это безвредное замечание zipalign про записи aapt2, их не показываем; настоящие ошибки показываем
zout="$("$ZIPALIGN" -f -p 4 "$(P "$in")" "$(P "$aligned")" 2>&1)" || { printf '%s\n' "$zout" >&2; die "zipalign не справился с $in"; }
printf '%s\n' "$zout" | grep -v -e 'header mismatch' -e '^[[:space:]]*$' >&2 || true
"$JAVA" -jar "$(P "$SIGNERJAR")" sign --v4-signing-enabled false \
    --ks "$(P "$ks")" --ks-pass "file:$(P "$kp")" \
    --out "$(P "$out")" "$(P "$aligned")"
rm -f "$aligned" "$out.idsig"

certs="$("$JAVA" -jar "$(P "$SIGNERJAR")" verify --print-certs "$(P "$out")" | tr -d '\r')" || die "Подпись $out не прошла проверку"
fp="$(printf '%s\n' "$certs" | sed -n 's/^Signer #1 certificate SHA-256 digest: *//p' | head -1)"
note "Подписано: ${out#$ROOT/} (SHA-256 сертификата: $fp)"
