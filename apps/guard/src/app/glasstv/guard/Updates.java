package app.glasstv.guard;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Обновления приложений не из магазина (05.11). Google Play обновляет только свои, а эти ставились с ПК: установщика
// у них нет, и Android сам их не обновит. Список и откуда брать: assets/updates.txt.
// Раз в сутки «Сторож» спрашивает последний выпуск каждого (GitHub или GitLab). Выпуск новый: качает файл, сверяет
// контрольную сумму выпуска, имя пакета, версию (должна быть выше стоящей) и подпись (та же, что у стоящего приложения),
// и ставит, когда приложение не на экране и ничего не играет.
// Без нажатий Android 14 ставит, только если установщик приложения сам «Сторож» (приложения один раз переставлены с ПК
// их же файлами: pm install -r -i app.glasstv.guard) и приложение собрано под Android 12 и новее. Остальным (Lampa)
// система показывает окно «Обновить»: его «Сторож» открывает только на главном экране, когда человек сам за пультом,
// и пишет записку, что нажать. Отказались или не встало: снова завтра.
// Права: REQUEST_INSTALL_PACKAGES (с ПК: appops set app.glasstv.guard REQUEST_INSTALL_PACKAGES allow),
// UPDATE_PACKAGES_WITHOUT_USER_ACTION и QUERY_ALL_PACKAGES (выдаются при установке)
final class Updates {
    static final long CHECK_EVERY = 20 * 3600_000L;   // раз в сутки, с запасом, чтобы проверка не уползала по часам
    static final long TICK = 30 * 60_000L;              // ставить скачанное, когда момент подходящий
    static final long TICK_TAP = 2 * 60_000L;           // ждём главного экрана для окна «Обновить»: смотреть чаще
    static final long TAP_INPUT_MS = 90_000;            // «человек за пультом»: нажимал не дольше полутора минут назад
    static final long MAX_APK = 400L * 1024 * 1024;
    static final long DOWNLOAD_MAX = 45 * 60_000;        // сервер GitHub бывает медленным (05.11: 12 МБ за 5 минут): больше 45 минут бросить до завтра
    // Пока окно установки открыто, система прячет все окна поверх (защита от подмены нажатий), и записку не видно.
    // Поэтому записка сначала, окно через 6 с. Курсор в окне стоит на «Отмена», «Обновить» слева: об этом и записка
    static final long NOTE_FIRST_MS = 6_000;
    static final long TAP_GIVE_UP = 15 * 60_000;        // окно «Обновить» не нажали за 15 минут (ушли, накрыл простой): убрать до завтра

    // updated: приложение обновилось (ByeByeDPI после обновления сам не включается, его включает присмотр «Сторожа»)
    interface Out { void log(String s); void note(String s); void updated(String pkg); }

    static final class App {
        final String pkg, src, pat32, pat64;
        App(String pkg, String src, String pat32, String pat64) {
            this.pkg = pkg; this.src = src; this.pat32 = pat32; this.pat64 = pat64;
        }
    }

    static final class Rel { String tag, url, digest; long size; }

    private final Context c;
    private final SharedPreferences sp;
    private final Out out;
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<String>());
    private final Map<String, long[]> tapOpen = Collections.synchronizedMap(new HashMap<String, long[]>());   // пакет: {сессия, когда открыли окно}
    final Hyde hyde;   // самообновление приложений HYDE (Hyde.java): та же нитка, тот же шаг, установки по одной

    Updates(Context c, Out out) {
        this.c = c;
        this.sp = c.getSharedPreferences("upd", Context.MODE_PRIVATE);
        this.out = out;
        this.hyde = new Hyde(c, this, out);
        // Сессии установки от прошлого запуска службы: их итог сюда уже не придёт. Бросаем, файл поставится заново
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            for (PackageInstaller.SessionInfo s : pi.getMySessions()) {
                try { pi.abandonSession(s.getSessionId()); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    List<App> apps() {
        List<App> l = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("updates.txt"), StandardCharsets.UTF_8))) {
            for (String s; (s = r.readLine()) != null; ) {
                s = s.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                String[] f = s.split("\\s+");
                if (f.length < 3) continue;
                l.add(new App(f[0], f[1], f[2], f.length > 3 ? f[3] : f[2]));
            }
        } catch (IOException e) {
            out.log("Обновления: нет списка updates.txt");
        }
        return l;
    }

    // Шаг по расписанию (в фоновой нитке). Возвращает, через сколько мс снова
    long tick() {
        if (System.currentTimeMillis() - sp.getLong("last", 0) >= CHECK_EVERY) check(false, false, null);
        if (hyde.due()) hyde.check(false);
        installReady(false, false, null);
        long next = tapWaiting() ? TICK_TAP : TICK;
        long hn = hyde.step();   // приложения HYDE после чужих: одна установка за раз, цепочкой каждые 20 с
        return hn > 0 ? Math.min(next, hn) : next;
    }

    // Команда с ПК и кнопка «Проверить сейчас»: проверить выпуск HYDE и сделать шаг установки (условия те же, что по расписанию)
    String hydeNow() {
        String res = hyde.check(true);
        hyde.step();
        return res + " | установка: " + hyde.lastStep();
    }

    // ===== Проверка выпусков и скачивание =====

    // Команда с ПК: проверить и сразу поставить одним замком, чтобы шаг по расписанию не вклинился между ними
    synchronized String checkNow(boolean force, String only) {
        return check(true, force, only) + " | установка: " + installReady(true, force, only);
    }

    // force: скачать и поставить заново даже ту же версию (для проверки с ПК); only: одно приложение или null
    synchronized String check(boolean byHand, boolean force, String only) {
        PackageManager pm = c.getPackageManager();
        int answered = 0;
        List<String> fresh = new ArrayList<>(), got = new ArrayList<>(), bad = new ArrayList<>();
        for (App a : apps()) {
            if (only != null && !only.equals(a.pkg)) continue;
            PackageInfo old = info(a.pkg, 0);
            if (old == null) continue;
            String label = label(a.pkg);
            try {
                Rel r = latest(a, old.applicationInfo);
                answered++;
                sp.edit().putString("latest." + a.pkg, r.tag).remove("err." + a.pkg).apply();
                if (!force && r.tag.equals(sp.getString("tag." + a.pkg, null))) { fresh.add(label); continue; }
                if (!force && r.tag.equals(sp.getString("ready." + a.pkg, null)) && file(a.pkg).exists()) { got.add(label + " " + r.tag); continue; }
                // Стоит ровно файл этого выпуска (контрольные суммы совпали): качать нечего
                if (!force && r.digest != null && r.digest.equalsIgnoreCase(sha256(new File(old.applicationInfo.sourceDir)))) {
                    sp.edit().putString("tag." + a.pkg, r.tag).apply();
                    fresh.add(label);
                    continue;
                }
                File apk = download(a, r);
                String why = verify(a.pkg, apk, force);
                if ("SAME".equals(why)) {
                    apk.delete();
                    sp.edit().putString("tag." + a.pkg, r.tag).remove("ready." + a.pkg).apply();
                    fresh.add(label);
                    continue;
                }
                if (why != null) {
                    apk.delete();
                    // этот выпуск больше не качаем: ошибка видна в состоянии, следующий выпуск проверится заново
                    sp.edit().putString("tag." + a.pkg, r.tag).putString("err." + a.pkg, why).apply();
                    bad.add(label + ": " + why);
                    continue;
                }
                sp.edit().putString("ready." + a.pkg, r.tag).remove("wait." + a.pkg).apply();
                got.add(label + " " + r.tag);
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                sp.edit().putString("err." + a.pkg, m).apply();
                bad.add(label + ": " + m);
                if (m.contains("api.github.com ответил 403")) break;   // лимит запросов GitHub: остальное в следующий раз
            }
        }
        if (answered > 0) sp.edit().putLong("last", System.currentTimeMillis()).apply();
        StringBuilder b = new StringBuilder();
        if (!got.isEmpty()) b.append("скачано: ").append(join(got));
        if (!bad.isEmpty()) b.append(b.length() > 0 ? "; " : "").append("не вышло: ").append(join(bad));
        if (!fresh.isEmpty()) b.append(b.length() > 0 ? "; " : "").append("свежие: ").append(join(fresh));
        String res = b.length() == 0 ? "нечего проверять" : b.toString();
        if (byHand || !got.isEmpty() || !bad.isEmpty()) out.log("Обновления: " + res);
        return res;
    }

    private Rel latest(App a, ApplicationInfo ai) throws Exception {
        boolean wide = ai.nativeLibraryDir != null && (ai.nativeLibraryDir.endsWith("arm64") || ai.nativeLibraryDir.endsWith("x86_64"));
        String pat = wide ? a.pat64 : a.pat32;
        Rel r = new Rel();
        if (a.src.startsWith("gh:")) {
            JSONObject j = new JSONObject(text("https://api.github.com/repos/" + a.src.substring(3) + "/releases/latest"));
            r.tag = j.getString("tag_name");
            JSONArray as = j.getJSONArray("assets");
            Pattern p = Pattern.compile(pat);
            for (int i = 0; i < as.length(); i++) {
                JSONObject x = as.getJSONObject(i);
                if (!p.matcher(x.getString("name")).matches()) continue;
                r.url = x.getString("browser_download_url");
                r.size = x.optLong("size", 0);
                String d = x.optString("digest", "");
                if (d.startsWith("sha256:")) r.digest = d.substring(7);
                return r;
            }
            throw new IOException("в выпуске " + r.tag + " нет файла " + pat);
        }
        if (a.src.startsWith("gl:")) {
            JSONArray arr = new JSONArray(text("https://gitlab.com/api/v4/projects/" + URLEncoder.encode(a.src.substring(3), "UTF-8") + "/releases?per_page=5"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                if (j.optBoolean("upcoming_release", false)) continue;
                r.tag = j.getString("tag_name");
                r.url = pat.replace("{tag}", r.tag);
                return r;
            }
            throw new IOException("на GitLab нет выпусков");
        }
        throw new IOException("непонятный источник " + a.src);
    }

    private File download(App a, Rel r) throws Exception {
        File dir = new File(c.getCacheDir(), "upd");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("нет папки для файла");
        File part = new File(dir, a.pkg + ".part"), apk = file(a.pkg);
        long free = new StatFs(dir.getPath()).getAvailableBytes();
        if (free < Math.max(r.size, 50L * 1024 * 1024) + 300L * 1024 * 1024) throw new IOException("мало места на ТВ");
        HttpURLConnection h = open(r.url);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long n = 0, t0 = System.currentTimeMillis();
        try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(part)) {
            byte[] b = new byte[64 * 1024];
            for (int k; (k = in.read(b)) > 0; ) {
                o.write(b, 0, k);
                md.update(b, 0, k);
                n += k;
                if (n > MAX_APK) throw new IOException("файл больше 400 МБ");
                if (System.currentTimeMillis() - t0 > DOWNLOAD_MAX) throw new IOException("сервер отдаёт слишком медленно");
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        } finally {
            h.disconnect();
        }
        if (r.size > 0 && n != r.size) { part.delete(); throw new IOException("файл скачался не целиком"); }
        if (r.digest != null && !r.digest.equalsIgnoreCase(hex(md.digest()))) {
            part.delete();
            throw new IOException("контрольная сумма не сошлась с выпуском");
        }
        apk.delete();
        if (!part.renameTo(apk)) throw new IOException("файл не переименовался");
        return apk;
    }

    // null: можно ставить; "SAME": не новее стоящего; иначе причина отказа
    private String verify(String pkg, File apk, boolean force) {
        PackageManager pm = c.getPackageManager();
        PackageInfo nw = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (nw == null) return "файл не читается как приложение";
        if (!pkg.equals(nw.packageName)) return "в файле другое приложение (" + nw.packageName + ")";
        PackageInfo old = info(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
        if (old == null) return "приложения больше нет на ТВ";
        long nv = nw.getLongVersionCode(), ov = old.getLongVersionCode();
        if (nv < ov || (nv == ov && !force)) return "SAME";
        // Подпись проверяет и сама система (чужой файл поверх не встанет), здесь проверка раньше, чтобы не трогать установку зря
        if (nw.signingInfo != null && old.signingInfo != null && !sameSigner(old.signingInfo, nw.signingInfo)) return "подпись не та, что у стоящего";
        return null;
    }

    private static boolean sameSigner(SigningInfo old, SigningInfo nw) {
        Signature[] o = old.getApkContentsSigners();
        Signature[] n = nw.hasMultipleSigners() ? nw.getApkContentsSigners() : nw.getSigningCertificateHistory();
        if (o == null || n == null || o.length == 0) return false;
        for (Signature s : o) {
            boolean found = false;
            for (Signature t : n) if (s.equals(t)) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }

    // ===== Установка =====

    synchronized String installReady(boolean byHand, boolean force, String only) {
        List<String> res = new ArrayList<>();
        String top = null;
        boolean topRead = false;
        for (App a : apps()) {
            if (only != null && !only.equals(a.pkg)) continue;
            String tag = sp.getString("ready." + a.pkg, null);
            if (tag == null) continue;
            String label = label(a.pkg);
            File apk = file(a.pkg);
            if (!apk.exists()) { sp.edit().remove("ready." + a.pkg).apply(); continue; }
            long[] open = tapOpen.get(a.pkg);
            if (open != null && System.currentTimeMillis() - open[1] > TAP_GIVE_UP) {
                tapOpen.remove(a.pkg);
                abandon((int) open[0]);
                inFlight.remove(a.pkg);
                sp.edit().putString("wait." + a.pkg, today()).apply();
                out.log("Обновления: окно «Обновить» для " + label + " так и не нажали, спрошу завтра");
                continue;
            }
            if (inFlight.contains(a.pkg)) { res.add(label + ": уже ставится"); continue; }
            if (!byHand && today().equals(sp.getString("wait." + a.pkg, ""))) continue;
            String why = verify(a.pkg, apk, force);
            if ("SAME".equals(why)) {   // уже стоит такая или новее (например, итог прошлой установки не дошёл)
                apk.delete();
                sp.edit().putString("tag." + a.pkg, tag).remove("ready." + a.pkg).apply();
                continue;
            }
            if (why != null) {
                apk.delete();
                sp.edit().putString("tag." + a.pkg, tag).remove("ready." + a.pkg).putString("err." + a.pkg, why).apply();
                res.add(label + ": " + why);
                continue;
            }
            if (!topRead) { top = GuardService.topPackage(); topRead = true; }
            if (a.pkg.equals(top) || playing(a.pkg)) { res.add(label + ": открыто, поставлю позже"); continue; }
            // ByeByeDPI держит туннель для YouTube: установка его закрывает, и видео в другом приложении оборвётся
            if (ByeRules.PKG.equals(a.pkg) && GuardService.mediaBusy()) { res.add(label + ": идёт видео, поставлю позже"); continue; }
            boolean tap = needsTap(a.pkg, apk);
            if (tap && !byHand && !tapMoment(top)) { res.add(label + ": ждёт главного экрана"); continue; }
            try {
                commit(a.pkg, apk, tap);
                res.add(label + (tap ? ": окно «Обновить»" : ": ставится"));
                if (tap) break;   // одно окно за раз
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                sp.edit().putString("wait." + a.pkg, today()).putString("err." + a.pkg, "установка: " + m).apply();
                res.add(label + ": " + m);
                out.log("Обновления: " + label + " не ставится (" + m + "), попробую завтра");
            }
        }
        return res.isEmpty() ? "ставить нечего" : join(res);
    }

    // Установка приложения HYDE (Hyde.step): без нажатия, итог придёт в done с отметкой hyde
    void commitHyde(String pkg, File apk) throws Exception { commit(pkg, apk, false, true); }

    // Идёт ли установка или открыто окно «Обновить»: тогда новую не начинаем
    boolean busy() { return !inFlight.isEmpty() || !tapOpen.isEmpty(); }

    private void commit(String pkg, File apk, boolean tap) throws Exception { commit(pkg, apk, tap, false); }

    private void commit(String pkg, File apk, boolean tap, boolean hyde) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams prm = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        prm.setAppPackageName(pkg);
        prm.setSize(apk.length());
        prm.setInstallReason(PackageManager.INSTALL_REASON_USER);
        // Обновление без нажатий бывает только с Android 12; раньше система всегда спрашивает (UpdDone покажет окно)
        if (android.os.Build.VERSION.SDK_INT >= 31) prm.setRequireUserAction(tap ? PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED
                : PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(prm);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream o = s.openWrite("base.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] b = new byte[64 * 1024];
                for (int k; (k = in.read(b)) > 0; ) o.write(b, 0, k);
                s.fsync(o);
            }
            Intent i = new Intent(c, UpdDone.class).putExtra("pkg", pkg).putExtra("tap", tap).putExtra("hyde", hyde);
            PendingIntent p = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            inFlight.add(pkg);
            s.commit(p.getIntentSender());
        } catch (Exception e) {
            inFlight.remove(pkg);
            try { pi.abandonSession(id); } catch (Exception ignored) { }
            throw e;
        }
    }

    // Итог установки (главная нитка, из UpdDone). Не synchronized: проверка может держать замок минутами, пока качает
    @SuppressWarnings("deprecation")
    void done(Intent i) {
        String pkg = i.getStringExtra("pkg");
        if (pkg == null) return;
        boolean tap = i.getBooleanExtra("tap", false);
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int sid = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (i.getBooleanExtra("hyde", false)) {   // приложение HYDE: окно «Обновить» не открываем, решает Hyde
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) abandon(sid);
            inFlight.remove(pkg);
            hyde.done(pkg, st, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            return;
        }
        String label = label(pkg);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (!tap || confirm == null) {
                // Ждали тихой установки, а система просит нажатие: окно покажем потом, на главном экране
                abandon(sid);
                inFlight.remove(pkg);
                sp.edit().putBoolean("tap." + pkg, true).apply();
                out.log("Обновления: " + label + " без нажатия не ставится, окно покажу на главном экране");
                return;
            }
            final Intent go = confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.note("Вышла новая версия " + label + ". Сейчас откроется окно: стрелкой влево выберите «Обновить» и нажмите OK. Настройки сохранятся");
            new Thread(() -> {
                try { Thread.sleep(NOTE_FIRST_MS); } catch (InterruptedException ignored) { }
                String top = GuardService.topPackage();
                if (top == null || !top.equals(homePackage())) {   // пока читали, ушли с главного экрана: окно в другой раз
                    abandon(sid);
                    inFlight.remove(pkg);
                    out.log("Обновления: ушли с главного экрана, окно " + label + " покажу позже");
                    return;
                }
                try {
                    c.startActivity(go);
                    tapOpen.put(pkg, new long[]{sid, System.currentTimeMillis()});
                } catch (Exception e) {
                    abandon(sid);
                    inFlight.remove(pkg);
                    sp.edit().putString("wait." + pkg, today()).apply();
                    out.log("Обновления: окно установки " + label + " не открылось (" + e.getClass().getSimpleName() + ")");
                }
            }, "guard-upd-tap").start();
            return;   // ждём итога нажатия
        }
        inFlight.remove(pkg);
        tapOpen.remove(pkg);
        if (st == PackageInstaller.STATUS_SUCCESS) {
            String tag = sp.getString("ready." + pkg, null);
            file(pkg).delete();
            SharedPreferences.Editor e = sp.edit().remove("ready." + pkg).remove("err." + pkg).remove("wait." + pkg);
            if (tag != null) e.putString("tag." + pkg, tag);
            e.apply();
            PackageInfo now = info(pkg, 0);
            String v = now == null ? "" : now.versionName;
            out.log("Обновления: " + label + " " + v + " встала");
            if (interactive()) out.note("Обновлено: " + label + " " + v);
            out.updated(pkg);
        } else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
            sp.edit().putString("wait." + pkg, today()).apply();
            out.log("Обновления: " + label + " не обновили (окно закрыли), спрошу завтра");
        } else {
            String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            sp.edit().putString("wait." + pkg, today()).putString("err." + pkg, "установка: " + st + " " + m).apply();
            out.log("Обновления: " + label + " не встала (" + st + ", " + m + "), попробую завтра");
        }
    }

    private void abandon(int sid) {
        if (sid < 0) return;
        try { c.getPackageManager().getPackageInstaller().abandonSession(sid); } catch (Exception ignored) { }
    }

    // Окно «Обновить» нужно, если установщик не мы или приложение собрано под старый Android (уже было: запомнено)
    private boolean needsTap(String pkg, File apk) {
        if (sp.getBoolean("tap." + pkg, false)) return true;
        if (!c.getPackageName().equals(installer(pkg))) return true;
        PackageInfo nw = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        return nw == null || nw.applicationInfo == null || nw.applicationInfo.targetSdkVersion < minSilentTarget();
    }

    // С какой версии Android, под которую собрано приложение, система разрешает тихое обновление (окно сдвигается
    // с каждым Android). Ошиблись: система всё равно попросит нажатие, и done() переведёт приложение на окно
    static int minSilentTarget() {
        int v = Build.VERSION.SDK_INT;
        if (v < 31) return Integer.MAX_VALUE;   // до Android 12 тихих обновлений нет совсем: всегда окно
        return v >= 35 ? 33 : v >= 34 ? 31 : v >= 33 ? 30 : 29;
    }

    private boolean tapWaiting() {
        for (App a : apps()) {
            File apk = file(a.pkg);
            if (sp.getString("ready." + a.pkg, null) != null && apk.exists() && !today().equals(sp.getString("wait." + a.pkg, ""))
                    && needsTap(a.pkg, apk)) return true;
        }
        return false;
    }

    // Подходящий момент для окна: экран включён, сверху главный экран, человек нажимал пульт только что, видео не идёт
    boolean tapMoment(String top) {
        if (!interactive() || top == null || !top.equals(homePackage())) return false;
        long ago = sinceInputMs();
        if (ago < 0 || ago > TAP_INPUT_MS) return false;
        GuardService g = GuardService.running;
        return g == null || g.playingVideo() == null;
    }

    private static final Pattern LAST_USER = Pattern.compile("lastUserActivityTime=\\d+ \\((\\d+) ms ago\\)");

    static long sinceInputMs() {
        Matcher m = LAST_USER.matcher(GuardService.dumpsys("power"));
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    // Ставится ли сейчас обновление этого приложения (присмотр за ByeByeDPI ждёт конца установки)
    boolean installing(String pkg) { return inFlight.contains(pkg); }

    // ===== Состояние для команды с ПК =====

    String status() {
        StringBuilder b = new StringBuilder();
        long last = sp.getLong("last", 0);
        b.append("проверка: ").append(last == 0 ? "ещё не было" : new SimpleDateFormat("d MMM H:mm", Locale.forLanguageTag("ru")).format(new Date(last)));
        for (App a : apps()) {
            PackageInfo pi = info(a.pkg, 0);
            if (pi == null) continue;
            b.append("; ").append(label(a.pkg)).append(' ').append(pi.versionName);
            String latest = sp.getString("latest." + a.pkg, null), tag = sp.getString("tag." + a.pkg, null);
            String ready = sp.getString("ready." + a.pkg, null), err = sp.getString("err." + a.pkg, null);
            if (ready != null) b.append(", скачана ").append(ready);
            else if (latest != null && latest.equals(tag)) b.append(", свежая");
            else if (latest != null) b.append(", на сайте ").append(latest);
            if (!c.getPackageName().equals(installer(a.pkg))) b.append(", ставил не Сторож");
            if (pi.applicationInfo.targetSdkVersion < minSilentTarget()) b.append(", с нажатием");
            if (err != null) b.append(", ошибка: ").append(err);
        }
        return b.toString();
    }

    // ===== Мелочи =====

    private PackageInfo info(String pkg, int flags) {
        try { return c.getPackageManager().getPackageInfo(pkg, flags); } catch (Exception e) { return null; }
    }

    String label(String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    String installer(String pkg) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) return c.getPackageManager().getInstallSourceInfo(pkg).getInstallingPackageName();
            return c.getPackageManager().getInstallerPackageName(pkg);   // Android 9 и 10
        } catch (Exception e) { return null; }
    }

    private String homePackage() {
        ResolveInfo r = c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return r == null || r.activityInfo == null ? "" : r.activityInfo.packageName;
    }

    private boolean interactive() {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isInteractive();
    }

    // Играет ли что-то у этого приложения (видео в фоне, музыка): тогда не трогаем, установка закрывает приложение
    private boolean playing(String pkg) {
        try {
            MediaSessionManager msm = c.getSystemService(MediaSessionManager.class);
            if (msm == null) return false;
            for (MediaController mc : msm.getActiveSessions(new ComponentName(c, Listen.class))) {
                PlaybackState st = mc.getPlaybackState();
                if (pkg.equals(mc.getPackageName()) && st != null && st.getState() == PlaybackState.STATE_PLAYING) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private File file(String pkg) { return new File(new File(c.getCacheDir(), "upd"), pkg + ".apk"); }

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() > 0 ? ", " : "").append(s);
        return b.toString();
    }

    private static HttpURLConnection open(String url) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("адрес не https");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(20_000);
        h.setReadTimeout(60_000);
        h.setInstanceFollowRedirects(true);   // GitHub отдаёт файл переадресацией, тоже на https
        h.setRequestProperty("User-Agent", "tv-guard-updates");
        if (url.startsWith("https://api.github.com/")) h.setRequestProperty("Accept", "application/vnd.github+json");
        int code = h.getResponseCode();
        if (code != 200) {
            h.disconnect();
            throw new IOException(new URL(url).getHost() + " ответил " + code);
        }
        return h;
    }

    private static String text(String url) throws IOException {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int k; (k = in.read(buf)) > 0; ) {
                b.write(buf, 0, k);
                if (b.size() > 4 * 1024 * 1024) throw new IOException("ответ слишком большой");
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            h.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[128 * 1024];
            for (int k; (k = in.read(b)) > 0; ) md.update(b, 0, k);
        }
        return hex(md.digest());
    }

    static String hex(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format(Locale.US, "%02x", x));
        return b.toString();
    }
}
