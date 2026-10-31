package app.glasstv.guard;

import android.util.Log;

// Настоящая подсветка панели TCL: ползунок «Яркость» в меню картинки и в быстрой панели (0..100).
// Системный screen_brightness на этом ТВ панель не трогает, поэтому идём в службу TCL tcl_tv_display
// через общую библиотеку прошивки com.tcl.tv.display (подключена в манифесте, вызов отражением)
final class Backlight {
    private Backlight() { }

    private static Object mgr() throws Exception {
        return Class.forName("com.tcl.tv.pq.TvBacklightManager").getMethod("getInstance").invoke(null);
    }

    // -1, если служба недоступна
    static int get() {
        try {
            Object m = mgr();
            return (Integer) m.getClass().getMethod("getBacklight").invoke(m);
        } catch (Throwable e) {
            Log.w("guard", "подсветка TCL: чтение не удалось", e);
            return -1;
        }
    }

    // «Адаптивная регулировка яркости» по датчику света: 1 включена, 0 выключена, -1 служба недоступна
    static int lightSensor() {
        try {
            Object m = mgr();
            return (Integer) m.getClass().getMethod("getLightSensorOnoff").invoke(m);
        } catch (Throwable e) {
            Log.w("guard", "датчик света TCL: чтение не удалось", e);
            return -1;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean setLightSensor(boolean on) {
        try {
            Object m = mgr();
            Class act = Class.forName("com.tcl.tv.pq.EnTclActType");
            return (Boolean) m.getClass().getMethod("setLightSensorOnoff", int.class, act)
                    .invoke(m, on ? 1 : 0, Enum.valueOf(act, "EN_TCL_ACT_EXEC_SAVE"));
        } catch (Throwable e) {
            Log.w("guard", "датчик света TCL: запись не удалась", e);
            return false;
        }
    }

    // save=false: только применить (после перезагрузки вернётся сохранённое), true: применить и сохранить в режим картинки
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean set(int v, boolean save) {
        try {
            Object m = mgr();
            Class ap = Class.forName("com.tcl.tv.pq.EnApplyType"), act = Class.forName("com.tcl.tv.pq.EnTclActType");
            Object a = Enum.valueOf(ap, "EN_APPLY_CURRENT");
            Object t = Enum.valueOf(act, save ? "EN_TCL_ACT_EXEC_SAVE" : "EN_TCL_ACT_EXEC");
            return (Boolean) m.getClass().getMethod("setBacklight", ap, int.class, act).invoke(m, a, Math.max(0, Math.min(100, v)), t);
        } catch (Throwable e) {
            Log.w("guard", "подсветка TCL: запись не удалась", e);
            return false;
        }
    }
}
