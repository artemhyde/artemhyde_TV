#!/usr/bin/env bash
# build-tvbro.sh: собрать HYDE Browser (TV Bro с нашими патчами) и подписать вашим ключом: out/hyde-browser.apk, пакет app.glasstv.browser.
# Патч 10-hyde-browser выполняет условия лицензии TV Bro для изменённой сборки: своё имя, значок, application ID и окно авторства,
# поэтому такой файл можно передавать другим. Без патча 10 получится out/tvbro.apk с прежним пакетом: только для своего ТВ.
# Запуск: bash tvbro/build-tvbro.sh [--patches-only]
#   --patches-only: только скачать исходники, наложить патчи и остановиться (быстрая проверка цепочки патчей, без Gradle и без больших загрузок)
# Что делает: клонирует исходники TV Bro с ключами core.autocrlf=false и core.longpaths=true, переходит на проверенный коммит 52b078f, накладывает tvbro/patches/*.patch
# по порядку имён (01, 02, 02a, 02b, 03 ...), собирает вариант fossGeckoExcludedRelease на JDK 21, подписывает ключом keys/tv.jks (build/new-key.sh).
# Нужны: git, JDK 21, Android SDK с платформой и build-tools 36 (лицензию SDK принимает человек), около 2,4 ГБ загрузок Gradle при первой сборке, ключ.
# Переменные: TVBRO_REPO (откуда клонировать, по умолчанию github.com/truefedex/tv-bro), TVBRO_COMMIT (коммит), TVBRO_PATCHES (папка с патчами), JDK21_HOME, ANDROID_HOME.
# Номер выпуска HYDE TV (build/release.sh): HYDE_VERSION_CODE и HYDE_VERSION_NAME уходят в Gradle как -PhydeVersionCode и -PhydeVersionName.
set -euo pipefail
source "$(dirname "$0")/../build/env.sh"

patches_only=0
for a in "$@"; do
    case "$a" in
        --patches-only) patches_only=1 ;;
        -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "Неизвестный параметр: $a" ;;
    esac
done

REPO="${TVBRO_REPO:-https://github.com/truefedex/tv-bro}"
COMMIT="${TVBRO_COMMIT:-52b078f}"
PATCHES="${TVBRO_PATCHES:-$ROOT/tvbro/patches}"
SRC="$OUT/tvbro-src"

command -v git >/dev/null 2>&1 || die "Не найден git."
[ -d "$PATCHES" ] || die "Репозиторий неполный: нет папки патчей $PATCHES. Скачайте репозиторий заново целиком; браузер с правками без патчей не собирать (обычный TV Bro ставится из Google Play)."
# Порядок по именам в локали C: 01, 02-..., 02a, 02b, 03 (дефис раньше букв)
patch_list="$(cd "$PATCHES" && ls -1 ./*.patch 2>/dev/null | sed 's#^\./##' | LC_ALL=C sort)"
[ -n "$patch_list" ] || die "В $PATCHES нет ни одного .patch"
mkdir -p "$OUT"

if [ "$(git config --global core.autocrlf 2>/dev/null || true)" = "true" ]; then
    note "У вас глобально включён core.autocrlf=true: на Windows из-за него патчи не накладываются. Здесь он отключается только для этого клона."
fi

# 1. Исходники на нужном коммите
if [ -d "$SRC/.git" ]; then
    note "Исходники уже скачаны: $SRC. Возвращаю их к чистому коммиту $COMMIT."
    git -C "$SRC" config core.autocrlf false
    git -C "$SRC" reset -q --hard
    git -C "$SRC" clean -qfd
else
    note "Скачиваю исходники TV Bro: $REPO (только коммит $COMMIT)"
    # core.longpaths: самый длинный файл TV Bro 113 символов, и в глубокой папке на Windows путь выходит за 260 (иначе падает на «Filename too long»)
    fetch_commit "$REPO" "$COMMIT" "$SRC" \
        || die "Не удалось скачать исходники TV Bro. Если выше написано «Filename too long», перенесите репозиторий ближе к корню диска (например C:/glasstv): на Windows пути длиннее 260 символов не открываются."
fi
git -C "$SRC" config core.longpaths true
git -C "$SRC" checkout -q -f "$COMMIT" || die "Нет коммита $COMMIT в клоне: исходники TV Bro изменились или клон неполный."
note "Коммит: $(git -C "$SRC" log --oneline -1)"

# 2. Патчи по порядку: сначала проверка, потом наложение
n=0
while IFS= read -r p; do
    [ -n "$p" ] || continue
    if ! git -C "$SRC" -c core.autocrlf=false apply --whitespace=nowarn --check "$PATCHES/$p" 2>/dev/null; then
        die "Патч $p не ложится. Если до этого все патчи легли, проверьте, что файл патча не переписан редактором или git (окончания строк CRLF важны: скачайте репозиторий заново архивом)."
    fi
    git -C "$SRC" -c core.autocrlf=false apply --whitespace=nowarn "$PATCHES/$p"
    n=$((n + 1))
    note "  патч $p наложен"
done <<< "$patch_list"
note "Патчей наложено: $n, изменённых и новых файлов: $(git -C "$SRC" status --porcelain -uall | wc -l | tr -d ' ')"
# HYDE Browser (патч 10): старые растровые значки и баннер TV Bro убрать из сборки совсем, остаются свои (mipmap-anydpi)
hyde=0
if [ -f "$SRC/app/src/main/res/drawable/hyde_icon_foreground.xml" ]; then
    hyde=1
    rm -f "$SRC"/app/src/main/res/mipmap-*dpi/ic_launcher.webp "$SRC"/app/src/main/res/mipmap-*dpi/banner.webp
    note "HYDE Browser: пакет app.glasstv.browser, значки TV Bro убраны"
fi
if [ "$patches_only" = 1 ]; then note "Остановился по --patches-only: исходники в $SRC"; exit 0; fi

# 3. Окружение сборки: JDK 21 и SDK с build-tools 36
JDK21="$(find_jdk 21 || true)"
[ -n "$JDK21" ] || die "Не найден JDK 21 (для сборки браузера нужна именно 21). Мастер ставит его в $GT_HOME."
SDK=""
while IFS= read -r r; do
    if ls "$r/build-tools" 2>/dev/null | grep -q '^36' && ls "$r/platforms" 2>/dev/null | grep -qE '^android-3[6-9]'; then SDK="$r"; break; fi
done < <(sdk_roots)
[ -n "$SDK" ] || die "Не найден Android SDK с build-tools 36 и платформой 36 или новее. Мастер ставит их через sdkmanager (лицензию принимает человек)."
require_build_env   # JDK 17 и build-tools 34 нужны для подписи (zipalign, apksigner)

export JAVA_HOME="$JDK21"
export ANDROID_HOME="$SDK"
export GRADLE_USER_HOME="$GT_HOME/gradle-home"        # кэш Gradle живёт рядом с остальными инструментами, а не в профиле
export ANDROID_USER_HOME="$GT_HOME/android-user-home"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

# Проект просит для Gradle Java 21 именно от JetBrains; годится и Temurin 21. На время сборки это требование убирается, после сборки файл возвращается.
cd "$SRC"
props="gradle/gradle-daemon-jvm.properties"
if [ -f "$props" ]; then
    trap 'git -C "$SRC" checkout -q -- gradle/gradle-daemon-jvm.properties' EXIT
    sed '/^toolchainVendor=/d' "$props" > "$props.tmp" && mv "$props.tmp" "$props"
fi

note "Сборка Gradle (при первом запуске долго: качается около 2,4 ГБ)..."
ver_props=()
if [ -n "${HYDE_VERSION_CODE:-}" ]; then ver_props=(-PhydeVersionCode="$HYDE_VERSION_CODE" -PhydeVersionName="$HYDE_VERSION_NAME"); fi
./gradlew --no-daemon -Porg.gradle.java.installations.auto-download=false ${ver_props[@]+"${ver_props[@]}"} assembleFossGeckoExcludedRelease

unsigned="$(ls app/build/outputs/apk/fossGeckoExcluded/release/*-unsigned.apk 2>/dev/null | head -1)"
[ -n "$unsigned" ] || die "Gradle не оставил apk в app/build/outputs/apk/fossGeckoExcluded/release/"
cd "$ROOT"
# Подпись делает sign.sh на JDK 17 и build-tools из env.sh
if [ "$hyde" = 1 ]; then
    bash "$GT_BUILD_DIR/sign.sh" "$SRC/$unsigned" "$OUT/hyde-browser.apk"
    note "Готово: out/hyde-browser.apk (HYDE Browser, пакет app.glasstv.browser). Условия лицензии TV Bro выполнены: его можно передавать."
else
    bash "$GT_BUILD_DIR/sign.sh" "$SRC/$unsigned" "$OUT/tvbro.apk"
    note "Готово: out/tvbro.apk (без патча 10: прежнее имя и значок TV Bro). Только для вашего ТВ, другим не передавайте (лицензия TV Bro)."
fi
