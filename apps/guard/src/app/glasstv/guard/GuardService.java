package app.glasstv.guard;

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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Сторож телевизора. Живёт фоном всё время и держит в порядке то, что раньше чинилось руками:
// беспроводную отладку после перезагрузки и смены сети, службу Key Mapper (кнопки пульта),
// принимает записки с телефона из домашней сети и показывает их поверх экрана
public class GuardService extends Service {
    static final int NOTE_PORT = 8765;

    // Слушать ли порт записок: значение по умолчанию лежит в ресурсе res/values/config.xml
    // Слушать ли порт записок: выбор на экране «Сторожа» (06.11), а пока не выбирали, значение из res/values/config.xml
    static boolean notesEnabled(Context c) {
        return c.getSharedPreferences("guard", MODE_PRIVATE).getBoolean("notesOn", c.getResources().getBoolean(R.bool.notes_enabled_default));
    }
    static GuardService running;

    // Просьбы к человеку на экране (записка «Разрешить отладку», вопрос про отчёты автору): не больше одной за раз,
    // следующая не раньше чем через 10 минут, чтобы два окна не вылезли друг на друга
    static final long PROMPT_GAP = 10 * 60_000;
    private static volatile long promptAt;
    static boolean promptFree() { return System.currentTimeMillis() - promptAt >= PROMPT_GAP; }
    static void prompted() { promptAt = System.currentTimeMillis(); }

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
        if (notesEnabled(this)) server.start();   // включено ли, решает выключатель на экране «Сторожа» (по умолчанию config.xml)
        loadNightHours(p);   // часы ночи до первых таймеров ночи и картинки
        watchAdb();
        keepAdb("запуск");
        // Кнопки пульта, главный экран и отключённые установщиком пакеты (Health.java, задача 10.11)
        health = new Health(this, h, new Health.Out() {
            @Override public void log(String s) { h.post(() -> GuardService.this.log(s)); }
            @Override public void note(String s) { showNote(s); }
            @Override public void soon(long ms) { updSoon(ms); }
        });
        health.start();
        // Прошивка сменилась: закрытые функции TCL и Dolby проверяются заново, итог в журнал и запиской (план Codex, блок 5).
        // Заодно главный экран и пакеты, которые отключил установщик: прошивка TCL умеет включать их обратно
        final String fw = Caps.firmwareNews(p);
        if (fw != null) new Thread(() -> {
            String rep = Caps.report(this);
            h.post(() -> {
                log(fw);
                log(rep);
                prompted();   // записка «запустите установщик» (Health) не перебьёт эту: следующая просьба не раньше чем через 10 минут
                showNote("Прошивка ТВ обновилась. Функции TCL и Dolby проверены заново, итог на экране «Сторож»");
            });
            health.afterFirmware();
            health.home(HealthRules.FIRMWARE);
        }, "guard-caps").start();
        // Сразу после загрузки службы спецвозможностей ещё поднимаются: Key Mapper смотрим через минуту
        h.postDelayed(startCheck, SystemClock.elapsedRealtime() < 5 * 60_000 ? 60_000 : 3_000);
        h.postDelayed(periodic, 15 * 60_000);
        h.postDelayed(nightTick, 10_000);
        h.postDelayed(idleTick, 60_000);
        h.postDelayed(svcTick, 90_000);
        dns = new DnsWatch(getContentResolver(), p, new DnsWatch.Out() {
            @Override public void log(String s) { h.post(() -> GuardService.this.log(s)); }
            @Override public void note(String s) { showNote(s); }
        });
        h.postDelayed(dnsTick, 60_000);
        upd = new Updates(this, new Updates.Out() {
            @Override public void log(String s) { h.post(() -> GuardService.this.log(s)); }
            @Override public void note(String s) { showNote(s); }
            @Override public void updated(String pkg) { if (bye != null && ByeRules.PKG.equals(pkg)) bye.soon("после обновления", 3_000); }
        });
        h.postDelayed(updTick, 3 * 60_000);
        bye = new Bye(this);   // присмотр за ByeByeDPI (модуль для России): сам решает, есть ли за чем смотреть
        bye.start();
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
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(events, f, RECEIVER_NOT_EXPORTED);
        else registerReceiver(events, f);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network net) {
                netEventAt = SystemClock.elapsedRealtime();
                // Сразу после регистрации Android сообщает о сети, которая уже была: это не смена, и после отказа при запуске
                // просить снова незачем (06.11: на чужом ТВ окно «Разрешить» вызывалось дважды за 4 секунды). Сети при
                // запуске не было (lastNet пуст): первая появившаяся сеть это смена, иначе после загрузки ТВ, когда Wi-Fi
                // поднимается позже «Сторожа», отладка не включилась бы до следующей смены сети
                boolean changed = lastNet == null || !lastNet.equals(net);
                lastNet = net;
                if (changed) refusedAt = 0;   // другая сеть: в ней отладка может быть уже разрешена
                h.postDelayed(() -> keepAdb(changed ? "сеть сменилась" : "сеть на месте", changed), 4_000);
                if (changed && bye != null) bye.soon("после смены сети", 6_000);
                h.removeCallbacks(dnsTick);
                h.postDelayed(dnsTick, 20_000);
            }
            @Override public void onLost(Network net) { netEventAt = SystemClock.elapsedRealtime(); }
        };
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            lastNet = cm.getActiveNetwork();   // сеть, которая уже есть при запуске (null: пока никакой)
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Exception ignored) { }
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
        // Тип службы передаётся только с Android 10, на Android 9 такого вызова нет
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        running = null;
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(events); } catch (Exception ignored) { }
        try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
        if (server != null) server.stop();
        if (adbWatch != null) try { getContentResolver().unregisterContentObserver(adbWatch); } catch (Exception ignored) { }
        if (veil != null) try { getSystemService(android.view.WindowManager.class).removeView(veil); } catch (Exception ignored) { }
        if (sound != null) { sound.shutdown(); sound = null; }
        if (bye != null) { bye.stop(); bye = null; }
        if (health != null) health.stop();
        super.onDestroy();
    }

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                if (saverActive) setSaver(false);
                homeOnScreenOff();
                if (bye != null) bye.screen(false);
                // Скачанный выпуск HYDE ждёт: экран погас, никто не увидит, как приложения закроются (если ТВ ещё не уснул)
                if (upd != null && upd.hyde.pending()) { h.removeCallbacks(updTick); h.postDelayed(updTick, 30_000); }
            } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                netEventAt = SystemClock.elapsedRealtime();   // после сна сеть и отладка поднимаются заново
                homeCancel();
                // Телевизор включили: через пару секунд проверить пульт, главный экран и отладку
                h.removeCallbacks(wakeCheck);
                h.postDelayed(wakeCheck, 4_000);
                h.postDelayed(() -> keepAdb("включение экрана", false), 4_000);
                h.postDelayed(svcTick, 20_000);   // сеть после сна поднимается не сразу
                h.removeCallbacks(dnsTick);
                h.postDelayed(dnsTick, 25_000);
                if (bye != null) bye.screen(true);
                // Записки «нужен установщик» и вопрос про отчёты автору: ТВ только что включили, сверху «Дом», человек у пульта
                if (upd != null && (upd.hyde.pending() || upd.ping.askPending() || health.notePending())) { h.removeCallbacks(updTick); h.postDelayed(updTick, 12_000); }
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
            keepAdb("проверка", false);
            noDream();
            applyPq("проверка");
            health.keys(HealthRules.PERIODIC);   // после неудачи только смотрит: перезапуск при включении ТВ и после установки пакета
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

    // Пакет, у которого медиасессия сейчас «играет», включая музыку «Волны», или null; без слушателя Listen молча null.
    // Для присмотра за ByeByeDPI: его туннель не включаем посреди просмотра и не обновляем его, пока что-то играет
    String playingAny() {
        try {
            MediaSessionManager msm = getSystemService(MediaSessionManager.class);
            if (msm == null) return null;
            for (MediaController mc : msm.getActiveSessions(new ComponentName(this, Listen.class))) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return mc.getPackageName();
            }
        } catch (Exception ignored) { }
        return null;
    }

    // Что-то играет: медиасессия или занятый видеодекодер (его видно и без слушателя, по праву DUMP)
    static boolean mediaBusy() {
        GuardService g = running;
        return (g != null && g.playingAny() != null) || Boolean.TRUE.equals(videoDecoding());
    }

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
    // Android 12+ пишет «lastUserActivityTime=…», Android 9–11 «mLastUserActivityTime=…» (10.11, виртуальный Android TV 9):
    // без учёта регистра подходят оба, иначе на старых ТВ простой и «человек у пульта» не видны
    private static final java.util.regex.Pattern LAST_USER = java.util.regex.Pattern.compile("lastUserActivityTime=(\\d+) \\((\\d+) ms ago\\)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private final Runnable idleTick = new Runnable() {
        @Override public void run() {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && pm.isInteractive() && homeIsOurs()) new Thread(GuardService.this::checkIdle, "guard-idle").start();
            h.postDelayed(this, 60_000);
        }
    };

    // Lampa держит экран включённым всегда, в меню тоже (KEEP_SCREEN_ON у окна), поэтому её замок о просмотре ничего не говорит.
    // У таких приложений просмотр видно по занятому видеодекодеру. 06.11: в меню Lampa простой не наступал никогда, и через
    // 10 минут шла её собственная заставка с часами («снова стоковая»). Заставку Lampa выключили в её настройках: если её снова
    // включат, она займёт декодер, и «Сторож» уступит ей, как фильму
    static final java.util.Set<String> KEEPS_SCREEN_ON = new java.util.HashSet<>(java.util.Collections.singletonList("top.rootu.lampa"));
    private static final java.util.regex.Pattern LOCK_WS = java.util.regex.Pattern.compile("ws=WorkSource\\{([^}]*)\\}");

    // Что видит простой. why пустое: ничего не мешает открыть экран блокировки (если нажатий не было 10 минут)
    static final class IdleState {
        long last = -1, ago = -1;
        String why = "";
    }

    // full false: при нажатиях меньше 10 минут назад остальное не проверяем (раз в минуту, лишний dumpsys ни к чему)
    IdleState readIdle(boolean full) {
        IdleState st = new IdleState();
        StringBuilder hard = new StringBuilder();
        String soft = null;
        try {
            Process pr = new ProcessBuilder("dumpsys", "power").redirectErrorStream(true).start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()))) {
                for (String s; (s = r.readLine()) != null; ) {
                    if (st.last < 0) {
                        java.util.regex.Matcher m = LAST_USER.matcher(s);
                        if (m.find()) { st.last = Long.parseLong(m.group(1)); st.ago = Long.parseLong(m.group(2)); }
                    }
                    if (s.contains("SCREEN_BRIGHT_WAKE_LOCK") || s.contains("SCREEN_DIM_WAKE_LOCK") || s.contains("FULL_WAKE_LOCK")) {
                        String owner = alwaysOnOwner(s);
                        if (owner != null) soft = owner;
                        else hard.append(hard.length() > 0 ? ", " : "").append(lockName(s));
                    }
                }
            }
            pr.waitFor();
        } catch (Exception e) {
            st.ago = -1;
        }
        if (st.ago < 0) { st.why = "простой не виден (нет права DUMP)"; return st; }
        if (!full && st.ago < IDLE_APP_MS) return st;
        if (hard.length() > 0) { st.why = "экран держит " + hard; return st; }
        if (soft != null) {
            Boolean video = videoDecoding();
            if (video == null) { st.why = "экран держит " + soft + ", декодер не виден: считаю, что идёт видео"; return st; }
            if (video) { st.why = "идёт видео (декодер занят), экран держит " + soft; return st; }
        }
        String playing = playingVideo();
        if (playing != null) st.why = "играет " + playing;
        return st;
    }

    // Пакет из KEEPS_SCREEN_ON, если замок экрана держат только такие приложения (замок окна: ws=WorkSource{uid пакет}), иначе null
    static String alwaysOnOwner(String line) {
        java.util.regex.Matcher m = LOCK_WS.matcher(line);
        if (!m.find()) return null;
        String owner = null;
        for (String part : m.group(1).split(",")) {
            String[] w = part.trim().split(" ");
            String pkg = w[w.length - 1];
            if (!KEEPS_SCREEN_ON.contains(pkg)) return null;
            owner = pkg;
        }
        return owner;
    }

    static String lockName(String line) {
        java.util.regex.Matcher m = LOCK_WS.matcher(line);
        if (m.find()) return m.group(1).replaceAll("\\b\\d+ ", "");
        int a = line.indexOf('\''), b = a < 0 ? -1 : line.indexOf('\'', a + 1);
        return b > a ? line.substring(a + 1, b) : "замок экрана";
    }

    // Идёт ли сейчас видео хоть в одном процессе (раздел Processes в dumpsys media.resource_manager, право DUMP).
    // Декодер, пока видео на паузе или элемент страницы ещё жив, остаётся выделенным (non-secure-codec/video-codec), а отметка
    // battery/video-codec есть только на время самого показа: она и признак. Если прошивка такой отметки не ведёт вовсе,
    // считаем по выделенному декодеру. null: отчёт не прочитался, тогда простой считает, что видео идёт, и фильм не перебивает
    static Boolean videoDecoding() {
        try {
            Process pr = new ProcessBuilder("dumpsys", "media.resource_manager").redirectErrorStream(true).start();
            boolean in = false, seen = false, held = false, active = false, batteryKnown = false;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()))) {
                for (String s; (s = r.readLine()) != null; ) {
                    String t = s.trim();
                    if (t.equals("Processes:")) { in = true; seen = true; continue; }
                    if (in && (t.startsWith("Process Pid override") || t.startsWith("Events logs"))) in = false;
                    if (t.contains("battery/video-codec")) batteryKnown = true;
                    if (in && t.startsWith("battery/video-codec")) active = true;
                    else if (in && t.contains("video-codec")) held = true;
                }
            }
            pr.waitFor();
            if (!seen) return null;
            return batteryKnown ? active : held || active;
        } catch (Exception e) {
            return null;
        }
    }

    // Для команды с ПК (do idle): что сейчас видит простой
    String idleReport() {
        IdleState st = readIdle(true);
        if (st.ago < 0) return st.why;
        long min = st.ago / 60_000;
        String head = "без нажатий " + min + " мин; ";
        if (!st.why.isEmpty()) return head + "экран блокировки не откроется: " + st.why;
        if (st.ago < IDLE_APP_MS) return head + "ничего не мешает, экран блокировки через " + (IDLE_APP_MS - st.ago + 59_999) / 60_000 + " мин";
        return head + (st.last == idleSentFor ? "экран блокировки уже открывали после этого нажатия" : "экран блокировки откроется на ближайшей проверке");
    }

    private void checkIdle() {
        IdleState st = readIdle(false);
        long last = st.last, ago = st.ago;
        if (ago < 0) {
            if (!idleDenied) h.post(() -> log("Простой не виден: выдать с ПК pm grant app.glasstv.guard android.permission.DUMP"));
            idleDenied = true;
            return;
        }
        idleDenied = false;
        if (ago < IDLE_APP_MS || !st.why.isEmpty() || last == idleSentFor) return;
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

    // Часы ночи (06.11, план Codex, блок 3: «расписание ночи» с пульта). По умолчанию 22 и 7, выбираются в «Настройках «Дома»»
    // или командой do night. Начало 18..23, конец 5..10: ночь всегда через полночь, на этом стоят isNight и lastBoundary
    static volatile int NIGHT_FROM = 22, NIGHT_TO = 7;

    static boolean nightHoursOk(int from, int to) { return from >= 18 && from <= 23 && to >= 5 && to <= 10; }

    static void loadNightHours(SharedPreferences sp) {
        int f = sp.getInt("nightFrom", 22), t = sp.getInt("nightTo", 7);
        if (nightHoursOk(f, t)) { NIGHT_FROM = f; NIGHT_TO = t; }
    }

    static String nf() { return NIGHT_FROM + ":00"; }
    static String nt() { return NIGHT_TO + ":00"; }

    // Новые часы ночи: сохранить, границу картинки пересчитать. Подсветку не трогаем: её ведёт nightTick по своей границе
    String setNightHours(int from, int to) {
        if (!nightHoursOk(from, to)) return "часы ночи вне рамок: начало 18..23, конец 5..10";
        p.edit().putInt("nightFrom", from).putInt("nightTo", to).apply();
        loadNightHours(p);
        h.removeCallbacks(pqTick);
        h.postDelayed(pqTick, 3_000);
        log("Ночь теперь с " + nf() + " до " + nt());
        return "ночь с " + nf() + " до " + nt();
    }
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
            log("Дневная подсветка " + v + ": вернётся в " + nt() + ", ночная " + nightLevel());
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
            log((night ? nf() : nt()) + ": подсветку ведёт «Ночной режим», не трогаю");
            return;
        }
        if (night) {
            int base = p.getInt("blBase", -1), level = nightLevel();
            if (base >= 0 && cur != base) {
                log(nf() + ": яркость сегодня меняли (" + base + " → " + cur + "), не трогаю до завтра");
                return;
            }
            if (cur <= level) { log(nf() + ": и так темно (" + cur + ")"); return; }
            p.edit().putInt("blDay", cur).apply();
            log("Вечер: подсветка " + cur + " → " + level + " до " + nt());
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
            // Запомнить и на диске: «Сторож», перезапущенный посреди приглушения (обновление, сбой), иначе не знал, что вернуть,
            // и подсветка оставалась вдвое ниже (06.11, после переустановки «Сторожа» в 18:28)
            p.edit().putInt("saverPrev", cur).apply();
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
            int prev = saverActive ? saverPrev : p.getInt("saverPrev", -1);   // после перезапуска «Сторожа» память пуста, на диске нет
            saverActive = false;
            saverPrev = -1;
            p.edit().remove("saverPrev").apply();
            if (prev >= 0) {
                Backlight.set(prev, false);
                log("Простой кончился: подсветка снова " + prev);
            }
        }
    }

    // Перенос настроек (06.11, план Codex, блок 3): только выбор человека, без журнала и служебных отметок.
    // Код записок не переносится: на другом ТВ он свой. Режим обслуживания кончается выключением отладки, поэтому
    // в файл идёт «выключать». Собирает и раскладывает файл «Дом» (Transfer.java), сюда приходит только часть «Сторожа»
    static final java.util.Set<String> EXPORT_KEYS = Prefs.set("nightEnabled", "nightLevel", "nightFrom", "nightTo", "adbMode",
            "notesOn", "dnsPref", "svcUrl", "svcPkg", "svcName", "pqAuto", "smartOn", "smartLevel", "smartMode", "smartTune",
            "eqOn", "eqDe", "eqVl", "eqBass", "eqTreble", Hyde.P_ON, Ping.P_ON);

    static String exportJson(Context c) throws Exception {
        org.json.JSONObject o = Prefs.dump(c.getSharedPreferences("guard", MODE_PRIVATE), EXPORT_KEYS, null);
        if (("s:" + ADB_SERVICE).equals(o.optString("adbMode"))) o.put("adbMode", "s:" + ADB_OFF);
        return o.toString();
    }

    static String importJson(Context c, String json) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(json);
        SharedPreferences sp = c.getSharedPreferences("guard", MODE_PRIVATE);
        java.util.Map<String, ?> before = new java.util.HashMap<>(sp.getAll());
        SharedPreferences.Editor ed = sp.edit();
        int n = Prefs.load(ed, o, EXPORT_KEYS, null);
        ed.commit();
        java.util.Map<String, ?> after = sp.getAll();
        final java.util.Set<String> changed = new java.util.HashSet<>();
        for (String k : EXPORT_KEYS) if (!java.util.Objects.equals(before.get(k), after.get(k))) changed.add(k);
        Log.i("guard", "перенос: загружено " + n + ", изменилось " + changed);
        GuardService g = running;
        if (g != null && !changed.isEmpty()) g.h.post(() -> g.applyImported(changed));
        return "загружено настроек: " + n;
    }

    // Загруженное применить сразу, без перезапуска. Трогаем только то, что поменялось: те же значения из копии
    // не перезапускают записки и не сбрасывают Dolby
    void applyImported(java.util.Set<String> ch) {
        if (ch.contains("nightFrom") || ch.contains("nightTo")) {
            loadNightHours(p);
            h.removeCallbacks(pqTick);
            h.postDelayed(pqTick, 3_000);
        }
        if (ch.contains("nightEnabled")) setNightEnabled(nightEnabled());
        if (ch.contains("notesOn")) setNotes(notesEnabled(this));
        if (ch.contains("adbMode")) {
            // «Выключать» с прошлого ТВ сразу не выключает: по сети может идти установка. Два часа обслуживания, потом само
            if (ADB_OFF.equals(adbMode())) setAdbMode(ADB_SERVICE, "перенос настроек, потом выключится");
            else setAdbMode(adbMode(), "перенос настроек");
        }
        boolean snd = false;
        for (String k : ch) snd |= k.startsWith("smart") || k.startsWith("eq");
        if (snd && sound != null) sound.eqChanged();
        log("Настройки загружены из копии: изменилось " + ch.size());
    }

    // Код записок (06.11): шесть цифр, создаётся при первой нужде, виден на экране «Сторожа». Новый: команда do notes --ez newcode true
    static synchronized String notesCode(Context c) {
        SharedPreferences sp = c.getSharedPreferences("guard", MODE_PRIVATE);
        String code = sp.getString("notesCode", "");
        if (code.length() != 6) {
            code = String.format(java.util.Locale.ROOT, "%06d", new java.security.SecureRandom().nextInt(1_000_000));
            sp.edit().putString("notesCode", code).apply();
        }
        return code;
    }

    static synchronized String newNotesCode(Context c) {
        c.getSharedPreferences("guard", MODE_PRIVATE).edit().remove("notesCode").apply();
        return notesCode(c);
    }

    // Вернуть подсветку из сохранённого без службы (команда «хватит», пока служба не запущена)
    static void restoreSaver(Context c) {
        SharedPreferences sp = c.getSharedPreferences("guard", MODE_PRIVATE);
        int prev = sp.getInt("saverPrev", -1);
        if (prev < 0) return;
        sp.edit().remove("saverPrev").apply();
        Backlight.set(prev, false);
        Log.i("guard", "Простой кончился без службы: подсветка снова " + prev);
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
        log("Вечер: подсветку ведёт датчик света, поэтому экран темнее вуалью до " + nt());
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
        // Видео по медиасессии или по работающему видеодекодеру (встроенный плеер Lampa медиасессии не даёт; 06.11, план Codex:
        // «не запускай сервер заново поверх фильма»). Декодер не виден: тоже ждём
        if (playingVideo() != null || !Boolean.FALSE.equals(videoDecoding())) return "не отвечает, но идёт видео: ждём";
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

    // Android 12 пишет не topResumedActivity, а «ResumedActivity: ActivityRecord{…}» (07.11, виртуальный Google TV 12, находка hyde-04):
    // без этого запасного вида «Сторож» на Android 12 не видел открытое приложение, и обновление могло закрыть его
    private static final java.util.regex.Pattern TOP12 = java.util.regex.Pattern.compile("ResumedActivity: ?ActivityRecord\\{\\S+ u\\d+ ([^/\\s]+)/");

    static String topPackage() { return topOf(dumpsys("activity", "activities")); }

    static String topOf(String dump) {
        java.util.regex.Matcher m = TOP.matcher(dump);
        if (m.find()) return m.group(1);
        m = TOP12.matcher(dump);
        return m.find() ? m.group(1) : null;
    }

    // ===== Беспроводная отладка: Android выключает её после перезагрузки и смены сети =====
    // Режим (06.11, аудит Codex: отладку нельзя было выключить, не убрав «Сторожа»), выбирается на экране «Сторожа»:
    // always: держать включённой всегда (иначе после перезагрузки и смены сети ТВ пропадает для ПК, так было раньше);
    // service: режим обслуживания, держать 2 часа от включения, потом выключить самому и перейти в off;
    // off: не включать никогда, что выбрано в настройках Android, то и есть.
    // Выключил человек сам в настройках Android (не перезагрузка и не смена сети: их Android делает сам), «Сторож» уважает:
    // переходит в off и пишет записку, как вернуть
    static final String ADB_ALWAYS = "always", ADB_SERVICE = "service", ADB_OFF = "off";
    static final long SERVICE_MS = 2 * 3600_000L;
    static final long QUIET_MS = 20_000;        // столько после смены сети или пробуждения выключение считается системным
    long netEventAt;                            // последняя смена сети, пробуждение или запуск (SystemClock.elapsedRealtime)
    long selfOffAt;                             // когда «Сторож» сам выключил отладку: это не решение человека
    // Когда «Сторож» увидел отладку включённой (0: сейчас выключена; 06.11, проверка на ТВ другой марки). Выключение через
    // мгновение после включения, или когда включённой её вообще не видели, делает Android, а не человек: нет Wi-Fi или
    // в этой сети её ещё не разрешили (окно «Разрешить отладку в этой сети?»), кто бы ни включал: «Сторож», мастер или
    // человек в настройках. Режим тогда остаётся прежним
    long adbOnAt;
    static final long REFUSE_MS = 5_000;
    // Когда Android последний раз так отказал. До смены сети «Сторож» не просит снова: иначе окно «Разрешить» всплывало бы
    // при каждом пробуждении экрана и каждые 15 минут. Просит при запуске, смене сети и выборе режима на экране «Сторожа»
    volatile long refusedAt;
    volatile Network lastNet;                   // сеть по умолчанию при запуске и из прошлого onAvailable
    long noWifiLogAt;                           // когда последний раз писали в журнал «ТВ не в Wi-Fi» (не чаще раза в час)

    // Есть ли сейчас Wi-Fi. Без него Android отказывает в отладке по сети молча, без окна «Разрешить», поэтому
    // такой отказ не повод перестать просить: попросим снова, как только Wi-Fi появится
    boolean wifiUp() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
    android.database.ContentObserver adbWatch;

    // Какую отладку держать. С Android 12 это «Отладка по Wi-Fi» (adb_wifi_enabled), с ней ТВ подключают по коду.
    // На Android 9–11 телевизоры подключают по порту 5555, и слушает он его, пока включена «Отладка по USB» (adb_enabled):
    // беспроводную там «Сторож» не трогает (на Android 11 она бы спрашивала «Разрешить» поверх фильма при смене сети)
    static final boolean WIFI_ADB = android.os.Build.VERSION.SDK_INT >= 31;
    static final String ADB_KEY = WIFI_ADB ? "adb_wifi_enabled" : "adb_enabled";

    boolean adbOn() { return Settings.Global.getInt(getContentResolver(), ADB_KEY, 0) == 1; }

    String adbMode() {
        String m = p.getString("adbMode", getString(R.string.adb_mode_default));
        return ADB_SERVICE.equals(m) || ADB_OFF.equals(m) ? m : ADB_ALWAYS;
    }

    long adbUntil() { return p.getLong("adbUntil", 0); }

    void setAdbMode(String m, String why) {
        SharedPreferences.Editor e = p.edit().putString("adbMode", m);
        if (ADB_SERVICE.equals(m)) e.putLong("adbUntil", System.currentTimeMillis() + SERVICE_MS);
        e.apply();
        h.removeCallbacks(serviceEnd);
        if (ADB_OFF.equals(m)) {
            adbSet(false);
            log("Отладка по сети выключена (" + why + "), «Сторож» её не включает");
        } else {
            if (ADB_SERVICE.equals(m)) h.postDelayed(serviceEnd, SERVICE_MS);
            log(ADB_SERVICE.equals(m) ? "Режим обслуживания на 2 часа (" + why + ")" : "Отладка по сети: держать всегда (" + why + ")");
            keepAdb(why);
        }
    }

    // Обслуживание кончилось: отладку выключаем сами и больше не включаем
    final Runnable serviceEnd = new Runnable() {
        @Override public void run() {
            if (!ADB_SERVICE.equals(adbMode())) return;
            long left = adbUntil() - System.currentTimeMillis();
            if (left > 1000) { h.postDelayed(this, left); return; }
            setAdbMode(ADB_OFF, "обслуживание кончилось");
            showNote("Режим обслуживания закончился: отладка по сети выключена. Включить снова: экран «Сторож»");
        }
    };

    private void adbSet(boolean on) {
        try {
            if (!on) selfOffAt = SystemClock.elapsedRealtime();
            Settings.Global.putInt(getContentResolver(), ADB_KEY, on ? 1 : 0);
        } catch (SecurityException e) {
            log("Нет права менять настройки: выдать с ПК WRITE_SECURE_SETTINGS");
        }
    }

    void keepAdb(String why) { keepAdb(why, true); }

    // retry false: обычная проверка (пробуждение, раз в 15 минут), после отказа Android она не просит снова
    void keepAdb(String why, boolean retry) {
        String m = adbMode();
        if (ADB_OFF.equals(m)) return;
        if (!retry && refusedAt != 0) return;
        if (ADB_SERVICE.equals(m) && System.currentTimeMillis() >= adbUntil()) { h.post(serviceEnd); return; }
        try {
            if (!adbOn()) {
                Settings.Global.putInt(getContentResolver(), ADB_KEY, 1);
                log("Отладка по сети включена (" + why + ")");
            }
        } catch (SecurityException e) {
            log("Нет права менять настройки: выдать с ПК WRITE_SECURE_SETTINGS");
        }
    }

    // Следим, кто выключил отладку. Выключение рядом со сменой сети, пробуждением или запуском делает сам Android,
    // своё выключение «Сторож» помнит, мгновенное выключение после включения это отказ Android (см. adbOnAt);
    // остальное значит, что человек выключил её в настройках
    void watchAdb() {
        netEventAt = SystemClock.elapsedRealtime();
        adbOnAt = adbOn() ? netEventAt : 0;
        adbWatch = new android.database.ContentObserver(h) {
            @Override public void onChange(boolean self) {
                long now = SystemClock.elapsedRealtime();
                if (adbOn()) { if (adbOnAt == 0) adbOnAt = now; refusedAt = 0; return; }   // включилась: отказа больше нет
                long onAt = adbOnAt;
                adbOnAt = 0;
                if (ADB_OFF.equals(adbMode())) return;
                if (WIFI_ADB && (onAt == 0 || now - onAt < REFUSE_MS)) {   // отказ бывает только у отладки по Wi-Fi
                    if (!wifiUp()) {
                        if (noWifiLogAt == 0 || now - noWifiLogAt > 3_600_000) {
                            noWifiLogAt = now;
                            log("Android не включил отладку по сети: ТВ не в Wi-Fi. Включу, когда Wi-Fi появится");
                        }
                        return;
                    }
                    if (refusedAt != 0 && now - refusedAt < 2_000) return;   // тот же отказ: Android сообщил о смене дважды
                    refusedAt = now;
                    log("Android не дал включить отладку по сети: в этой сети её ещё не разрешили (окно «Разрешить» на экране ТВ). Снова попрошу при смене сети");
                    return;
                }
                if (humanOff(now, netEventAt, selfOffAt, now)) {
                    setAdbMode(ADB_OFF, "выключили в настройках Android");
                    showNote("Отладку по сети выключили в настройках: «Сторож» больше не включает её сам. Вернуть: экран «Сторож»");
                }
            }
        };
        getContentResolver().registerContentObserver(Settings.Global.getUriFor(ADB_KEY), false, adbWatch);
        if (ADB_SERVICE.equals(adbMode())) h.post(serviceEnd);   // после перезапуска службы: досчитать обслуживание
    }

    // Решение человека, а не системы: не рядом со сменой сети или пробуждением, не сам «Сторож» и не сразу после загрузки ТВ
    static boolean humanOff(long now, long netAt, long selfAt, long uptime) {
        return uptime > 3 * 60_000 && now - netAt > QUIET_MS && (selfAt == 0 || now - selfAt > QUIET_MS);
    }

    // ===== Записки с телефона: выключатель на экране «Сторожа» (06.11) =====

    void setNotes(boolean on) {
        p.edit().putBoolean("notesOn", on).apply();
        if (server == null) return;
        server.stop();
        if (on) server.start();
        log(on ? "Записки с телефона включены" : "Записки с телефона выключены");
    }

    // ===== Кнопки пульта и главный экран (Health.java): если служба Key Mapper упала, фирменные кнопки пульта молчат =====

    Health health;
    final Runnable checkKeys = () -> health.keys(HealthRules.MANUAL);   // кнопка «Проверить сейчас»
    private final Runnable startCheck = () -> {
        health.keys(HealthRules.START);
        health.home(HealthRules.START);
    };
    private final Runnable wakeCheck = () -> {
        health.keys(HealthRules.WAKE);
        health.home(HealthRules.WAKE);
    };

    // ===== Присмотр за DNS (DnsWatch) =====
    DnsWatch dns;
    boolean dnsBusy;   // только в главной нитке
    final Runnable dnsTick = new Runnable() {
        @Override public void run() {
            h.removeCallbacks(this);
            PowerManager pm = getSystemService(PowerManager.class);
            if (dns == null || pm == null || !pm.isInteractive()) { h.postDelayed(this, DnsWatch.EVERY); return; }
            if (dnsBusy) return;   // проверка уже идёт и сама назначит следующую: иначе цепочки удваиваются
            dnsBusy = true;
            new Thread(() -> {
                long next = dns.check(false);
                h.post(() -> {
                    dnsBusy = false;
                    h.removeCallbacks(dnsTick);
                    h.postDelayed(dnsTick, next);
                });
            }, "guard-dns-check").start();
        }
    };

    // ===== Обновления приложений не из магазина (Updates) =====
    Updates upd;
    Bye bye;   // присмотр за ByeByeDPI (Bye.java)
    boolean updBusy;   // только в главной нитке
    // Следующий шаг обновлений через ms (после проверки вручную: цепочка установок HYDE идёт дальше сама)
    void updSoon(long ms) { h.post(() -> { h.removeCallbacks(updTick); h.postDelayed(updTick, ms); }); }

    final Runnable updTick = new Runnable() {
        @Override public void run() {
            h.removeCallbacks(this);
            if (upd == null || updBusy) return;   // идущий шаг сам назначит следующий
            updBusy = true;
            new Thread(() -> {
                long next;
                try { next = upd.tick(); } catch (Exception e) { next = Updates.TICK; log("Обновления: сбой шага (" + e + ")"); }
                final long n = next;
                h.post(() -> {
                    updBusy = false;
                    h.removeCallbacks(updTick);
                    h.postDelayed(updTick, n);
                });
            }, "guard-upd").start();
        }
    };

    // ===== Записки =====

    // Когда сверху «Дом», записку показывает он сам, в своём месте и стиле: карточка «Сторожа» слева сверху закрывала его часы
    // (10.11, снимок с настоящего ТВ). Договор с «Домом»: упорядоченная рассылка app.glasstv.home.NOTE с полем text под разрешением HYDE;
    // «Дом» ловит её только пока он на экране и отвечает RESULT_OK. Не ответил (не на экране, старый «Дом», открыто его
    // «Оформление»): своя карточка. Поле from: phone (записка с телефона) или guard (сообщение «Сторожа»), для подписи в «Доме»
    static final String HOME_NOTE = "app.glasstv.home.NOTE", HYDE_PERMISSION = "app.glasstv.permission.HYDE";

    void showNote(String text) { showNote(text, "guard"); }

    void showNote(String text, String from) {
        h.post(() -> {
            try {
                sendOrderedBroadcast(new Intent(HOME_NOTE).setPackage(HydeRules.HOME).putExtra("text", text).putExtra("from", from), HYDE_PERMISSION,
                        new BroadcastReceiver() {
                            @Override public void onReceive(Context c, Intent i) {
                                if (getResultCode() == android.app.Activity.RESULT_OK) Log.i("guard", "записка: показал «Дом»");
                                else card.show(text);
                            }
                        }, h, android.app.Activity.RESULT_CANCELED, null, null);
            } catch (Exception e) {
                card.show(text);
            }
        });
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
