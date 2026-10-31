package app.glasstv.nightmode;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

// Меню поверх фильма. Сверху вниз: шапка, строка состояния, один сквозной ползунок «Яркость» (правая часть это настоящая
// подсветка панели 100..0, левее нуля красная зона затемнения до 80 %), под ним кнопка «Выключить ночной режим»,
// «Уснуть через» и «Тёплый свет». Сдвинули ползунок, и ночной режим уже включён: служба держит выбранное, «Назад» меню
// закрывает и яркость остаётся. Всё это влезает в экран 960x540 dp
public class MainActivity extends Activity {
    static final int[] MINUTES = {15, 20, 30, 45, 60, 90};
    static final int WARM = 0xFFE9C27A;
    static final int RED_TEXT = 0xFFFF8F84;

    SharedPreferences prefs;
    FrameLayout root;
    LinearLayout panel;
    TextView status, caption, off, warmLabel;
    Bar bar;
    boolean bl;         // шкала с подсветкой панели; иначе подсветкой управлять нельзя и вся шкала это затемнение
    boolean preOn;      // ночной режим уже шёл, когда меню открыли
    int origIdx = -1;   // где стоял ползунок при открытии: нынешняя подсветка телевизора (режим не шёл)
    int origExact = -1; // она же без округления до шага 5, её вернём, если ползунок вернули на место
    boolean engaged;    // ночной режим включили движением ползунка в этом меню
    boolean quiet;      // меню закрывает кнопка со своим действием: значок «Ночной режим» не нужен
    boolean left;       // итог для ночного режима уже подведён (меню закрылось или ушло в фон)
    boolean shown;      // меню проявилось до конца: только тогда его окно вырезается из вуали
    long engagedAt;
    final ArrayList<TextView> warmChips = new ArrayList<>();
    final Handler h = new Handler(Looper.getMainLooper());
    float dp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("night", MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        NightService.migrate(prefs);
        // Ночной режим прервался аварийно (служба мертва, а отметка «включён» осталась: убили процесс или обновили
        // приложение): вернуть обычную подсветку и сказать «Сторожу», что режим выключен, иначе ночной ход он не ведёт
        if (!NightService.running) {
            if (prefs.getBoolean("active", false)) {
                prefs.edit().putBoolean("active", false).putLong("endAt", 0).apply();
                NightService.notifyGuard(this, false);
            }
            NightService.restoreBacklight(this, prefs);
        }
        if (quickStart(getIntent())) return;
        open();

        root = new FrameLayout(this);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(40), px(24), px(40), px(26));
        // Ширина не прыгает между «режим выключен» и «идёт»: ряд таймера задаёт её только пока он шире (иначе шапка слиплась бы)
        panel.setMinimumWidth(px(772));
        panel.setClipToPadding(false);   // кнопка в фокусе чуть растёт: у правого края её не срезать (27.10)
        GradientDrawable bg = new GradientDrawable();
        // Непрозрачная: при 95 % сквозь шапку проступали крупные часы главного экрана, при 98 % еле видны (снимки 27.10)
        bg.setColor(0xFF141418);
        bg.setCornerRadius(px(30));
        bg.setStroke(px(1), 0x1FFFFFFF);
        panel.setBackground(bg);
        panel.setElevation(px(24));
        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        render();

        panel.setAlpha(0f);
        panel.setTranslationY(px(18));
        panel.animate().alpha(1f).translationY(0).setDuration(240)
                .setInterpolator(new DecelerateInterpolator(2f))
                .withEndAction(() -> { shown = true; publishHole(); }).start();
        // Размер меню меняется (идёт таймер или нет, шире ряд кнопок): вырез в вуали следует за ним
        panel.addOnLayoutChangeListener((v, x0, y0, x1, y1, ox0, oy0, ox1, oy1) -> publishHole());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (!quickStart(i) && panel != null) render();
    }

    // Проверка из adb: am start -n app.glasstv.nightmode/.MainActivity --ei seconds 70 [--ei level -40]
    // Уровень необязателен: -80..-10 затемнение в %, 0..100 подсветка; без него ночь идёт на нынешней яркости
    // (или на прежнем уровне, если режим уже идёт). Перед обновлением приложения: --ez unadmin true снимает права
    // администратора (переустановка с ними вешает систему TCL)
    boolean quickStart(Intent i) {
        if (i != null && i.getBooleanExtra("unadmin", false)) {
            quiet = true;
            getSystemService(DevicePolicyManager.class).removeActiveAdmin(new ComponentName(this, AdminReceiver.class));
            close();
            return true;
        }
        int s = i == null ? -1 : i.getIntExtra("seconds", -1);
        if (s < 0) return false;
        quiet = true;
        if (i.hasExtra("level")) {
            prefs.edit().putInt("lvl", Math.max(NightService.MIN_LEVEL, Math.min(NightService.MAX_LEVEL, i.getIntExtra("level", 0)))).apply();
        } else if (!NightService.running) {
            prefs.edit().putInt("lvl", NightService.canBacklight() ? NightService.levelOf(Backlight.get()) : 0).apply();
        }
        NightService.send(this, NightService.START, s);
        close();
        return true;
    }

    // Снимок на момент открытия: чем гасим, где стоял ползунок и что было до нас
    void open() {
        preOn = NightService.running;
        bl = preOn ? NightService.usesBl : NightService.canBacklight();
        if (preOn) return;
        // Режим не шёл: ползунок встаёт на ТЕКУЩУЮ подсветку телевизора. Это и исходное значение для правила «вернули на место»
        origExact = bl ? Backlight.get() : -1;
        origIdx = NightService.indexOf(NightService.levelOf(origExact), bl);
        prefs.edit().putInt("lvl", NightService.levelAt(origIdx, bl)).apply();   // запуск по таймеру без движения ползунка ничего не меняет
    }

    @Override protected void onResume() {
        super.onResume();
        h.post(clock);
        publishHole();
    }

    @Override protected void onPause() {
        h.removeCallbacks(clock);
        dropHole();   // до итога для режима: уходящее меню уже затемнено вместе с экраном
        if (isFinishing()) leave();
        super.onPause();
    }

    // Служба рисует вуаль поверх всех окон, в том числе поверх меню: на 80 % подписи гаснут вместе с фильмом и не читаются
    // (проверено расчётом: белый текст около 1,9 к 1, мелкие подписи около 1,4 к 1). Поэтому окно меню отдаём службе как вырез:
    // вуаль остаётся по краям, а само меню светит как на подсветке 0. Без движения, если границы те же (слушатель раскладки частый)
    void publishHole() {
        if (!shown || isFinishing() || panel == null || panel.getWidth() == 0) return;
        int[] p = new int[2];
        panel.getLocationOnScreen(p);
        int top = p[1] - Math.round(panel.getTranslationY());
        int in = px(1);   // на волос внутрь: сглаженная кромка меню не оставляет светлой каймы
        Rect r = new Rect(p[0] + in, top + in, p[0] + panel.getWidth() - in, top + panel.getHeight() - in);
        if (r.equals(NightService.hole)) return;
        NightService.holeR = px(30) - in;
        NightService.hole = r;
        if (NightService.running) NightService.send(this, NightService.STYLE, 0);
    }

    // Меню закрывается или ушло в фон: вуаль снова накрывает весь экран
    void dropHole() {
        if (NightService.hole == null) return;
        NightService.hole = null;
        if (NightService.running) NightService.send(this, NightService.STYLE, 0);
    }

    // Ушли из меню «Домой» (или экран погас): закрываем его сами. Режим singleTask вернул бы то же меню со старым снимком
    // (подсветку за это время мог сменить «Сторож»), а так следующий запуск читает всё заново
    @Override protected void onStop() {
        if (!isFinishing()) { leave(); finish(); }
        super.onStop();
    }

    // Ползунок стоит на исходной яркости: ночной режим при закрытии меню не останется
    boolean atOrigin() { return !preOn && bar != null && bar.pos() == origIdx; }

    // Итог для ночного режима при закрытии меню. Ползунок на исходном: режим не включаем, подсветка встаёт как была.
    // Иначе, если режим идёт, на пять секунд виден значок, что он включён и держит яркость (кроме закрытия кнопкой со своим действием)
    void leave() {
        if (left) return;
        left = true;
        if (engaged && atOrigin()) {
            engaged = false;
            NightService.send(this, NightService.STOP, 0);
        } else if (!quiet && (NightService.running || engaged)) {
            NightService.send(this, NightService.LEVEL, 5);
        }
    }

    final Runnable clock = new Runnable() {
        @Override public void run() {
            updateStatus();
            h.postDelayed(this, 1000);
        }
    };

    void render() {
        panel.removeAllViews();
        warmChips.clear();
        boolean timer = NightService.running && NightService.endAt != 0;

        // Заголовок, справа быстрый переход к панели «Звук» (27.10): поверх того же фильма подкрутить речь и бас,
        // не выходя на главный экран. В правом углу, а не отдельной строкой: меню и так во всю высоту экрана;
        // с ползунка до неё одно нажатие вверх
        // Шапка рамкой: ширину меню по-прежнему задаёт ряд таймера, а кнопка прижата вправо
        // (с распоркой по весу меню растягивалось на весь экран и срезало кнопку)
        FrameLayout head = new FrameLayout(this);
        head.setClipChildren(false);
        head.setClipToPadding(false);
        TextView title = text("Ночной режим", 30, 0xFFFFFFFF);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(48),
                Gravity.END | Gravity.CENTER_VERTICAL);
        sl.leftMargin = px(32);
        head.addView(chip("Настроить звук (Dolby)", v -> openSound()), sl);
        panel.setClipChildren(false);
        panel.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        status = text("", 17, 0x99FFFFFF);
        status.setPadding(0, px(2), 0, 0);
        panel.addView(status);

        // Яркость: слева простыми словами, что сейчас на экране, справа подсказка. Ползунок встаёт на нынешнюю
        // яркость телевизора (или на выбранное, если режим идёт), любое его движение сразу видно на экране
        FrameLayout cap = new FrameLayout(this);
        cap.setPadding(0, px(14), 0, px(2));
        caption = text("", 18, 0xFFFFFFFF);
        caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cap.addView(caption, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.BOTTOM));
        TextView hint = text(bl ? "Влево темнее, вправо светлее" : "Подсветка панели недоступна, затемняем экран", 15, 0x80FFFFFF);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM);
        hl.leftMargin = px(24);
        cap.addView(hint, hl);
        panel.addView(cap, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        int steps = NightService.steps(bl);
        bar = bl ? new Bar(this, steps, NightService.zero(true), new int[]{0, NightService.ZERO, steps},
                        new String[]{"−80 %", "0", "100 %"}, "красная зона")
                : new Bar(this, steps, -1, new int[]{0, steps}, new String[]{"80 %", "0 %"}, null);
        bar.setId(View.generateViewId());
        bar.setPos(NightService.indexOf(NightService.level(prefs, bl), bl), false);
        bar.setListener(this::moved);
        bar.setOnClickListener(v -> close());   // OK на ползунке: готово, меню закрывается, яркость остаётся (как «Назад»)
        panel.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Крупная кнопка сразу под ползунком, видна всегда: убирает затемнение, возвращает прежнюю подсветку и закрывает меню
        off = bigButton("Выключить ночной режим", v -> turnOff());
        off.setId(View.generateViewId());
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52));
        ol.topMargin = px(12);
        panel.addView(off, ol);

        // Таймер: «Уснуть через» запускает усыпление с тем, что стоит на ползунке; когда таймер уже идёт,
        // можно добавить время
        ArrayList<TextView> timerChips = new ArrayList<>();
        View timerFirst = null;
        if (timer) {
            label("Таймер");
            LinearLayout row = row();
            timerChips.add(add(row, chip("+15 минут", v -> { quiet = true; NightService.send(this, NightService.ADD, 15 * 60); close(); })));
            timerChips.add(add(row, chip("+30 минут", v -> { quiet = true; NightService.send(this, NightService.ADD, 30 * 60); close(); })));
            timerFirst = timerChips.get(0);
        } else {
            label("Уснуть через, минут");
            LinearLayout row = row();
            for (int m : MINUTES) {
                TextView c = add(row, chip(String.valueOf(m), v -> start(m * 60)));
                timerChips.add(c);
                if (m == 20) timerFirst = c;
            }
            timerChips.add(add(row, chip("Без таймера", v -> start(0))));
        }
        // Вниз с кнопки «Выключить» ведёт на одну и ту же кнопку («20» или «+15 минут»), а вверх с любой кнопки таймера обратно на неё
        timerFirst.setId(View.generateViewId());
        for (TextView c : timerChips) c.setNextFocusUpId(off.getId());
        bar.setNextFocusDownId(off.getId());
        off.setNextFocusUpId(bar.getId());
        off.setNextFocusDownId(timerFirst.getId());

        warmLabel = label("Тёплый свет");
        LinearLayout warmRow = row();
        warmChips.add(add(warmRow, chip("Включён", v -> setWarm(true))));
        warmChips.add(add(warmRow, chip("Выключен", v -> setWarm(false))));

        restyle();
        // requestFocusFromTouch: курсор встаёт на ползунок, даже если ТВ остался в режиме касаний
        bar.post(() -> bar.requestFocusFromTouch());
    }

    // Ползунок сдвинули пультом: это и есть включение ночного режима. Служба не останавливается, даже когда ползунок
    // проходит исходную точку: там она держит исходную подсветку без вуали (на экране как до режима), а «режим не включён»
    // решает закрытие меню (leave). Так служба не гаснет и не встаёт заново посреди удержания кнопки
    void moved(int idx) {
        int lvl = NightService.levelAt(idx, bl);
        prefs.edit().putInt("lvl", lvl).apply();
        // Обычную подсветку запоминаем до первого изменения; точное число, а не округлённое до шага 5
        if (bl && origExact >= 0 && !prefs.contains("bl0")) prefs.edit().putInt("bl0", origExact).apply();
        engaged = true;
        engagedAt = SystemClock.elapsedRealtime();
        NightService.send(this, NightService.LEVEL, 0);
        showCaption(lvl);
        updateStatus();
    }

    void setWarm(boolean on) {
        prefs.edit().putBoolean("warm", on).apply();
        restyle();
        if (NightService.running) NightService.send(this, NightService.STYLE, 0);   // идущий режим перерисует слой
    }

    // Отмечает выбранное
    void restyle() {
        boolean warm = prefs.getBoolean("warm", true);
        warmChips.get(0).setSelected(warm);
        warmChips.get(1).setSelected(!warm);
        showCaption(NightService.level(prefs, bl));
        updateStatus();
    }

    void start(int seconds) {
        quiet = true;
        engaged = false;   // дальше режимом владеет служба (с таймером или без), закрытие меню его не трогает
        NightService.send(this, NightService.START, seconds);
        close();
    }

    // Кнопка «Выключить ночной режим»: убрать затемнение, вернуть обычную подсветку (служба берёт её из "bl0"), закрыть меню
    void turnOff() {
        quiet = true;
        if (NightService.running || engaged) NightService.send(this, NightService.STOP, 0);
        engaged = false;
        close();
    }

    // Подпись над шкалой простыми словами: правее нуля яркость, в красной зоне затемнение
    void showCaption(int lvl) {
        String s;
        int color = 0xFFFFFFFF;
        if (!bl) {
            s = lvl < 0 ? "Затемнение " + (-lvl) + " %" : "Без затемнения";
        } else if (lvl >= 0) {
            s = "Яркость " + lvl + " %";
        } else {
            s = "Ниже нуля: затемнение " + (-lvl) + " %";
            color = RED_TEXT;
        }
        caption.setText(s);
        caption.setTextColor(color);
    }

    void updateStatus() {
        if (status == null) return;
        // Служба не поднялась (например, нет разрешения поверх окон): не врать «включён»
        if (engaged && !NightService.running && SystemClock.elapsedRealtime() - engagedAt > 3000) engaged = false;
        boolean on = (NightService.running || engaged) && !atOrigin();   // на исходной яркости режим не включён
        if (!on) {
            status.setText("Ночной режим выключен: подвиньте ползунок, и он включится");
        } else if (NightService.running && NightService.endAt != 0) {
            status.setText("Сон через " + NightService.clock(NightService.endAt - SystemClock.elapsedRealtime()));
        } else {
            status.setText("Включён, без таймера");
        }
        status.setTextColor(on ? WARM : 0x99FFFFFF);
        warmLabel.setText("Тёплый свет (янтарный оттенок ниже нуля и при угасании)");
    }

    // Панель «Звук» (app.glasstv.sound) встаёт поверх фильма, а меню ночного режима уходит
    void openSound() {
        quiet = true;
        leave();   // ползунок на исходном: режим не оставляем
        try {
            startActivity(new Intent().setClassName("app.glasstv.sound", "app.glasstv.sound.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
        close();
    }

    void close() {
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    TextView label(String s) {
        TextView t = text(s, 15, 0x80FFFFFF);
        t.setPadding(0, px(14), 0, px(8));
        panel.addView(t);
        return t;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        panel.setClipChildren(false);
        panel.addView(r);
        return r;
    }

    TextView add(LinearLayout row, TextView chip) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, px(48));
        if (row.getChildCount() > 0) lp.leftMargin = px(12);
        row.addView(chip, lp);
        return chip;
    }

    TextView chip(String s, View.OnClickListener l) {
        TextView t = text(s, 19, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(px(78));
        t.setPadding(px(22), 0, px(22), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{0xFF141418, WARM, 0xFFFFFFFF}));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, round(0xFFFFFFFF, 0));
        bg.addState(new int[]{android.R.attr.state_selected}, round(0x1FE9C27A, WARM));
        bg.addState(new int[]{}, round(0x17FFFFFF, 0));
        t.setBackground(bg);
        t.setOnClickListener(l);
        t.setOnFocusChangeListener((v, f) -> v.animate()
                .scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(140).start());
        return t;
    }

    // Крупная красноватая кнопка «Выключить ночной режим» на всю ширину меню; в фокусе белая, как остальные
    TextView bigButton(String s, View.OnClickListener l) {
        TextView t = text(s, 20, RED_TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF141418, RED_TEXT}));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, round(0xFFFFFFFF, 0));
        bg.addState(new int[]{}, round(0x26FF6B5E, 0x66FF6B5E));
        t.setBackground(bg);
        t.setOnClickListener(l);
        t.setOnFocusChangeListener((v, f) -> v.animate()
                .scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(140).start());
        return t;
    }

    GradientDrawable round(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(16));
        if (stroke != 0) g.setStroke(px(2), stroke);
        return g;
    }

    int px(int v) { return Math.round(v * dp); }
}
