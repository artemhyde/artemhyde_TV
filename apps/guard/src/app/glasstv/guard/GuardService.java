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
        h.postDelayed(idleTick, 60_000);
        h.postDelayed(svcTick, 90_000);
        noDream();
        new VolumeHold(this);
        // Умный звук: если пользователь включал, эффект встанет сам (после загрузки ТВ и после перезапуска службы)
        sound = new SmartSound(this, p, this::log);
        sound.start();
        h.postDelayed(pqTick, 8_000);
        h.postDelayed(() -> picWake("запуск"), 10_000);   // выбор из «Картинки» после перезагрузки ТВ или обновления Сторожа

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
                if (saverActive) setSaver(false);
                homeOnScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                homeCancel();
                // Телевизор включили: через пару секунд проверить пульт и отладку
                h.removeCallbacks(checkKeys);
                h.postDelayed(checkKeys, 4_000);
                h.postDelayed(() -> keepAdb("включение экрана"), 4_000);
                h.postDelayed(svcTick, 20_000);   // сеть после сна поднимается не сразу
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
                // Выбор из «Картинки» TCL тоже затирает при пробуждении: три прохода
                h.postDelayed(() -> picWake("включение экрана"), 3_000);
                h.postDelayed(() -> picWake("включение экрана"), 6_500);
                h.postDelayed(() -> picWake("после пробуждения"), 12_000);
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
            noDream();
            applyPq("проверка");
            checkKeys.run();
            h.postDelayed(this, 15 * 60_000);
        }
    };

    // ===== Экран погас: на главный экран (27.10) =====
    // ТВ должен просыпаться на «Доме», а не в том приложении, где его выключили. Закрывать чужие приложения системой не дано,
    // поэтому через 2 с после ACTION_SCREEN_OFF, если экран всё ещё погашен, отправляем на главный экран (его роль у «Дома»).
    // При включении ничего не делаем: подсветку не трогаем и вуаль не кладём, экран загорается сразу (ноябрь: мягкое включение
    // сбрасывало подсветку в 0 и поднимало её за 800 мс, а плата TCL к этому моменту уже включила матрицу: выходило двойное мигание).
    // Если идёт видео (медиасессия в состоянии «играет», например режим «Только аудио» у YouTube), экран не трогаем: раз в
    // минуту смотрим, не остановилось ли оно, пока экран погашен, но не дольше 30 минут. Фоновая музыка «Волны» (Aura) не
    // мешает: главный экран её не останавливает. AudioManager.isMusicActive здесь не годится: Lampa держит поток AAudio
    // «запущенным» всё время, даже без звука (проверено на ТВ 27.10). Только Handler, без будильников: Doze не будим.
    // Не спорит с включением: как только экран зажёгся, проверка снимается, а следующее выключение начинает её заново
    static final long HOME_DELAY = 2_000, HOME_POLL = 60_000, HOME_MAX = 30 * 60_000;
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
            startActivity(homeIntent(true));
            log(how + ": на главный экран");
        } catch (Exception e) {
            log(how + ": главный экран не открылся (" + e.getClass().getSimpleName() + ")");
        }
    }

    static final java.util.Set<String> BACKGROUND_AUDIO = new java.util.HashSet<>(java.util.Arrays.asList(
            "app.glasstv.aura", "app.glasstv.volna"));

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

    // ===== Простой: один таймер вместо заставки Android (ноябрь) =====
    // Заставки Android нет совсем (screensaver_enabled 0): это был второй экран поверх всего. «Дом» сам ведёт простой у себя,
    // а для остальных окон его ведёт «Сторож»: 10 минут без нажатий, ничего не идёт, и открывается экран блокировки «Дома».
    // Время последнего нажатия и замки экрана знает только служба питания, поэтому раз в минуту читаем dumpsys power (право DUMP
    // выдаётся с ПК: pm grant app.glasstv.guard android.permission.DUMP). Плеер, который идёт, держит замок экрана
    // (SCREEN_BRIGHT_WAKE_LOCK), и это видно здесь же, в том числе у плееров без медиасессии (Lampa, браузер)
    static final long IDLE_APP_MS = 10 * 60_000;
    private long idleSentFor = -1;     // нажатие, после которого блокировку уже открыли: второй раз не открываем
    private boolean idleDenied;
    private static final java.util.regex.Pattern LAST_USER = java.util.regex.Pattern.compile("lastUserActivityTime=(\\d+) \\((\\d+) ms ago\\)");

    private final Runnable idleTick = new Runnable() {
        @Override public void run() {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && pm.isInteractive() && homeIsOurs()) new Thread(GuardService.this::checkIdle, "guard-idle").start();
            h.postDelayed(this, 60_000);
        }
    };

    private void checkIdle() {
        long last = -1, ago = -1;
        boolean keepOn = false;
        try {
            Process pr = new ProcessBuilder("dumpsys", "power").redirectErrorStream(true).start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()))) {
                for (String s; (s = r.readLine()) != null; ) {
                    if (last < 0) {
                        java.util.regex.Matcher m = LAST_USER.matcher(s);
                        if (m.find()) { last = Long.parseLong(m.group(1)); ago = Long.parseLong(m.group(2)); }
                    }
                    if (s.contains("SCREEN_BRIGHT_WAKE_LOCK") || s.contains("SCREEN_DIM_WAKE_LOCK") || s.contains("FULL_WAKE_LOCK")) keepOn = true;
                }
            }
            pr.waitFor();
        } catch (Exception e) {
            ago = -1;
        }
        if (ago < 0) {
            if (!idleDenied) h.post(() -> log("Простой не виден: выдать с ПК pm grant app.glasstv.guard android.permission.DUMP"));
            idleDenied = true;
            return;
        }
        idleDenied = false;
        if (ago < IDLE_APP_MS || keepOn || last == idleSentFor || playingVideo() != null) return;
        idleSentFor = last;
        h.post(() -> {
            try {
                startActivity(homeIntent(false));
                log("Простой 10 минут: экран блокировки");
            } catch (Exception e) {
                log("Простой: главный экран не открылся (" + e.getClass().getSimpleName() + ")");
            }
        });
    }

    // Главный экран: idle просит его сразу встать на экран блокировки, now: без переходов (ТВ выключили)
    Intent homeIntent(boolean now) {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("idle", true).putExtra("now", now);
    }

    // Простой и заставку ведём, только если главный экран сейчас «Дом»: иначе это не наш экран, Android разберётся сам
    boolean homeIsOurs() {
        android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && "app.glasstv.home".equals(r.activityInfo.packageName);
    }

    // Заставка Android могла вернуться (обновление прошивки, настройки): держим её выключенной
    void noDream() {
        if (!homeIsOurs()) return;
        try {
            ContentResolver cr = getContentResolver();
            if (Settings.Secure.getInt(cr, "screensaver_enabled", 1) != 0) {
                Settings.Secure.putInt(cr, "screensaver_enabled", 0);
                log("Заставка Android снова выключена");
            }
            // Без заставки по этому таймеру экран просто погас бы посреди чего угодно
            if (Settings.System.canWrite(this) && Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 0) < Integer.MAX_VALUE) {
                Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, Integer.MAX_VALUE);
            }
        } catch (Exception ignored) { }
    }

    // ===== Подсветка: только на границах 22:00 и 7:00 (ноябрь) =====
    // Подсветку панели меняет только TCL (TvBacklightManager, тот же ползунок «Яркость» в быстрой панели), системный
    // screen_brightness до панели не доходит. «Сторож» трогает её ровно два раза в сутки и больше никогда: ни при включении
    // экрана, ни по таймерам, ни после ночного режима. Сутки начинаются в 7:00.
    // В 22:00 сверяем подсветку с той, что осталась после прошлой границы (blBase). Не совпала: сегодня яркость меняли руками
    // (панель TCL, наш ползунок, «Картинка»), и ночной уровень не ставим. Поменяли после 22:00: сюда мы уже не вернёмся.
    // В 7:00 возвращаем дневную, только если ночной уровень ставил сам «Сторож» (тогда есть blDay).
    // Запись с сохранением: чтобы при включении ТВ не вернул прошлую яркость. Подсветка читается и пишется и на выключенном ТВ
    // (проверено 02.11), поэтому границу можно пройти и во сне, при включении ничего догонять не надо.
    // Если включена «Адаптивная регулировка яркости» или службы TCL нет, вместо подсветки на ночь ложится тёмная вуаль

    static final int NIGHT_FROM = 22, NIGHT_TO = 7;
    static final int NIGHT_LEVEL = 11;    // ночная подсветка из 100
    static final float NIGHT_VEIL = 0.6f;   // система режет прозрачность окна поверх до 0,8: на экране выходит около 0,48
    private android.view.View veil;
    private Runnable ramp;
    private boolean nmActive;   // «Ночной режим» сейчас сам ведёт подсветку

    void setNightModeActive(boolean on) {
        nmActive = on;
        if (on) { stopRamp(); return; }
        h.postDelayed(() -> picWake("ночной режим выключен"), 2_500);   // подсветку выбора из «Картинки», если он держится
        // 7:00 прошли, пока подсветку вёл «Ночной режим»: дневную возвращаем сейчас
        h.postDelayed(() -> {
            int day = p.getInt("blDay", -1);
            if (day < 0 || isNight() || nmActive) return;
            p.edit().remove("blDay").apply();
            int cur = Backlight.get();
            if (cur >= 0 && cur != day) { log("Ночной режим выключен днём: подсветка снова " + day); moveBacklight(cur, day); }
        }, 3_000);
    }

    // Дневная подсветка с ПК: днём сразу, ночью до 7:00
    void setDayLevel(int v) {
        if (isNight()) {
            p.edit().putInt("blDay", v).apply();
            log("Дневная подсветка " + v + ": вернётся в 7:00, ночная " + nightLevel());
            return;
        }
        stopRamp();
        p.edit().remove("blDay").apply();
        if (nmActive) { p.edit().putInt("blDay", v).apply(); return; }
        Backlight.set(v, true);
        p.edit().putInt("blBase", v).apply();
        log("Дневная подсветка " + v);
    }

    // «Картинке» нужно знать, ведёт ли «Ночной режим» подсветку
    boolean nmActive() { return nmActive; }

    // Повтор выбора из «Картинки» (Pic.java): ничего не пишет, если выбора нет или он уже снят границей 22:00 и 7:00
    void picWake(String why) {
        Pic.expireDue(this, p);
        Pic.reapply(getApplicationContext(), p, why);
    }

    static boolean isNight() {
        int hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hr >= NIGHT_FROM || hr < NIGHT_TO;
    }

    boolean nightEnabled() { return p.getBoolean("nightEnabled", true); }

    int nightLevel() { return p.getInt("nightLevel", NIGHT_LEVEL); }

    // Последняя уже наступившая граница, в миллисекундах
    static long lastBoundary(long now) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(now);
        int hr = c.get(java.util.Calendar.HOUR_OF_DAY);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        if (hr >= NIGHT_FROM) c.set(java.util.Calendar.HOUR_OF_DAY, NIGHT_FROM);
        else if (hr >= NIGHT_TO) c.set(java.util.Calendar.HOUR_OF_DAY, NIGHT_TO);
        else { c.add(java.util.Calendar.DAY_OF_MONTH, -1); c.set(java.util.Calendar.HOUR_OF_DAY, NIGHT_FROM); }
        return c.getTimeInMillis();
    }

    // Раз в полминуты сверяем часы с границей: так она не теряется, даже если процессор спал или «Сторож» перезапускался
    private final Runnable nightTick = new Runnable() {
        @Override public void run() {
            long b = lastBoundary(System.currentTimeMillis()), done = p.getLong("boundaryAt", 0);
            if (done == 0) {
                // Первый запуск этой версии: границу не отрабатываем, только запоминаем, что сейчас стоит
                int cur = Backlight.get();
                android.content.SharedPreferences.Editor e = p.edit().putLong("boundaryAt", b)
                        .remove("nightManual").remove("blSet").remove("blBeforeSleep").remove("softWake");
                if (cur >= 0) e.putInt("blBase", cur);
                if (!isNight()) e.remove("blDay");
                e.apply();
            } else if (b > done) {
                p.edit().putLong("boundaryAt", b).apply();
                onBoundary(isNight());
            }
            h.postDelayed(this, 30_000);
        }
    };

    // Граница: 22:00 (night) или 7:00. Единственное место, где «Сторож» сам меняет подсветку
    void onBoundary(boolean night) {
        Pic.expireDue(this, p);   // граница снимает выбор из «Картинки», он возвращает то, что было до него
        if (!nightEnabled()) return;
        int sensor = Backlight.lightSensor(), cur = saverActive ? saverPrev : Backlight.get();
        if (sensor == 1 || cur < 0) {
            // Подсветку ведёт датчик (или служба TCL недоступна): только вуаль
            p.edit().putString("nightHow", night ? "вуаль" : "").apply();
            if (night) showVeil(60_000); else if (veil != null) { hideVeil(60_000); log("Утро: вуаль убрана"); }
            return;
        }
        if (veil != null) hideVeil(1_500);
        p.edit().putString("nightHow", night ? "подсветка" : "").apply();
        if (nmActive) {
            log((night ? "22:00" : "7:00") + ": подсветку ведёт «Ночной режим», не трогаю");
            return;
        }
        if (night) {
            int base = p.getInt("blBase", -1), level = nightLevel();
            if (base >= 0 && cur != base) {
                log("22:00: яркость сегодня меняли (" + base + " → " + cur + "), не трогаю до завтра");
                return;
            }
            if (cur <= level) { log("22:00: и так темно (" + cur + ")"); return; }
            p.edit().putInt("blDay", cur).apply();
            log("Вечер: подсветка " + cur + " → " + level + " до 7:00");
            moveBacklight(cur, level);
        } else {
            int day = p.getInt("blDay", -1);
            p.edit().remove("blDay").apply();
            if (day < 0 || cur == day) { p.edit().putInt("blBase", cur).apply(); return; }
            log("Утро: подсветка снова " + day);
            moveBacklight(cur, day);
        }
    }

    // На работающем экране плавно, по одному делению за минуту, на выключенном сразу. Конец хода сохраняем.
    // Пока идёт ход, каждый шаг сверяет, что подсветку не трогали: тронули, и ход окончен, остаётся то, что выбрали
    private void moveBacklight(int from, int to) {
        stopRamp();
        PowerManager pm = getSystemService(PowerManager.class);
        boolean awake = pm != null && pm.isInteractive();
        if (saverActive) {
            // Подсветка сейчас вдвое ниже: новая рабочая сохраняется, а вдвое от неё держим до первой кнопки
            saverPrev = to;
            Backlight.set(to, true);
            Backlight.set(Math.max(1, Math.round(to / 2f)), false);
            p.edit().putInt("blBase", to).apply();
            return;
        }
        if (!awake || from == to) {
            Backlight.set(to, true);
            p.edit().putInt("blBase", to).apply();
            return;
        }
        final int steps = Math.abs(to - from), dir = to > from ? 1 : -1;
        final long every = Math.max(120, 60_000 / steps);
        ramp = new Runnable() {
            int v = from;
            @Override public void run() {
                int cur = Backlight.get();
                if (nmActive || saverActive || Pic.held(p) || cur != v) {
                    ramp = null;
                    if (cur >= 0) p.edit().putInt("blBase", cur).apply();
                    if (cur != v) log("Яркость поменяли во время перехода (" + cur + "): оставляю");
                    return;
                }
                v += dir;
                Backlight.set(v, v == to);
                if (v == to) {
                    ramp = null;
                    p.edit().putInt("blBase", to).apply();
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

    // ===== Экран блокировки: через 15 минут простоя подсветка вдвое =====
    // «Дом» шлёт --es do saver --ez on true|false; любая кнопка возвращает сразу
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
            log("Простой: подсветка вдвое, " + from + " → " + to);
        } else {
            if (!saverActive) return;
            saverActive = false;
            if (saverPrev >= 0) Backlight.set(saverPrev, false);
            saverPrev = -1;
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
        // Выключили посреди ночи: вернуть дневную сразу, а не ждать 7:00. Включили: ночь начнётся со следующих 22:00
        int day = p.getInt("blDay", -1);
        if (!on && day >= 0 && !nmActive) {
            p.edit().remove("blDay").apply();
            int cur = Backlight.get();
            if (cur >= 0) moveBacklight(cur, day);
        }
        if (!on && veil != null) hideVeil(1_500);
    }

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

    // ===== Присмотр за локальной службой (ноябрь) =====
    // Домашний сервер в другом приложении (адрес вида http://127.0.0.1:порт/путь) иногда перестаёт отвечать. Раз в 3 минуты,
    // пока экран горит и не идёт видео, «Сторож» спрашивает этот адрес. Два промаха подряд (один бывает, пока сеть просыпается):
    //  - процесса приложения нет (его выгрузила система): «Сторож» открывает приложение, оно само поднимает свой сервер,
    //    и через 10 секунд возвращает на экран то, что было открыто;
    //  - процесс жив, а сервер молчит: остановить чужое приложение Android обычному приложению не даёт (это право системы),
    //    поэтому приложение открывается с запиской «закройте его через меню». Дальше 5 минут проверка каждые 20 секунд: процесс
    //    ушёл, и «Сторож» сразу запускает приложение заново (первая ветка). Записка не чаще раза в час.
    // Что смотреть, задаётся командой с ПК (Cmd, do svc): в коде пакета нет, без настройки присмотр выключен
    static final long SVC_EVERY = 3 * 60_000, SVC_NOTE_EVERY = 60 * 60_000;
    private volatile int svcMiss;
    private volatile long svcNoteAt;

    final Runnable svcTick = new Runnable() {
        @Override public void run() {
            h.removeCallbacks(this);
            PowerManager pm = getSystemService(PowerManager.class);
            if (!p.getString("svcUrl", "").isEmpty() && pm != null && pm.isInteractive())
                new Thread(() -> svcCheck(false), "guard-svc").start();
            // После записки 5 минут проверяем каждые 20 секунд: человек закроет приложение, и оно сразу поднимется заново
            h.postDelayed(this, System.currentTimeMillis() < svcFastUntil ? 20_000 : SVC_EVERY);
        }
    };
    private volatile long svcFastUntil;

    static boolean answers(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(3_000);
            c.setReadTimeout(4_000);
            try { return c.getResponseCode() < 500; } finally { c.disconnect(); }
        } catch (Exception e) {
            return false;
        }
    }

    // now: проверка с ПК, один промах уже считается вторым
    synchronized String svcCheck(boolean now) {
        String url = p.getString("svcUrl", ""), pkg = p.getString("svcPkg", ""), name = p.getString("svcName", "Служба");
        if (url.isEmpty()) return "присмотр выключен";
        if (answers(url)) {
            if (svcMiss >= 2) h.post(() -> log(name + " снова отвечает"));
            svcMiss = 0;
            return "отвечает";
        }
        if (playingVideo() != null) return "не отвечает, но идёт видео: ждём";
        svcMiss = now ? Math.max(2, svcMiss + 1) : svcMiss + 1;
        if (svcMiss < 2) return "не отвечает, промах " + svcMiss;
        boolean alive = processAlive(pkg);
        long t = System.currentTimeMillis();
        // Живой и молчащий процесс: приложение с запиской открываем не чаще раза в час, чтобы не мешать
        if (alive && t - svcNoteAt < SVC_NOTE_EVERY) return "завис живым, записка уже была";
        String back = topPackage();
        Intent open = launchIntent(pkg);
        if (open == null) {
            h.post(() -> log(name + " не отвечает, а открыть приложение нечем (" + pkg + ")"));
            return "не отвечает, открыть нечем";
        }
        h.post(() -> {
            try { startActivity(open); } catch (Exception e) { log(name + ": приложение не открылось (" + e.getClass().getSimpleName() + ")"); }
        });
        if (alive) {
            svcNoteAt = t;
            svcFastUntil = t + 5 * 60_000;
            h.postDelayed(svcTick, 20_000);
            showNote(name + " не отвечает. Закройте приложение через его меню (Выход): «Сторож» сразу запустит его заново");
            h.post(() -> log(name + " не отвечает, процесс жив: приложение открыто, ждём выхода из него"));
            return "завис живым: открыто приложение и записка";
        }
        h.post(() -> log(name + " не отвечала, процесса не было: приложение открыто, сервер поднимается"));
        boolean up = false;
        for (int i = 0; i < 10 && !up; i++) {
            try { Thread.sleep(1_000); } catch (InterruptedException ignored) { }
            up = answers(url);
        }
        final boolean ok = up;
        if (ok) svcMiss = 0;
        // Поднялась: вернуть экран, который был открыт (главный экран без перехода на блокировку, приложение через его вход).
        // Не поднялась: приложение остаётся открытым, пусть человек видит, что с ним
        Intent ret = !ok || back == null || back.equals(pkg) ? null
                : back.equals(homePackage()) ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                : launchIntent(back);
        h.post(() -> {
            if (ret != null) try { startActivity(ret); } catch (Exception ignored) { }
            log(ok ? name + " поднялась" + (ret != null ? ", экран возвращён (" + back + ")" : "") : name + " так и не ответила за 10 с, приложение открыто");
        });
        return ok ? "поднята" : "не поднялась";
    }

    Intent launchIntent(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        android.content.pm.PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    String homePackage() {
        android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        return r == null || r.activityInfo == null ? "" : r.activityInfo.packageName;
    }

    // Строки dumpsys целиком (право DUMP выдаётся с ПК)
    static String dumpsys(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "dumpsys";
        System.arraycopy(args, 0, cmd, 1, args.length);
        StringBuilder b = new StringBuilder();
        try {
            Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()))) {
                for (String s; (s = r.readLine()) != null; ) b.append(s).append('\n');
            }
            pr.waitFor();
        } catch (Exception ignored) { }
        return b.toString();
    }

    static boolean processAlive(String pkg) {
        return !pkg.isEmpty() && dumpsys("activity", "processes", pkg).contains(":" + pkg + "/u");
    }

    private static final java.util.regex.Pattern TOP = java.util.regex.Pattern.compile("topResumedActivity=ActivityRecord\\{\\S+ u\\d+ ([^/\\s]+)/");

    static String topPackage() {
        java.util.regex.Matcher m = TOP.matcher(dumpsys("activity", "activities"));
        return m.find() ? m.group(1) : null;
    }

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
