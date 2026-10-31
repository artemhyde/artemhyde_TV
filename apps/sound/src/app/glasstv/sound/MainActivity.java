package app.glasstv.sound;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

// Полоса «Звук» поверх текущего приложения, управление только пультом. Фильм за ней продолжает идти,
// каждое нажатие через 150 мс уходит «Сторожу» и сразу слышно. Протокол обмена описан в Eq.java.
// С 27.10 (версия 1.2.0) вид общий с «Картинкой» (Ui.java): тонкая полупрозрачная полоса внизу экрана в стиле «Графит»,
// плоские капсулы режимов, ползунки с тонкой линией и круглым бегунком, почти без текста. Слева в шапке вкладки
// «Звук» и «Картинка»: OK на соседней открывает другое приложение на том же месте (оно встаёт без выезда), это закрывается.
// Пульт: вверх и вниз выбирают строку (сверху шапка: две вкладки и четыре режима, ниже четыре ползунка), влево и вправо в шапке
// двигают выделение по вкладкам и режимам, OK выбирает режим или открывает вкладку, на ползунке влево и вправо меняют значение.
// «Назад» закрывает, без нажатий полоса уходит сама через 20 секунд. Открывается и из меню «Ночного режима»
public class MainActivity extends Activity {
    static final int AUTO_CLOSE_MS = 20_000, SEND_DELAY_MS = 150;
    static final int ACCENT = 0xFF78A9FF;
    static final String OTHER_PKG = "app.glasstv.picture";
    static final int TABS = 2, MODES = 4;     // в шапке слева направо: вкладка «Звук», вкладка «Картинка», четыре режима

    final Handler h = new Handler(Looper.getMainLooper());
    float dp;

    final LinearLayout[] caps = new LinearLayout[TABS + MODES];
    final TextView[] capText = new TextView[TABS + MODES];
    final LinearLayout[] rows = new LinearLayout[4];
    final TextView[] labels = new TextView[4], values = new TextView[4];
    final Bar[] bars = new Bar[4];
    TextView status;
    LinearLayout panel;

    final int[] v = Eq.VALUES[0].clone();     // что показано и что отправим: речь, выравнивание, бас, детали
    int row = 0, col = TABS;                   // строка (0 = шапка, 1..4 = ползунки) и выделенное место в шапке
    boolean touched;                           // пользователь уже что-то нажал: ответ «Сторожа» значения больше не меняет
    boolean dirty;                             // есть неотправленное изменение
    String note = "";                          // что мешает работе (жёлтым), пусто если всё хорошо
    boolean custom;                            // числа не совпадают ни с одним режимом

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        Ui.init(this);
        build();
        paint(false);

        if (getIntent().getBooleanExtra("swap", false)) {
            panel.setAlpha(1f);       // пришли с соседней вкладки: та же полоса на том же месте, без выезда
        } else {
            panel.setAlpha(0f);
            panel.setTranslationY(px(24));   // выезжает снизу, откуда и стоит
            panel.animate().alpha(1f).translationY(0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        load();
    }

    // Полоса уже открыта, а кнопку нажали снова: продлить на 20 секунд и перечитать состояние
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        bump();
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        bump();
    }

    @Override protected void onPause() {
        h.removeCallbacks(closer);
        flush();                               // что успели нажать, обязательно уходит «Сторожу»
        super.onPause();
    }

    // Окна больше не видно (нажали «Домой» и подобное): не оставлять полосу висеть в фоне
    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    @Override public void onBackPressed() { close(); }

    // ===== Пульт =====

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        bump();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: moveRow(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: moveRow(1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: side(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: side(1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (row == 0 && e.getRepeatCount() == 0) press(col);
                return true;
            default:
                return super.onKeyDown(code, e);     // «Назад» и остальное: как обычно
        }
    }

    void moveRow(int d) {
        int n = Math.max(0, Math.min(4, row + d));
        if (n == row) return;
        row = n;
        paint(true);
    }

    void side(int d) {
        if (row == 0) {
            int n = Math.max(0, Math.min(caps.length - 1, col + d));
            if (n == col) return;
            col = n;
            paint(true);
        } else {
            int k = row - 1;
            int n = Eq.clamp(k, v[k] + d);
            if (n == v[k]) return;
            v[k] = n;
            paint(true);
            changed();
        }
    }

    // OK в шапке: вкладка «Картинка» открывает соседа, режим ставит свои числа
    void press(int c) {
        if (c == 0) return;                    // это и есть «Звук»
        if (c == 1) { swap(); return; }
        choose(c - TABS);
    }

    void swap() {
        flush();
        if (Ui.open(this, OTHER_PKG)) {
            finish();
            overridePendingTransition(0, 0);
        } else {
            note = "Приложение «Картинка» не установлено";
            paint(false);
        }
    }

    // OK на режиме: ползунки встают по таблице режима
    void choose(int i) {
        System.arraycopy(Eq.VALUES[i], 0, v, 0, 4);
        paint(true);
        changed();
    }

    // ===== Отправка и чтение =====

    // Применять с задержкой 150 мс после последнего нажатия: пока держат кнопку, шлётся один раз в конце
    void changed() {
        touched = true;
        dirty = true;
        h.removeCallbacks(send);
        h.postDelayed(send, SEND_DELAY_MS);
    }

    final Runnable send = this::flush;

    void flush() {
        h.removeCallbacks(send);
        if (!dirty) return;
        dirty = false;
        int p = Eq.presetOf(v);
        Eq.set(this, h, s -> reply(s, true), p == Eq.CUSTOM ? "custom" : Eq.IDS[p], v.clone());
    }

    void load() {
        Eq.read(this, h, s -> {
            if (s != null && !touched) {
                System.arraycopy(s.v, 0, v, 0, 4);
                int p = Eq.presetOf(v);
                col = p == Eq.CUSTOM ? TABS : TABS + p;
                paint(false);
            }
            reply(s, false);
        });
    }

    // Строка состояния справа в шапке: что мешает звуку (коралловым), иначе пусто или «Свой»
    void reply(Eq.State s, boolean afterSet) {
        if (isDestroyed() || status == null) return;
        String t = "";
        if (s == null) t = "Сторож не отвечает";
        else if (!s.svc) t = "Сторож не запущен";
        else if ("busy".equals(s.fx)) t = "Звук занят другим приложением";
        else if ("fail".equals(s.fx)) t = "Эффект не запустился";
        else if ("level".equals(s.src) && s.on && !afterSet) t = "Работает прежний «Умный звук»";
        note = t;
        paint(false);
    }

    // ===== Закрытие =====

    final Runnable closer = this::close;

    void bump() {
        h.removeCallbacks(closer);
        h.postDelayed(closer, AUTO_CLOSE_MS);
    }

    void close() {
        flush();
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    // ===== Вид =====

    // Раскрасить по состоянию: какой режим выбран, где выделение, значения ползунков
    void paint(boolean animate) {
        int sel = Eq.presetOf(v);
        custom = sel == Eq.CUSTOM;
        for (int i = 0; i < caps.length; i++) {
            boolean tab = i < TABS, on = row == 0 && col == i;
            boolean chosen = tab ? i == 0 : i - TABS == sel;
            caps[i].setActivated(on);
            caps[i].setSelected(chosen);
            capText[i].setTextColor(on ? Ui.DARK : tab ? (chosen ? Ui.W92 : Ui.W60) : chosen ? ACCENT : Ui.W72);
        }
        for (int k = 0; k < rows.length; k++) {
            boolean on = row == k + 1;
            rows[k].setActivated(on);
            labels[k].setTextColor(on ? Ui.W92 : Ui.W72);
            values[k].setTextColor(on ? Ui.WHITE : Ui.W92);
            values[k].setText(Eq.show(k, v[k]));
            bars[k].setLit(on);
            bars[k].set(v[k], animate);
        }
        if (!note.isEmpty()) {
            status.setText(note);
            status.setTextColor(Ui.WARN);
        } else {
            status.setText(custom ? "Свой" : "");
            status.setTextColor(Ui.W60);
        }
    }

    void build() {
        FrameLayout root = new FrameLayout(this);
        // Тонкая полупрозрачная полоса внизу: фильм виден выше неё, подкручивать речь и бас можно прямо на живой сцене
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(20), px(12), px(20), px(10));
        panel.setBackground(Ui.glass(dp));
        int w = Math.min(px(880), getResources().getDisplayMetrics().widthPixels - px(64));
        FrameLayout.LayoutParams pl0 = new FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pl0.bottomMargin = px(24);
        root.addView(panel, pl0);
        setContentView(root);

        // Шапка: две вкладки, тонкая черта, четыре режима, справа состояние
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String[] tabNames = {"Звук", "Картинка"};
        for (int i = 0; i < caps.length; i++) {
            boolean tab = i < TABS;
            LinearLayout c = new LinearLayout(this);
            c.setGravity(Gravity.CENTER);
            c.setPadding(px(12), 0, px(12), 0);
            c.setBackground(tab ? Ui.tab() : Ui.mode(ACCENT));
            TextView tt = Ui.text(this, tab ? tabNames[i] : Eq.TITLES[i - TABS], 14, true, Ui.W72);
            tt.setSingleLine();
            c.addView(tt);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(30));
            lp.leftMargin = px(i == 0 || i == TABS ? 0 : 4);
            if (i == TABS) {
                View bar = new View(this);
                GradientDrawable line = new GradientDrawable();
                line.setColor(0x29FFFFFF);
                bar.setBackground(line);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Math.max(1, px(1)), px(16));
                bp.leftMargin = px(12);
                bp.rightMargin = px(12);
                head.addView(bar, bp);
            }
            head.addView(c, lp);
            caps[i] = c;
            capText[i] = tt;
        }
        View gap = new View(this);
        head.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        status = Ui.text(this, "", 13, false, Ui.W60);
        status.setSingleLine();
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setGravity(Gravity.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = px(12);
        head.addView(status, sp);
        panel.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(30)));

        // Четыре ползунка в тонких строках: подпись, линия, число
        for (int k = 0; k < rows.length; k++) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(12), 0, px(12), 0);
            r.setBackground(Ui.row(dp));
            TextView l = Ui.text(this, Eq.NAMES[k], 14, false, Ui.W72);
            l.setSingleLine();
            r.addView(l, new LinearLayout.LayoutParams(px(156), LinearLayout.LayoutParams.WRAP_CONTENT));   // ширины подписи и числа те же, что у «Картинки»: при смене вкладки линии стоят на месте
            Bar bar = new Bar(this, Eq.MIN[k], Eq.MAX[k], v[k], false, ACCENT);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            bp.leftMargin = px(8);
            bp.rightMargin = px(8);
            r.addView(bar, bp);
            TextView n = Ui.text(this, "", 14, true, Ui.W92);
            n.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            r.addView(n, new LinearLayout.LayoutParams(px(72), LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(32));
            rp.topMargin = px(k == 0 ? 8 : 2);
            panel.addView(r, rp);
            rows[k] = r;
            labels[k] = l;
            bars[k] = bar;
            values[k] = n;
        }
    }

    int px(int v) { return Math.round(v * dp); }
}
