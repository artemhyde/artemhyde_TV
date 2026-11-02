#!/usr/bin/env bash
# build-aura.sh: собрать плеер для «Волны» (Aura Music с нашими патчами) и подписать вашим ключом: out/aura.apk
# Запуск: bash aura/build-aura.sh [--patches-only]
#   --patches-only: только скачать исходники, наложить патчи и остановиться (быстрая проверка цепочки)
# Что делает: клонирует исходники Aura Music, переходит на проверенный коммит тега v3.2.0, накладывает патчи из aura/patches,
# собирает вариант FossTvArmeabi (без служб Google, интерфейс ТВ, 32-битный ARM) на JDK 21, подписывает ключом keys/tv.jks.
# Подпись обязана совпадать с подписью «Волны»: команды принимаются только от неё.
# Нужны: git, JDK 21, Android SDK с платформой 36 и build-tools 36 (лицензию SDK принимает человек), около 1,5 ГБ загрузок.
# Переменные: AURA_REPO (откуда клонировать), AURA_COMMIT (коммит), AURA_ABI (Armeabi по умолчанию; для 64-битного ТВ Arm64).
# Лицензия папки aura/: GPL-3.0. Если передаёте apk другим людям, передавайте и его исходники: тег и эти патчи.
set -euo pipefail
source "$(dirname "$0")/../build/env.sh"

patches_only=0
for a in "$@"; do
    case "$a" in
        --patches-only) patches_only=1 ;;
        -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "Неизвестный параметр: $a" ;;
    esac
done

REPO="${AURA_REPO:-https://github.com/TeamAuraMusic/AuraMusic.git}"
COMMIT="${AURA_COMMIT:-2e4f1263f710d88b6841c9ee097bb94a0e456f16}"
ABI="${AURA_ABI:-Armeabi}"

PATCHES="$ROOT/aura/patches"
SRC="$OUT/aura-src"

command -v git >/dev/null 2>&1 || die "Не найден git."
patch_list="$(cd "$PATCHES" 2>/dev/null && ls -1 ./*.patch 2>/dev/null | sed 's#^\./##' | LC_ALL=C sort)"
[ -n "$patch_list" ] || die "В $PATCHES нет ни одного .patch: репозиторий скачан неполно."
mkdir -p "$OUT"

# 1. Исходники на нужном коммите
if [ -d "$SRC/.git" ]; then
    note "Исходники уже скачаны: $SRC. Возвращаю их к чистому коммиту."
    git -C "$SRC" config core.autocrlf false
    git -C "$SRC" reset -q --hard
    git -C "$SRC" clean -qfd
else
    rm -rf "$SRC"
    note "Скачиваю исходники Aura Music: $REPO"
    git -c core.autocrlf=false -c core.longpaths=true clone -q "$REPO" "$SRC" || { rm -rf "$SRC"; die "Не удалось скачать исходники Aura Music."; }
    git -C "$SRC" config core.autocrlf false
fi
git -C "$SRC" config core.longpaths true
git -C "$SRC" checkout -q -f "$COMMIT" || die "Нет коммита $COMMIT в клоне."
note "Коммит: $(git -C "$SRC" log --oneline -1)"

# 2. Патчи по порядку: сначала проверка, потом наложение
while IFS= read -r p; do
    [ -n "$p" ] || continue
    git -C "$SRC" -c core.autocrlf=false apply --whitespace=nowarn --check "$PATCHES/$p" 2>/dev/null || die "Патч $p не ложится на коммит $COMMIT."
    git -C "$SRC" -c core.autocrlf=false apply --whitespace=nowarn "$PATCHES/$p"
    note "  патч $p наложен"
done <<< "$patch_list"
if [ "$patches_only" = 1 ]; then note "Остановился по --patches-only: исходники в $SRC"; exit 0; fi

# 3. Окружение сборки: JDK 21 и SDK с платформой 36
JDK21="$(find_jdk 21 || true)"
[ -n "$JDK21" ] || die "Не найден JDK 21. Мастер ставит его в $GT_HOME."
SDK=""
while IFS= read -r r; do
    if ls "$r/build-tools" 2>/dev/null | grep -q '^36' && ls "$r/platforms" 2>/dev/null | grep -qE '^android-36$'; then SDK="$r"; break; fi
done < <(sdk_roots)
[ -n "$SDK" ] || die "Не найден Android SDK с build-tools 36 и платформой android-36. Мастер ставит их через sdkmanager, лицензию принимает человек."
require_build_env   # JDK 17 и build-tools 34 нужны для подписи (zipalign, apksigner)

export JAVA_HOME="$JDK21"
export ANDROID_HOME="$SDK"
export GRADLE_USER_HOME="$GT_HOME/gradle-home"
export ANDROID_USER_HOME="$GT_HOME/android-user-home"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

# Сборка требует настроек подписи, поэтому собираем с временным ключом, а настоящую подпись ставит sign.sh: пароль вашего ключа в Gradle не попадает
cd "$SRC"
tmp_key="$OUT/work/_aura-temp.jks"
mkdir -p "$OUT/work"
rm -f "$tmp_key"
"$JAVA_HOME/bin/keytool" -genkeypair -keystore "$(P "$tmp_key")" -storepass temporary -keypass temporary -alias temp -keyalg RSA -keysize 2048 -validity 1 -dname "CN=temp" >/dev/null 2>&1
printf 'release.storeFile=%s\nrelease.storePassword=temporary\nrelease.keyAlias=temp\nrelease.keyPassword=temporary\n' "$(P "$tmp_key")" > local.properties

note "Сборка Gradle (при первом запуске долго: качается около 1,5 ГБ)..."
"$JAVA_HOME/bin/java" -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain --no-daemon \
    -Porg.gradle.java.installations.auto-download=false ":app:assembleFossTv${ABI}Release"

built="$(ls app/build/outputs/apk/fossTv$ABI/release/*.apk 2>/dev/null | head -1)"
[ -n "$built" ] || die "Gradle не оставил apk в app/build/outputs/apk/fossTv$ABI/release/"
rm -f local.properties "$tmp_key"
cd "$ROOT"
bash "$GT_BUILD_DIR/sign.sh" "$SRC/$built" "$OUT/aura.apk"
note "Готово: out/aura.apk"
