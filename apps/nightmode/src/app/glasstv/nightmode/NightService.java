package app.glasstv.nightmode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
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
import android.provider.Settings;
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
    static final String START = "start", ADD = "add", STYLE = "style", STOP = "stop", LEVEL = "level";
    // Один сквозной ползунок «Яркость» (27.10): слева красная зона, справа настоящая подсветка панели.
    // Шкала слева направо: затемнение поверх экрана 80, 70 … 10 % (шаг 10, подсветка при этом 0), отметка «0», дальше подсветка
    // панели 5, 10 … 100 (шаг 5, TvBacklightManager TCL, тот же ползунок «Яркость», что в меню картинки).
    // Уровень это одно число со знаком: -80..-10 затемнение в %, 0..100 подсветка. Он лежит в prefs "lvl" и есть сам ночной режим:
    // службу будит любое движение ползунка, а выбранное она держит, пока не выключат (или не кончится таймер).
    // Подсветкой управлять нельзя (включена «Адаптивная регулировка яркости» TCL или нет службы): шкала короче и вся из затемнения
    // 80 … 0 % (шаг 5), уровень тогда -80..0, всё положительное считается «без затемнения»
    static final int RED_STEPS = 8, BL_STEPS = 20, ZERO = RED_STEPS, STEPS = RED_STEPS + BL_STEPS, VEIL_STEPS = 16;
    static final int MIN_LEVEL = -80, MAX_LEVEL = 100;
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

    WindowManager wm;
    Veil shade;
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
        migrate(prefs);
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
        }
        return START_STICKY;
    }

    void begin(long showBadgeMs) {
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
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
        try { if (badge != null) wm.removeView(badge); } catch (Exception ignored) { }
        shade = null;
        badge = null;
    }

    // Шкала ползунка: число шагов и шаг отметки «0» (с подсветкой и без неё)
    static int steps(boolean bl) { return bl ? STEPS : VEIL_STEPS; }

    static int zero(boolean bl) { return bl ? ZERO : -1; }

    // Уровень на шаге шкалы и обратно
    static int levelAt(int idx, boolean bl) {
        if (!bl) return (idx - VEIL_STEPS) * 5;
        return idx < ZERO ? (idx - ZERO) * 10 : (idx - ZERO) * 5;
    }

    static int indexOf(int lvl, boolean bl) {
        int i = bl ? ZERO + Math.round(lvl / (lvl < 0 ? 10f : 5f)) : VEIL_STEPS + (lvl < 0 ? Math.round(lvl / 5f) : 0);
        return Math.max(0, Math.min(steps(bl), i));
    }

    // Уровень, который отвечает подсветке телевизора сейчас (до шага 5); -1 у подсветки значит «не знаем», тогда без изменений
    static int levelOf(int backlight) {
        return backlight < 0 ? 0 : Math.min(MAX_LEVEL, Math.round(backlight / 5f) * 5);
    }

    // Выбранный уровень. Пока его никто не выбирал, ночь идёт на нынешней яркости (а не на нуле подсветки)
    static int level(SharedPreferences prefs, boolean bl) {
        if (!prefs.contains("lvl")) return levelOf(bl ? Backlight.get() : -1);
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, prefs.getInt("lvl", 0)));
    }

    // Прошлые версии хранили выбор иначе: "pos" (шаг 0..20, слева светлее: 16 шагов подсветки от рабочей до нуля, дальше 4 шага
    // затемнения 20..80 %), ещё раньше "mode" («Вполовину» или «Минимум») и "dim" (четыре уровня). Переносим по смыслу:
    // «Вполовину» это половина обычной подсветки, «Минимум» затемнение 80 %. Нужно, пока служба после обновления
    // подхватывает идущую ночь
    static void migrate(SharedPreferences prefs) {
        if (!prefs.contains("pos") && !prefs.contains("mode") && !prefs.contains("dim")) return;
        if (!prefs.contains("lvl")) {
            int p;
            if (prefs.contains("pos")) {
                p = prefs.getInt("pos", 8);
            } else {
                int m = prefs.contains("mode") ? prefs.getInt("mode", 0) : (prefs.getInt("dim", 1) >= 2 ? 1 : 0);
                p = m >= 1 ? 20 : 8;
            }
            p = Math.max(0, Math.min(20, p));
            int lvl;
            if (p > 16) {
                lvl = -20 * (p - 16);
            } else {
                int base = prefs.getInt("bl0", -1);
                if (base < 0) base = Backlight.get();
                lvl = base < 0 ? 0 : levelOf(Math.round(base * (1f - p / 16f)));
            }
            prefs.edit().putInt("lvl", lvl).apply();
        }
        prefs.edit().remove("pos").remove("mode").remove("dim").apply();
    }

    // Можно ли вести подсветку самим: датчик света TCL выключен и служба отвечает
    static boolean canBacklight() { return Backlight.lightSensor() == 0 && Backlight.get() >= 0; }

    // Вуаль поверх экрана на уровне: только красная зона (подсветка при этом 0), правее нуля вуали нет
    static float veil(int lvl) { return lvl < 0 ? -lvl / 100f : 0f; }

    // Плотность слоя поверх. Правее нуля слоя нет совсем: это настоящая подсветка, вправо светлее (иначе 50 вышло бы темнее
    // исходных 45). В красной зоне вуаль уровня, а тёплый свет (если включён) добавляет янтарь сверху. Слои складываются как
    // прозрачности, 1-(1-a)(1-w), поэтому каждый шаг вниз виден; плотнее CAP не бывает
    static float overlay(SharedPreferences prefs, int lvl) {
        float a = veil(lvl);
        if (a == 0f || !prefs.getBoolean("warm", true)) return a;
        float w = WARM * Math.min(1f, a / WARM_RAMP);
        return Math.min(CAP, a + w * (1f - a));
    }

    // Подсветка на шаге шкалы правее нуля. Шаг, где стояла обычная подсветка (до округления до 5), держит её точное число:
    // «исходное» на ползунке и есть исходное, а не соседнее круглое
    int backlightAt(int lvl) {
        int b = prefs.getInt("bl0", -1);
        return b >= 0 && levelOf(b) == lvl ? b : lvl;
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
        if (b >= 0) Backlight.set(b, false);
        // Старые версии писали системную яркость: вернуть и её, если осталась
        int b0 = prefs.getInt("b0", -1);
        if (b0 >= 0) try { Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, b0); } catch (Exception ignored) { }
        prefs.edit().remove("bl0").remove("b0").apply();
    }

    // «Сторож» ночью сам ведёт подсветку: пока мы её меняем, пусть не принимает это за пульт
    static void notifyGuard(Context c, boolean on) {
        try {
            c.sendBroadcast(new Intent().setClassName("app.glasstv.guard", "app.glasstv.guard.Cmd").putExtra("do", "nm").putExtra("on", on));
        } catch (Exception ignored) { }
    }

    void applyBacklight(int target) {
        if (target == lastBacklight || target < 0) return;
        Backlight.set(target, false);
        lastBacklight = target;
    }

    void applyShade() {
        if (shade == null) return;
        int lvl = level(prefs, useBl);
        float a = stuck ? CAP : overlay(prefs, lvl);
        if (useBl) base(prefs);
        // Подсветка: выбранная правее нуля, в красной зоне ноль; без управления ей не трогаем (-1)
        int bl = !useBl ? -1 : stuck || lvl < 0 ? MIN_BACKLIGHT : Math.max(MIN_BACKLIGHT, backlightAt(lvl));
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
        if (!prefs.getBoolean("active", false)) restoreBacklight(this, prefs);
        running = false;
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
