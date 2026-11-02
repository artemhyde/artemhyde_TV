#!/usr/bin/env bash
# new-key.sh: создать ваш ключ подписи keys/tv.jks (пароль в keys/tv.pass, имя в сертификате CN=GlassTV), один раз. Пароль нигде не печатается.
# Запуск: bash build/new-key.sh
# Ключ один на все приложения: подпись должна совпадать у связанных приложений и при обновлении поверх старой версии.
# Потеряете keys/tv.jks: для обновления придётся удалить приложения с ТВ и поставить заново. Никому не передавайте ключ и пароль, в git они не попадают.
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_build_env

jks="$KEYS/tv.jks"; pass="$KEYS/tv.pass"

fingerprint() {
    "$KEYTOOL" -list -keystore "$(P "$jks")" -storepass:file "$(P "$pass")" 2>/dev/null | grep -i 'SHA-256' | head -1 | sed 's/^[^:]*: *//' || true
}

if [ -f "$jks" ]; then
    note "Ключ уже есть: keys/tv.jks (второй раз не создаю, чтобы не потерять подпись)."
    if [ -f "$pass" ]; then fp="$(fingerprint)"; [ -n "$fp" ] && note "Отпечаток ключа (SHA-256): $fp"; fi
    exit 0
fi

mkdir -p "$KEYS"; chmod 700 "$KEYS" 2>/dev/null || true
umask 077
head -c 24 /dev/urandom | base64 | tr -d '/+=\n' > "$pass"
chmod 600 "$pass" 2>/dev/null || true

gen() {
    "$KEYTOOL" -genkeypair -keystore "$(P "$jks")" -alias tv -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=GlassTV" "$@" >/dev/null 2>&1
}
if ! gen -storepass:file "$(P "$pass")" -keypass:file "$(P "$pass")"; then
    # Запасной путь для keytool, который не знает :file (пароль на секунды виден в списке процессов на этом ПК)
    rm -f "$jks"
    gen -storepass "$(cat "$pass")" -keypass "$(cat "$pass")" || { rm -f "$jks" "$pass"; die "keytool не смог создать ключ"; }
fi
chmod 600 "$jks" 2>/dev/null || true

note "Ключ создан: keys/tv.jks (пароль лежит в keys/tv.pass, в чат и в git его не кладите)."
fp="$(fingerprint)"
[ -n "$fp" ] && note "Отпечаток нового ключа (SHA-256): $fp"
note "Сохраните папку keys/ в надёжном месте: без неё приложения не обновить поверх."
