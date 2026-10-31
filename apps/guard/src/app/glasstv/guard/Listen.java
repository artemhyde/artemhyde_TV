package app.glasstv.guard;

import android.service.notification.NotificationListenerService;

// Пустой слушатель уведомлений: сами уведомления «Сторожу» не нужны, а с ним система отдаёт список медиасессий
// (MediaSessionManager.getActiveSessions). По нему «Сторож» решает, идёт ли видео, когда экран гаснет.
// Включение один раз: cmd notification allow_listener app.glasstv.guard/app.glasstv.guard.Listen
public class Listen extends NotificationListenerService {
}
