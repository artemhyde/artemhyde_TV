package app.glasstv.nightmode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

// Держит затемнение поверх всех приложений, показывает обратный отсчёт и усыпляет телевизор
public class NightService extends Service {
    static final String START = "start", ADD = "add", STYLE = "style", STOP = "stop", LEVEL = "level", RELEASE = "release";
    // Один сквозной ползунок «Яркость» (ноябрь), шаг 1 на всей шкале. Уровень это одно число со знаком, лежит в prefs "lvl".
    // 0..100: настоящая подсветка панели 1:1 (TvBacklightManager TCL, тот же ползунок «Яркость», что в меню картинки). Это обычная
    // яркость телевизора, служба для неё не нужна: меню пишет её само, а если служба шла, при закрытии меню отпускает её (RELEASE).
    // -1..-100: красная зона, служба держит её, пока не выключат. Подсветка 0, вуаль растёт до предела (0,8) к -50. Дальше одно окно
    // темнее не станет (система режет его на 0,8), поэтому поверх кладётся второе такое же, от -50 до -100 его плотность растёт до
    // 0,6: на -100 числа на экране ещё в 2,5 раза ниже. Первая попытка (02.11) снижала контраст TvPqManager: цифры менялись (100, 50),
    // а глазами разницы не было: у панели работают динамический контраст и локальное затемнение
    // Подсветкой управлять нельзя (включена «Адаптивная регулировка яркости» TCL или нет службы): шкала только из затемнения,
    // уровень -100..0, вуаль до 0,8 к -100, всё положительное считается «без затемнения»
    static final int SCALE = 100;             // шагов в каждую сторону от нуля
    static final int MIN_LEVEL = -SCALE, MAX_LEVEL = 100;
    static final int VEIL_FULL = -50;         // на этом уровне вуаль уже на пределе, дальше растёт второй слой
    static final float SECOND_MAX = 0.6f;     // плотность второго слоя на -100
    static final float WARM = 0.16f;          // «Тёплый свет»: янтарная добавка к вуали красной зоны (до 16 %)
    static final float WARM_RAMP = 0.4f;      // добавка нарастает, пока вуаль не дойдёт до 40 %: шаг через ноль не прыгает
    static final int MIN_BACKLIGHT = 0;       // ноль подсветки: самое тёмное, что разрешает меню TCL
    static final float CAP = 0.8f;           // система не даёт окну поверх других плотнее 80 %
    static final float FADE_TO = CAP;        // к этой плотности экран плавно гаснет в конце
    static final long FADE_MS = 30_000;
    static final long MARK_MS = 5 * 60_000;  // напоминание каждые 5 минут
    static final long LATE_MS = 2 * 60_000;  // таймер «проспал» дольше: телевизор уже выключали, не усыпляем

    static volatile boolean running;
    static volatile boolean usesBl;          // идущий режим гасит подсветкой панели (иначе одной вуалью): меню читает, как рисовать шкалу
    static volatile long endAt;              // elapsedRealtime; 0 = без таймера
    static volatile Rect hole;               // окно открытого меню в координатах экрана: его вуаль не накрывает (null: меню закрыто)
    static volatile float holeR;             // скругление углов этого окна, px
    static NightService self;                // меню и служба в одном процессе: вырез в вуали меняется напрямую

    WindowManager wm;
    Veil shade, shade2;
    LinearLayout badge;
    TextView badgeText;
    SharedPreferences prefs;
    final Handler h = new Handler(Looper.getMainLooper());
    long badgeUntil;
    long lastMark;
    boolean badgeShown;
    boolean stuck;                           // таймер вышел, а усыпить нечем: держим экран тёмным
    int lastBacklight = -1;
    boolean useBl;                           // подсветкой управляем мы (датчик света TCL выключен)

    static void send(Context c, String action, int seconds) {
        Intent i = new Intent(c, NightService.class).setAction(action).putExtra("seconds", seconds);
        if (START.equals(action) || ADD.equals(action) || LEVEL.equals(action)) c.startForegroundService(i);
        else if (STOP.equals(action) || running) c.startService(i);   // STOP идёт и тогда, когда служба ещё поднимается после LEVEL: команды не теряются
    }

    static int shadeColor(float a, boolean warm) {
        int alpha = Math.round(a * 255);
        return warm ? Color.argb(alpha, 46, 20, 0) : Color.argb(alpha, 0, 0, 0);
    }

    static String clock(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = getSystemService(WindowManager.class);
        prefs = getSharedPreferences("night", MODE_PRIVATE);
        self = this;
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    // Телевизор выключили: ночной режим окончен, следующее включение с обычной яркостью и без вуали
    final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (running) finishNight(false);
        }
    };

    // Вырез под меню поменялся (при разворачивании плашки это каждый кадр, поэтому без команды службе)
    static void refreshHole() {
        NightService s = self;
        if (s == null || s.shade == null) return;
        s.shade.setHole(hole, holeR);
        if (s.shade2 != null) s.shade2.setHole(hole, holeR);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        int seconds = intent == null ? 0 : intent.getIntExtra("seconds", 0);
        long now = SystemClock.elapsedRealtime();

        if (action == null) {
            // Система перезапустила службу после нехватки памяти: продолжаем с того же места
            if (!prefs.getBoolean("active", false)) { stopSelf(); return START_NOT_STICKY; }
            long saved = prefs.getLong("endAt", 0);
            if (saved != 0 && saved <= now) { finishNight(false); return START_NOT_STICKY; }
            endAt = saved;
            begin(0);
        } else if (START.equals(action)) {
            endAt = seconds > 0 ? now + seconds * 1000L : 0;
            stuck = false;
            if (endAt != 0 && !adminActive())
                Toast.makeText(this, "Нет прав на выключение: экран потемнеет, но телевизор сам не уснёт", Toast.LENGTH_LONG).show();
            begin(6_000);
        } else if (ADD.equals(action)) {
            endAt = (endAt == 0 ? now : Math.max(endAt, now)) + seconds * 1000L;
            stuck = false;
            begin(5_000);
        } else if (LEVEL.equals(action)) {
            // Ползунок сдвинули: уровень уже в prefs. Если режима не было, он включается (без таймера, без значка: меню открыто)
            stuck = false;
            long show = seconds * 1000L;   // seconds > 0: заодно показать значок (меню закрыли, режим остался)
            if (running) {
                if (show > 0) badgeUntil = now + show;
                applyShade();
                if (show > 0) { h.removeCallbacks(tick); tick.run(); }
            } else {
                endAt = 0;
                begin(show);
            }
        } else if (STYLE.equals(action)) {
            applyShade();
        } else if (STOP.equals(action)) {
            finishNight(false);
            return START_NOT_STICKY;
        } else if (RELEASE.equals(action)) {
            // Меню закрыли правее нуля: выбранное теперь обычная яркость, возвращать нечего
            if (running && useBl) Backlight.set(level(prefs, true), true);
            prefs.edit().remove("bl0").apply();
            finishNight(false);
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    void begin(long showBadgeMs) {
        try {
            // Тип службы передаётся только с Android 10, на Android 9 такого вызова нет
            if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, notification());
        } catch (Exception e) {
            // Без значка в шторке тоже работает, лишь бы не упасть
        }
        if (!addWindows()) { finishNight(false); return; }
        boolean first = !running;
        running = true;
        useBl = canBacklight();
        usesBl = useBl;
        if (useBl) base(prefs);   // обычную подсветку запоминаем до первого её изменения
        if (first) notifyGuard(this, true);
        prefs.edit().putBoolean("active", true).putLong("endAt", endAt).apply();
        lastMark = endAt == 0 ? 0 : (endAt - SystemClock.elapsedRealtime()) / MARK_MS;
        badgeUntil = SystemClock.elapsedRealtime() + showBadgeMs;
        applyShade();
        h.removeCallbacks(tick);
        tick.run();
    }

    boolean addWindows() {
        if (shade != null) return true;
        try {
            int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

            shade = new Veil(this);
            WindowManager.LayoutParams full = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    type, flags, PixelFormat.TRANSLUCENT);
            // Сразу полной плотности: меню уже показывало это затемнение, плавный разгон дал бы вспышку
            wm.addView(shade, full);
            // Второй слой: отдельное окно, предел 0,8 у каждого свой
            shade2 = new Veil(this);
            WindowManager.LayoutParams full2 = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    type, flags, PixelFormat.TRANSLUCENT);
            wm.addView(shade2, full2);

            float dp = getResources().getDisplayMetrics().density;
            badge = new LinearLayout(this);
            badge.setOrientation(LinearLayout.HORIZONTAL);
            badge.setGravity(Gravity.CENTER_VERTICAL);
            badge.setPadding(px(dp, 16), px(dp, 9), px(dp, 20), px(dp, 9));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0xE6141418);
            pill.setCornerRadius(px(dp, 40));
            pill.setStroke(px(dp, 1), 0x26FFFFFF);
            badge.setBackground(pill);
            ImageView moon = new ImageView(this);
            moon.setImageResource(R.drawable.moon);
            badge.addView(moon, new LinearLayout.LayoutParams(px(dp, 22), px(dp, 22)));
            badgeText = new TextView(this);
            badgeText.setTextColor(0xFFF2EBDD);
            badgeText.setTextSize(17);
            badgeText.setPadding(px(dp, 10), 0, 0, 0);
            badge.addView(badgeText);
            badge.setAlpha(0f);
            // Значок без NOT_TOUCHABLE, иначе система делает его полупрозрачным
            WindowManager.LayoutParams corner = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    type, flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
            corner.gravity = Gravity.TOP | Gravity.END;
            corner.x = px(dp, 36);
            corner.y = px(dp, 30);
            wm.addView(badge, corner);
            return true;
        } catch (Exception e) {
            removeWindows();
            Toast.makeText(this, "Нет разрешения показывать поверх других окон", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    void removeWindows() {
        try { if (shade != null) wm.removeView(shade); } catch (Exception ignored) { }
        try { if (shade2 != null) wm.removeView(shade2); } catch (Exception ignored) { }
        try { if (badge != null) wm.removeView(badge); } catch (Exception ignored) { }
        shade = null;
        shade2 = null;
        badge = null;
    }

    // Шкала ползунка: число шагов и шаг отметки «0» (с подсветкой и без неё)
    static int steps(boolean bl) { return bl ? 2 * SCALE : SCALE; }

    static int zero(boolean bl) { return bl ? SCALE : -1; }

    // Уровень на шаге шкалы и обратно
    static int levelAt(int idx, boolean bl) { return idx - SCALE; }

    static int indexOf(int lvl, boolean bl) { return Math.max(0, Math.min(steps(bl), lvl + SCALE)); }

    // Выбранный уровень. Пока его никто не выбирал, ночь идёт на нынешней яркости (а не на нуле подсветки)
    static int level(SharedPreferences prefs, boolean bl) {
        if (!prefs.contains("lvl")) return bl ? Math.max(0, Backlight.get()) : 0;
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, prefs.getInt("lvl", 0)));
    }

    // Можно ли вести подсветку самим: датчик света TCL выключен и служба отвечает
    static boolean canBacklight() { return Backlight.lightSensor() == 0 && Backlight.get() >= 0; }

    // Вуаль поверх экрана на уровне: только красная зона, правее нуля вуали нет. Предел 0,8 (больше система не даёт окну поверх других)
    static float veil(int lvl, boolean bl) {
        if (lvl >= 0) return 0f;
        return bl ? Math.min(CAP, -lvl / (float) -VEIL_FULL * CAP) : -lvl / (float) SCALE * CAP;
    }

    // Плотность слоя поверх. Правее нуля слоя нет совсем: это настоящая подсветка, вправо светлее (иначе 50 вышло бы темнее
    // исходных 45). В красной зоне вуаль уровня, а тёплый свет (если включён) добавляет янтарь сверху. Слои складываются как
    // прозрачности, 1-(1-a)(1-w), поэтому каждый шаг вниз виден; плотнее CAP не бывает
    static float overlay(SharedPreferences prefs, int lvl, boolean bl) {
        float a = veil(lvl, bl);
        if (a == 0f || !prefs.getBoolean("warm", true)) return a;
        float w = WARM * Math.min(1f, a / WARM_RAMP);
        return Math.min(CAP, a + w * (1f - a));
    }

    // Обычная подсветка до ночного режима: запоминаем один раз в "bl0", чтобы вернуть
    static int base(SharedPreferences prefs) {
        int b = prefs.getInt("bl0", -1);
        if (b < 0) {
            b = Backlight.get();
            if (b >= 0) prefs.edit().putInt("bl0", b).apply();
        }
        return b;
    }

    static void restoreBacklight(Context c, SharedPreferences prefs) {
        int b = prefs.getInt("bl0", -1);
        if (b >= 0) Backlight.set(b, true);
        prefs.edit().remove("bl0").apply();
    }

    // Только для тех, у кого ночной режим шёл на прошлой версии: она снижала контраст и держала исходный в "ct0"
    static void restoreContrast(SharedPreferences prefs) {
        int c0 = prefs.getInt("ct0", -1);
        if (c0 < 0) return;
        Backlight.setContrast(c0);
        prefs.edit().remove("ct0").apply();
    }

    // Служба мертва, а следы остались (убили процесс, обновили приложение): вернуть контраст и сказать «Сторожу», что режим
    // выключен. Подсветку из "bl0" возвращаем только после красной зоны: правее нуля выбранное и есть обычная яркость, а старая
    // цифра в "bl0" не значит ничего. Из-за неё при открытии меню подсветка прыгала на прошлое значение (0 → 80, ноябрь)
    static void recover(Context c, SharedPreferences prefs) {
        restoreContrast(prefs);
        boolean was = prefs.getBoolean("active", false);
        if (!was || prefs.getInt("lvl", 0) >= 0) prefs.edit().remove("bl0").apply();
        if (was) {
            prefs.edit().putBoolean("active", false).putLong("endAt", 0).apply();
            restoreBacklight(c, prefs);
            notifyGuard(c, false);
        }
    }

    // «Сторож» ночью сам ведёт подсветку: пока мы её меняем, пусть не принимает это за пульт
    static void notifyGuard(Context c, boolean on) {
        try {
            c.sendBroadcast(new Intent().setClassName("app.glasstv.guard", "app.glasstv.guard.AppCmd").putExtra("do", "nm").putExtra("on", on));
        } catch (Exception ignored) { }
    }

    void applyBacklight(int target) {
        if (target == lastBacklight || target < 0) return;
        Backlight.set(target, false);
        lastBacklight = target;
    }

    // Плотность второго слоя: только с подсветкой и только ниже -50
    static float second(int lvl, boolean bl) {
        if (!bl || lvl >= VEIL_FULL) return 0f;
        return SECOND_MAX * (VEIL_FULL - lvl) / (float) (VEIL_FULL - MIN_LEVEL);
    }

    void applyShade() {
        if (shade == null) return;
        int lvl = level(prefs, useBl);
        float a = stuck ? CAP : overlay(prefs, lvl, useBl);
        if (useBl) base(prefs);
        // Подсветка: выбранная правее нуля, в красной зоне ноль; без управления ей не трогаем (-1)
        int bl = !useBl ? -1 : stuck || lvl < 0 ? MIN_BACKLIGHT : lvl;
        if (endAt != 0) {
            long left = endAt - SystemClock.elapsedRealtime();
            if (left < FADE_MS) {
                // Последние полминуты экран плавно гаснет: вуаль плотнее, подсветка к минимуму
                float pr = 1f - Math.max(0, left) / (float) FADE_MS;
                a += (FADE_TO - a) * pr;
                if (bl > MIN_BACKLIGHT) bl = Math.round(bl + (MIN_BACKLIGHT - bl) * pr);
            }
        }
        shade.setColor(shadeColor(Math.min(1f, a / CAP), prefs.getBoolean("warm", true)));
        shade.setHole(hole, holeR);   // пока меню открыто, оно остаётся читаемым, вуаль только вокруг него
        if (shade2 != null) {
            shade2.setColor(Color.argb(Math.round(Math.min(1f, second(lvl, useBl) / CAP) * 255), 0, 0, 0));
            shade2.setHole(hole, holeR);
        }
        applyBacklight(bl);
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (endAt != 0 && now >= endAt) {
                // Отсчёт стоял, пока телевизор был выключен: только снять затемнение
                if (now - endAt > LATE_MS) { finishNight(false); return; }
                if (adminActive()) { finishNight(true); return; }
                stuck = true;
                endAt = 0;
                prefs.edit().putLong("endAt", 0).apply();
                badgeUntil = 0;
                applyShade();
            }

            boolean show = now < badgeUntil;
            if (endAt == 0) {
                badgeText.setText("Ночной режим");
            } else {
                long left = endAt - now;
                long mark = left / MARK_MS;
                if (mark < lastMark) badgeUntil = now + 5_000;   // пересекли очередные 5 минут
                lastMark = mark;
                show = now < badgeUntil || left <= 60_000;
                badgeText.setText("Сон через " + clock(left));
                if (left < FADE_MS) applyShade();
            }
            if (show != badgeShown) {
                badgeShown = show;
                badge.animate().alpha(show ? 1f : 0f).setDuration(show ? 250 : 600).start();
            }
            h.postDelayed(this, 1000 - (SystemClock.elapsedRealtime() % 1000) + 5);
        }
    };

    void finishNight(boolean sleep) {
        h.removeCallbacks(tick);
        running = false;
        usesBl = false;
        endAt = 0;
        prefs.edit().putBoolean("active", false).putLong("endAt", 0).apply();
        removeWindows();
        restoreBacklight(this, prefs);   // до сна, чтобы утром телевизор включился с обычной яркостью
        restoreContrast(prefs);
        lastBacklight = -1;
        notifyGuard(this, false);
        if (sleep) {
            try { getSystemService(DevicePolicyManager.class).lockNow(); } catch (SecurityException ignored) { }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    boolean adminActive() {
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(new ComponentName(this, AdminReceiver.class));
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        removeWindows();
        try { unregisterReceiver(screenOff); } catch (Exception ignored) { }
        if (!prefs.getBoolean("active", false)) { restoreBacklight(this, prefs); restoreContrast(prefs); }
        running = false;
        if (self == this) self = null;
        super.onDestroy();
    }

    Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("night", "Ночной режим", NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, "night")
                .setSmallIcon(R.drawable.moon)
                .setContentTitle("Ночной режим")
                .setContentText(endAt == 0 ? "Выбранная яркость держится" : "Телевизор уснёт по таймеру")
                .setOngoing(true)
                .build();
    }

    static int px(float dp, int v) { return Math.round(v * dp); }
}
