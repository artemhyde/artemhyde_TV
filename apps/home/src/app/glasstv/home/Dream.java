package app.glasstv.home;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;

// Заставка: когда ТВ простаивает в любом приложении, показывает ТОТ ЖЕ экран, что блокировка «Дома»: то же фото,
// часы и дата сверху, погода и курс, стих. Раньше это был другой экран (часы внизу, без стиха), и через 10 минут
// блокировка «перескакивала» на него: теперь переход незаметен (27.10).
// Через 15 минут простоя «Сторож» убавляет настоящую подсветку вдвое (энергосбережение, экран не гаснет),
// любая кнопка заканчивает заставку и сразу возвращает подсветку
public class Dream extends DreamService {
    static final long SAVER_MS = 15 * 60_000;

    private Ambient amb;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable saver = () -> guard(true);

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        amb = new Ambient(this, false);
        setContentView(amb.root);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        amb.start();
        h.postDelayed(saver, SAVER_MS);
    }

    @Override public void onDreamingStopped() {
        h.removeCallbacks(saver);
        guard(false);
        if (amb != null) amb.stop();
        super.onDreamingStopped();
    }

    // Подсветку меняет «Сторож» (у него доступ к TvBacklightManager TCL и своя ночная логика)
    private void guard(boolean on) {
        try {
            sendBroadcast(new Intent().setClassName("app.glasstv.guard", "app.glasstv.guard.Cmd").putExtra("do", "saver").putExtra("on", on));
        } catch (Exception ignored) { }
    }
}
