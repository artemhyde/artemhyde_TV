package app.glasstv.guard;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Сторож телевизора. Живёт фоном всё время и держит в порядке то, что раньше чинилось руками:
// беспроводную отладку после перезагрузки и смены сети, службу Key Mapper (кнопки пульта),
// принимает записки с телефона из домашней сети и показывает их поверх экрана
public class GuardService extends Service {
    static final String KEYMAPPER = "io.github.sds100.keymapper/io.github.sds100.keymapper.system.accessibility.MyAccessibilityService";
    static final int NOTE_PORT = 8765;

    // Слушать ли порт записок: значение по умолчанию лежит в ресурсе res/values/config.xml
    static boolean notesEnabled(Context c) { return c.getResources().getBoolean(R.bool.notes_enabled_default); }
    static GuardService running;

    final Handler h = new Handler(Looper.getMainLooper());
    SharedPreferences p;
    NoteServer server;
    NoteCard card;
    SmartSound sound;                      // «Умный звук»: выравнивание громкости (SmartSound.java)
    private ConnectivityManager.NetworkCallback netCb;

    static void start(Context c) {
        try { c.startForegroundService(new Intent(c, GuardService.class)); } catch (Exception e) { Log.w("guard", "не запустился", e); }
    }

    @Override public void onCreate() {
        super.onCreate();
        running = this;
        p = getSharedPreferences("guard", MODE_PRIVATE);
        goForeground();

        card = new NoteCard(this);
        server = new NoteServer(this, NOTE_PORT);
        if (notesEnabled(this)) server.start();   // включено ли, решает res/values/config.xml (notes_enabled_default)
        keepAdb("запуск");
        // Сразу после загрузки службы спецвозможностей ещё поднимаются: Key Mapper смотрим через минуту
        h.postDelayed(checkKeys, SystemClock.elapsedRealtime() < 5 * 60_000 ? 60_000 : 3_000);
        h.postDelayed(periodic, 15 * 60_000);
        h.postDelayed(nightTick, 10_000);
        // Рабочая подсветка на случай мягкого включения после сна (до первого сна её ещё никто не запомнил)
        h.postDelayed(() -> { int b = Backlight.get(); if (b > 0 && !isNight()) p.edit().putInt("blBeforeSleep", b).apply(); }, 5_000);
        // Умный звук: если пользователь включал, эффект встанет сам (после загрузки ТВ и после перезапуска службы)
        sound = new SmartSound(this, p, this::log);
        sound.start();
        h.postDelayed(pqTick, 8_000);
        h.postDelayed(() -> picWake("запуск", false), 10_000);   // выбор из «Картинки» после перезагрузки ТВ или обновления Сторожа

        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(events, f, RECEIVER_NOT_EXPORTED);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network net) { h.postDelayed(() -> keepAdb("сеть сменилась"), 4_000); }
        };
        try { getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(netCb); } catch (Exception ignored) { }
        log("Сторож запущен");
    }

    // Каждый запуск через startForegroundService обязан снова объявить службу видимой,
    // иначе система через 10 секунд роняет процесс (так было 26.10 в 18:03)
    @Override public int onStartCommand(Intent i, int flags, int id) {
        goForeground();
        return START_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("guard", "Сторож", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "guard").setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Сторож работает").setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        running = null;
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(events); } catch (Exception ignored) { }
        try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
        if (server != null) server.stop();
        if (veil != null) try { getSystemService(android.view.WindowManager.class).removeView(veil); } catch (Exception ignored) { }
        if (sound != null) { sound.shutdown(); sound = null; }
        super.onDestroy();
    }

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                showWakeVeil();
                homeOnScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                softWake();
                homeCancel();
                // Телевизор включили: через пару секунд проверить пульт и отладку
                h.removeCallbacks(checkKeys);
                h.postDelayed(checkKeys, 4_000);
                h.postDelayed(() -> keepAdb("включение экрана"), 4_000);
                // Звук выключили на время проверки, пока ТВ спал: вернуть, как только экран включился
                if (p.getBoolean("unmuteOnWake", false)) h.postDelayed(() -> {
                    p.edit().remove("unmuteOnWake").apply();
                    AudioManager am = getSystemService(AudioManager.class);
                    if (am.isStreamMute(AudioManager.STREAM_MUSIC)) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                    log("Звук включён обратно после проверки");
                }, 3_000);
                if (sound != null) sound.verifySoon();
                h.postDelayed(() -> applyPq("включение экрана"), 500);   // раньше ночной проверки (+2 с): датчик света должен быть выключен до неё
                // ПРАВКА ПРОВЕРКИ: после пробуждения TCL сам возвращает сохранённую картинку (по комментарию ниже, GuardService.java:109, дневная подсветка возвращается через
                // 2-4 с), поэтому запись на +0,5 с может быть затёрта; второй заход через 12 с ничего не пишет, если вечер на месте, и подхватывает «система сбросила»
                h.postDelayed(() -> applyPq("после пробуждения"), 12_000);
                // Выбор из «Картинки» TCL тоже затирает при пробуждении: три прохода (мягкое включение держит подсветку до 5,8 с, на первом её не трогаем)
                h.postDelayed(() -> picWake("включение экрана", true), 3_000);
                h.postDelayed(() -> picWake("включение экрана", false), 6_500);
                h.postDelayed(() -> picWake("после пробуждения", false), 12_000);
                // После пробуждения TCL может вернуть дневную подсветку: ночью быстро опускаем снова
                h.postDelayed(() -> applyNight(4_000), 2_000);
            } else if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) {
                // Наушники отвалились: пауза, чтобы звук не рявкнул на всю комнату
                AudioManager am = getSystemService(AudioManager.class);
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
                log("Наушники отключились: пауза");
            }
        }
    };

    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            keepAdb("проверка");
            applyPq("проверка");
            checkKeys.run();
            h.postDelayed(this, 15 * 60_000);
        }
    };

    // ===== Экран погас: на главный экран (27.10) =====
    // ТВ должен просыпаться на «Доме», а не в том приложении, где его выключили. Закрывать чужие приложения системой не дано,
    // поэтому на 1,5 с после ACTION_SCREEN_OFF, если экран всё ещё погашен, отправляем на главный экран (его роль у «Дома»).
    // Если идёт видео (медиасессия в состоянии «играет», например режим «Только аудио» у YouTube), экран не трогаем: раз в
    // минуту смотрим, не остановилось ли оно, пока экран погашен, но не дольше 30 минут. Фоновая музыка «Волны» (Aura) не
    // мешает: главный экран её не останавливает. AudioManager.isMusicActive здесь не годится: Lampa держит поток AAudio
    // «запущенным» всё время, даже без звука (проверено на ТВ 27.10). Только Handler, без будильников: Doze не будим.
    // Не спорит с включением: как только экран зажёгся, проверка снимается, а следующее выключение начинает её заново
    static final long HOME_DELAY = 1_500, HOME_POLL = 60_000, HOME_MAX = 30 * 60_000;
    private boolean homePending;   // музыка играла при погасшем экране: главный экран отправим, когда она стихнет
    private long homeSince;        // когда экран погас (отсчёт тридцати минут)
    private final Runnable homeCheck = () -> homeStep("экран погас", true);

    void homeOnScreenOff() {
        h.removeCallbacks(homeCheck);
        homePending = false;
        homeSince = SystemClock.elapsedRealtime();
        h.postDelayed(homeCheck, HOME_DELAY);
    }

    void homeCancel() {
        h.removeCallbacks(homeCheck);
        homePending = false;
    }

    // Проверка без выключения ТВ (Cmd: --es do home): та же логика сразу, экран может быть и включён
    void homeNow() {
        h.removeCallbacks(homeCheck);
        homeSince = SystemClock.elapsedRealtime();
        homeStep("команда home", false);
    }

    // fromOff: вызов от погасшего экрана (таймер); если экран к этому времени включили, ничего не делаем
    void homeStep(String why, boolean fromOff) {
        PowerManager pm = getSystemService(PowerManager.class);
        boolean awake = pm != null && pm.isInteractive();
        if (awake && fromOff) { homePending = false; return; }
        String video = playingVideo();
        if (video != null) {
            if (awake) { log(why + ", идёт видео (" + video + "): главный экран не отправляю"); return; }   // только проверка командой при включённом экране
            if (SystemClock.elapsedRealtime() - homeSince >= HOME_MAX) {
                homePending = false;
                log(why + ", видео идёт 30 минут: главный экран не отправляю");
                return;
            }
            if (!homePending) { homePending = true; log(why + ", идёт видео (" + video + "): главный экран позже"); }
            h.postDelayed(homeCheck, HOME_POLL);   // для команды при погасшем экране ход продолжит тот же таймер
            return;
        }
        String how = homePending ? why + ", видео остановилось" : why;
        homePending = false;
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            log(how + ": на главный экран");
        } catch (Exception e) {
            log(how + ": главный экран не открылся (" + e.getClass().getSimpleName() + ")");
        }
    }

    static final java.util.Set<String> BACKGROUND_AUDIO = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.auramusic.app", "app.glasstv.volna"));

    // Пакет, у которого медиасессия сейчас «играет» (кроме фоновой музыки), или null. Без включённого слушателя Listen
    // система список не даёт: тогда считаем, что ничего не играет, и уводим на главный экран, как раньше просили
    String playingVideo() {
        try {
            MediaSessionManager msm = getSystemService(MediaSessionManager.class);
            if (msm == null) return null;
            for (MediaController mc : msm.getActiveSessions(new ComponentName(this, Listen.class))) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING && !BACKGROUND_AUDIO.contains(mc.getPackageName()))
                    return mc.getPackageName();
            }
        } catch (SecurityException e) {
            log("медиасессии не видны: включите слушатель (cmd notification allow_listener app.glasstv.guard/app.glasstv.guard.Listen)");
        }
        return null;
    }

    // ===== Ночь: с 22:00 до 7:00 экран темнее =====
    // Подсветку панели меняет только TCL (TvBacklightManager, тот же ползунок «Яркость» в быстрой панели), системный
    // screen_brightness до панели не доходит. Ночью «Сторож» плавно опускает саму подсветку: картинка остаётся
    // контрастной, а не серой, как под вуалью. Запись без сохранения: после перезагрузки вернётся дневная.
    // Если включена «Адаптивная регулировка яркости», подсветку ведёт датчик света и перебьёт нас: тогда как раньше
    // ложится тёмная вуаль. С пультом не спорим: если яркость поменяли руками, до утра её не трогаем

    static final int NIGHT_FROM = 22, NIGHT_TO = 7;
    static final int NIGHT_LEVEL = 11;    // ночная подсветка из 100 (днём обычно 45); 27.10 сделана темнее (было 18)
    static final float NIGHT_VEIL = 0.6f;   // система режет прозрачность окна поверх до 0,8: на экране выходит около 0,48
    private android.view.View veil;
    private Runnable ramp;
    private boolean nmActive;   // «Ночной режим» сейчас сам ведёт подсветку

    void setNightModeActive(boolean on) {
        nmActive = on;
        if (on) stopRamp();
        else {
            h.postDelayed(() -> applyNight(4_000), 1_500);   // режим выключили: вернуть свой ночной или дневной уровень
            h.postDelayed(() -> picWake("ночной режим выключен", false), 2_500);   // и подсветку выбора из «Картинки», если он держится
        }
    }

    // «Картинке» нужно знать, ведёт ли «Ночной режим» подсветку и идёт ли мягкое включение
    boolean nmActive() { return nmActive; }

    // Повтор выбора из «Картинки» (Pic.java): ничего не пишет, если выбора нет или он уже снят границей 22:00 и 7:00
    void picWake(String why, boolean softWakeBusy) {
        Pic.expireDue(this, p);
        Pic.reapply(getApplicationContext(), p, why, softWakeBusy && wakeStep != null);
    }

    static boolean isNight() {
        int hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hr >= NIGHT_FROM || hr < NIGHT_TO;
    }

    boolean nightEnabled() { return p.getBoolean("nightEnabled", true); }

    int nightLevel() { return p.getInt("nightLevel", NIGHT_LEVEL); }

    void applyNight() { applyNight(60_000); }

    void applyNight(long fadeMs) {
        Pic.expireDue(this, p);   // граница прошла: выбор из «Картинки» снят и подсветка вернулась до того, как ночной ход решит, что делать
        if (nightEnabled() && isNight()) startNight(fadeMs);
        else endNight(nightEnabled() ? fadeMs : 1_500);
    }

    void startNight(long fadeMs) {
        // Выбор пользователя в «Картинке» держится до границы 22:00 или 7:00: ни хода подсветки, ни вуали (её он бы затемнил сверх выбора)
        if (Pic.held(p)) { if (veil != null) hideVeil(1_500); return; }
        int sensor = Backlight.lightSensor(), cur = Backlight.get();
        if (sensor == 1 || cur < 0) {
            // Подсветку ведёт датчик (или служба TCL недоступна): только вуаль
            if (veil == null) showVeil(fadeMs);
            p.edit().putString("nightHow", "вуаль").apply();
            return;
        }
        if (veil != null) hideVeil(1_500);
        p.edit().putString("nightHow", "подсветка").apply();
        if (nmActive || saverActive || p.getBoolean("nightManual", false) || ramp != null) return;
        int set = p.getInt("blSet", -1), day = p.getInt("blDay", -1), night = nightLevel();
        if (set >= 0 && cur == set) return;   // уже ночная
        if (set >= 0 && cur != day) {
            // Мы ставили одно, а сейчас другое, и это не дневная после пробуждения: поменяли с пульта
            p.edit().putBoolean("nightManual", true).apply();
            log("Яркость поменяли с пульта (" + cur + "): до утра не трогаю");
            return;
        }
        if (cur <= night) return;   // и так темно
        p.edit().putInt("blDay", cur).apply();
        log("Вечер: подсветка " + cur + " → " + night + " до 7:00");
        rampTo(cur, night, fadeMs, true);
    }

    void endNight(long fadeMs) {
        if (nmActive || saverActive) return;   // утро во время «Ночного режима» или заставки: вернём, когда они кончатся
        if (veil != null) {
            hideVeil(fadeMs);
            log(isNight() ? "Ночное затемнение выключено" : "Утро: вуаль убрана");
        }
        stopRamp();
        if (Pic.held(p)) return;   // выбор из «Картинки» держится: дневную подсветку не возвращаем
        int day = p.getInt("blDay", -1), set = p.getInt("blSet", -1);
        boolean manual = p.getBoolean("nightManual", false);
        p.edit().remove("nightManual").remove("nightHow").apply();
        if (day < 0) return;
        int cur = Backlight.get();
        if (cur >= 0 && cur < day && (cur == set || !manual)) {
            log(isNight() ? "Ночная подсветка выключена: снова " + day : "Утро: подсветка снова " + day);
            rampTo(cur, day, fadeMs, false);
        } else {
            p.edit().remove("blDay").remove("blSet").apply();
        }
    }

    // Плавно, по одному делению: за минуту на глаз незаметно. Каждый шаг сверяет, что подсветку не трогали
    private void rampTo(int from, int to, long ms, boolean night) {
        stopRamp();
        int steps = Math.max(1, Math.abs(to - from));
        long every = Math.max(120, ms / steps);
        final int dir = to > from ? 1 : -1;
        p.edit().putInt("blSet", from).apply();
        ramp = new Runnable() {
            int v = from;
            @Override public void run() {
                int cur = Backlight.get();
                if (nmActive || saverActive || Pic.held(p)) { ramp = null; return; }   // выбор в «Картинке» сильнее ночного хода
                if (cur != v) {
                    ramp = null;
                    if (night) {
                        p.edit().putBoolean("nightManual", true).apply();
                        log("Яркость поменяли с пульта (" + cur + "): до утра не трогаю");
                    }
                    return;
                }
                v += dir;
                Backlight.set(v, false);
                p.edit().putInt("blSet", v).apply();
                if (v == to) {
                    ramp = null;
                    if (!night) p.edit().remove("blDay").remove("blSet").apply();
                    return;
                }
                h.postDelayed(this, every);
            }
        };
        h.post(ramp);
    }

    void stopRamp() {
        if (ramp != null) h.removeCallbacks(ramp);
        ramp = null;
    }

    // ===== Мягкое включение (27.10) =====
    // ТВ в режиме «Всегда онлайн» просыпается за секунду и сразу на прежнем месте, без всякого перехода.
    // Перед сном кладём тёмную вуаль (система не даёт окну поверх плотнее 0,8), а при включении подсветка панели
    // поднимается с нуля до рабочей за 800 мс и вуаль растворяется за секунду. TCL через 2–4 с после пробуждения
    // сама возвращает сохранённую дневную подсветку: 5 секунд держим нашу (ночью это ночной уровень)
    private android.view.View wakeVeil;
    private Runnable wakeStep;

    boolean softWakeEnabled() { return p.getBoolean("softWake", true); }

    void showWakeVeil() {
        // Рабочая подсветка запоминается ДО сна: в первые мгновения после пробуждения TCL отдаёт 0 (проверено 27.10,
        // иначе включение уводило подсветку в ноль и держало её там)
        int before = Backlight.get();
        if (before > 0 && !saverActive && !nmActive) p.edit().putInt("blBeforeSleep", before).apply();
        if (!softWakeEnabled() || wakeVeil != null) return;
        android.view.View v = new android.view.View(this);
        v.setBackgroundColor(0xFF000000);
        v.setAlpha(0.8f);
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(-1, -1,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.setTitle("guard-wake");
        try {
            getSystemService(android.view.WindowManager.class).addView(v, lp);
            wakeVeil = v;
        } catch (Exception ignored) { }
    }

    void softWake() {
        if (!softWakeEnabled()) return;
        if (wakeVeil == null) showWakeVeil();
        final android.view.View v = wakeVeil;
        wakeVeil = null;
        if (v != null) v.animate().alpha(0f).setDuration(1_000).withEndAction(() -> {
            try { getSystemService(android.view.WindowManager.class).removeView(v); } catch (Exception ignored) { }
        }).start();
        if (nmActive || saverActive || Backlight.lightSensor() != 0) return;   // подсветку ведёт не «Сторож»
        int set = p.getInt("blSet", -1), before = p.getInt("blBeforeSleep", -1);
        final int pic = Pic.wantBacklight(p);   // выбор из «Картинки» держится: поднимаем до него
        final int target = pic > 0 ? pic : isNight() && nightEnabled() && !p.getBoolean("nightManual", false) && set > 0 ? set : before;
        if (target <= 0) return;   // не знаем рабочую: подсветку не трогаем, остаётся только растворение вуали
        if (wakeStep != null) h.removeCallbacks(wakeStep);
        final long start = android.os.SystemClock.uptimeMillis();
        Backlight.set(0, false);
        wakeStep = new Runnable() {
            @Override public void run() {
                long t = android.os.SystemClock.uptimeMillis() - start;
                if (t < 800) {
                    float k = t / 800f;
                    k = 1f - (1f - k) * (1f - k);   // плавный разгон в конце медленнее
                    Backlight.set(Math.round(target * k), false);
                    h.postDelayed(this, 40);
                } else if (t < 5_800) {
                    if (Backlight.get() != target) Backlight.set(target, false);   // TCL вернула свою: ставим нашу
                    h.postDelayed(this, 200);
                } else {
                    wakeStep = null;
                }
            }
        };
        h.post(wakeStep);
    }

    // ===== Заставка: через 15 минут простоя подсветка вдвое (27.10) =====
    // Заставку «Дома» шлёт --es do saver --ez on true|false; любая кнопка (конец заставки) возвращает сразу
    private boolean saverActive;
    private int saverPrev = -1;

    void setSaver(boolean on) {
        if (on) {
            if (saverActive || nmActive || Backlight.lightSensor() != 0) return;
            int cur = Backlight.get();
            if (cur <= 0) return;
            saverActive = true;
            saverPrev = cur;
            stopRamp();
            final int from = cur, to = Math.max(1, Math.round(cur / 2f));
            final long start = android.os.SystemClock.uptimeMillis();
            h.post(new Runnable() {
                @Override public void run() {
                    if (!saverActive) return;
                    float k = Math.min(1f, (android.os.SystemClock.uptimeMillis() - start) / 4_000f);
                    Backlight.set(Math.round(from + (to - from) * k), false);
                    if (k < 1f) h.postDelayed(this, 100);
                }
            });
            log("Заставка: подсветка вдвое, " + from + " → " + to);
        } else {
            if (!saverActive) return;
            saverActive = false;
            if (saverPrev >= 0) Backlight.set(saverPrev, false);
            saverPrev = -1;
            h.postDelayed(() -> applyNight(4_000), 1_000);
        }
    }

    void showVeil(long fadeMs) {
        if (veil != null) return;
        android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
        android.view.View v = new android.view.View(this);
        v.setBackgroundColor(0xFF000000);
        v.setAlpha(0f);
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(-1, -1,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.setTitle("guard-night");
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            log("Ночная вуаль не встала: нет права поверх окон");
            return;
        }
        veil = v;
        v.animate().alpha(NIGHT_VEIL).setDuration(fadeMs).start();
        log("Вечер: подсветку ведёт датчик света, поэтому экран темнее вуалью до 7:00");
    }

    void hideVeil(long fadeMs) {
        android.view.View v = veil;
        veil = null;
        if (v == null) return;
        v.animate().alpha(0f).setDuration(fadeMs).withEndAction(() -> {
            try { getSystemService(android.view.WindowManager.class).removeView(v); } catch (Exception ignored) { }
        }).start();
    }

    // Кнопка на экране «Сторож»: ночное затемнение вкл/выкл
    void setNightEnabled(boolean on) {
        p.edit().putBoolean("nightEnabled", on).apply();
        applyNight();
    }

    // Следующая проверка ровно на границе 22:00 или 7:00 (плюс несколько секунд)
    private final Runnable nightTick = new Runnable() {
        @Override public void run() {
            applyNight();
            java.util.Calendar c = java.util.Calendar.getInstance();
            java.util.Calendar next = (java.util.Calendar) c.clone();
            next.set(java.util.Calendar.MINUTE, 0);
            next.set(java.util.Calendar.SECOND, 5);
            int hr = c.get(java.util.Calendar.HOUR_OF_DAY);
            if (hr < NIGHT_TO) next.set(java.util.Calendar.HOUR_OF_DAY, NIGHT_TO);
            else if (hr < NIGHT_FROM) next.set(java.util.Calendar.HOUR_OF_DAY, NIGHT_FROM);
            else { next.add(java.util.Calendar.DAY_OF_MONTH, 1); next.set(java.util.Calendar.HOUR_OF_DAY, NIGHT_TO); }
            h.postDelayed(this, Math.max(60_000, next.getTimeInMillis() - c.getTimeInMillis()));
        }
    };

    // ===== Картинка TCL: «Вечер» с 22:00 и «День» с 7:00 (Pq.java) =====
    // Границы те же, что у ночи (NIGHT_FROM, NIGHT_TO). Правило «не спорить с пультом» живёт в Pq.tick():
    // что записали мы, то помним; если на панели уже другое, до следующего перехода это не трогаем.
    // Подсветку ведёт ночной ход выше, поэтому Pq её не касается
    static final java.util.Set<String> PQ_SKIP = new java.util.HashSet<String>(java.util.Arrays.asList("backlight"));

    boolean pqAuto() { return p.getBoolean("pqAuto", false); }

    // Выбор из «Картинки» (Pic.java) сильнее этой автоматики: пока он держится, «Вечер» и «День» молчат. Граница 22:00 или 7:00
    // снимает выбор (возвращает то, что было до него) раньше, чем сюда попадёт следующее решение: Pic.expireDue идёт первым в той же очереди
    void applyPq(String why) {
        Pic.expireDue(this, p);
        if (!pqAuto()) return;
        if (Pic.held(p)) return;
        final boolean evening = isNight();
        final Context app = getApplicationContext();
        Pq.async(() -> {
            if (Pic.held(p)) return;   // выбор успели сделать, пока задача стояла в очереди
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && !pm.isInteractive()) return;   // экран спит: дождёмся ACTION_SCREEN_ON
            final String r = Pq.tick(app, evening, why, PQ_SKIP);
            if (r != null) h.post(() -> log("Картинка: " + r));
        });
    }

    // Граница ночи для картинки: на 22:00:02 и 7:00:02, за три секунды до nightTick (он в :05)
    private final Runnable pqTick = new Runnable() {
        @Override public void run() {
            applyPq("таймер");
            java.util.Calendar c = java.util.Calendar.getInstance();
            java.util.Calendar next = (java.util.Calendar) c.clone();
            next.set(java.util.Calendar.MINUTE, 0);
            next.set(java.util.Calendar.SECOND, 2);
            int hr = c.get(java.util.Calendar.HOUR_OF_DAY);
            if (hr < NIGHT_TO) next.set(java.util.Calendar.HOUR_OF_DAY, NIGHT_TO);
            else if (hr < NIGHT_FROM) next.set(java.util.Calendar.HOUR_OF_DAY, NIGHT_FROM);
            else { next.add(java.util.Calendar.DAY_OF_MONTH, 1); next.set(java.util.Calendar.HOUR_OF_DAY, NIGHT_TO); }
            h.postDelayed(this, Math.max(60_000, next.getTimeInMillis() - c.getTimeInMillis()));
        }
    };

    // ===== Беспроводная отладка: Android выключает её после перезагрузки и смены сети =====

    boolean adbOn() { return Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1; }

    void keepAdb(String why) {
        try {
            if (!adbOn()) {
                Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                log("Отладка по сети включена (" + why + ")");
            }
        } catch (SecurityException e) {
            log("Нет права менять настройки: выдать с ПК WRITE_SECURE_SETTINGS");
        }
    }

    // ===== Key Mapper: если его служба упала, фирменные кнопки пульта молчат =====

    boolean keyMapperAlive() {
        AccessibilityManager am = getSystemService(AccessibilityManager.class);
        ComponentName want = ComponentName.unflattenFromString(KEYMAPPER);
        for (AccessibilityServiceInfo s : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ComponentName c = ComponentName.unflattenFromString(s.getId());
            if (want.equals(c)) return true;
        }
        return false;
    }

    final Runnable checkKeys = () -> {
        if (keyMapperAlive()) { p.edit().putString("keys", "работает").apply(); return; }
        ContentResolver cr = getContentResolver();
        try {
            String was = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String others = without(was, "io.github.sds100.keymapper");
            // Выключить и снова включить службу: система заново её поднимет
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others);
            h.postDelayed(() -> {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        others.isEmpty() ? KEYMAPPER : others + ":" + KEYMAPPER);
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                h.postDelayed(() -> {
                    boolean ok = keyMapperAlive();
                    p.edit().putString("keys", ok ? "перезапущен в " + now() : "не поднялся").apply();
                    log(ok ? "Key Mapper упал и перезапущен" : "Key Mapper не поднялся: открыть его и нажать Fix");
                }, 3_000);
            }, 800);
        } catch (SecurityException e) {
            log("Key Mapper упал, а прав перезапустить нет");
        }
    };

    private static String without(String list, String pkg) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String s : list.split(":")) {
            if (s.isEmpty() || s.startsWith(pkg + "/")) continue;
            if (b.length() > 0) b.append(':');
            b.append(s);
        }
        return b.toString();
    }

    // ===== Записки =====

    void showNote(String text) {
        h.post(() -> card.show(text));
        log("Записка: " + text);
    }

    // ===== Журнал для экрана «Сторож» =====

    static String now() { return new SimpleDateFormat("d MMM, H:mm", Locale.forLanguageTag("ru")).format(new Date()); }

    void log(String s) {
        Log.i("guard", s);
        String all = p.getString("log", "");
        String line = now() + "  " + s;
        String[] old = all.isEmpty() ? new String[0] : all.split("\n");
        StringBuilder b = new StringBuilder(line);
        for (int i = 0; i < Math.min(old.length, 11); i++) b.append('\n').append(old[i]);
        p.edit().putString("log", b.toString()).apply();
    }
}
