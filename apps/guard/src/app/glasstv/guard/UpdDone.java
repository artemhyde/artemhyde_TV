package app.glasstv.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

// Итог установки обновления от системы (Updates.commit). Без службы итог теряется, и это не страшно: при следующем
// шаге «Сторож» увидит, что версия уже стоит, или поставит файл снова
public class UpdDone extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        GuardService g = GuardService.running;
        if (g == null || g.upd == null) {
            Log.i("guard", "Обновления: итог установки пришёл без службы, статус " + i.getIntExtra(PackageInstaller.EXTRA_STATUS, -99));
            return;
        }
        g.upd.done(i);
    }
}
