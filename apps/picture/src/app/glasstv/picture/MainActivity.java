package app.glasstv.picture;

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

// Полоса «Картинка» поверх текущего приложения (27.10, версия 1.0.0), управление только пультом. Фильм за ней продолжает идти,
// каждое нажатие через 150 мс уходит «Сторожу» и сразу видно. Протокол обмена описан в Pic.java. Вид общий со «Звуком» (Ui.java):
// тонкая полупрозрачная полоса внизу в стиле «Графит», плоские капсулы режимов, ползунки с тонкой линией и круглым бегунком.
// Слева в шапке вкладки «Звук» и «Картинка»: OK на соседней открывает другое приложение на том же месте, это закрывается.
// Пульт: вверх и вниз выбирают строку (сверху шапка: две вкладки и четыре режима, ниже четыре ползунка), влево и вправо в шапке
// двигают выделение, OK выбирает режим или открывает вкладку, на ползунке влево и вправо меняют значение (подсветка при удержании
// ускоряется). «Назад» закрывает, без нажатий полоса уходит сама через 20 секунд.
// Выбор действует до ближайшей границы 22:00 или 7:00: потом «Сторож» сам возвращает то, что было, и снова ведёт «Вечер» и «День».
// Режим картинки самого ТВ (Кино, Спорт, Игра) приложение не переключает: пишет в тот, что активен сейчас
public class MainActivity extends Activity {
    static final int AUTO_CLOSE_MS = 20_000, SEND_DELAY_MS = 150;
    static final int ACCENT = 0xFF5ED3B4;    // мятный, как акцент «Дома» (у «Звука» синий)
    static final String OTHER_PKG = "app.glasstv.sound";
    static final int TABS = 2, MODES = 5;     // в шапке слева направо: вкладка «Звук», вкладка «Картинка», четыре режима и «Авто»

    final Handler h = new Handler(Looper.getMainLooper());
    float dp;

    final LinearLayout[] caps = new LinearLayout[TABS + MODES];
    final TextView[] capText = new TextView[TABS + MODES];
    final LinearLayout[] rows = new LinearLayout[4];
    final TextView[] labels = new TextView[4], values = new TextView[4];
    final Bar[] bars = new Bar[4];
    TextView status;
    LinearLayout panel;

    final int[] v = Pic.START.clone();         // что показано: подсветка, чёрный, пик, тепло
    int selected = -1;                         // какой режим совпал с живыми числами (-1 свой)
    int row = 0, col = TABS;                   // строка (0 = шапка, 1..4 = ползунки) и выделенное место в шапке
    boolean touched;                           // пользователь уже что-то нажал: ответ на чтение значения больше не меняет
    int dirty;                                 // биты ползунков, изменённых и ещё не отправленных
    boolean moved;                             // после выбора режима двигали ползунок: ответ на режим значения не трогает
    boolean held;                              // выбор держится до границы
    String until = "", note = "";              // граница (22:00 или 07:00) и то, что мешает работе
    TextView noteLine;                         // то, что мешает, своей строкой под шапкой (видна, только когда есть)

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
            case KeyEvent.KEYCODE_DPAD_LEFT: side(-stepFor(e)); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: side(stepFor(e)); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (row == 0 && e.getRepeatCount() == 0) press(col);
                return true;
            default:
                return super.onKeyDown(code, e);     // «Назад» и остальное: как обычно
        }
    }

    // Подсветка идёт по одному делению, а если кнопку держат, то быстрее: 3, потом 6 за нажатие
    int stepFor(KeyEvent e) {
        if (row != 1) return 1;
        int r = e.getRepeatCount();
        return r < 8 ? 1 : r < 20 ? 3 : 6;
    }

    void moveRow(int d) {
        int n = Math.max(0, Math.min(4, row + d));
        if (n == row) return;
        row = n;
        paint(true);
    }

    // Шаг по шапке (d от -1 до 1) или по значению. На слабом шаге в шапке d всегда ±1, на ползунке может быть больше
    void side(int d) {
        if (row == 0) {
            int n = Math.max(0, Math.min(caps.length - 1, col + Integer.signum(d)));
            if (n == col) return;
            col = n;
            paint(true);
        } else {
            int k = row - 1;
            int n = Pic.clamp(k, v[k] + d);
            if (n == v[k]) return;
            v[k] = n;
            moved = true;
            paint(true);
            changed(1 << k);
        }
    }

    // OK в шапке: вкладка «Звук» открывает соседа, режим просит «Сторожа» поставить свои числа
    void press(int c) {
        if (c == 1) return;                    // это и есть «Картинка»
        if (c == 0) { swap(); return; }
        choose(c - TABS);
    }

    void swap() {
        flush();
        if (Ui.open(this, OTHER_PKG)) {
            finish();
            overridePendingTransition(0, 0);
        } else {
            note = "Приложение «Звук» не установлено";
            paint(false);
        }
    }

    // OK на режиме: сразу «Сторожу» (подсветку под сигнал SDR или HDR выбирает он), ползунки встанут по его ответу
    void choose(int i) {
        touched = true;
        moved = false;
        dirty = 0;
        h.removeCallbacks(send);
        selected = i == MODES - 1 ? -1 : i;   // «Авто» не режим: выбор снимается
        if (i == MODES - 1) held = false;
        paint(true);
        Pic.setPreset(this, h, s -> reply(s, true, true), Pic.IDS[i]);
    }

    // ===== Отправка и чтение =====

    // Применять с задержкой 150 мс после последнего нажатия: пока держат кнопку, шлётся один раз в конце
    void changed(int bit) {
        touched = true;
        dirty |= bit;
        h.removeCallbacks(send);
        h.postDelayed(send, SEND_DELAY_MS);
    }

    final Runnable send = this::flush;

    void flush() {
        h.removeCallbacks(send);
        if (dirty == 0) return;
        int mask = dirty;
        dirty = 0;
        Pic.setValues(this, h, s -> reply(s, true, false), v.clone(), mask);
    }

    void load() {
        Pic.read(this, h, s -> reply(s, false, false));
    }

    // Ответ «Сторожа». Числа в ответе ставят ползунки при открытии (если ещё ничего не нажимали) и после выбора режима (если после него
    // не двигали ползунок). Какой режим совпал, выбор держится или нет и что мешает, берётся из каждого ответа
    void reply(Pic.State s, boolean afterSet, boolean presetReply) {
        if (isDestroyed() || status == null) return;
        if (s == null) { note = "Сторож не отвечает"; paint(false); return; }
        boolean fill = s.has && (presetReply ? !moved : !touched);
        if (fill) System.arraycopy(s.v, 0, v, 0, 4);
        selected = s.preset;
        held = s.held;
        until = s.until;
        if (!afterSet && fill) col = selected >= 0 ? TABS + selected : TABS;
        if (!s.svc) note = "Сторож не запущен";
        else if ("fail".equals(s.st)) note = "ТВ не отдаёт настройки картинки: эта панель работает только на ТВ TCL";
        else if ("nm".equals(s.st)) note = "Подсветку ведёт «Ночной режим»";
        else note = "";
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
        for (int i = 0; i < caps.length; i++) {
            boolean tab = i < TABS, on = row == 0 && col == i;
            boolean chosen = tab ? i == 1 : i - TABS == MODES - 1 ? !held : i - TABS == selected;
            caps[i].setActivated(on);
            caps[i].setSelected(chosen);
            capText[i].setTextColor(on ? Ui.DARK : tab ? (chosen ? Ui.W92 : Ui.W60) : chosen ? ACCENT : Ui.W72);
        }
        boolean peakIdle = v[Pic.BLACK] == 0 && v[Pic.PEAK] > 0;     // без локального затемнения пик не работает: значение бледнеет
        for (int k = 0; k < rows.length; k++) {
            boolean on = row == k + 1;
            rows[k].setActivated(on);
            labels[k].setTextColor(on ? Ui.W92 : Ui.W72);
            values[k].setTextColor(k == Pic.PEAK && peakIdle ? Ui.W60 : on ? Ui.WHITE : Ui.W92);
            values[k].setText(Pic.show(k, v[k]));
            bars[k].setLit(on);
            bars[k].set(v[k], animate);
        }
        // Что мешает: своей строкой под шапкой (06.11, ТВ другой марки): справа в шапке после пяти режимов места почти нет,
        // и пометка обрезалась. Справа в шапке остаются подсказка к пику и до какой границы держится выбор
        noteLine.setText(note);
        noteLine.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
        if (!note.isEmpty()) {
            status.setText("");
        } else if (row == 3 && peakIdle) {
            status.setText("без затемнения пика нет");
            status.setTextColor(Ui.W60);
        } else {
            status.setText(held && !until.isEmpty() ? "до " + until : "");
            status.setTextColor(Ui.W60);
        }
    }

    void build() {
        FrameLayout root = new FrameLayout(this);
        // Тонкая полупрозрачная полоса внизу: фильм виден выше неё, картинку можно подкручивать на живой сцене
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
            TextView tt = Ui.text(this, tab ? tabNames[i] : Pic.TITLES[i - TABS], 14, true, Ui.W72);
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
        noteLine = Ui.text(this, "", 13, false, Ui.WARN);
        noteLine.setSingleLine();
        noteLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        noteLine.setVisibility(View.GONE);
        noteLine.setPadding(px(24), 0, 0, 0);   // вровень с текстом вкладок
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = px(2);
        np.bottomMargin = px(4);
        panel.addView(noteLine, np);

        // Четыре ползунка в тонких строках: подпись, линия, значение
        for (int k = 0; k < rows.length; k++) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(12), 0, px(12), 0);
            r.setBackground(Ui.row(dp));
            TextView l = Ui.text(this, Pic.NAMES[k], 14, false, Ui.W72);
            l.setSingleLine();
            r.addView(l, new LinearLayout.LayoutParams(px(156), LinearLayout.LayoutParams.WRAP_CONTENT));
            Bar bar = new Bar(this, Pic.MIN[k], Pic.MAX[k], v[k], k == Pic.BLACK || k == Pic.PEAK, ACCENT);
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
