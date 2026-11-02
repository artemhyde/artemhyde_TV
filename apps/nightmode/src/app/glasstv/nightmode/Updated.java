package app.glasstv.nightmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Приложение обновили, пока шёл ночной режим: система убила службу и сама её не поднимет, а подсветка осталась
// ночной (в красной зоне это 0), контраст приглушён, и «Сторож» считает, что ею по-прежнему ведает «Ночной режим». Выключаем
// режим честно: обычная подсветка из "bl0", контраст из "ct0" и «Сторожу» сигнал «выключен» (так было 27.10 после переустановки)
public class Updated extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        NightService.recover(c, c.getSharedPreferences("night", Context.MODE_PRIVATE));
    }
}
