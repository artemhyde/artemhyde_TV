#!/usr/bin/env bash
# release-key.sh: создать ключ выпуска HYDE TV, один раз и вне репозитория: HYDE_RELEASE_DIR (по умолчанию ~/.hyde-release),
# в ней release.jks и release.pass. Пароль нигде не печатается, показывается только отпечаток сертификата.
# Этим ключом подписываются общие выпуски (build/release.sh). Личные сборки по-прежнему подписываются своим keys/tv.jks.
# Потеря ключа выпуска: ни один ТВ не получит обновление поверх, всем придётся переставлять приложения с переносом настроек.
# Поэтому сразу после создания сделайте копию папки (флешка, менеджер паролей) и никому её не передавайте.
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_build_env

rdir="${HYDE_RELEASE_DIR:-$HOME/.hyde-release}"
mkdir -p "$rdir"
case "$(cd "$rdir" && pwd)/" in "$ROOT"/*) die "Папка ключа выпуска внутри репозитория ($rdir): нужна папка снаружи";; esac
jks="$rdir/release.jks"; pass="$rdir/release.pass"

fingerprint() {
    "$KEYTOOL" -list -v -keystore "$(P "$jks")" -storepass:file "$(P "$pass")" 2>/dev/null | grep -m1 'SHA256:' | sed 's/^[^:]*: *//' || true
}

if [ -f "$jks" ]; then
    note "Ключ выпуска уже есть: $jks (второй раз не создаю, чтобы не потерять подпись)."
    [ -f "$pass" ] && note "Отпечаток сертификата (SHA-256): $(fingerprint)"
    exit 0
fi

chmod 700 "$rdir" 2>/dev/null || true
umask 077
head -c 32 /dev/urandom | base64 | tr -d '/+=\n' > "$pass"
chmod 600 "$pass" 2>/dev/null || true
"$KEYTOOL" -genkeypair -keystore "$(P "$jks")" -storetype PKCS12 -alias release -keyalg RSA -keysize 4096 -validity 10950 \
    -dname "CN=HYDE TV" -storepass:file "$(P "$pass")" -keypass:file "$(P "$pass")" >/dev/null 2>&1 \
    || { rm -f "$jks" "$pass"; die "keytool не создал ключ выпуска"; }
chmod 600 "$jks" 2>/dev/null || true

note "Ключ выпуска создан: $jks"
note "Отпечаток сертификата (SHA-256): $(fingerprint)"
note "Сразу сделайте копию папки $rdir (флешка, менеджер паролей): без неё выпуски не обновить поверх."
