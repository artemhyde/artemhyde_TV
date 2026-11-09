package app.glasstv.volna;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

// Пока музыка нужна, «Волна» сама служба переднего плана. Без этого система замораживала Aura прямо во время игры:
// прошивка TCL запрещает Aura свою такую службу, а после первого открытия её экрана связь с «Волной» её уже не защищала
// (в журнале cch-bound-ui-services и freezing). Команды копились, звук шёл рывками, а любое событие (громкость, включение ТВ)
// размораживало её, и музыка играла снова. Служба поднимает важность «Волны», а вместе с ней и привязанной к ней Aura
public class Keeper extends Service {
    static void on(Context c) {
        try { c.startForegroundService(new Intent(c, Keeper.class)); } catch (Exception e) { Log.w("volna", "служба не запустилась", e); }
    }

    static void off(Context c) {
        c.stopService(new Intent(c, Keeper.class));
    }

    // Каждый запуск через startForegroundService обязан снова объявить службу видимой
    @Override public int onStartCommand(Intent i, int flags, int id) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("volna", "Волна", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "volna").setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Волна играет").setOngoing(true).build();
        // Тип службы передаётся только с Android 10, на Android 9 такого вызова нет
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
