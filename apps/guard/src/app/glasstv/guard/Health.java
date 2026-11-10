package app.glasstv.guard;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.List;

// Присмотр за кнопками пульта, главным экраном и пакетами, отключёнными установщиком (выпуск 1.2.0, задача 10.11).
// 10.11 на ТВ TCL после переустановок «Дома» прошивка (TclAppBoot) не давала системе поднять службу Key Mapper
// («forbid bind service»: у Key Mapper был AUTO_START: ignore), а «Сторож» только писал об этом в журнал. Теперь так.
// Перезапуск службы по порядку ServiceRepair из Clean Your TV (Gathin, лицензия MIT, github.com/Gathin23/cleanyourtv):
// список со службой, без неё и снова со службой, по секунде между записями, и перед каждой записью сверка, что список
// за паузу никто не поменял. Пока службы нет в списке, Android снимает с неё пометку «упала» (упавшую он больше не
// привязывает), а запись «без службы» в список, где её и так нет, Android не замечает: поэтому первой идёт «со службой».
// Не поднялась: записка «запустите установщик» на главном экране, не чаще раза в сутки (AUTO_START из приложения не
// прочитать и не выдать, нужен MANAGE_APPOPS: лечит только установщик). Главный экран не HYDE и отключённые установщиком
// пакеты, которые обновление прошивки включило обратно, идут в ту же записку. Состояние уходит в ежедневный отчёт (Ping)
final class Health {
    interface Out {
        void log(String s);
        void note(String s);
        void soon(long ms);   // шаг обновлений через ms: там записка ждёт человека у главного экрана
    }

    interface Done { void done(String result); }

    static final String K_SHOW = "keys", K_BROKEN = "keys.broken", K_WHY = "keys.why", K_REPAIRED = "keys.repairedAt",
            K_NOTE = "keys.noteAt", H_LOST = "home.lostAt", H_NOTE = "home.noteAt",
            D_LIST = "disabled.list", D_FW = "disabled.fw", D_NOTE = "disabled.noteFw";
    static final long STEP_MS = 1_000, VERIFY_MS = 3_000, VERIFY_AGAIN_MS = 5_000, PACKAGE_DELAY = 5_000, NOTE_SOON = 5_000;
    private static final String LIST = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;

    private final Context c;
    private final Handler h;
    private final SharedPreferences p;
    private final Out out;
    private boolean busy;                                   // идёт перезапуск Key Mapper (только главная нитка)
    private final List<Done> waiters = new ArrayList<>();   // кто ждёт его итога (команды с ПК)
    private long lastTry = -1;                              // когда был прошлый перезапуск (elapsedRealtime)

    Health(Context c, Handler h, Out out) {
        this.c = c;
        this.h = h;
        this.out = out;
        this.p = c.getSharedPreferences("guard", Context.MODE_PRIVATE);
    }

    // Пакет поставили, обновили или удалили: тогда система заново привязывает службы спецвозможностей (так кнопки и
    // пропали 10.11). Через 5 с после последнего такого события проверить кнопки и главный экран
    private final Runnable afterPackages = () -> {
        keys(HealthRules.PACKAGE);
        home(HealthRules.PACKAGE);
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context x, Intent i) {
            h.removeCallbacks(afterPackages);
            h.postDelayed(afterPackages, PACKAGE_DELAY);
        }
    };

    void start() {
        IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(packages, f, Context.RECEIVER_NOT_EXPORTED);
        else c.registerReceiver(packages, f);
    }

    void stop() {
        h.removeCallbacks(afterPackages);
        try { c.unregisterReceiver(packages); } catch (Exception ignored) { }
    }

    // ===== Кнопки пульта: служба Key Mapper =====

    void keys(String why) { keys(why, null); }

    // Проверить и при надобности перезапустить (главная нитка). done: итог для команды с ПК или null
    void keys(String why, Done done) {
        if (busy) { if (done != null) waiters.add(done); return; }   // перезапуск уже идёт: его итог и будет ответом
        if (alive(c)) { fine(); if (done != null) done.done("работает"); return; }
        if (!installed(c, HealthRules.KM_PKG)) {
            // Key Mapper не стоит (на ТВ без своих кнопок его не ставят): не вписывать несуществующую службу в настройки
            p.edit().putString(K_SHOW, "не установлен").remove(K_BROKEN).remove(K_WHY).apply();
            if (done != null) done.done("не установлен");
            return;
        }
        long since = lastTry < 0 ? -1 : SystemClock.elapsedRealtime() - lastTry;
        if (!HealthRules.shouldRepair(why, p.getBoolean(K_BROKEN, false), since)) {
            if (done != null) done.done(p.getString(K_SHOW, "не поднялся"));
            return;
        }
        busy = true;
        if (done != null) waiters.add(done);
        lastTry = SystemClock.elapsedRealtime();
        ContentResolver cr = c.getContentResolver();
        write(cr, HealthRules.steps(Settings.Secure.getString(cr, LIST)), 0, why);
    }

    // Шаг перезапуска: записать список, если в нём всё ещё то, что оставил прошлый шаг, и через секунду следующий
    private void write(ContentResolver cr, String[][] steps, int i, String why) {
        try {
            if (!HealthRules.same(Settings.Secure.getString(cr, LIST), steps[i][0]) || !Settings.Secure.putString(cr, LIST, steps[i][1])) {
                changed(why);
                return;
            }
            if (i + 1 < steps.length) {
                h.postDelayed(() -> write(cr, steps, i + 1, why), STEP_MS);
                return;
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);   // прошивка TCL после отказа пишет сюда 0
        } catch (SecurityException e) {
            p.edit().putString(K_SHOW, "нет прав перезапустить").putBoolean(K_BROKEN, true).putString(K_WHY, HealthRules.WHY_RIGHTS).apply();
            out.log("Key Mapper упал, а прав перезапустить его нет (" + why + "): нужен установщик");
            out.soon(NOTE_SOON);
            finish("нет прав перезапустить (WRITE_SECURE_SETTINGS)");
            return;
        }
        h.postDelayed(() -> verify(why, false), VERIFY_MS);
    }

    // Поднялась ли служба. На холодном ТВ процесс Key Mapper стартует не сразу: второй взгляд ещё через 5 с
    private void verify(String why, boolean again) {
        if (alive(c)) {
            p.edit().putString(K_SHOW, "перезапущен в " + GuardService.now()).putLong(K_REPAIRED, System.currentTimeMillis())
                    .remove(K_BROKEN).remove(K_WHY).remove(K_NOTE).apply();
            out.log("Key Mapper упал и перезапущен (" + why + ")");
            finish("перезапущен");
            return;
        }
        if (!again) { h.postDelayed(() -> verify(why, true), VERIFY_AGAIN_MS); return; }
        p.edit().putString(K_SHOW, "не поднялся").putBoolean(K_BROKEN, true).putString(K_WHY, HealthRules.WHY_FIRMWARE).apply();
        out.log("Key Mapper не поднялся (" + why + "): прошивка не даёт запустить его службу, нужен установщик");
        out.soon(NOTE_SOON);
        finish("не поднялся");
    }

    // Список спецвозможностей поменяли во время перезапуска (человек в настройках, установщик): чужое не перетираем,
    // через 3 с смотрим, что вышло. Неудачей это не считается: следующая попытка по обычному расписанию
    private void changed(String why) {
        h.postDelayed(() -> {
            boolean ok = alive(c);
            if (ok) fine();
            out.log("Key Mapper: список спецвозможностей поменяли во время перезапуска (" + why + "), " + (ok ? "служба работает" : "попробую позже"));
            finish(ok ? "работает" : "список спецвозможностей поменяли во время перезапуска, попробую позже");
        }, VERIFY_MS);
    }

    private void finish(String result) {
        busy = false;
        List<Done> w = new ArrayList<>(waiters);
        waiters.clear();
        for (Done d : w) d.done(result);
    }

    // Служба работает: снять пометку о поломке (и сказать в журнал, если она была). Лимит записки «раз в сутки» тоже
    // снимается: следующий сбой это новый случай, о нём надо сказать (10.11, после живой проверки на ТВ)
    private void fine() {
        if (p.getBoolean(K_BROKEN, false)) out.log("Кнопки пульта снова работают");
        p.edit().putString(K_SHOW, "работает").remove(K_BROKEN).remove(K_WHY).remove(K_NOTE).apply();
    }

    // Служба Key Mapper привязана системой (список включённых служб у AccessibilityManager только из привязанных)
    static boolean alive(Context c) {
        AccessibilityManager am = c.getSystemService(AccessibilityManager.class);
        if (am == null) return false;
        ComponentName want = ComponentName.unflattenFromString(HealthRules.KM_SERVICE);
        for (AccessibilityServiceInfo s : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (want.equals(ComponentName.unflattenFromString(s.getId()))) return true;
        }
        return false;
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ===== Главный экран =====

    // Чей сейчас главный экран по умолчанию ("android": не выбран, Android спросит)
    static String homeNow(Context c) {
        ResolveInfo r = c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return r == null || r.activityInfo == null ? "" : r.activityInfo.packageName;
    }

    // Проверка главного экрана (любая нитка): если не HYDE, запомнить, с какого времени
    void home(String why) {
        if (!installed(c, HydeRules.HOME)) { p.edit().remove(H_LOST).apply(); return; }   // «Дома» нет: защищать нечего
        String now = homeNow(c);
        if (HydeRules.HOME.equals(now)) {
            if (p.contains(H_LOST)) {
                p.edit().remove(H_LOST).remove(H_NOTE).apply();   // следующая потеря главного экрана: новый случай, скажем снова
                out.log("Главный экран снова HYDE");
            }
            return;
        }
        if (p.contains(H_LOST)) return;
        p.edit().putLong(H_LOST, System.currentTimeMillis()).apply();
        out.log("Главный экран не HYDE (" + why + "): " + (now.isEmpty() || "android".equals(now) ? "не выбран" : now));
        out.soon(HealthRules.HOME_SETTLE + 10_000);
    }

    // ===== Пакеты, отключённые установщиком =====

    static List<String> list(Context c) {
        try {
            return HealthRules.parseList(c.getSharedPreferences("guard", Context.MODE_PRIVATE).getString(D_LIST, ""));
        } catch (IllegalArgumentException e) {
            return new ArrayList<>();
        }
    }

    // Какие из них сейчас включены. Пакета нет на ТВ: включать нечего, не считается
    static List<String> reenabled(Context c, List<String> list) {
        PackageManager pm = c.getPackageManager();
        List<String> on = new ArrayList<>();
        for (String pkg : list) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
                if (ai.enabled) on.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return on;
    }

    // Команда установщика: полный текущий список отключённых им пакетов (пустой: списка нет). Служба может быть ещё не
    // запущена (сразу после установки «Сторожа»), поэтому без неё. Ответ для установщика
    static String setList(Context c, String raw) {
        List<String> l;
        try {
            l = HealthRules.parseList(raw);
        } catch (IllegalArgumentException e) {
            return "список не принят: " + e.getMessage();
        }
        // установщик только что всё отключил заново: о прошлом обновлении прошивки говорить нечего
        c.getSharedPreferences("guard", Context.MODE_PRIVATE).edit().putString(D_LIST, HealthRules.join(l)).remove(D_FW).commit();
        return listStatus(c);
    }

    static String listStatus(Context c) {
        List<String> l = list(c);
        if (l.isEmpty()) return "списка отключённых установщиком нет";
        List<String> on = reenabled(c, l);
        // Вид ответа закреплён у установщика (installer/disabled.go, reDisabledReply): имена через запятую после ", "
        return "отключённых установщиком " + l.size() + ", снова включено " + on.size() + (on.isEmpty() ? "" : ", " + HealthRules.join(on));
    }

    // Обновилась прошивка: включила ли она обратно то, что отключал установщик (нитка не главная)
    void afterFirmware() {
        List<String> l = list(c);
        if (l.isEmpty()) return;
        List<String> on = reenabled(c, l);
        if (on.isEmpty()) {
            out.log("После обновления прошивки отключённые установщиком пакеты по-прежнему отключены (" + l.size() + ")");
            return;
        }
        p.edit().putString(D_FW, Build.FINGERPRINT).apply();
        out.log("Обновление прошивки снова включило " + on.size() + " из " + l.size() + " пакетов, отключённых установщиком: " + HealthRules.join(on));
        out.soon(NOTE_SOON);
    }

    // ===== Записка «нужен установщик» =====

    // Есть ли что сказать, по памяти и без проверок (зовётся и из главной нитки при включении экрана)
    boolean notePending() {
        long now = System.currentTimeMillis();
        if (p.getBoolean(K_BROKEN, false) && HealthRules.due(p.getLong(K_NOTE, 0), now)) return true;
        if (p.contains(H_LOST) && HealthRules.due(p.getLong(H_NOTE, 0), now)) return true;
        String fw = p.getString(D_FW, null);
        return fw != null && !fw.equals(p.getString(D_NOTE, null));
    }

    // Нитка обновлений: одна записка на все причины, когда человек у главного экрана и ничего не идёт. Причины проверяются
    // заново: то, что уже починилось (установщик, человек в настройках), не говорится. true: записка показана
    boolean noteNow(Updates u) {
        if (!notePending()) return false;
        long now = System.currentTimeMillis();
        if (p.getBoolean(K_BROKEN, false) && (!installed(c, HealthRules.KM_PKG) || alive(c))) fine();
        if (p.contains(H_LOST)) home(HealthRules.PERIODIC);
        boolean keys = p.getBoolean(K_BROKEN, false) && HealthRules.due(p.getLong(K_NOTE, 0), now);
        String homePkg = homeNow(c);
        boolean homeLost = p.contains(H_LOST) && HealthRules.homeSettled(p.getLong(H_LOST, 0), now) && HealthRules.due(p.getLong(H_NOTE, 0), now);
        String fw = p.getString(D_FW, null);
        boolean fwDue = fw != null && !fw.equals(p.getString(D_NOTE, null));
        int re = fwDue ? reenabled(c, list(c)).size() : 0;
        if (fwDue && re == 0) p.edit().putString(D_NOTE, fw).apply();   // установщик или человек уже отключили: говорить нечего
        String text = HealthRules.note(keys ? p.getString(K_WHY, HealthRules.WHY_FIRMWARE) : null,
                CapsRules.isTcl(Build.MANUFACTURER, Build.BRAND), homeLost, homePkg, re);
        if (text == null || !GuardService.promptFree() || !interactive() || !u.tapMoment(GuardService.topPackage())) return false;
        GuardService.prompted();
        SharedPreferences.Editor e = p.edit();
        if (keys) e.putLong(K_NOTE, now);
        if (homeLost) e.putLong(H_NOTE, now);
        if (re > 0) e.putString(D_NOTE, fw);   // о включённом прошивкой говорим один раз на обновление прошивки
        e.apply();
        out.note(text);
        return true;
    }

    private boolean interactive() {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isInteractive();
    }

    // ===== Поля ежедневного отчёта (нитка обновлений) =====

    static String keysField(Context c, long lastReport) {
        boolean inst = installed(c, HealthRules.KM_PKG);
        return HealthRules.keysField(inst, inst && alive(c),
                c.getSharedPreferences("guard", Context.MODE_PRIVATE).getLong(K_REPAIRED, 0), lastReport);
    }

    static String homeField(Context c) {
        return HealthRules.homeField(installed(c, HydeRules.HOME), HydeRules.HOME.equals(homeNow(c)));
    }

    // -1: списка от установщика нет, поле не шлётся
    static int reenabledCount(Context c) {
        List<String> l = list(c);
        return l.isEmpty() ? -1 : reenabled(c, l).size();
    }
}
