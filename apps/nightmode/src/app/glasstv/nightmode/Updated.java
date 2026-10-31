package app.glasstv.nightmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

// Приложение обновили, пока шёл ночной режим: система убила службу и сама её не поднимет, а подсветка осталась
// ночной (в красной зоне это 0), и «Сторож» считает, что ею по-прежнему ведает «Ночной режим». Выключаем режим
// честно: обычная подсветка из "bl0" и «Сторожу» сигнал «выключен» (так было 27.10 после переустановки)
public class Updated extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        SharedPreferences prefs = c.getSharedPreferences("night", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("active", false)) return;
        prefs.edit().putBoolean("active", false).putLong("endAt", 0).apply();
        NightService.restoreBacklight(c, prefs);
        NightService.notifyGuard(c, false);
    }
}
