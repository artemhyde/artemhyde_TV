package app.glasstv.volna;

import android.service.notification.NotificationListenerService;

// Служба-слушатель уведомлений: система держит её всегда запущенной,
// поэтому «Волна» живёт в фоне и успевает поставить следующий трек, даже когда открыто другое приложение
public class Listener extends NotificationListenerService {
    @Override public void onListenerConnected() {
        Engine.get(this).connect();
    }

    @Override public void onCreate() {
        super.onCreate();
        Engine.get(this).connect();
    }
}
