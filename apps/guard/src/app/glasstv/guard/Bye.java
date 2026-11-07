package app.glasstv.guard;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// Присмотр за ByeByeDPI (07.11, необязательный модуль для России; docs/byedpi.md в публичной копии).
// ByeByeDPI держит VPN на самом ТВ без сервера: через него идут только YouTube и «Волна». После перезагрузки ТВ он включается
// сам, а после обновления, сбоя или выгрузки системой нет. Пока экран горит, «Сторож» раз в 5 секунд смотрит, есть ли VPN
// (дёшево, через ConnectivityManager), после пробуждения и смены сети проверяет сразу. Туннеля нет, ByeByeDPI стоит,
// присмотр включён и ничего не играет: включает его через выключатель автора ToggleActivity (окна у него нет).
// Присмотр включает установщик, и только после удачной загрузки списка приложений: без списка через ByeByeDPI пошли бы все.
// Выключил человек сам (в окне ByeByeDPI или включил другой VPN): не включаем до перезагрузки ТВ; остановил в настройках
// Android: пока его не откроют. Решения и разбор dumpsys в ByeRules (тест на ПК). Работа в своей нитке: dumpsys не держит
// главную, проверки идут по одной
final class Bye {
    static final String PKG = ByeRules.PKG;
    static final long POLL_UP = 5_000, POLL_DOWN = 10_000, POLL_IDLE = 60_000, VERIFY_MS = 15_000;

    private final GuardService g;
    private final SharedPreferences p;
    private final HandlerThread thread;
    private final Handler bh;

    // Дальше всё только в нитке bh
    private boolean lastUp;          // туннель был при прошлом взгляде
    private long upAt;               // когда его видели последний раз (часы ТВ, как у событий окон)
    private long upSaved;            // когда upAt последний раз записан на диск
    private String netSeen;          // сеть VPN, чей владелец уже известен
    private boolean netOurs = true;
    private int fails;               // неудачных запусков подряд
    private long tryAt = -1, selfAt = -1;   // последняя попытка и свой запуск (SystemClock.elapsedRealtime)
    private String pending;          // запуск идёт: почему включаем (для журнала), ждём туннель VERIFY_MS
    private final List<Long> starts = new ArrayList<>();   // свои запуски (elapsedRealtime): падает снова и снова, перестаём
    private boolean stoppedSaid, loopSaid;
    private String why;              // причина следующей проверки
    private volatile String state = "";

    private final Runnable tick = () -> { String w = why; why = null; step(w); };

    Bye(GuardService g) {
        this.g = g;
        this.p = g.p;
        thread = new HandlerThread("guard-bye");
        thread.start();
        bh = new Handler(thread.getLooper());
        // Туннель видели при прошлом запуске «Сторожа» в эту же загрузку ТВ: если он пропал, пока «Сторожа» не было, это тоже разбираем
        if (bootId().equals(p.getString("byeUpBoot", ""))) {
            upAt = upSaved = p.getLong("byeUpAt", 0);
            lastUp = upAt > 0;
        }
    }

    // Первая проверка: после загрузки ТВ ByeByeDPI включается сам, поэтому не раньше полутора минут после неё
    void start() { soon("при запуске «Сторожа»", Math.max(5_000, 90_000 - SystemClock.elapsedRealtime())); }

    void stop() {
        bh.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    void screen(boolean on) {
        if (on) soon("после пробуждения", 4_000);
        else bh.post(() -> bh.removeCallbacks(tick));   // экран погас: не опрашиваем, при включении проверим сразу
    }

    // Проверить через ms (свежий повод заменяет запланированную проверку)
    void soon(String reason, long ms) {
        bh.post(() -> {
            if (reason != null) why = reason;
            bh.removeCallbacks(tick);
            bh.postDelayed(tick, ms);
        });
    }

    // Команда с ПК (Cmd, do vpn): on, off, check или пусто (только состояние). Ответ в done, из нитки присмотра
    void command(String a, java.util.function.Consumer<String> done) {
        boolean queued = bh.post(() -> {
            if ("on".equals(a)) {
                p.edit().putBoolean("byeWatch", true).remove("byeOffBoot").remove("byeOffWhy").remove("byeOffAt").apply();
                fails = 0;
                tryAt = -1;
                starts.clear();
                stoppedSaid = loopSaid = false;
                say("Присмотр за ByeByeDPI включён: если туннель пропадёт, «Сторож» включит его сам");
                step("команда с ПК");
            } else if ("off".equals(a)) {
                p.edit().putBoolean("byeWatch", false).apply();
                pending = null;
                bh.removeCallbacks(tick);
                state = ByeRules.OFF;
                say("Присмотр за ByeByeDPI выключен командой с ПК");
            } else if ("check".equals(a)) {
                step("команда с ПК");
            }
            done.accept(report());
        });
        if (!queued) done.accept("служба «Сторожа» останавливается");   // нитка присмотра уже закрыта: ответить сразу
    }

    // Строка на экране «Сторожа»: коротко, одной строкой. Только память и настройки, без dumpsys (зовут из главной нитки)
    String brief() {
        String st = state;
        return st.isEmpty() ? (p.getBoolean("byeWatch", false) ? "проверяю" : ByeRules.OFF) : st;
    }

    // Для отчёта и do vpn: то же с подробностями (когда и как выключили, сколько раз включал сам)
    String status() {
        String st = brief();
        if (ByeRules.HUMAN.equals(st)) {
            long at = p.getLong("byeOffAt", 0);
            return "выключен" + (at > 0 ? " в " + hm(at) : "") + " (" + p.getString("byeOffWhy", "") + "), не включаю до перезагрузки ТВ";
        }
        if (ByeRules.UP.equals(st)) {
            int n = p.getInt("byeCount", 0);
            boolean few = n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14);
            return st + (n > 0 ? "; включал сам " + n + (few ? " раза" : " раз") + ", последний " + p.getString("byeLast", "") : "");
        }
        return st;
    }

    // Полный ответ для do vpn: присмотр, версия, состояние и что «Сторожу» видно
    private String report() {
        String v;
        try { v = g.getPackageManager().getPackageInfo(PKG, 0).versionName; } catch (Exception e) { v = null; }
        String st = status();
        return (p.getBoolean("byeWatch", false) ? "присмотр включён" : "присмотр выключен") + "; ByeByeDPI " + (v == null ? "не установлен" : v)
                + (ByeRules.OFF.equals(st) ? "" : "; " + st)
                + (usageAllowed() ? "; окна видно" : "; окон не видно (нет права на историю использования): пропажу туннеля считаю решением человека");
    }

    // ===== Шаг присмотра =====

    private void step(String reason) {
        bh.removeCallbacks(tick);
        if (!p.getBoolean("byeWatch", false)) { state = ByeRules.OFF; pending = null; return; }   // без присмотра не опрашиваем
        ApplicationInfo ai;
        try { ai = g.getPackageManager().getApplicationInfo(PKG, 0); } catch (PackageManager.NameNotFoundException e) { ai = null; }
        if (ai == null) {
            // ByeByeDPI убрали (откат установщиком или руками): присмотр снимается, чужую установку потом не трогаем
            p.edit().putBoolean("byeWatch", false).remove("byeOffBoot").remove("byeOffWhy").remove("byeOffAt")
                    .remove("byeUpAt").remove("byeUpBoot").remove("byeCount").remove("byeLast").apply();
            lastUp = false;
            pending = null;
            state = ByeRules.OFF;
            say("ByeByeDPI убран с ТВ: присмотр за ним выключен");
            return;
        }
        boolean screen = interactive();
        long now = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
        Network vpn = vpnNet();
        if (vpn != null) {
            boolean ours = ours(vpn, ai.uid);
            // Туннель поднялся не от нашего запуска (человек или автозапуск самого ByeByeDPI): счёт запусков с начала
            if (!lastUp && pending == null) { starts.clear(); loopSaid = false; }
            if (ours && pending != null) {
                int n = p.getInt("byeCount", 0) + 1;
                p.edit().putInt("byeCount", n).putString("byeLast", GuardService.now() + " (" + pending + ")").apply();
                say("ByeByeDPI снова включён (" + pending + ")");
            }
            pending = null;
            fails = 0;
            if (ours && human()) {
                p.edit().remove("byeOffBoot").remove("byeOffWhy").remove("byeOffAt").apply();
                say("ByeByeDPI снова включили: «Сторож» опять присматривает за ним");
            }
            if (!ours && !human()) markHuman("включён другой VPN", "Включён VPN другого приложения: ByeByeDPI «Сторож» не включает до перезагрузки ТВ");
            lastUp = true;
            upAt = wall;
            if (wall - upSaved > 60_000) {
                upSaved = wall;
                p.edit().putLong("byeUpAt", wall).putString("byeUpBoot", bootId()).apply();
            }
            state = ours ? ByeRules.UP : ByeRules.OTHER;
            if (screen) bh.postDelayed(tick, POLL_UP);
            return;
        }

        if (lastUp) {
            // Туннель пропал. Выключил человек, если окно ByeByeDPI было на экране после того, как туннель видели; обновление
            // и сбой своего запуска не в счёт (ByeRules.byHuman)
            lastUp = false;
            p.edit().remove("byeUpAt").remove("byeUpBoot").apply();
            upSaved = 0;
            long since = upAt - 2_000;
            Boolean win = windowSince(since);
            boolean updated = false;
            try { updated = g.getPackageManager().getPackageInfo(PKG, 0).lastUpdateTime >= since; } catch (Exception ignored) { }
            if (ByeRules.byHuman(win, selfAt < 0 ? -1 : now - selfAt, updated)) {
                if (win == null) markHuman("кто выключил, не видно", "Туннель ByeByeDPI пропал, а окон «Сторожу» не видно: считаю, что его выключил человек, и не включаю до перезагрузки ТВ");
                else markHuman("в его окне", "ByeByeDPI выключили в его окне: «Сторож» не включает его до перезагрузки ТВ");
            }
        }
        if (pending != null) {
            if (now - tryAt < VERIFY_MS) { state = ByeRules.START; if (screen) bh.postDelayed(tick, POLL_UP); return; }
            fails++;
            say(ByeRules.retryAfter(fails) < 0 ? "ByeByeDPI не включился три раза подряд: «Сторож» больше не пробует до перезагрузки ТВ. Откройте ByeByeDPI и нажмите большую кнопку"
                    : "ByeByeDPI не включился (" + pending + "), «Сторож» попробует ещё раз позже");
            pending = null;
        }

        ByeRules.See s = new ByeRules.See();
        s.watch = true;
        s.installed = true;
        s.stopped = (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0;
        s.human = human();
        s.updating = g.upd != null && g.upd.installing(PKG);
        s.screen = screen;
        s.fails = fails;
        s.sinceTry = tryAt < 0 ? -1 : now - tryAt;
        s.recent = ByeRules.recent(starts, now);
        String d = ByeRules.decide(s);
        if (ByeRules.START.equals(d)) {   // видео смотрим только перед самым запуском: это dumpsys
            s.playing = playing();
            d = ByeRules.decide(s);
        }
        if (s.stopped && !stoppedSaid) say("ByeByeDPI остановили в настройках Android: «Сторож» не включает его, пока его не откроют");
        stoppedSaid = s.stopped;
        if (ByeRules.GAVE_UP.equals(d) && s.recent >= ByeRules.MAX_STARTS && !loopSaid) {
            loopSaid = true;
            say("ByeByeDPI падает снова и снова (включал " + s.recent + " раза за 10 минут): «Сторож» больше не включает его до перезагрузки ТВ");
        }
        if (ByeRules.START.equals(d)) launch(reason == null ? "туннель пропал" : reason);
        state = d;
        if (screen) bh.postDelayed(tick, ByeRules.START.equals(d) ? POLL_UP : ByeRules.GAVE_UP.equals(d) ? POLL_IDLE : POLL_DOWN);
    }

    // Выключатель автора без окна: только включить, если выключен (ToggleActivity, extra only_start). Своя задача, чтобы на экран
    // не вылезло главное окно ByeByeDPI, если оно осталось в недавних; без анимации и без следа в недавних
    private void launch(String reason) {
        Intent i = new Intent().setComponent(new ComponentName(PKG, ByeRules.TOGGLE)).putExtra("only_start", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_HISTORY);
        selfAt = tryAt = SystemClock.elapsedRealtime();
        pending = reason;
        starts.add(selfAt);
        while (starts.size() > ByeRules.MAX_STARTS) starts.remove(0);   // старше нужного не храним
        try {
            g.startActivity(i);
        } catch (Exception e) {
            say("ByeByeDPI не запускается (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void markHuman(String why, String line) {
        p.edit().putString("byeOffBoot", bootId()).putString("byeOffWhy", why).putLong("byeOffAt", System.currentTimeMillis()).apply();
        say(line);
    }

    private boolean human() { return bootId().equals(p.getString("byeOffBoot", "")); }

    // Номер загрузки ТВ: решение человека живёт до перезагрузки. Нет счётчика: время загрузки с точностью до 10 минут
    private String bootId() {
        int n = Settings.Global.getInt(g.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        return n >= 0 ? "n" + n : "t" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 600_000;
    }

    // Поднятая сеть VPN. Сеть видна и тем, кто в туннель не входит (сам «Сторож» в него не входит), без права на неё
    private Network vpnNet() {
        try {
            ConnectivityManager cm = g.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return n;
            }
        } catch (Exception ignored) { }
        return null;
    }

    // Этот VPN от ByeByeDPI: владелец сети из dumpsys connectivity (право DUMP), один раз на каждую новую сеть. Не видно: его
    private boolean ours(Network n, int uid) {
        String key = n.toString();
        if (!key.equals(netSeen)) {
            netSeen = key;
            int owner = ByeRules.vpnOwner(GuardService.dumpsys("connectivity"));
            netOurs = owner < 0 || owner == uid;
        }
        return netOurs;
    }

    // Было ли окно ByeByeDPI на экране с since: по событиям окон, точно, даже если человек нажал кнопку и сразу ушёл. Нужно право
    // «доступ к истории использования» (установщик даёт его вместе с модулем). Без него null: окна приложению не видны совсем
    // (dumpsys activity без этого права отказывает, а dumpsys window из приложения недоступен, проверено 07.11)
    private Boolean windowSince(long since) {
        if (!usageAllowed()) return null;
        try {
            UsageEvents ue = g.getSystemService(UsageStatsManager.class).queryEvents(since - 3_600_000, System.currentTimeMillis() + 1_000);
            List<ByeRules.Ev> l = new ArrayList<>();
            UsageEvents.Event e = new UsageEvents.Event();
            while (ue.hasNextEvent()) {
                ue.getNextEvent(e);
                if (PKG.equals(e.getPackageName())) l.add(new ByeRules.Ev(e.getTimeStamp(), e.getEventType(), e.getPackageName(), e.getClassName()));
            }
            return ByeRules.windowSince(l, since);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean usageAllowed() {
        try {
            int m = g.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), g.getPackageName());
            return m == AppOpsManager.MODE_ALLOWED
                    || (m == AppOpsManager.MODE_DEFAULT && g.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED);
        } catch (Exception e) {
            return false;
        }
    }

    // Идёт видео (занят видеодекодер, право DUMP) или играет медиасессия (если включён слушатель): не перебиваем
    private boolean playing() { return g.playingAny() != null || Boolean.TRUE.equals(GuardService.videoDecoding()); }

    private boolean interactive() {
        PowerManager pm = g.getSystemService(PowerManager.class);
        return pm != null && pm.isInteractive();
    }

    private void say(String s) { g.h.post(() -> g.log(s)); }

    private static String hm(long t) { return new SimpleDateFormat("d MMM, H:mm", Locale.forLanguageTag("ru")).format(new Date(t)); }
}
