package app.glasstv.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

// Зажатая кнопка громкости (ноябрь). Пока работает Key Mapper, все кнопки идут через фильтр спецвозможностей, а на ТВ кнопку
// громкости этот фильтр сразу отдаёт системе: повторов нет, зажатая кнопка даёт один шаг (замер: с Key Mapper 1 деление,
// без него 0→31 за 1,4 с). Ни Key Mapper, ни другая служба спецвозможностей этой кнопки вообще не видят.
// Поэтому повторы делаем сами: система сообщила о шаге громкости, через 400 мс смотрим в dumpsys input (42 мс, право DUMP), зажата
// ли ещё кнопка, и пока зажата, шагаем в ту же сторону. Без фильтра система повторяет сама, тогда ничего не делаем
final class VolumeHold {
    private static final String TAG = "guard";
    private static final long FIRST_MS = 400, STEP_MS = 55, POLL_MS = 110, MAX_HOLD_MS = 9_000;

    private final AudioManager am;
    private final Handler bg;
    private volatile boolean stepping;
    private volatile boolean down;
    private long endedAt;

    VolumeHold(Context c) {
        am = c.getSystemService(AudioManager.class);
        HandlerThread t = new HandlerThread("guard-volume");
        t.start();
        bg = new Handler(t.getLooper());
        c.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context x, Intent i) { onVolume(i); }
        }, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
    }

    private void onVolume(Intent i) {
        if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return;
        int now = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
        int was = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);
        if (now < 0 || was < 0 || now == was || stepping) return;
        if (SystemClock.elapsedRealtime() - endedAt < 300) return;   // запоздалое сообщение о нашем же шаге
        final int dir = now > was ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        stepping = true;
        bg.postDelayed(() -> start(dir), FIRST_MS);
    }

    private void start(int dir) {
        if (!held()) { stepping = false; return; }
        down = true;
        final long until = SystemClock.elapsedRealtime() + MAX_HOLD_MS;
        Log.i(TAG, "громкость: кнопка зажата, шаги " + (dir > 0 ? "вверх" : "вниз"));
        bg.post(new Runnable() {
            long nextPoll = SystemClock.elapsedRealtime() + POLL_MS;
            @Override public void run() {
                long t = SystemClock.elapsedRealtime();
                if (t >= nextPoll) {
                    down = held();
                    nextPoll = SystemClock.elapsedRealtime() + POLL_MS;
                }
                int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                boolean edge = dir > 0 ? v >= am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : v <= 0;
                if (!down || edge || t >= until) {
                    stepping = false;
                    endedAt = SystemClock.elapsedRealtime();
                    return;
                }
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                bg.postDelayed(this, STEP_MS);
            }
        });
    }

    // Кнопка всё ещё нажата и повторы за нас никто не делает. Ошибка или нет права: считаем, что не нажата, остаётся один шаг
    private boolean held() {
        boolean filter = false, key = false;
        try {
            Process pr = new ProcessBuilder("dumpsys", "input").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
                for (String s; (s = r.readLine()) != null; ) {
                    if (s.contains("InputFilterEnabled: true")) filter = true;
                    else if (s.contains("KeyDowns:") && !s.contains("KeyDowns: 0")) key = true;
                }
            }
            pr.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "громкость: dumpsys input не прочитался: " + e);
            return false;
        }
        return filter && key;
    }
}
