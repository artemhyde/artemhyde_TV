package app.glasstv.volna;

import android.content.Context;
import android.provider.Settings;

// Длительности анимаций. В системе ТВ анимации ускорены вдвое (масштаб 0,5, чтобы чужие приложения были бодрее),
// а у нас плавность расписана по миллисекундам: делим на масштаб, и на экране выходит ровно задуманное время
final class M {
    private static float scale = 1f;

    static void init(Context c) {
        try { scale = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f); }
        catch (Exception ignored) { }
        if (scale <= 0.01f) scale = 1f;   // анимации выключены совсем: не растягиваем до бесконечности
    }

    static long t(long ms) { return Math.round(ms / scale); }
}
