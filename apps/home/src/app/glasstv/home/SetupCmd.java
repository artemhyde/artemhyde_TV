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
            Transfer.apply(app, b, t -> {
                finish(r, t);
                // Новый цвет стекла из копии: процесс «Дома» гасится, главный экран поднимется уже с ним (мастер зовёт «Домой»)
                if (ThemeChoice.needsRestart(app))
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
            });
        } else if ("net".equals(d)) {
            // Погода и курсы для поддержки (10.11): когда в последний раз пришли и почему не пришли
            Data x = Data.get(app);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d.MM H:mm", java.util.Locale.US);
            String w = "погода: " + (!Setup.weatherOn(app) ? "выключена" : "город " + Setup.city(app) + " (" + Setup.country(app) + ")"
                    + ", пришла " + (x.weatherAt() > 0 ? f.format(new java.util.Date(x.weatherAt())) : "ни разу")
                    + (x.weatherFailAt() > x.weatherAt() ? ", сбой " + f.format(new java.util.Date(x.weatherFailAt())) + ": " + x.weatherDetail() : ""));
            String k = "курсы: " + (!Setup.ratesOn(app) ? "выключены" : Setup.rates(app) + " → " + Setup.ratesSource(app)
                    + ", пришли " + (x.ratesAt() > 0 ? f.format(new java.util.Date(x.ratesAt())) : "ни разу")
                    + (x.ratesFailAt() > x.ratesAt() ? ", сбой " + f.format(new java.util.Date(x.ratesFailAt())) + ": " + x.ratesDetail() : ""));
            finish(r, w + "; " + k);
        } else if ("guardnote".equals(d)) {
            // Проверка записки «Сторожа» с ПК (10.11): «Дом» шлёт себе ту же упорядоченную рассылку, что и «Сторож».
            // --es text «текст» [--es from phone|guard]. Итог (показал ли «Дом») пишется в журнал «home»: ждать его здесь нельзя,
            // упорядоченные рассылки идут по одной, и эта ждала бы саму себя до таймаута системы (проверено: «Дом» зависал)
            Intent n = new Intent(HomeActivity.NOTE_ACTION).setPackage(app.getPackageName()).putExtra("text", String.valueOf(i.getStringExtra("text")));
            if (i.hasExtra("from")) n.putExtra("from", i.getStringExtra("from"));
            app.sendOrderedBroadcast(n, HomeActivity.HYDE_PERMISSION, new BroadcastReceiver() {
                @Override public void onReceive(Context c2, Intent x) {
                    Log.i("home", "записка «Сторожа», проверка: " + (getResultCode() == android.app.Activity.RESULT_OK ? "показал «Дом»"
                            : "«Дом» не показал (не на экране или открыто «Оформление»), показал бы «Сторож»"));
                }
            }, null, android.app.Activity.RESULT_CANCELED, null, null);
            finish(r, "записка отправлена, итог в журнале: logcat -s home");
        } else {
            finish(r, "нужно --es do export, --es do import --es file путь, --es do net или --es do guardnote --es text «текст»");
        }
    }

    private static void finish(PendingResult r, String text) {
        Log.i("home", "перенос: " + text);
        r.setResultData(text);
        r.finish();
    }
}
