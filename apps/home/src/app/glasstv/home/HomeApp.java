package app.glasstv.home;

import android.app.Application;

// Старт процесса «Дома»: цвет стекла читается раньше, чем кто-нибудь тронет Theme (ThemeChoice.java)
public class HomeApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ThemeChoice.load(this);
    }
}
