package app.glasstv.volna;

import android.service.notification.NotificationListenerService;

// Служба-слушатель уведомлений: система держит её всегда запущенной,
// поэтому «Волна» живёт в фоне и успевает поставить следующий трек, даже когда открыто другое приложение.
// К Aura отсюда не подключаемся: она поднимается, только когда музыку попросили
public class Listener extends NotificationListenerService {
    @Override public void onListenerConnected() {
        Engine.get(this).watchSessions();
    }

    @Override public void onCreate() {
        super.onCreate();
        Engine.get(this);
    }
}
