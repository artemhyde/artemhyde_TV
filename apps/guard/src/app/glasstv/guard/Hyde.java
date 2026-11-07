package app.glasstv.guard;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Самообновление HYDE (выпуск 1.0.1, правила в HydeRules). Раз в сутки вместе с проверкой чужих приложений (Updates.tick)
// «Сторож» читает опись hyde-update.json последнего выпуска на GitHub, качает новые APK тех приложений HYDE, что стоят на ТВ,
// сверяет размер и сумму с описью, а пакет, версию и подпись ключом выпуска с самим файлом. Ставит без нажатий и по одному,
// когда ничего не играет и пультом не пользуются: сначала остальные, потом «Дом», сам «Сторож» последним (установка
// закрывает его процесс, Android присылает MY_PACKAGE_REPLACED, и Boot запускает службу заново).
// Выпуск с новыми правами или настройками (silent_from выше версии «Сторожа») не ставится: на экране «Сторожа» и запиской
// «Вышел HYDE <версия>: для него нужен установщик на компьютере». Приложение с активными правами администратора
// («Ночной режим») не трогаем: переустановка поверх них вешает систему примерно на минуту, его обновит установщик.
// Выключатель «Обновлять HYDE сам» на экране «Сторожа» (hydeAuto в настройках guard, переносится вместе с ними).
// Проверочный выпуск для виртуального ТВ: команда с ПК --es do hyde --es url http://127.0.0.1:<порт>/ (adb reverse);
// от приложений этот адрес не принимается (Cmd), подпись файлов всё равно проверяется ключом выпуска
final class Hyde {
    static final String P_ON = "hydeAuto";
    static final long CHAIN_MS = 20_000;          // идёт установка или цепочка: следующий шаг через 20 с
    static final long WAIT_MS = 2 * 60_000;       // скачано, но ставить пока нельзя (видео, пульт): смотреть раз в 2 минуты
    static final long RETRY_MS = 30 * 60_000;     // проверка не вышла (нет сети): снова не раньше чем через полчаса
    static final long MAX_APK = 200L * 1024 * 1024;

    private final Context c;
    private final SharedPreferences sp, gp;
    private final Updates u;
    private final Updates.Out out;
    private volatile String current;    // что ставится прямо сейчас (для экрана)
    private volatile String lastStep = "";

    Hyde(Context c, Updates u, Updates.Out out) {
        this.c = c;
        this.u = u;
        this.out = out;
        this.sp = c.getSharedPreferences("upd", Context.MODE_PRIVATE);
        this.gp = c.getSharedPreferences("guard", Context.MODE_PRIVATE);
    }

    // ===== Выключатель и сборка =====

    static boolean on(Context c) {
        return c.getSharedPreferences("guard", Context.MODE_PRIVATE).getBoolean(P_ON, c.getResources().getBoolean(R.bool.hyde_auto_default));
    }

    void setOn(boolean on, String who) {
        if (on == on(c)) return;
        gp.edit().putBoolean(P_ON, on).apply();
        if (on) sp.edit().remove("hyde.last").remove("hyde.tried").apply();   // включили: проверить на ближайшем шаге
        else current = null;
        out.log("Обновления HYDE " + (on ? "включены" : "выключены") + " (" + who + ")");
    }

    private static Boolean release;

    // Сборка выпуска: сам «Сторож» подписан ключом выпуска. Сборка из исходников своим ключом обновлений выпуска не примет
    static boolean releaseBuild(Context c) {
        if (release == null) release = HydeRules.RELEASE_CERT.equals(installedCert(c.getPackageManager(), c.getPackageName()));
        return release;
    }

    // Проверочный адрес выпуска (только командой с ПК, Cmd): https или сам ТВ через adb reverse. Пустой или null: убрать
    String setTestBase(String url) {
        if (url == null || url.trim().isEmpty()) {
            sp.edit().remove("hyde.url").remove("hyde.last").remove("hyde.tried").remove("hyde.json").apply();
            out.log("Обновления HYDE: проверочный адрес убран, снова выпуск на GitHub");
            return "проверочный адрес убран";
        }
        if (!HydeRules.testBaseOk(url)) return "адрес не подходит: только https://… или http://127.0.0.1:<порт>/ с косой чертой в конце";
        sp.edit().putString("hyde.url", url).remove("hyde.last").remove("hyde.tried").remove("hyde.json").apply();
        out.log("Обновления HYDE: проверочный адрес " + url);
        return "проверочный адрес " + url;
    }

    private String testBase() {
        String s = sp.getString("hyde.url", null);
        return HydeRules.testBaseOk(s) ? s : null;
    }

    // Пора ли проверять выпуск (шаг по расписанию)
    boolean due() {
        if (!releaseBuild(c) || !on(c)) return false;
        long now = System.currentTimeMillis();
        return now - sp.getLong("hyde.last", 0) >= Updates.CHECK_EVERY && now - sp.getLong("hyde.tried", 0) >= RETRY_MS;
    }

    // ===== Проверка выпуска и скачивание =====

    synchronized String check(boolean byHand) {
        if (!releaseBuild(c)) return "сборка не из выпуска HYDE (подписана своим ключом): её обновляет только установщик";
        if (!on(c)) return said(byHand, "выключено (включить: кнопка «Обновлять HYDE сам» на экране «Сторожа»)");
        String base = testBase();
        String txt;
        try {
            txt = fetch(HydeRules.jsonUrl(base));
        } catch (Exception e) {
            return checkFailed(msg(e), byHand);
        }
        long now = System.currentTimeMillis();
        if (txt == null) {   // 404: в последнем выпуске описи нет (так у 1.0.0), обновлять нечего
            sp.edit().putLong("hyde.last", now).remove("hyde.json").remove("hyde.need").remove("hyde.err").apply();
            clean(new HashSet<String>());
            return said(byHand, "в последнем выпуске нет hyde-update.json, обновлять нечего");
        }
        HydeRules.Release r;
        try {
            r = parse(txt);
        } catch (Exception e) {
            return checkFailed("опись не читается (" + msg(e) + ")", byHand);
        }
        String bad = HydeRules.problem(r);
        if (bad != null) return checkFailed("опись: " + bad, byHand);
        sp.edit().putLong("hyde.last", now).putString("hyde.json", txt).putString("hyde.base", base == null ? "" : base)
                .remove("hyde.err").apply();
        Map<String, HydeRules.Have> have = have(r);
        long my = myCode();
        String v = HydeRules.verdict(r, my, have);
        if (HydeRules.FRESH.equals(v)) {
            sp.edit().remove("hyde.need").remove("hyde.needWhy").remove("hyde.skipped").apply();
            clean(new HashSet<String>());
            return said(byHand, "свежие, последний выпуск " + (r.version == null ? "нового формата" : r.version));
        }
        if (HydeRules.INSTALLER.equals(v)) {
            String why = HydeRules.installerWhy(r, my), ver = r.version == null ? "новый выпуск" : r.version;
            boolean first = !ver.equals(sp.getString("hyde.need", null));
            sp.edit().putString("hyde.need", ver).putString("hyde.needWhy", why).remove("hyde.chain").remove("hyde.todo")
                    .remove("hyde.skipped").remove("hyde.wait").apply();
            clean(new HashSet<String>());
            String res = "вышел " + ver + ", для него нужен установщик на компьютере (" + why + ")";
            if (first || byHand) out.log("Обновления HYDE: " + res);
            return res;
        }
        sp.edit().remove("hyde.need").remove("hyde.needWhy").apply();
        List<HydeRules.Step> plan = HydeRules.plan(r, have, c.getPackageName());
        Set<String> keep = new HashSet<>();
        List<String> got = new ArrayList<>(), failed = new ArrayList<>(), skipped = new ArrayList<>(), skippedShort = new ArrayList<>();
        for (HydeRules.Step s : plan) {
            String label = u.label(s.app.pkg);
            if (s.skip != null) { skipped.add(label + " (" + s.skip + ")"); skippedShort.add(label); continue; }
            keep.add(s.app.pkg);
            if (ready(s.app)) continue;
            if (failed(s.app) != null && !byHand) continue;
            try {
                download(base, r, s.app);
                String why = verifyFile(s.app);
                if (why != null) {
                    fail(s.app, why, false);
                    failed.add(label + ": " + why);
                    continue;
                }
                sp.edit().putLong("hyde.ready." + s.app.pkg, s.app.code).remove("hyde.fail." + s.app.pkg).apply();
                got.add(label);
            } catch (Exception e) {
                String m = msg(e);
                fail(s.app, m, false);
                failed.add(label + ": " + m);
            }
        }
        clean(keep);
        SharedPreferences.Editor ed = sp.edit();
        if (skippedShort.isEmpty()) ed.remove("hyde.skipped"); else ed.putString("hyde.skipped", join(skippedShort));
        ed.apply();
        StringBuilder res = new StringBuilder("выпуск " + r.version);
        if (!got.isEmpty()) res.append(", скачано: ").append(join(got));
        if (!failed.isEmpty()) res.append("; не вышло: ").append(join(failed));
        if (!skipped.isEmpty()) res.append("; обновит установщик: ").append(join(skipped));
        if (got.isEmpty() && failed.isEmpty() && skipped.isEmpty()) res.append(", всё скачано");
        if (byHand || !got.isEmpty() || !failed.isEmpty()) out.log("Обновления HYDE: " + res);
        return res.toString();
    }

    private String said(boolean byHand, String res) {
        if (byHand) out.log("Обновления HYDE: " + res);
        return res;
    }

    private String checkFailed(String m, boolean byHand) {
        String prev = sp.getString("hyde.err", null);
        sp.edit().putString("hyde.err", m).putLong("hyde.tried", System.currentTimeMillis()).apply();
        if (byHand || !m.equals(prev)) out.log("Обновления HYDE: проверка не вышла (" + m + ")");
        return "проверка не вышла: " + m;
    }

    static HydeRules.Release parse(String txt) throws Exception {
        JSONObject j = new JSONObject(txt);
        HydeRules.Release r = new HydeRules.Release();
        r.schema = j.optInt("schema", HydeRules.SCHEMA);
        r.version = j.optString("version", null);
        r.code = j.optLong("version_code", 0);
        r.signer = j.optString("signer_cert_sha256", null);
        r.why = j.optString("why", "");
        // silent_from: с какой версии выпуск ставится без установщика; старое поле needs_installer значит «только с этой»
        r.silentFrom = j.has("silent_from") ? j.optLong("silent_from", Long.MAX_VALUE)
                : j.optBoolean("needs_installer", false) ? r.code : 0;
        JSONArray as = j.optJSONArray("apps");
        if (as != null) {
            for (int i = 0; i < as.length(); i++) {
                JSONObject x = as.getJSONObject(i);
                HydeRules.App a = new HydeRules.App();
                a.pkg = x.optString("package", null);
                a.code = x.optLong("version_code", 0);
                a.file = x.optString("file", null);
                a.size = x.optLong("size", 0);
                a.sha256 = x.optString("sha256", null);
                r.apps.add(a);
            }
        }
        return r;
    }

    // Опись из последней проверки (того же адреса), или null
    private HydeRules.Release cached() {
        String txt = sp.getString("hyde.json", null);
        String base = testBase();
        if (txt == null || !sp.getString("hyde.base", "").equals(base == null ? "" : base)) return null;
        try {
            HydeRules.Release r = parse(txt);
            return HydeRules.problem(r) == null && r.schema <= HydeRules.SCHEMA ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void download(String base, HydeRules.Release r, HydeRules.App a) throws Exception {
        File dir = dir();
        File part = new File(dir, "hyde-" + a.pkg + ".part"), apk = file(a.pkg);
        if (a.size > MAX_APK) throw new IOException("файл больше 200 МБ");
        if (new StatFs(dir.getPath()).getAvailableBytes() < a.size + 300L * 1024 * 1024) throw new IOException("мало места на ТВ");
        HttpURLConnection h = open(HydeRules.apkUrl(base, r, a));
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long n = 0, t0 = System.currentTimeMillis();
        try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(part)) {
            byte[] b = new byte[64 * 1024];
            for (int k; (k = in.read(b)) > 0; ) {
                o.write(b, 0, k);
                md.update(b, 0, k);
                n += k;
                if (n > a.size) throw new IOException("файл больше, чем в описи");
                if (System.currentTimeMillis() - t0 > Updates.DOWNLOAD_MAX) throw new IOException("сервер отдаёт слишком медленно");
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        } finally {
            h.disconnect();
        }
        if (n != a.size) { part.delete(); throw new IOException("файл скачался не целиком"); }
        if (!a.sha256.equalsIgnoreCase(Updates.hex(md.digest()))) { part.delete(); throw new IOException("сумма файла не сошлась с описью"); }
        apk.delete();
        if (!part.renameTo(apk)) throw new IOException("файл не переименовался");
    }

    // Файл тот, что в описи: пакет, версия и подпись ключом выпуска. null: да, иначе что не так
    private String verifyFile(HydeRules.App a) {
        PackageInfo nw = c.getPackageManager().getPackageArchiveInfo(file(a.pkg).getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (nw == null) return "файл не читается как приложение";
        if (!a.pkg.equals(nw.packageName)) return "в файле другое приложение (" + nw.packageName + ")";
        if (nw.getLongVersionCode() != a.code) return "в файле версия " + nw.getLongVersionCode() + ", а в описи " + a.code;
        if (!HydeRules.RELEASE_CERT.equals(cert(nw.signingInfo))) return "файл подписан не ключом выпуска";
        return null;
    }

    // ===== Установка =====

    // Шаг установки (нитка обновлений, Updates.tick). Возвращает, через сколько мс снова (-1: ставить нечего)
    synchronized long step() {
        if (!u.busy()) current = null;   // пока идёт установка, на экране остаётся, что именно ставится
        if (!releaseBuild(c) || !on(c)) { lastStep = "выключено"; return -1; }
        if (sp.getString("hyde.need", null) != null) {
            // Установщик уже прогнали (версии на ТВ догнали выпуск): записка и строка больше не нужны
            HydeRules.Release rr = cached();
            if (rr != null && !HydeRules.INSTALLER.equals(HydeRules.verdict(rr, myCode(), have(rr))))
                sp.edit().remove("hyde.need").remove("hyde.needWhy").apply();
        }
        if (sp.getString("hyde.need", null) != null) {
            lastStep = "нужен установщик на компьютере";
            return noteInstaller() ? -1 : WAIT_MS;
        }
        HydeRules.Release r = cached();
        if (r == null) { lastStep = "описи выпуска нет"; return -1; }
        String self = c.getPackageName();
        List<HydeRules.Step> plan = HydeRules.plan(r, have(r), self);
        Set<String> finished = new HashSet<>();
        List<String> todo = new ArrayList<>();
        for (HydeRules.Step s : plan) {
            if (s.skip != null) continue;
            if (failed(s.app) != null || !ready(s.app)) finished.add(s.app.pkg);
            else todo.add(s.app.pkg);
        }
        String chain = sp.getString("hyde.chain", null);
        if (todo.isEmpty()) {
            if (chain != null) finishChain(r);
            lastStep = "ставить нечего";
            return -1;
        }
        if (!r.version.equals(chain)) {   // начинаем выпуск: запомнить, что ставим, чтобы в конце сказать, что встало
            List<String> all = new ArrayList<>();
            for (HydeRules.Step s : plan) if (s.skip == null) all.add(s.app.pkg);
            sp.edit().putString("hyde.chain", r.version).putString("hyde.todo", String.join(",", all)).remove("hyde.wait").apply();
        }
        if (u.busy()) { lastStep = "идёт другая установка"; return CHAIN_MS; }
        final boolean busy = GuardService.mediaBusy();
        final boolean screen = interactive();
        final long since = screen ? Updates.sinceInputMs() : -1;
        final String top = screen ? GuardService.topPackage() : null;
        String[] wait = new String[1];
        HydeRules.Step s = HydeRules.pick(plan, finished, st -> HydeRules.moment(busy, screen, since, top, st.app.pkg, st.phase == 1), wait);
        if (s == null) {
            sp.edit().putString("hyde.wait", wait[0]).apply();
            lastStep = wait[0] == null ? "жду" : wait[0];
            return WAIT_MS;
        }
        String label = u.label(s.app.pkg);
        String bad = verifyFile(s.app);
        if (bad != null) {
            fail(s.app, bad, false);
            out.log("Обновления HYDE: " + label + " не ставлю (" + bad + ")");
            lastStep = label + ": " + bad;
            return CHAIN_MS;
        }
        sp.edit().remove("hyde.wait").apply();
        if (s.phase == 2) out.log("Обновления HYDE: ставлю сам «Сторож» " + r.version + ", служба перезапустится");
        try {
            current = label;
            u.commitHyde(s.app.pkg, file(s.app.pkg));
            lastStep = "ставлю " + label;
        } catch (Exception e) {
            current = null;
            fail(s.app, "установка: " + msg(e), false);
            out.log("Обновления HYDE: " + label + " не ставится (" + msg(e) + "), попробую завтра");
            lastStep = label + ": " + msg(e);
        }
        return CHAIN_MS;
    }

    // Итог установки от системы (главная нитка, из Updates.done)
    void done(String pkg, int st, String msg) {
        current = null;
        HydeRules.Release r = cached();
        HydeRules.App a = r == null ? null : find(r, pkg);
        String label = u.label(pkg);
        if (st == PackageInstaller.STATUS_SUCCESS) {
            file(pkg).delete();
            sp.edit().remove("hyde.ready." + pkg).remove("hyde.fail." + pkg).apply();
            PackageInfo pi = info(pkg);
            out.log("Обновления HYDE: " + label + " " + (pi == null ? "" : pi.versionName) + " встал");
            if (HydeRules.HOME.equals(pkg)) new Handler(Looper.getMainLooper()).postDelayed(this::homeAfter, 4_000);
            return;
        }
        if (a == null) return;
        boolean never = st == PackageInstaller.STATUS_PENDING_USER_ACTION;   // тихо Android не ставит: ждать нечего
        String why = never ? "без нажатия не ставится, обновит установщик на компьютере"
                : "установка: " + st + (msg == null ? "" : " " + msg);
        fail(a, why, never);
        out.log("Обновления HYDE: " + label + " не встал (" + why + ")" + (never ? "" : ", попробую завтра"));
    }

    // После «Дома»: главный экран снова он? Роль «Домой» при обновлении сохраняется, проверяем на всякий случай
    private void homeAfter() {
        GuardService g = GuardService.running;
        if (g == null) return;
        if (!g.homeIsOurs()) out.log("Обновления HYDE: после обновления «Дома» главный экран не он, нужен установщик или «Настройки «Дома»»");
    }

    // Выпуск закончен: что встало, что нет (сам «Сторож» узнаёт о себе уже после перезапуска, по версии)
    private void finishChain(HydeRules.Release r) {
        List<String> ok = new ArrayList<>(), no = new ArrayList<>();
        for (String pkg : sp.getString("hyde.todo", "").split(",")) {
            if (pkg.isEmpty()) continue;
            HydeRules.App a = find(r, pkg);
            PackageInfo pi = info(pkg);
            if (a != null && pi != null && pi.getLongVersionCode() >= a.code) ok.add(u.label(pkg));
            else no.add(u.label(pkg) + (a == null || failed(a) == null ? "" : " (" + failed(a) + ")"));
        }
        out.log("Обновления HYDE: выпуск " + sp.getString("hyde.chain", r.version) + (ok.isEmpty() ? "" : ", обновлены: " + join(ok))
                + (no.isEmpty() ? "" : "; не обновлены: " + join(no)));
        sp.edit().remove("hyde.chain").remove("hyde.todo").remove("hyde.wait").apply();
    }

    // Выпуску нужен установщик: один раз на выпуск записка, когда человек у главного экрана. true: сказано (или некому)
    private boolean noteInstaller() {
        String ver = sp.getString("hyde.need", null);
        if (ver == null || ver.equals(sp.getString("hyde.noted", null))) return true;
        if (!u.tapMoment(GuardService.topPackage())) return false;
        sp.edit().putString("hyde.noted", ver).apply();
        out.note("Вышел HYDE " + ver + ": для него нужен установщик на компьютере");
        return true;
    }

    // Скачано и ждёт установки (экран погас: хороший момент) или ждёт записки
    boolean pending() {
        if (!releaseBuild(c) || !on(c)) return false;
        String need = sp.getString("hyde.need", null);
        if (need != null) return !need.equals(sp.getString("hyde.noted", null));
        return sp.getString("hyde.chain", null) != null;
    }

    // ===== Состояние =====

    // Одна строка на экране «Сторожа»
    String brief() {
        if (!releaseBuild(c)) return "только у сборки выпуска";
        long last = sp.getLong("hyde.last", 0);
        String s = HydeRules.brief(on(c), last == 0 ? null : fmt(last), sp.getString("hyde.need", null), sp.getString("hyde.chain", null),
                current, sp.getString("hyde.wait", null), sp.getString("hyde.err", null), sp.getString("hyde.skipped", null));
        return testBase() != null ? s + " · проверочный адрес" : s;
    }

    // Строка требует внимания (оранжевым): нужен установщик, ошибка, кого-то обновит только установщик
    boolean attention() {
        return on(c) && releaseBuild(c) && (sp.getString("hyde.need", null) != null || sp.getString("hyde.chain", null) == null
                && (sp.getString("hyde.err", null) != null || sp.getString("hyde.skipped", null) != null));
    }

    boolean fresh() {
        return on(c) && releaseBuild(c) && sp.getLong("hyde.last", 0) > 0 && sp.getString("hyde.need", null) == null
                && sp.getString("hyde.chain", null) == null && sp.getString("hyde.err", null) == null && sp.getString("hyde.skipped", null) == null;
    }

    // Подробно: для команды с ПК и отчёта
    String status() {
        if (!releaseBuild(c)) return "сборка не из выпуска HYDE (подписана своим ключом): самообновления нет, обновляет установщик";
        StringBuilder b = new StringBuilder(on(c) ? "включено" : "выключено");
        long last = sp.getLong("hyde.last", 0);
        b.append("; проверка: ").append(last == 0 ? "ещё не было" : fmt(last));
        PackageInfo me = info(c.getPackageName());
        b.append("; на ТВ ").append(me == null ? "?" : me.versionName);
        HydeRules.Release r = cached();
        if (r != null) b.append("; последний выпуск ").append(r.version);
        String need = sp.getString("hyde.need", null);
        if (need != null) {
            b.append("; вышел ").append(need).append(", нужен установщик (").append(sp.getString("hyde.needWhy", "")).append(')');
            if (r != null && r.why != null && !r.why.isEmpty()) b.append(", в описи: ").append(r.why);
        }
        String chain = sp.getString("hyde.chain", null);
        if (chain != null) {
            b.append("; ставлю ").append(chain);
            if (current != null) b.append(", сейчас ").append(current);
            String w = sp.getString("hyde.wait", null);
            if (w != null) b.append(", ").append(w);
            List<String> ready = new ArrayList<>();
            if (r != null) for (HydeRules.App a : r.apps) if (ready(a)) ready.add(u.label(a.pkg));
            if (!ready.isEmpty()) b.append(", скачаны: ").append(join(ready));
        }
        String sk = sp.getString("hyde.skipped", null);
        if (sk != null) b.append("; обновит установщик: ").append(sk);
        String err = sp.getString("hyde.err", null);
        if (err != null) b.append("; ошибка: ").append(err);
        if (!lastStep.isEmpty()) b.append("; шаг: ").append(lastStep);
        String tb = testBase();
        if (tb != null) b.append("; проверочный адрес ").append(tb);
        return b.toString();
    }

    String lastStep() { return lastStep; }

    // ===== Мелочи =====

    private Map<String, HydeRules.Have> have(HydeRules.Release r) {
        PackageManager pm = c.getPackageManager();
        Set<String> admins = new HashSet<>();
        try {
            List<ComponentName> l = c.getSystemService(DevicePolicyManager.class).getActiveAdmins();
            if (l != null) for (ComponentName cn : l) admins.add(cn.getPackageName());
        } catch (Exception ignored) { }
        Map<String, HydeRules.Have> m = new HashMap<>();
        for (HydeRules.App a : r.apps) {
            PackageInfo pi;
            try { pi = pm.getPackageInfo(a.pkg, PackageManager.GET_SIGNING_CERTIFICATES); } catch (Exception e) { continue; }
            HydeRules.Have h = new HydeRules.Have();
            h.code = pi.getLongVersionCode();
            h.cert = cert(pi.signingInfo);
            h.installer = u.installer(a.pkg);
            h.admin = admins.contains(a.pkg);
            m.put(a.pkg, h);
        }
        return m;
    }

    private long myCode() {
        PackageInfo pi = info(c.getPackageName());
        return pi == null ? 0 : pi.getLongVersionCode();
    }

    private PackageInfo info(String pkg) {
        try { return c.getPackageManager().getPackageInfo(pkg, 0); } catch (Exception e) { return null; }
    }

    private static HydeRules.App find(HydeRules.Release r, String pkg) {
        for (HydeRules.App a : r.apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    // SHA-256 сертификата подписи (один подписант), или null
    static String cert(SigningInfo si) {
        if (si == null || si.hasMultipleSigners()) return null;
        Signature[] s = si.getApkContentsSigners();
        if (s == null || s.length != 1) return null;
        try {
            return Updates.hex(MessageDigest.getInstance("SHA-256").digest(s[0].toByteArray()));
        } catch (Exception e) {
            return null;
        }
    }

    static String installedCert(PackageManager pm, String pkg) {
        try { return cert(pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo); } catch (Exception e) { return null; }
    }

    private boolean ready(HydeRules.App a) {
        return sp.getLong("hyde.ready." + a.pkg, 0) == a.code && file(a.pkg).length() == a.size;
    }

    // Не вышло: этот выпуск приложения снова пробуем завтра (forever: больше не пробуем, его обновит установщик)
    private void fail(HydeRules.App a, String why, boolean forever) {
        file(a.pkg).delete();
        sp.edit().remove("hyde.ready." + a.pkg).putString("hyde.fail." + a.pkg, (forever ? "*" : today()) + "|" + a.code + "|" + why)
                .putString("hyde.err", u.label(a.pkg) + ": " + why).apply();
    }

    // Почему этот выпуск приложения сейчас не ставим (не вышло сегодня или не выйдет вовсе), или null
    private String failed(HydeRules.App a) {
        String f = sp.getString("hyde.fail." + a.pkg, null);
        if (f == null) return null;
        String[] p = f.split("\\|", 3);
        if (p.length < 3 || !String.valueOf(a.code).equals(p[1])) return null;
        return "*".equals(p[0]) || today().equals(p[0]) ? p[2] : null;
    }

    // Убрать скачанное, что больше не нужно (кроме keep)
    private void clean(Set<String> keep) {
        File[] fs = dir().listFiles();
        if (fs == null) return;
        SharedPreferences.Editor ed = sp.edit();
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith("hyde-")) continue;
            String pkg = n.substring(5).replaceAll("\\.(apk|part)$", "");
            if (keep.contains(pkg) && n.endsWith(".apk")) continue;
            f.delete();
            ed.remove("hyde.ready." + pkg);
        }
        ed.apply();
    }

    private File dir() {
        File d = new File(c.getCacheDir(), "upd");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private File file(String pkg) { return new File(dir(), "hyde-" + pkg + ".apk"); }

    private boolean interactive() {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isInteractive();
    }

    private static String fmt(long t) { return new SimpleDateFormat("d MMM, H:mm", Locale.forLanguageTag("ru")).format(new Date(t)); }

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    private static String msg(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() > 0 ? ", " : "").append(s);
        return b.toString();
    }

    // Только https и проверочный адрес на самом ТВ (обычный http разрешён в network_security_config только к 127.0.0.1)
    private static HttpURLConnection open(String url) throws IOException {
        if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:"))
            throw new IOException("адрес не https");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(20_000);
        h.setReadTimeout(60_000);
        h.setInstanceFollowRedirects(true);   // GitHub отдаёт файлы выпуска переадресацией, тоже на https
        h.setRequestProperty("User-Agent", "tv-guard-updates");
        int code = h.getResponseCode();
        if (code != 200) {
            h.disconnect();
            throw new HttpCode(code, new URL(url).getHost() + " ответил " + code);
        }
        return h;
    }

    private static final class HttpCode extends IOException {
        final int code;
        HttpCode(int code, String m) { super(m); this.code = code; }
    }

    // Текст описи или null, если её в выпуске нет (404)
    private static String fetch(String url) throws IOException {
        HttpURLConnection h;
        try {
            h = open(url);
        } catch (HttpCode e) {
            if (e.code == 404) return null;
            throw e;
        }
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int k; (k = in.read(buf)) > 0; ) {
                b.write(buf, 0, k);
                if (b.size() > 1024 * 1024) throw new IOException("опись слишком большая");
            }
            return new String(b.toByteArray(), "UTF-8");
        } finally {
            h.disconnect();
        }
    }
}
