package app.glasstv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

// «Волна» сообщает сюда, что играет: главный экран и заставка показывают строку с треком вместо её плашки
public class Now extends BroadcastReceiver {
    static volatile boolean playing;
    static volatile String title = "", artist = "";
    private static final List<Runnable> listeners = new ArrayList<>();
    private static int shown;   // сколько наших экранов (главный, заставка) сейчас открыто

    @Override public void onReceive(Context c, Intent i) {
        // «Сторож» собирает суточную сводку автору (12.11): две строки, погода и курсы, пришли или почему нет (Fresh.report)
        if ("state".equals(i.getStringExtra("do"))) {
            Context app = c.getApplicationContext();
            Data x = Data.get(app);
            long now = System.currentTimeMillis();
            setResultData(Fresh.report(Setup.weatherOn(app), now, x.weatherAt(), x.weatherFailAt(), x.weatherProblem(), x.weatherDetail())
                    + "\n" + Fresh.report(Setup.ratesOn(app), now, x.ratesAt(), x.ratesFailAt(), x.ratesProblem(), x.ratesDetail()));
            return;
        }
        // «Волна» только что запустилась и спрашивает, открыт ли наш экран
        if (i.getBooleanExtra("hello", false)) {
            if (shown > 0) tellVolna(c, true);
            return;
        }
        playing = i.getBooleanExtra("playing", false);
        title = nz(i.getStringExtra("title"));
        artist = nz(i.getStringExtra("artist"));
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // Экран открылся: пока открыт хоть один, «Волна» прячет плашку и присылает сюда, что играет
    static void attach(Context c, Runnable r) {
        listeners.add(r);
        if (shown++ == 0) tellVolna(c, true);
        r.run();
    }

    static void detach(Context c, Runnable r) {
        if (!listeners.remove(r)) return;
        if (--shown == 0) tellVolna(c, false);
    }

    private static void tellVolna(Context c, boolean homeVisible) {
        try {
            c.sendBroadcast(new Intent().setClassName("app.glasstv.volna", "app.glasstv.volna.AppCmd")
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra("do", homeVisible ? "home_on" : "home_off"));
        } catch (Exception ignored) { }
    }
}
