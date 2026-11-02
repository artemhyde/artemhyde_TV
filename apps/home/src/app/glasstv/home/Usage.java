package app.glasstv.home;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.PrintWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// История использования приложений (UsageEvents) для двух задач главного экрана:
// 1. чистый запуск плиток: ТВ гас после того, как приложением пользовались в последний раз (clean);
// 2. просмотр во встроенном плеере Lampa: она непрерывно простояла на экране 10 минут и больше (lampaEnd).
// Читается только в фоне, не чаще раза в 5 с и только новые события: главный поток ничего не ждёт
final class Usage {
    static final String TAG = "HomeWatch";
    private static final long MIN_GAP = 5_000;              // не чаще раза в 5 с
    private static final long WINDOW = 3 * 86_400_000L;     // как далеко назад смотрим в первый раз
    private static final long OVERLAP = 2_000;              // запас на события, которые система записала с опозданием
    private static final long LAMPA_MIN = 10 * 60_000;      // столько Lampa должна простоять на экране без перерыва
    private static final long MERGE = 5_000;                // перерыв короче: экраны Lampa мигают друг за другом
    private static final long OFF_SLACK = 3_000;            // приложение гаснет вместе с экраном, но на секунду-другую позже

    private static final Object LOCK = new Object();
    private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "home-usage");
        t.setDaemon(true);
        return t;
    });

    // Всё ниже под LOCK
    private static long scanned, lastRun;
    private static boolean running;
    private static long off;                                 // последнее засыпание экрана или выключение
    private static final Map<String, Long> paused = new HashMap<>();   // когда пакет последний раз ушёл с экрана
    private static long lampaStart, lampaLeft, longEnd;      // отрезок Lampa на экране и конец последнего долгого

    // Перечитать новые события в фоне. done зовётся там же, в фоне (кто хочет на экран, пусть переносит сам).
    // Не чаще раза в 5 с: пока рано или чтение уже идёт, ничего не делает и done не зовёт
    static void refresh(Context c, Runnable done) {
        synchronized (LOCK) {
            long t = SystemClock.elapsedRealtime();
            if (running || (lastRun != 0 && t - lastRun < MIN_GAP)) return;
            running = true;
            lastRun = t;
        }
        final Context app = c.getApplicationContext();
        EX.execute(() -> {
            try {
                scan(app);
            } catch (Exception e) {
                Log.w(TAG, "история использования не прочиталась: " + e);
            } finally {
                synchronized (LOCK) { running = false; }
            }
            if (done != null) done.run();
        });
    }

    private static void scan(Context c) {
        UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
        if (um == null) return;
        long now = System.currentTimeMillis();
        long from;
        synchronized (LOCK) { from = scanned == 0 ? now - WINDOW : Math.max(now - WINDOW, scanned - OVERLAP); }
        UsageEvents ev = um.queryEvents(from, now + 1000);
        if (ev == null) return;
        UsageEvents.Event e = new UsageEvents.Event();
        int n = 0;
        synchronized (LOCK) {
            boolean first = scanned == 0;
            long was = endLocked();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                apply(e.getEventType(), e.getTimeStamp(), e.getPackageName());
                n++;
            }
            scanned = now;
            // В журнал только первое чтение и перемены: каждые 5 с он не нужен
            if (first || was != endLocked())
                Log.i(TAG, "история: событий " + n + ", экран гас " + date(off) + ", Lampa долго на экране до " + date(endLocked()));
        }
    }

    // Одно событие. Повторная обработка того же события безвредна (перекрытие окон чтения): везде берётся максимум
    private static void apply(int type, long t, String p) {
        switch (type) {
            case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
            case UsageEvents.Event.KEYGUARD_SHOWN:
            case UsageEvents.Event.DEVICE_SHUTDOWN:
                if (t > off) off = t;
                break;
            case UsageEvents.Event.ACTIVITY_PAUSED: {
                Long o = paused.get(p);
                if (o == null || t > o) paused.put(p, t);
                if (Last.LAMPA.equals(p) && lampaStart != 0 && lampaLeft == 0) lampaLeft = t;
                break;
            }
            case UsageEvents.Event.ACTIVITY_RESUMED:
                if (!Last.LAMPA.equals(p)) break;
                if (lampaStart == 0) {
                    lampaStart = t;
                    lampaLeft = 0;
                } else if (lampaLeft != 0) {
                    if (t - lampaLeft >= MERGE) {
                        closeLampa();
                        lampaStart = t;
                    }
                    lampaLeft = 0;
                }
                break;
            default:
                break;
        }
    }

    private static void closeLampa() {
        if (lampaLeft - lampaStart >= LAMPA_MIN && lampaLeft > longEnd) longEnd = lampaLeft;
        lampaStart = 0;
        lampaLeft = 0;
    }

    // Нужно ли открыть приложение с чистого листа: ТВ засыпал позже, чем приложение в последний раз ушло с экрана.
    // Пока история не прочитана ни разу, ответ «нет»: открываем как раньше
    static boolean clean(String pkg) {
        synchronized (LOCK) {
            if (off == 0) return false;
            Long p = paused.get(pkg);
            return p == null || off >= p - OFF_SLACK;
        }
    }

    // Когда закончился последний отрезок, в котором Lampa непрерывно была на экране 10 минут и больше (0: не было)
    static long lampaEnd() {
        synchronized (LOCK) { return endLocked(); }
    }

    // Закрытые отрезки плюс последний, если Lampa с него ушла, а следующего захода ещё не было
    private static long endLocked() {
        long open = lampaStart != 0 && lampaLeft != 0 && lampaLeft - lampaStart >= LAMPA_MIN ? lampaLeft : 0;
        return Math.max(longEnd, open);
    }

    // Просмотр в Just Player начат из Lampa? Смотрим, что было на экране перед плеером: если там была Lampa и ушла
    // с экрана не больше чем за 15 с до плеера, то да. Другие приложения между ней и плеером (проводник, например) это отменяют.
    // Наши приложения и системные не в счёт. start: когда началась сессия плеера. Ответ приходит в фоне
    static void cameFromLampa(Context c, long start, Consumer<Boolean> done) {
        final Context app = c.getApplicationContext();
        EX.execute(() -> {
            boolean from = false;
            try {
                from = fromLampa(app, start);
            } catch (Exception e) {
                Log.w(TAG, "не смог выяснить, откуда запущен плеер: " + e);
            }
            done.accept(from);
        });
    }

    private static boolean fromLampa(Context c, long start) {
        UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
        if (um == null) return false;
        UsageEvents ev = um.queryEvents(start - 20 * 60_000, start + 15_000);
        if (ev == null) return false;
        UsageEvents.Event e = new UsageEvents.Event();
        String cand = null;          // последнее чужое приложение на экране до плеера
        long candT = 0;              // и когда с ним последний раз что-то происходило
        boolean jpFront = false;
        boolean res = false;
        long gap = Long.MAX_VALUE;   // берём приход в плеер, ближайший к началу сессии
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int ty = e.getEventType();
            if (ty != UsageEvents.Event.ACTIVITY_RESUMED && ty != UsageEvents.Event.ACTIVITY_PAUSED) continue;
            String p = e.getPackageName();
            long t = e.getTimeStamp();
            if (Last.JP.equals(p)) {
                if (ty == UsageEvents.Event.ACTIVITY_PAUSED) { jpFront = false; continue; }
                if (jpFront) continue;
                jpFront = true;
                boolean from = cand != null && isLampa(cand) && t - candT <= 15_000;
                if (Math.abs(t - start) < gap) {
                    gap = Math.abs(t - start);
                    res = from;
                }
            } else if (!ignored(p)) {
                cand = p;
                candT = t;
            }
        }
        Log.i(TAG, "плеер начат из Lampa: " + res + " (до него на экране " + cand + ")");
        return res;
    }

    private static boolean isLampa(String p) { return Last.LAMPA.equals(p); }

    private static boolean ignored(String p) {
        return p.startsWith("app.glasstv.") || p.equals("android") || p.startsWith("com.android.") || p.startsWith("com.google.android.");
    }

    private static String date(long t) { return t == 0 ? "нет" : new Date(t).toString(); }

    private static long paused(String p) {
        Long t = paused.get(p);
        return t == null ? 0 : t;
    }

    static void dump(PrintWriter w) {
        synchronized (LOCK) {
            w.println("История: прочитана до " + date(scanned) + ", экран гас " + date(off));
            w.println("Lampa долго на экране до: " + date(endLocked()) + " (отрезок сейчас: с " + date(lampaStart)
                    + ", ушла " + date(lampaLeft) + ")");
            for (String p : new String[]{Last.ST, Last.LAMPA, Last.VK, Last.JP, "com.cxinventor.file.explorer", "tv.twitch.android.app"})
                w.println("  " + p + ": ушло с экрана " + date(paused(p)) + ", чистый запуск: " + (off != 0 && (paused(p) == 0 || off >= paused(p) - OFF_SLACK)));
        }
    }
}
