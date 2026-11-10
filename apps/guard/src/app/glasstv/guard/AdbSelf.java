package app.glasstv.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Обновления HYDE без нажатий на Android 9–11 (выпуск 1.2.0: человек один раз ставит HYDE, дальше обновления встают сами).
// Тихо обновлять приложения Android до 12 обычному приложению не даёт, поэтому «Сторож»
// ставит файл через adb своего же ТВ: на этих телевизорах adbd слушает порт 5555, пока включена «Отладка по USB» (её держит
// «Сторож», через неё подключается и установщик). Один раз на экране ТВ «Разрешить отладку» для ключа «Сторожа»: мастер
// просит это во время установки, а где не вышло, «Сторож» один раз спрашивает запиской сам.
// Пределы, которые держит этот код: только 127.0.0.1:5555 этого ТВ; одна команда: поставить APK HYDE, подписанный ключом
// выпуска (проверка здесь и в Hyde); команд извне нет; ключ RSA в Android Keystore, достать его нельзя; выключатель
// «Обновлять HYDE сам» гасит и это. Отозвать разрешение можно в «Настройках разработчика» ТВ («Отозвать доступ к отладке»)
final class AdbSelf {
    static final String HOST = "127.0.0.1";
    static final int PORT = 5555;
    static final String ALIAS = "hyde-guard-adb";
    static final long PAIR_WAIT = 120_000;                   // сколько ждать «Разрешить» на экране
    static final long KEEP_EVERY = 20 * 3600_000L;           // раз в сутки зайти: Android 11 снимает разрешение через 7 дней без входа
    static final long REVOKE_GUESS = 6 * 24 * 3600_000L;     // не входили неделю и разрешение пропало: снял Android 11, можно спросить ещё раз
    static final String OK = "ok", NEED = "need", ABSENT = "absent", TLS = "tls", ERROR = "error";

    private final Context c;
    private final SharedPreferences sp;
    // Ждём «Разрешить»: общее на процесс, а не на объект. Команда мастера без запущенной службы создаёт свой AdbSelf, и
    // опрос do adbself иначе не увидел бы, что окно уже на экране, и попросил бы второе
    private static volatile boolean pairing;

    AdbSelf(Context c) {
        this.c = c;
        this.sp = c.getSharedPreferences("upd", Context.MODE_PRIVATE);
    }

    // Нужен ли путь через adb: Android 9, 10, 11 (с Android 12 «Сторож» обновляет HYDE сам, без отладки)
    static boolean needed() { return Build.VERSION.SDK_INT < 31; }

    String state() { return sp.getString("adb.state", null); }

    boolean ready() { return needed() && OK.equals(state()); }

    boolean pairingNow() { return pairing; }

    // ===== Ключ «Сторожа» (Android Keystore: закрытая часть не покидает хранилище) =====

    private KeyStore store() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build());
            g.generateKeyPair();
        }
        return ks;
    }

    private AdbLink.Signer signer(KeyStore ks) throws Exception {
        final PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        return in -> {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(key);
            s.update(in);
            return s.sign();
        };
    }

    private byte[] publicKey(KeyStore ks) throws Exception {
        RSAPublicKey pub = (RSAPublicKey) ks.getCertificate(ALIAS).getPublicKey();
        return AdbRules.publicKey(pub.getModulus(), pub.getPublicExponent(), "hyde-guard@tv");
    }

    // Адрес adbd: этот ТВ. В отладочной сборке «Сторожа» (только для проверки на виртуальном ТВ) его можно сменить командой
    private String[] addr() {
        String a = sp.getString("adb.addr", null);
        if (a != null && debuggable(c)) {
            int i = a.lastIndexOf(':');
            if (i > 0) return new String[]{a.substring(0, i), a.substring(i + 1)};
        }
        return new String[]{HOST, String.valueOf(PORT)};
    }

    static boolean debuggable(Context c) { return (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0; }

    String setAddr(String a) {
        if (!debuggable(c)) return "адрес отладки меняется только в отладочной сборке «Сторожа»";
        if (a == null || a.trim().isEmpty()) { sp.edit().remove("adb.addr").apply(); return "адрес отладки: этот ТВ"; }
        if (!a.matches("[0-9.]+:\\d{1,5}")) return "адрес вида 10.0.2.2:5575";
        sp.edit().putString("adb.addr", a).apply();
        return "адрес отладки " + a;
    }

    // Открытый ключ «Сторожа» строкой adb_keys: только в отладочной сборке (проверка на виртуальном ТВ, где ключ кладут заранее)
    String publicKeyLine() {
        if (!debuggable(c)) return "только в отладочной сборке";
        try {
            byte[] k = publicKey(store());
            return new String(k, 0, k.length - 1, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "ключ не читается: " + e.getMessage();
        }
    }

    // ===== Вход =====

    // Войти без показа окна. Итог: OK, NEED (ключ не разрешён), ABSENT (порт закрыт), TLS, ERROR. Пишется в состояние
    synchronized String probe() {
        return connect(false, 0);
    }

    // Войти, при незнакомом ключе показать окно «Разрешить отладку» и ждать человека до wait мс
    synchronized String pair(long wait) {
        pairing = true;
        try {
            return connect(true, wait);
        } finally {
            pairing = false;
        }
    }

    private String connect(boolean offer, long wait) {
        String[] a = addr();
        String res;
        String err = null;
        try {
            KeyStore ks = store();
            try (AdbLink l = AdbLink.open(a[0], Integer.parseInt(a[1]), 10_000)) {
                int r = l.handshake(signer(ks), publicKey(ks), offer, wait);
                res = r == AdbLink.OK ? OK : r == AdbLink.NEED_ALLOW ? NEED : TLS;
            }
        } catch (ConnectException e) {
            res = ABSENT;
            err = "порт " + a[1] + " закрыт: «Отладка по USB» выключена или этот ТВ не слушает сеть";
        } catch (Exception e) {
            res = ERROR;
            err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        remember(res, err);
        return res;
    }

    private void remember(String res, String err) {
        String before = state();
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = sp.edit().putString("adb.state", res).putLong("adb.at", now);
        if (OK.equals(res)) e.putLong("adb.ok", now).remove("adb.err");
        else if (err != null) e.putString("adb.err", err);
        // Разрешение пропало после недели без входа: это Android 11 снял его сам, а не человек отозвал. Одна новая записка
        if (NEED.equals(res) && OK.equals(before) && now - sp.getLong("adb.ok", now) >= REVOKE_GUESS) e.putBoolean("adb.asked", false);
        e.apply();
    }

    // Вход без окна: ключ «Всегда разрешать» система держит в /data/misc/adb/adb_keys (на Android 11 ещё и снимает его через
    // неделю без входа), adbd хватает подписи. Открытый ключ «Сторож» шлёт только в pair: иначе на экране вылезло бы окно
    // Раз в сутки (Hyde.check): зайти, чтобы Android 11 не снял разрешение, и узнать, не отозвали ли его
    void keepAlive() {
        if (!needed() || !Hyde.on(c)) return;
        if (System.currentTimeMillis() - sp.getLong("adb.at", 0) < KEEP_EVERY && state() != null) return;
        probe();
    }

    // ===== Установка =====

    // Поставить файл HYDE. null: встало; иначе причина. Файл проверяется ещё раз: пакет тот, подпись ключом выпуска.
    // Себя «Сторож» ставит последним: процесс закроется посреди ответа, итог он узнает после перезапуска по версии
    synchronized String install(File apk, String pkg) {
        if (!needed() || !Hyde.on(c)) return "обновления через отладку выключены";
        PackageInfo nw = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES);
        if (nw == null || !pkg.equals(nw.packageName) || !HydeRules.RELEASE_CERT.equals(Hyde.cert(nw)))
            return "файл не тот или подписан не ключом выпуска";
        String[] a = addr();
        try {
            KeyStore ks = store();
            try (AdbLink l = AdbLink.open(a[0], Integer.parseInt(a[1]), 10_000)) {
                int r = l.handshake(signer(ks), publicKey(ks), false, 0);
                if (r != AdbLink.OK) {
                    remember(r == AdbLink.NEED_ALLOW ? NEED : TLS, null);
                    return r == AdbLink.NEED_ALLOW ? "отладка «Сторожу» больше не разрешена" : "adbd просит TLS";
                }
                remember(OK, null);
                try (InputStream in = new FileInputStream(apk)) {
                    return AdbRules.installResult(l.exec(AdbRules.installService(apk.length(), c.getPackageName()), in, apk.length(), 5 * 60_000));
                }
            }
        } catch (ConnectException e) {
            remember(ABSENT, "порт " + a[1] + " закрыт");
            return "отладка ТВ недоступна (порт " + a[1] + " закрыт)";
        } catch (Exception e) {
            return "через отладку: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ===== Одна записка там, где разрешения нет =====

    // Пора ли попросить «Разрешить» (один раз на ТВ; ещё раз только после того, как Android 11 снял разрешение сам)
    boolean askPending() {
        return needed() && Hyde.releaseBuild(c) && Hyde.on(c) && NEED.equals(state()) && !sp.getBoolean("adb.asked", false) && !pairing;
    }

    void markAsked() { sp.edit().putBoolean("adb.asked", true).apply(); }

    // Забыть ключ «Сторожа» (команда с ПК): разрешение на ТВ останется, но ключа к нему больше нет
    synchronized String forget() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
        } catch (Exception e) {
            return "ключ не удалился: " + e.getMessage();
        }
        sp.edit().remove("adb.state").remove("adb.at").remove("adb.ok").remove("adb.err").remove("adb.asked").apply();
        return "ключ «Сторожа» удалён";
    }

    // ===== Состояние =====

    // Коротко для экрана и описи: null, если путь не нужен (Android 12 и новее)
    String brief() {
        if (!needed()) return null;
        String s = state();
        if (pairing) return "жду «Разрешить» на экране ТВ";
        if (OK.equals(s)) return "через отладку ТВ";
        if (NEED.equals(s)) return "отладка «Сторожу» не разрешена";
        if (ABSENT.equals(s)) return "отладка ТВ выключена";
        if (TLS.equals(s)) return "отладка ТВ в режиме Wi-Fi";
        if (ERROR.equals(s)) return "отладка ТВ: ошибка";
        return "отладку ещё не пробовал";
    }

    String status() {
        if (!needed()) return "не нужно: Android 12 и новее обновляет без отладки";
        StringBuilder b = new StringBuilder(brief());
        long ok = sp.getLong("adb.ok", 0), at = sp.getLong("adb.at", 0);
        if (ok > 0) b.append("; последний вход ").append(fmt(ok));
        if (at > ok) b.append("; проверка ").append(fmt(at));
        String err = sp.getString("adb.err", null);
        if (err != null && !OK.equals(state())) b.append("; ").append(err);
        if (sp.getBoolean("adb.asked", false)) b.append("; записку уже показывал");
        if (sp.getString("adb.addr", null) != null && debuggable(c)) b.append("; проверочный адрес ").append(sp.getString("adb.addr", ""));
        return b.toString();
    }

    private static String fmt(long t) { return new SimpleDateFormat("d MMM, H:mm", Locale.forLanguageTag("ru")).format(new Date(t)); }
}
