package app.glasstv.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

// Быстрая полоса таблеток поверх любого приложения, в том числе поверх идущего фильма. Её открывает кнопка
// «шестерёнка» на пульте через Key Mapper (Send intent, явный компонент app.glasstv.home/.PillsActivity).
// Системную панель TCL перехватить нельзя, поэтому наша полоса стала первым экраном шестерёнки, а панель TCL одной из таблеток.
// Окно полностью прозрачное, без затемнения, в своей задаче (taskAffinity в манифесте): «Дом» не выезжает вперёд
// и не пересоздаётся. Управление только пультом: влево и вправо по таблеткам (на краю стоим), OK запускает,
// «Назад» закрывает, повторное нажатие шестерёнки закрывает (приходит в onNewIntent), через 10 секунд без нажатий
// полоса закрывается сама. В покое ноль кадров: ничего не крутится и не перерисовывается
public class PillsActivity extends Activity {
    static final long IDLE_MS = 10_000;   // у панели TCL 15 с
    static final String PREFS = "pills", LAST = "last";
    static final String HINT = "Шестерёнка ещё раз: закрыть · долго: панель TCL";
    static final String PICTURE = "app.glasstv.picture", NIGHT = "app.glasstv.nightmode", SOUND = "app.glasstv.sound";
    private String music;

    boolean has(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Exception e) { return false; }
    }

    // Одна таблетка: что показать и как запустить
    private static final class Pill {
        final String id, label;
        final int icon;
        Pill(String id, String label, int icon) { this.id = id; this.label = label; this.icon = icon; }
    }

    private final List<Pill> pills = new ArrayList<>();
    private final List<PillView> views = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable autoClose = this::close;
    private LinearLayout strip;
    private float dp;
    private int sel;
    private boolean closing, okDown;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        Fonts.init(this);

        // Таблетка только к приложению, которое стоит (07.11: в выпуске «Звук» ставится только с Dolby, «Волна» по желанию,
        // а таблетка без приложения ничего не открывала). «Картинки» может не быть на этом телевизоре: тогда таблетки нет вовсе
        if (has(NIGHT)) pills.add(new Pill("night", "Ночь", PillView.MOON));
        if (has(SOUND)) pills.add(new Pill("sound", "Звук", PillView.SOUND));
        if (getPackageManager().getLaunchIntentForPackage(PICTURE) != null) pills.add(new Pill("picture", "Картинка", PillView.SUN));
        // Музыка: что выбрал человек («Настройки «Дома»», «Музыка»), без выбора «Волна», если стоит; нет ничего: таблетки нет
        music = Setup.musicApp(this);
        if (music != null) pills.add(new Pill("volna", Setup.VOLNA.equals(music) ? "Волна" : "Музыка", PillView.WAVE));
        pills.add(new Pill("style", "Стиль", PillView.SHAPE));   // вид «Дома»: «Графит» или «Vision», по кругу
        // Панель TCL только на TCL и только если её служба есть в прошивке (план Codex, блок 5); иначе обычные настройки Android
        if (tclPanel()) pills.add(new Pill("tcl", "Панель TCL", PillView.GRID));
        else pills.add(new Pill("settings", "Настройки ТВ", PillView.GRID));

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        // Прозрачность полосы применяется к каждой таблетке напрямую, без отдельного слоя
        strip = new LinearLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setGravity(Gravity.CENTER_HORIZONTAL);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.bottomMargin = px(48);   // там же, где была бы панель TCL
        root.addView(strip, sp);

        strip.addView(new Hint(this, tclPanel() ? HINT : "Шестерёнка ещё раз: закрыть", dp), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout row = new LinearLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.topMargin = px(12);
        strip.addView(row, rp);

        // Фокус при открытии на таблетке, выбранной в прошлый раз; в первый раз на «Ночи»
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST, "night");
        for (int i = 0; i < pills.size(); i++) {
            Pill p = pills.get(i);
            PillView v = new PillView(this, p.icon, p.label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = px(10);
            row.addView(v, lp);
            views.add(v);
            if (p.id.equals(last)) sel = i;
        }
        views.get(sel).setLit(true, false);

        setContentView(root);
        // Появление: вся полоса поднимается на 18 dp и проявляется за 200 мс. Старт после первой раскладки,
        // когда картинки стекла уже готовы: иначе тяжёлый первый кадр съел бы начало движения
        strip.setAlpha(0f);
        strip.setTranslationY(18 * dp);
        strip.post(() -> strip.animate().alpha(1f).translationY(0f).setStartDelay(0)
                .setDuration(M.t(200)).setInterpolator(PillView.EASE).start());
        restartIdle();
    }

    int px(float v) { return Math.round(v * dp); }

    // ===== Закрытие и запуск =====

    // Уход: обратно за 160 мс, потом закрытие без системной анимации
    void close() {
        if (closing) return;
        closing = true;
        h.removeCallbacksAndMessages(null);
        strip.animate().cancel();
        strip.animate().alpha(0f).translationY(18 * dp).setStartDelay(0).setDuration(M.t(160))
                .setInterpolator(PillView.EASE).withEndAction(this::leave).start();
    }

    // Закрыться мгновенно: цель уже запущена, анимация ухода только задержала бы её появление
    void leave() {
        finish();
        overridePendingTransition(0, 0);
    }

    // Сначала запускаем цель, сразу закрываемся. Если запуск не удался, полоса просто закрывается как по «Назад»
    void run(int i) {
        if (closing) return;
        Pill p = pills.get(i);
        closing = true;
        h.removeCallbacksAndMessages(null);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST, p.id).apply();
        if (start(p.id)) { leave(); return; }
        closing = false;
        close();
    }

    boolean start(String id) {
        try {
            switch (id) {
                case "night": return open(new Intent().setClassName("app.glasstv.nightmode", "app.glasstv.nightmode.MainActivity"));
                case "sound": return open(new Intent().setClassName("app.glasstv.sound", "app.glasstv.sound.MainActivity"));
                case "picture": {
                    Intent i = getPackageManager().getLaunchIntentForPackage(PICTURE);
                    return i != null && open(i);
                }
                case "style":
                    // Стиль «Дома»: запоминается сразу, «Дом» перестроится, как только покажется (он же под полосой)
                    Style.toggle(this);
                    return true;
                case "volna":
                    if (Setup.VOLNA.equals(music)) return open(new Intent().setClassName("app.glasstv.volna", "app.glasstv.volna.MenuActivity"));
                    Intent mi = music == null ? null : Go.plain(this, music);
                    return mi != null && open(mi);
                case "tcl":
                    // Системная панель TCL: служба открыта без разрешения, Key Mapper запускал её так же
                    // Нет службы (другая прошивка): null, и полоса закроется обычным плавным уходом
                    return startService(tclPanelIntent()) != null;
                case "settings": return open(new Intent(android.provider.Settings.ACTION_SETTINGS));
                default: return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    static Intent tclPanelIntent() {
        return new Intent("com.tcl.settings.SHOW_WINDOW")
                .setComponent(new ComponentName("com.android.tv.settings", "com.tcl.settings.ShowWindowService"))
                .putExtra("Type", "quicksettings");
    }

    boolean tclPanel() {
        boolean tcl = "TCL".equalsIgnoreCase(android.os.Build.MANUFACTURER) || "TCL".equalsIgnoreCase(android.os.Build.BRAND);
        return tcl && getPackageManager().resolveService(tclPanelIntent(), 0) != null;
    }

    // Цель появляется мгновенно: свой переход нулевой
    boolean open(Intent i) {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        return true;
    }

    // ===== Пульт =====

    void restartIdle() {
        h.removeCallbacks(autoClose);
        h.postDelayed(autoClose, IDLE_MS);
    }

    // Влево и вправо по кругу не ходим: на краю стоим
    void move(int d) {
        int n = sel + d;
        if (n < 0 || n >= views.size()) return;
        views.get(sel).setLit(false, true);
        sel = n;
        views.get(sel).setLit(true, true);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean nav = ok || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
        // Любое нажатие сбрасывает отсчёт, в том числе громкость, которую отдаём системе как есть
        if (!closing) restartIdle();
        if (!nav) return super.dispatchKeyEvent(e);
        if (closing) return true;
        if (k == KeyEvent.KEYCODE_BACK) {
            // Закрываем на отпускании: «вверх» от «Назад» не должен достаться приложению под нами
            if (!down) close();
            return true;
        }
        if (ok) {
            // Запуск на отпускании: «вверх» от OK остаётся у нас и не нажимает ничего в запущенном окне
            if (down) { if (e.getRepeatCount() == 0) okDown = true; }
            else if (okDown) { okDown = false; run(sel); }
            return true;
        }
        if (down) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) move(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) move(1);
            // Вверх и вниз ничего не делают: съедаем, чтобы фокус не уходил в пустоту
        }
        return true;
    }

    // Повторное нажатие шестерёнки при открытой полосе: переключатель, закрываем
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        close();
    }

    @Override public void onBackPressed() { close(); }

    // Ушли со сна или «Домой»: закрыться самой. noHistory при остановке из-за сна экран не закрывает, и после
    // пробуждения полоса висела бы поверх фильма без таймера (замечание проверки 27.10)
    @Override protected void onStop() {
        h.removeCallbacksAndMessages(null);
        if (!isChangingConfigurations() && !isFinishing()) finish();
        super.onStop();
    }

    // ===== Подпись над рядом =====

    // Мелкая подпись на своей тёмной капсуле, запечённой в картинку один раз. Тени у букв не хватало: на главном экране
    // подпись ложится на стих заставки и читалась вперемешку с ним (снимок на ТВ 27.10). Капсула держит её на любом кадре
    private static final class Hint extends View {
        private final Bitmap bmp;
        private final int pad, tw, th;

        Hint(Context c, String s, float dp) {
            super(c);
            TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            t.setTypeface(Fonts.regular(Fonts.CAPTION));
            t.setTextSize(Fonts.CAPTION * dp);
            Paint.FontMetrics fm = t.getFontMetrics();
            tw = (int) Math.ceil(t.measureText(s));
            th = (int) Math.ceil(fm.descent - fm.ascent);
            // Поле под широкую тень (размытие 12 dp тянется примерно на 12 dp от букв); размер и место подписи не меняются
            pad = Math.round(14 * dp);
            Bitmap m = Bitmap.createBitmap(tw + 2 * pad, th + 2 * pad, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(m);
            float base = pad - fm.ascent;
            float hx = 12 * dp, hy = 4 * dp;
            android.graphics.RectF cap = new android.graphics.RectF(pad - hx, pad - hy, pad + tw + hx, pad + th + hy);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(0xE61E1C24);
            cv.drawRoundRect(cap, cap.height() / 2, cap.height() / 2, bg);
            bg.setStyle(Paint.Style.STROKE);
            bg.setStrokeWidth(dp);
            bg.setColor(0x1FFFFFFF);
            cap.inset(dp / 2, dp / 2);
            cv.drawRoundRect(cap, cap.height() / 2, cap.height() / 2, bg);
            t.setColor(0xC7FFFFFF);
            cv.drawText(s, pad, base, t);
            bmp = Tile.fixed(m);   // неизменяемую картинку видеоядро не грузит заново на каждом кадре (05.11)
        }

        @Override protected void onMeasure(int w, int hh) { setMeasuredDimension(tw, th); }

        @Override public boolean hasOverlappingRendering() { return false; }

        @Override protected void onDraw(Canvas c) { c.drawBitmap(bmp, -pad, -pad, null); }
    }
}
