package app.glasstv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

// Перенос настроек с ПК (06.11): установщик и Claude. Закрыт правом DUMP: слать может только adb.
// adb shell am broadcast -n app.glasstv.home/.SetupCmd --es do export
//     копия в «Загрузки», ответ: путь на ТВ, забрать adb pull
// adb push hyde-settings.json /data/local/tmp/hyde-settings.json
// adb shell am broadcast -n app.glasstv.home/.SetupCmd --es do import --es file /data/local/tmp/hyde-settings.json
//     разложить по «Дому», «Сторожу» и «Волне», ответ: сколько настроек приняло каждое
public class SetupCmd extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        final PendingResult r = goAsync();
        final Context app = c.getApplicationContext();
        String d = i.getStringExtra("do");
        if ("export".equals(d)) {
            Transfer.collect(app, b -> {
                String name = Transfer.save(app, b);
                finish(r, name == null ? "ошибка: «Загрузки» недоступны"
                        : "/sdcard/Download/" + name + " (части: " + (b.has("guard") ? "«Сторож» " : "") + (b.has("volna") ? "«Волна» " : "") + "«Дом»)");
            });
        } else if ("import".equals(d)) {
            JSONObject b;
            try {
                b = i.hasExtra("json") ? new JSONObject(i.getStringExtra("json")) : Transfer.readFile(String.valueOf(i.getStringExtra("file")));
            } catch (Exception e) {
                finish(r, "ошибка: " + e.getMessage());
                return;
            }
            Transfer.apply(app, b, t -> finish(r, t));
        } else {
            finish(r, "нужно --es do export или --es do import --es file путь");
        }
    }

    private static void finish(PendingResult r, String text) {
        Log.i("home", "перенос: " + text);
        r.setResultData(text);
        r.finish();
    }
}
