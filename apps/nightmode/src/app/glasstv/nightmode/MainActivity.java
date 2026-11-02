package app.glasstv.nightmode;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

// Меню поверх фильма в два этапа (ноябрь). Первое нажатие (Premier или «Ночь» в полосе шестерёнки): маленькая плашка внизу экрана,
// один ползунок «Яркость» и кнопка «Таймер сна». Второе нажатие той же кнопки, или «Вниз» и OK на «Таймер сна»: плашка плавно
// вырастает в полное меню (таймер 15..90 минут, «Выключить», тёплый свет, звук). Третье нажатие закрывает.
// Ползунок показывает настоящую подсветку телевизора 1:1 и пишет её напрямую: служба нужна только красной зоне и таймеру.
// Всё это влезает в экран 960x540 dp
public class MainActivity extends Activity {
    static final int[] MINUTES = {15, 20, 30, 45, 60, 90};
    static final int WARM = 0xFFE9C27A;
    static final int RED_TEXT = 0xFFFF8F84;
    static final int RADIUS = 22;               // скругление как у полос «Звук» и «Картинка»
    static final long AUTO_CLOSE_MS = 10_000;   // плашка закрывается сама, если пультом ничего не нажимали

    SharedPreferences prefs;
    FrameLayout root;
    LinearLayout panel;
    TextView status, caption, side, off;
    Bar bar;
    boolean bl;         // шкала с подсветкой панели; иначе подсветкой управлять нельзя и вся шкала это затемнение
    boolean full;       // развёрнуто полное меню, иначе плашка
    boolean toTimer;    // развернули кнопкой «Таймер сна»: курсор сразу на таймере
    boolean touched;    // ползунок двигали в этом меню
    boolean savePending;// подсветка уже на экране, но ещё не сохранена
    int origExact = -1; // настоящая подсветка при открытии: её вернёт «Выключить» и служба после красной зоны
    boolean quiet;      // меню закрывает кнопка со своим действием: значок «Ночной режим» не нужен
    boolean left;       // итог для ночного режима уже подведён (меню закрылось или ушло в фон)
    boolean shown;      // меню проявилось до конца: только тогда его окно вырезается из вуали
    long movedAt;
    View focusTo;
    final ArrayList<TextView> warmChips = new ArrayList<>();
    final Handler h = new Handler(Looper.getMainLooper());
    float dp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("night", MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        if (!NightService.running) NightService.recover(this, prefs);
        if (quickStart(getIntent())) return;
        open();

        root = new FrameLayout(this);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipToPadding(false);   // кнопка в фокусе чуть растёт: у правого края её не срезать (27.10)
        // Подложка как у полос: графит сверху вниз, непрозрачная (при 95 % сквозь шапку проступали часы главного экрана)
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF302E38, 0xFF1E1C24});
        bg.setCornerRadius(px(RADIUS));
        bg.setStroke(Math.max(1, px(1)), 0x2EFFFFFF);
        panel.setBackground(bg);
        panel.setElevation(px(24));
        root.addView(panel, panelParams());
        setContentView(root);
        render();
        armAutoClose();

        panel.setAlpha(0f);
        panel.setTranslationY(px(18));
        panel.animate().alpha(1f).translationY(0).setDuration(240)
                .setInterpolator(new DecelerateInterpolator(2f))
                .withEndAction(() -> { shown = true; publishHole(); }).start();
        // Размер меню меняется (идёт таймер или нет): вырез в вуали следует за ним
        panel.addOnLayoutChangeListener((v, x0, y0, x1, y1, ox0, oy0, ox1, oy1) -> publishHole());
    }

    // Плашка и полное меню стоят на одном месте внизу, меню растёт вверх от него
    FrameLayout.LayoutParams panelParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(full ? FrameLayout.LayoutParams.WRAP_CONTENT : px(640),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = px(24);
        return lp;
    }

    // Та же кнопка нажата снова: плашка разворачивается, полное меню закрывается
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (quickStart(i) || panel == null) return;
        if (full) close(); else expand(false);
    }

    // Проверка из adb: am start -n app.glasstv.nightmode/.MainActivity --ei seconds 70 [--ei level -40]
    // Уровень необязателен: -100..-1 красная зона, 0..100 подсветка; без него ночь идёт на нынешней яркости
    // (или на прежнем уровне, если режим уже идёт). Перед обновлением приложения: --ez unadmin true снимает права
    // администратора (переустановка с ними вешает систему TCL)
    boolean quickStart(Intent i) {
        if (i != null && i.getBooleanExtra("unadmin", false)) {
            quiet = true;
            getSystemService(DevicePolicyManager.class).removeActiveAdmin(new ComponentName(this, AdminReceiver.class));
            close();
            return true;
        }
        // Ремонт с ПК: вернуть контраст, если его оставила версия 1.8.0. Прочитать: am broadcast -n app.glasstv.guard/.Cmd --es do pq --es a dump
        if (i != null && i.hasExtra("contrast")) {
            quiet = true;
            Backlight.setContrast(i.getIntExtra("contrast", 100));
            close();
            return true;
        }
        int s = i == null ? -1 : i.getIntExtra("seconds", -1);
        if (s < 0) return false;
        quiet = true;
        if (i.hasExtra("level")) {
            prefs.edit().putInt("lvl", Math.max(NightService.MIN_LEVEL, Math.min(NightService.MAX_LEVEL, i.getIntExtra("level", 0)))).apply();
        } else if (!NightService.running) {
            prefs.edit().putInt("lvl", NightService.canBacklight() ? Backlight.get() : 0).apply();
        }
        NightService.send(this, NightService.START, s);
        close();
        return true;
    }

    // Снимок на момент открытия. Ползунок встаёт на настоящую подсветку телевизора один к одному (0 значит 0, никакой памяти
    // о прошлых числах), а если идёт красная зона, то на неё: подсветка там 0, и настоящее число уровень не покажет
    void open() {
        boolean on = NightService.running;
        bl = on ? NightService.usesBl : NightService.canBacklight();
        if (!bl) {
            if (!on) prefs.edit().putInt("lvl", 0).apply();
            return;
        }
        origExact = Backlight.get();
        if (origExact >= 0 && (!on || (NightService.level(prefs, true) >= 0 && !fading()))) prefs.edit().putInt("lvl", origExact).apply();
    }

    // Последние полминуты таймера служба сама гасит подсветку: это не выбор, ползунок за ней не идёт
    boolean fading() {
        return NightService.running && NightService.endAt != 0 && NightService.endAt - SystemClock.elapsedRealtime() < NightService.FADE_MS;
    }

    // Яркость поменяли не отсюда (панель TCL, ход «Сторожа» в 22:00): ползунок встаёт на новое число
    void syncReal() {
        if (!bl || bar == null || SystemClock.elapsedRealtime() - movedAt < 1500 || fading()) return;
        int lvl = NightService.level(prefs, true), real = Backlight.get();
        if (lvl < 0 || real < 0 || real == lvl) return;
        prefs.edit().putInt("lvl", real).apply();
        bar.setPos(NightService.indexOf(real, true), true);
        showCaption(real);
        if (!touched) origExact = real;
    }

    @Override protected void onResume() {
        super.onResume();
        h.post(clock);
        publishHole();
    }

    @Override protected void onPause() {
        h.removeCallbacks(clock);
        h.removeCallbacks(autoClose);
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
        NightService.holeR = px(RADIUS) - in;
        NightService.hole = r;
        NightService.refreshHole();
    }

    // Меню закрывается или ушло в фон: вуаль снова накрывает весь экран
    void dropHole() {
        if (NightService.hole == null) return;
        NightService.hole = null;
        NightService.refreshHole();
    }

    // Ушли из меню «Домой» (или экран погас): закрываем его сами. Режим singleTask вернул бы то же меню со старым снимком
    // (подсветку за это время мог сменить «Сторож»), а так следующий запуск читает всё заново
    @Override protected void onStop() {
        if (!isFinishing()) { leave(); finish(); }
        super.onStop();
    }

    // Итог при закрытии меню. Правее нуля выбранное и есть обычная яркость: она сохраняется, служба без таймера больше не нужна.
    // Красная зона или таймер остаются, и на пять секунд виден значок (кроме закрытия кнопкой со своим действием)
    void leave() {
        if (left || bar == null) return;
        left = true;
        flushSave();
        int lvl = NightService.level(prefs, bl);
        if (NightService.running && lvl >= 0 && NightService.endAt == 0) NightService.send(this, NightService.RELEASE, 0);
        else if (!quiet && NightService.running) NightService.send(this, NightService.LEVEL, 5);
    }

    final Runnable clock = new Runnable() {
        @Override public void run() {
            updateStatus();
            syncReal();
            h.postDelayed(this, 1000);
        }
    };

    final Runnable autoClose = this::close;

    // Сохранять каждый шаг удержания незачем: на экран шаг попадает сразу, а в память TCL последний, когда пульт отпустили
    final Runnable saveBl = () -> {
        savePending = false;
        Backlight.set(NightService.level(prefs, true), true);
    };

    void flushSave() {
        h.removeCallbacks(saveBl);
        if (savePending) saveBl.run();
    }

    void armAutoClose() {
        h.removeCallbacks(autoClose);
        if (!full && panel != null) h.postDelayed(autoClose, AUTO_CLOSE_MS);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        armAutoClose();
        return super.dispatchKeyEvent(e);
    }

    void render() {
        panel.removeAllViews();
        warmChips.clear();
        status = null;
        focusTo = null;
        if (full) renderFull(); else renderSmall();
        showCaption(NightService.level(prefs, bl));
        updateStatus();
        // requestFocusFromTouch: курсор встаёт куда надо, даже если ТВ остался в режиме касаний
        final View f = toTimer && focusTo != null ? focusTo : bar;
        f.post(f::requestFocusFromTouch);
    }

    // Плашка: подпись, ползунок и кнопка «Таймер сна»
    void renderSmall() {
        panel.setPadding(px(26), px(16), px(26), px(16));
        panel.setMinimumWidth(0);
        addBrightness(px(0));
        TextView more = chip("Таймер сна", v -> expand(true));
        more.setId(View.generateViewId());
        more.setOnKeyListener((v, code, e) -> {
            if (code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) expand(true);
            return true;
        });
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(44));
        ml.gravity = Gravity.CENTER_HORIZONTAL;
        ml.topMargin = px(6);
        panel.setClipChildren(false);
        panel.addView(more, ml);
        bar.setNextFocusDownId(more.getId());
        more.setNextFocusUpId(bar.getId());
        more.setNextFocusLeftId(more.getId());
        more.setNextFocusRightId(more.getId());
    }

    // Полное меню: шапка со звуком, состояние, тот же ползунок, «Выключить», таймер, тёплый свет
    void renderFull() {
        panel.setPadding(px(40), px(24), px(40), px(26));
        // Ширина не прыгает между «таймера нет» и «идёт»
        panel.setMinimumWidth(px(772));
        boolean timer = NightService.running && NightService.endAt != 0;

        // Заголовок, справа быстрый переход к панели «Звук» (27.10): поверх того же фильма подкрутить речь и бас,
        // не выходя на главный экран. Шапка рамкой: ширину меню по-прежнему задаёт ряд таймера, а кнопка прижата вправо
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

        addBrightness(px(14));

        // Крупная кнопка сразу под ползунком: убирает затемнение, возвращает прежнюю подсветку и закрывает меню
        off = bigButton("Выключить ночной режим", v -> turnOff());
        off.setId(View.generateViewId());
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52));
        ol.topMargin = px(12);
        panel.addView(off, ol);

        // Таймер запускает усыпление с тем, что стоит на ползунке; когда таймер уже идёт, можно добавить время
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
        }
        // Вниз с кнопки «Выключить» ведёт на одну и ту же кнопку («20» или «+15 минут»), а вверх с любой кнопки таймера обратно на неё
        timerFirst.setId(View.generateViewId());
        for (TextView c : timerChips) c.setNextFocusUpId(off.getId());
        bar.setNextFocusDownId(off.getId());
        off.setNextFocusUpId(bar.getId());
        off.setNextFocusDownId(timerFirst.getId());
        focusTo = timerFirst;

        label("Тёплый свет (янтарный оттенок ниже нуля и при угасании)");
        LinearLayout warmRow = row();
        warmChips.add(add(warmRow, chip("Включён", v -> setWarm(true))));
        warmChips.add(add(warmRow, chip("Выключен", v -> setWarm(false))));
        restyle();
    }

    // Яркость: слева простыми словами, что сейчас на экране, справа подсказка, под ними ползунок
    void addBrightness(int top) {
        FrameLayout cap = new FrameLayout(this);
        cap.setPadding(full ? 0 : px(4), top, full ? 0 : px(4), px(2));
        caption = text("", 18, 0xFFFFFFFF);
        caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cap.addView(caption, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.BOTTOM));
        side = text("", 15, 0x80FFFFFF);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM);
        hl.leftMargin = px(24);
        cap.addView(side, hl);
        panel.addView(cap, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        int steps = NightService.steps(bl);
        bar = bl ? new Bar(this, steps, NightService.zero(true), new int[]{0, NightService.zero(true), steps},
                        new String[]{"−100 %", "0", "100 %"}, "красная зона")
                : new Bar(this, steps, -1, new int[]{0, steps}, new String[]{"100 %", "0 %"}, null);
        bar.setId(View.generateViewId());
        bar.setPos(NightService.indexOf(NightService.level(prefs, bl), bl), false);
        bar.setListener(this::moved);
        bar.setOnClickListener(v -> close());   // OK на ползунке: готово, меню закрывается, яркость остаётся (как «Назад»)
        panel.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    // Плашка вырастает в полное меню: панель едет от своих границ к новым, вырез в вуали едет вместе с ней (каждый кадр),
    // а содержимое проявляется уже на ходу. Ввод не блокируем: курсор на месте с первого кадра
    void expand(boolean timerFocus) {
        if (full || panel == null) return;
        full = true;
        toTimer = timerFocus;
        h.removeCallbacks(autoClose);
        final int l0 = panel.getLeft(), t0 = panel.getTop(), r0 = panel.getRight(), b0 = panel.getBottom();
        render();
        panel.setLayoutParams(panelParams());
        for (int i = 0; i < panel.getChildCount(); i++) panel.getChildAt(i).setAlpha(0f);
        panel.setClipChildren(true);
        panel.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                panel.getViewTreeObserver().removeOnPreDrawListener(this);
                final int l1 = panel.getLeft(), t1 = panel.getTop(), r1 = panel.getRight(), b1 = panel.getBottom();
                panel.setLeft(l0);
                panel.setTop(t0);
                panel.setRight(r0);
                panel.setBottom(b0);
                ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
                a.setDuration(280);
                a.setInterpolator(new DecelerateInterpolator(2f));
                a.addUpdateListener(v -> {
                    float f = (Float) v.getAnimatedValue();
                    panel.setLeft(Math.round(l0 + (l1 - l0) * f));
                    panel.setTop(Math.round(t0 + (t1 - t0) * f));
                    panel.setRight(Math.round(r0 + (r1 - r0) * f));
                    panel.setBottom(Math.round(b0 + (b1 - b0) * f));
                    publishHole();
                });
                a.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator x) {
                        panel.setClipChildren(false);   // кнопки в фокусе снова могут расти за край ряда
                        publishHole();
                    }
                });
                a.start();
                for (int i = 0; i < panel.getChildCount(); i++) {
                    panel.getChildAt(i).animate().alpha(1f).setStartDelay(90).setDuration(200).start();
                }
                return true;
            }
        });
    }

    // Ползунок сдвинули пультом. Правее нуля и без службы это просто яркость телевизора: сразу на экран, в память TCL когда
    // отпустили. Красная зона или таймер идут через службу: перед первым её шагом запоминаем настоящую подсветку, чтобы вернуть
    void moved(int idx) {
        int lvl = NightService.levelAt(idx, bl);
        prefs.edit().putInt("lvl", lvl).apply();
        touched = true;
        movedAt = SystemClock.elapsedRealtime();
        if (bl && !NightService.running && lvl >= 0) {
            Backlight.set(lvl, false);
            savePending = true;
            h.removeCallbacks(saveBl);
            h.postDelayed(saveBl, 400);
        } else {
            if (bl && origExact >= 0 && !prefs.contains("bl0")) prefs.edit().putInt("bl0", origExact).apply();
            savePending = false;
            h.removeCallbacks(saveBl);
            NightService.send(this, NightService.LEVEL, 0);
        }
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
    }

    void start(int seconds) {
        quiet = true;
        left = true;
        flushSave();
        // Правее нуля выбранное и есть обычная яркость: после таймера телевизор проснётся с ней
        int lvl = NightService.level(prefs, bl);
        if (bl && lvl >= 0) prefs.edit().putInt("bl0", lvl).apply();
        else if (bl && origExact >= 0 && !prefs.contains("bl0")) prefs.edit().putInt("bl0", origExact).apply();
        NightService.send(this, NightService.START, seconds);
        close();
    }

    // Кнопка «Выключить ночной режим»: убрать затемнение, вернуть обычную подсветку (служба берёт её из "bl0"), закрыть меню.
    // Службы нет: вернуть ту, что была при открытии меню
    void turnOff() {
        quiet = true;
        left = true;
        h.removeCallbacks(saveBl);
        savePending = false;
        if (NightService.running) NightService.send(this, NightService.STOP, 0);
        else if (bl && touched && origExact >= 0) Backlight.set(origExact, true);
        close();
    }

    // Подпись над шкалой простыми словами: правее нуля яркость, ниже нуля красная зона
    void showCaption(int lvl) {
        if (caption == null) return;
        String s;
        int color = 0xFFFFFFFF;
        if (!bl) {
            s = lvl < 0 ? "Затемнение " + (-lvl) + " %" : "Без затемнения";
        } else if (lvl >= 0) {
            s = "Яркость " + lvl + " %";
        } else {
            s = "Красная зона: −" + (-lvl) + " %";
            color = RED_TEXT;
        }
        caption.setText(s);
        caption.setTextColor(color);
    }

    void updateStatus() {
        if (side == null) return;
        boolean timer = NightService.running && NightService.endAt != 0;
        String left = timer ? "Сон через " + NightService.clock(NightService.endAt - SystemClock.elapsedRealtime()) : null;
        int lvl = NightService.level(prefs, bl);
        // В плашке нет строки состояния: идущий таймер виден справа от подписи
        side.setText(!full && timer ? left : bl ? "Влево темнее, вправо светлее" : "Подсветка панели недоступна, затемняем экран");
        side.setTextColor(!full && timer ? WARM : 0x80FFFFFF);
        if (status == null) return;
        status.setText(timer ? left : lvl < 0 ? "Красная зона держится, пока её не выключить" : "Ползунок меняет яркость телевизора, таймер не запущен");
        status.setTextColor(timer || lvl < 0 ? WARM : 0x99FFFFFF);
    }

    // Панель «Звук» (app.glasstv.sound) встаёт поверх фильма, а меню ночного режима уходит
    void openSound() {
        quiet = true;
        leave();
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
