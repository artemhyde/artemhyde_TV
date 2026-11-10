package app.glasstv.home;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Фон и текст главного экрана и заставки, по сетке 960x540 dp.
// Простой («блокировка»): резкое фото, по углам часы, погода, стих и подпись места.
// Активный: фото плавно размывается и темнеет, стих уходит вверх, справа добавляется курс.
// Заставка: другая композиция и другое фото, экран медленно гаснет, остаются часы и подпись
final class Ambient implements Data.Listener {
    static final PathInterpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    final Context c;
    final float dp;
    final FrameLayout root;
    private final FrameLayout[] frame = new FrameLayout[2];
    private final ImageView[] sharp = new ImageView[2], soft = new ImageView[2];
    private int front;
    // Наплыв фото (drift) шагами по таймеру, см. ниже
    private View driftView;
    private float driftA, driftB;
    private long driftMs, driftDone, driftMark;
    private final View dim, top, bottom;
    // Подложка пробуждения (09.11): тёмная поверх фото и под текстом. Пока ТВ спал, началось новое время суток: прежнее
    // фото не показываем вовсе, новое проявляется из темноты (раньше при включении мелькала старая картинка)
    private final View wake;
    final TextView clock, date, temp, place, rates, ref, caption;
    final GlassClock glass;   // часы «матовое стекло» в «Vision» (09.11); прежние тонкие часы остаются в «Графите»
    private final TextView[] verse = new TextView[2];
    private int vis;   // какой из двух слоёв стиха сейчас виден
    // Записка с телефона (10.11, Notes): стоит на месте цитаты. Текст идёт в те же строки, рисунок в свою картинку
    private Notes.Note note;
    private ImageView noteArt;
    // Записка «Сторожа» на время (10.11, договор с ним: приходит рассылкой, пока «Дом» на экране): на экране блокировки
    // встаёт на место цитаты и своей записки, потом всё возвращается. На главном экране её показывает плашка (HomeActivity)
    private String flashText, flashRef;
    private long flashUntil;
    private boolean quotesShown;   // какая подборка на экране: при смене цитат на Библию текст меняется сразу
    final WeatherIcon icon;
    final LinearLayout tempRow;
    final Particles particles;
    private final Data data;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Bitmap shown;
    private Data.Photo shownPhoto;   // какое фото на экране: чтобы не менять то же самое
    private float blurLevel;
    boolean active;
    boolean vision;           // стиль «Vision» (Style): текст по центру, фото чёткое, частиц нет
    boolean lite;             // облегчённый режим (Setup.lite, план Codex, блок 6): без частиц и наплыва, фото вдвое меньше по сторонам
    private boolean styled;

    Ambient(Context c) {
        this.c = c;
        lite = Setup.lite(c);
        dp = c.getResources().getDisplayMetrics().density;
        Fonts.init(c);
        data = Data.get(c);
        root = new FrameLayout(c);
        root.setBackgroundColor(0xFF07070A);
        // Свечение и тень плитки в фокусе выходят за рамку ряда: корень не должен их срезать (27.10 был ровный срез сверху)
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // Два слоя фото для смены через растворение; в каждом резкая картинка и её размытая копия
        for (int i = 0; i < 2; i++) {
            FrameLayout f = new Flat(c);
            ImageView s = new ImageView(c), b = new ImageView(c);
            s.setScaleType(ImageView.ScaleType.CENTER_CROP);
            b.setScaleType(ImageView.ScaleType.CENTER_CROP);
            b.setAlpha(0f);
            f.addView(s, new FrameLayout.LayoutParams(-1, -1));
            f.addView(b, new FrameLayout.LayoutParams(-1, -1));
            f.setAlpha(i == 0 ? 1f : 0f);
            root.addView(f, new FrameLayout.LayoutParams(-1, -1));
            frame[i] = f;
            sharp[i] = s;
            soft[i] = b;
        }
        // Подложки под текст: сверху до строки даты и погоды (на светлом небе без неё дата пропадала),
        // снизу до 55 % на нижних 240 dp. Тени у текста нет: она держала анимации на 30 кадрах
        top = new FlatView(c);
        GradientDrawable topShade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0x4D000000, 0x00000000});
        if (android.os.Build.VERSION.SDK_INT >= 29) topShade.setColors(new int[]{0x66000000, 0x4D000000, 0x00000000}, new float[]{0f, 0.62f, 1f});
        top.setBackground(topShade);
        // 10.11: подложки запечены в само фото экрана блокировки (lockOf), отдельными видами их нет. Градиент во весь экран,
        // смешиваемый с фото, стоил этому ТВ 12–16 мс на каждом кадре перехода (замер на TCL); в картинке он бесплатен.
        // Виды остаются без места на экране: старые вызовы видимости и прозрачности ничего не рисуют
        top.setLayoutParams(new FrameLayout.LayoutParams(-1, px(190)));
        bottom = new FlatView(c);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x33000000, 0x8C000000}));
        bottom.setLayoutParams(new FrameLayout.LayoutParams(-1, px(240), Gravity.BOTTOM));
        dim = new FlatView(c);
        dim.setBackgroundColor(0xFF000000);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        wake = new FlatView(c);
        wake.setBackgroundColor(0xFF07070A);
        wake.setVisibility(View.GONE);
        root.addView(wake, new FrameLayout.LayoutParams(-1, -1));
        // Частицы как на PS5: над фоном, под текстом и плитками
        particles = new Particles(c);
        root.addView(particles, new FrameLayout.LayoutParams(-1, -1));

        int clockBase = 92, dateBase = 124;
        clock = Fonts.text(c, 64, Fonts.light28, Fonts.WHITE);
        clock.setLetterSpacing(-0.02f);
        clock.setTranslationX(-px(4));   // у цифр свой боковой зазор: видимый край на линии 56 dp
        root.addView(clock, Grid.at(clock, dp, Grid.MARGIN, clockBase, false, 0));
        glass = new GlassClock(c, GlassClock.SIZE_SP * LOCK * dp);   // крупным, на плитках уменьшается: цифры чёткие
        glass.setVisibility(View.GONE);
        root.addView(glass, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        glass.addOnLayoutChangeListener(regroup);
        clock.addOnLayoutChangeListener(regroup);
        date = Fonts.text(c, Fonts.BODY, Fonts.reg18, Fonts.WHITE_72);
        root.addView(date, Grid.at(date, dp, Grid.MARGIN, dateBase, false, 0));

        // Погода справа: значок и температура на линии часов, место на линии даты, курс ниже
        tempRow = new LinearLayout(c);
        tempRow.setOrientation(LinearLayout.HORIZONTAL);
        tempRow.setGravity(Gravity.CENTER_VERTICAL);
        icon = new WeatherIcon(c);
        temp = Fonts.text(c, Fonts.TITLE, Fonts.light24, Fonts.WHITE);
        tempRow.addView(icon, new LinearLayout.LayoutParams(px(30), px(30)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = px(10);
        tempRow.addView(temp, tl);
        FrameLayout.LayoutParams rl = Grid.at(temp, dp, Grid.RIGHT, 92, true, 0);
        root.addView(tempRow, rl);
        place = Fonts.text(c, Fonts.BODY, Fonts.reg18, Fonts.WHITE_72);
        place.setGravity(Gravity.END);
        root.addView(place, Grid.at(place, dp, Grid.RIGHT, 124, true, Grid.span(5)));
        rates = Fonts.text(c, Fonts.CAPTION, Fonts.reg18, Fonts.WHITE_60);
        rates.setGravity(Gravity.END);
        rates.setAlpha(0f);
        // Без погоды курс встаёт на строку названия места, а не висит ниже пустого места
        root.addView(rates, Grid.at(rates, dp, Grid.RIGHT, Setup.weatherOn(c) ? 148 : 124, true, Grid.span(7)));
        for (View v : new View[]{date, tempRow, place, rates}) v.addOnLayoutChangeListener(regroup);

        // Стих растёт вверх от общей нижней линии, ссылка на книгу на самой нижней линии экрана
        for (int i = 0; i < 2; i++) {
            TextView v = Fonts.text(c, Fonts.LEAD, Fonts.reg24, Fonts.WHITE_92);
            v.setLineHeight(px(28));
            v.setFontFeatureSettings("");
            // Без тени: размытая тень под текстом держала все анимации на 30 кадрах в секунду
            Typo.balanced(v);
            v.setAlpha(i == 0 ? 1f : 0f);
            root.addView(v, Grid.above(v, dp, Grid.MARGIN, 472, Grid.span(6)));
            verse[i] = v;
        }
        ref = Fonts.text(c, Fonts.CAPTION, Fonts.med18, Fonts.WHITE_60);
        ref.setLetterSpacing(0.02f);
        root.addView(ref, Grid.at(ref, dp, Grid.MARGIN, Grid.BOTTOM, false, 0));

        // Рисунок записки: 16:9 со скруглёнными углами, на месте цитаты (непрозрачный, 16 бит: смешивать нечего)
        noteArt = new ImageView(c);
        noteArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        noteArt.setVisibility(View.GONE);
        final float nr = 14 * dp;
        noteArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), nr); }
        });
        noteArt.setClipToOutline(true);
        root.addView(noteArt, noteParams());
        note = Notes.current(c);
        quotesShown = Setup.quotes(c);
        if (note != null && note.draw()) loadNoteArt(note.at);

        caption = Fonts.text(c, Fonts.CAPTION, Fonts.reg18, Fonts.WHITE_60);
        caption.setGravity(Gravity.END);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(caption, Grid.at(caption, dp, Grid.RIGHT, Grid.BOTTOM, true, Grid.span(7)));

        // Тени у текста нет: читаемость дают подложки сверху и снизу
        // Настройки на ТВ (Setup): чего нет, того на экране нет совсем, остальное стоит на своих местах
        applySetup();
    }

    // Человек поменял настройки «Дома» (город, курсы, стих): показать и спрятать строки, переставить курсы, обновить текст
    void applySetup() {
        boolean w = Setup.weatherOn(c), r = Setup.ratesOn(c), v = Setup.versesOn(c) || note != null || flashText != null;
        for (View x : new View[]{tempRow, place}) x.setVisibility(w ? View.VISIBLE : View.GONE);
        rates.setVisibility(r ? View.VISIBLE : View.GONE);
        for (View x : new View[]{verse[0], verse[1], ref}) x.setVisibility(v ? View.VISIBLE : View.GONE);
        // Цитаты сменили на Библию или обратно: новый текст сразу, а не в следующее время суток
        boolean q = Setup.quotes(c);
        if (q != quotesShown) {
            quotesShown = q;
            if (Setup.versesOn(c)) newVerseNow();
        }
        if (styled) layoutText();
        boolean l = Setup.lite(c);
        if (l != lite) {
            lite = l;
            showParticles();
            if (lite) stopDrift();
            if (shownPhoto != null) showPhoto(shownPhoto, false);   // фото в нужном размере сразу
        }
    }

    // «Оформление» (10.11): пока открыто, частицы стоят. В уменьшенной карточке их почти не видно, а каждый их кадр
    // перерисовывает весь экран вместе с выбором и фоном
    boolean calm;

    void setCalm(boolean on) {
        calm = on;
        showParticles();
    }

    private int sample() { return lite ? 2 : 1; }

    // Частицы только в «Графите» и не в облегчённом режиме
    private void showParticles() {
        boolean off = vision || lite || calm;
        particles.setVisibility(off ? View.GONE : View.VISIBLE);
        if (off) particles.stop();
        else particles.level(active ? 1f : Particles.LOCK, 0);
    }

    int px(float v) { return Math.round(v * dp); }

    // Рамка фото и подложки без промежуточного буфера при прозрачности. В рамке на экране видна одна картинка: размытая
    // копия прозрачна на экране блокировки, а в плитках резкое фото спрятано под непрозрачной копией; подложка рисуется
    // одним градиентом. Иначе Android на каждом кадре растворения рисовал весь экран дважды, в буфер и из буфера:
    // на ТВ смена фото шла по 44 мс на кадр вместо 16 (07.11, замер gfxinfo). Вид от этого не меняется
    static final class Flat extends FrameLayout {
        Flat(Context c) { super(c); }
        @Override public boolean hasOverlappingRendering() { return false; }
    }

    static final class FlatView extends View {
        FlatView(Context c) { super(c); }
        @Override public boolean hasOverlappingRendering() { return false; }
    }

    // ===== Стиль (Style) =====

    void setStyle(boolean v) {
        if (styled && vision == v) return;
        styled = true;
        vision = v;
        layoutText();
        showParticles();
        // Копия фото под ряд у каждого стиля своя: размытая тёмная у «Графита», чёткая с затемнением у «Vision»
        if (shownPhoto != null) showPhoto(shownPhoto, false);
    }

    // Места текста. «Графит»: часы и дата слева сверху, погода справа, стих слева внизу.
    // «Vision»: дата над крупными часами по центру, погода и курс под ними, стих по центру внизу, подпись фото сверху справа
    // 05.11: в «Vision» всё на 16..28 dp выше (место под крупные сферы), подписи места съёмки нет: экран чище
    static final int V_DATE = 58, V_CLOCK = 158, V_TEMP = 202, V_PLACE = 228, V_RATES = 250;

    // Экран блокировки в «Vision» (09.11, как экран блокировки айфона: часы и всё рядом крупнее, по нажатию плавно
    // уменьшаются вверх на своё место): часы, дата и погода на экране
    // блокировки крупнее и растут вниз от верха строки даты; по нажатию плавно сжимаются вверх на место,
    // в простой так же плавно вырастают. Двигаются только масштаб и опора вида, без перекладки: видеоядру это дёшево.
    // Вечером 09.11: в 1,5 раза выходило слишком крупно, как заставка планшета, и погода тоже; стало 1,25
    static final float LOCK = 1.25f;
    static final int V_TOP = 44;   // верх строки даты: от этой линии группа растёт вниз
    private float groupScale = 1f;
    private android.animation.ValueAnimator groupAnim;
    // Сжатие: отклик сразу, но без рывка в начале, и долгий мягкий хвост (09.11 вечером: плавнее;
    // прежняя кривая шторок iOS 0.32/0.72 за 0,9 с стартовала резче); рост в простой: плавный разгон и торможение
    static final PathInterpolator SHRINK = new PathInterpolator(0.3f, 0.5f, 0.1f, 1f), GROW = new PathInterpolator(0.45f, 0f, 0.25f, 1f);
    private final View.OnLayoutChangeListener regroup = (v, l, t, r, b, ol, ot, or, ob) -> applyGroup(groupScale);

    // Масштаб группы g (1 на плитках, LOCK на экране блокировки) вокруг общей точки: середина экрана, верх строки даты.
    // Стекло часов нарисовано в масштабе LOCK, поэтому его собственный масштаб g / LOCK
    private void applyGroup(float g) {
        groupScale = g;
        float px = root.getWidth() / 2f, py = V_TOP * dp;
        for (View v : new View[]{date, clock, tempRow, place, rates}) {
            v.setPivotX(px - v.getLeft());
            v.setPivotY(py - v.getTop());
            v.setScaleX(g);
            v.setScaleY(g);
        }
        glass.setPivotX(px - glass.getLeft());
        glass.setPivotY(py - glass.getTop());
        glass.setScaleX(g / glassBuilt);
        glass.setScaleY(g / glassBuilt);
    }

    private void animateGroup(float to, long ms) {
        if (groupAnim != null) groupAnim.cancel();
        if (!vision) { applyGroup(1f); return; }
        groupAnim = android.animation.ValueAnimator.ofFloat(groupScale, to);
        groupAnim.setDuration(M.t(ms));
        groupAnim.setInterpolator(to < groupScale ? SHRINK : GROW);
        groupAnim.addUpdateListener(an -> applyGroup((float) an.getAnimatedValue()));
        groupAnim.start();
    }

    // Стекло часов собрано в масштабе крупных часов экрана блокировки «Vision» (LOCK) или в своём (1 в «Графите»)
    private float glassBuilt = LOCK;

    private void layoutText() {
        boolean v = vision;
        // Часы на выбор (10.11, «Оформление»): тонкие Inter Light, плотные Inter SemiBold или стекло. Начертание ставится
        // до раскладки: по метрикам шрифта считается базовая линия
        String ck = Look.clock(c);
        boolean bold = Look.BOLD.equals(ck), g = Look.GLASS.equals(ck);
        clock.setTypeface(bold && Fonts.semi18 != null ? Fonts.semi18 : Fonts.light28);
        clock.setLetterSpacing(bold ? -0.035f : -0.02f);
        clock.setTextSize(v ? 96 : 64);
        clock.setTranslationX(v ? 0 : -px(4));
        clock.setLayoutParams(v ? Grid.center(clock, dp, V_CLOCK) : Grid.at(clock, dp, Grid.MARGIN, 92, false, 0));
        // Стекло рисует время на той же базовой линии, текст часов остаётся невидимым (по нему строится раскладка).
        // «Vision»: по центру, крупным (масштаб LOCK) и на плитках уменьшается. «Графит»: слева, в своём размере
        glassBuilt = v ? LOCK : 1f;
        glass.setSizePx((v ? GlassClock.SIZE_SP * LOCK : GlassClock.SIZE_SP * 64f / 96f) * dp);
        FrameLayout.LayoutParams glp;
        if (v) {
            glp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            float lockBase = V_TOP * dp + LOCK * (V_CLOCK - V_TOP) * dp;   // базовая линия крупных часов на экране блокировки
            glp.topMargin = Math.round(lockBase - glass.baseFromTop());
        } else {
            glp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            glp.leftMargin = Math.round(Grid.MARGIN * dp - glass.padLeft() - px(4));
            glp.topMargin = Math.round(92 * dp - glass.baseFromTop());
        }
        glass.setLayoutParams(glp);
        glass.setVisibility(g ? View.VISIBLE : View.GONE);
        clock.setVisibility(g ? View.INVISIBLE : View.VISIBLE);
        if (g) glass.setTime(clock.getText().toString());
        // Верхняя подложка экрана блокировки: в «Vision» часы и погода там в полтора раза крупнее и опускаются до трети
        // экрана, подложка 190 dp кончалась выше строки места, и на светлом фото она пропадала (09.11, живой ТВ).
        // Тянем её до 420 dp; на плитках подложка и так гаснет
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0x4D000000, 0x00000000});
        if (android.os.Build.VERSION.SDK_INT >= 29)
            shade.setColors(v ? new int[]{0x73000000, 0x5C000000, 0x00000000} : new int[]{0x66000000, 0x4D000000, 0x00000000},
                    new float[]{0f, v ? 0.74f : 0.62f, 1f});
        top.setBackground(shade);
        top.getLayoutParams().height = px(v ? 420 : 190);
        top.requestLayout();
        if (groupAnim != null) groupAnim.cancel();
        applyGroup(v && !active ? LOCK : 1f);
        date.setLayoutParams(v ? Grid.center(date, dp, V_DATE) : Grid.at(date, dp, Grid.MARGIN, 124, false, 0));
        tempRow.setLayoutParams(v ? Grid.center(temp, dp, V_TEMP) : Grid.at(temp, dp, Grid.RIGHT, 92, true, 0));
        place.setGravity(v ? Gravity.CENTER_HORIZONTAL : Gravity.END);
        place.setLayoutParams(v ? Grid.center(place, dp, V_PLACE) : Grid.at(place, dp, Grid.RIGHT, 124, true, Grid.span(5)));
        rates.setGravity(v ? Gravity.CENTER_HORIZONTAL : Gravity.END);
        boolean w = Setup.weatherOn(c);
        rates.setLayoutParams(v ? Grid.center(rates, dp, w ? V_RATES : V_PLACE)
                : Grid.at(rates, dp, Grid.RIGHT, w ? 148 : 124, true, Grid.span(7)));
        for (TextView t : verse) {
            t.setGravity(v ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            t.setLayoutParams(v ? Grid.aboveCenter(t, dp, 466, 680) : Grid.above(t, dp, Grid.MARGIN, 472, Grid.span(6)));
        }
        ref.setLayoutParams(v ? Grid.center(ref, dp, Grid.BOTTOM) : Grid.at(ref, dp, Grid.MARGIN, Grid.BOTTOM, false, 0));
        noteArt.setLayoutParams(noteParams());
        caption.setLayoutParams(Grid.at(caption, dp, Grid.RIGHT, Grid.BOTTOM, true, Grid.span(7)));
        caption.setVisibility(v ? View.GONE : View.VISIBLE);
    }

    // «Vision»: фото остаётся чётким. Затемнение сверху под часы и снизу под сферы запекается в саму картинку один раз,
    // в кадре это тот же один слой фона, что и размытая копия у «Графита»
    static Bitmap bake(Bitmap bm) {
        int w = bm.getWidth(), hh = bm.getHeight();
        Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setScale(0.84f, 0.84f, 0.84f, 1f);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        c.drawBitmap(bm, 0, 0, p);
        android.graphics.Paint g = new android.graphics.Paint();
        g.setShader(new android.graphics.LinearGradient(0, 0, 0, hh,
                // под капсулами (около 0,58 высоты) тоже темнее: на ярком ночном городе стеклянная капсула иначе теряется
                new int[]{0x99000000, 0x4D000000, 0x40000000, 0x66000000, 0xC7000000}, new float[]{0f, 0.28f, 0.46f, 0.6f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, hh, g);
        // 05.11: в 16-битном цвете с подмешанным шумом (без полос на переходах). Фон самый крупный слой кадра, а этот ТВ
        // делит память с обработкой картинки и упирается в неё: вдвое меньше чтения на каждом кадре
        Bitmap lean = Bitmap.createBitmap(w, hh, Bitmap.Config.RGB_565);
        android.graphics.Paint d = new android.graphics.Paint(android.graphics.Paint.DITHER_FLAG);
        new android.graphics.Canvas(lean).drawBitmap(out, 0, 0, d);
        return Tile.fixed(lean);
    }

    private Bitmap softOf(Bitmap bm) { return softOf(bm, false); }

    // Градиент (still) в «Графите» под рядом почти не темнеет, как на макете: он и так спокойный, а фото затемняется сильно
    private Bitmap softOf(Bitmap bm, boolean still) {
        if (vision) return bake(bm);
        // 16 бит: 32-битную картинку во весь экран этот ТВ смешивает с тем, что под ней (замер 10.11: +12 мс на кадр)
        return HomeActivity.opaque(still ? Blur.of(bm, 1, 0.82f) : Blur.of(bm, 4, SOFT_BRIGHT));
    }

    // Фото экрана блокировки с подложками под текст: сверху до строки даты и погоды (в «Vision» до 420 dp, там часы
    // крупнее и ниже), снизу до 55 % на нижних 240 dp. Запекается один раз при смене фото, в 16 битах с подмешанным шумом
    private Bitmap lockOf(Bitmap bm) {
        if (bm == null) return null;
        int w = bm.getWidth(), hh = bm.getHeight();
        Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.RGB_565);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint d = new android.graphics.Paint(android.graphics.Paint.DITHER_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(bm, 0, 0, d);
        float topH = hh * (vision ? 420f : 190f) / Grid.H, botH = hh * 240f / Grid.H;
        android.graphics.Paint g = new android.graphics.Paint(android.graphics.Paint.DITHER_FLAG);
        g.setShader(new android.graphics.LinearGradient(0, 0, 0, topH,
                vision ? new int[]{0x73000000, 0x5C000000, 0x00000000} : new int[]{0x66000000, 0x4D000000, 0x00000000},
                new float[]{0f, vision ? 0.74f : 0.62f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, topH, g);
        g.setShader(new android.graphics.LinearGradient(0, hh - botH, 0, hh, new int[]{0x00000000, 0x33000000, 0x8C000000}, null,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, hh - botH, w, hh, g);
        return Tile.fixed(out);
    }

    // ===== Жизненный цикл =====

    private boolean running;

    void start() {
        running = true;
        Now.attach(c, nowChanged);
        data.addListener(this);
        data.refresh();
        Data.Photo first = Walls.current(c, data);
        if (shown == null) showPhoto(first, false);
        else {
            if (shownPhoto == null || !shownPhoto.still) drift(frame[front]);   // вернулись на экран: наплыв продолжается с текущего кадра
            // Пока нас не было, началось новое время суток. Раньше прежнее фото 2,2 с растворялось в новом, и при включении ТВ
            // было видно «старую картинку»: теперь сразу тёмная подложка, новое фото читается в фоне и проявляется из неё
            if (first != null && shownPhoto != null && !first.id.equals(shownPhoto.id)) wakeTo(first);
        }
        onData();
        // Время в первом же кадре, а не следующим сообщением: иначе при включении на кадр мелькало прежнее
        h.removeCallbacks(tick);
        tick.run();
        h.postDelayed(rotate, CHECK_MS);
        if (!vision && !lite && !calm) particles.level(active ? 1f : Particles.LOCK, 0);
    }

    void stop() {
        running = false;
        Now.detach(c, nowChanged);
        data.removeListener(this);
        h.removeCallbacksAndMessages(null);
        stopDrift();
        particles.stop();
    }

    Runnable onNow;   // главный экран обновляет карточку «Играет», когда «Волна» сообщает о треке
    final Runnable nowChanged = () -> { if (onNow != null) onNow.run(); };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            String hm = new SimpleDateFormat("H:mm", Locale.forLanguageTag("ru")).format(now);
            clock.setText(hm);
            if (glass.getVisibility() == View.VISIBLE) glass.setTime(hm);
            String d = new SimpleDateFormat("EEEE, d MMMM", Locale.forLanguageTag("ru")).format(now);
            date.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
            h.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50);
        }
    };

    // Фото и стих меняются три раза в день (утро, день, вечер), а не по таймеру: раз в минуту проверяем, не началось ли
    // новое время суток, и заодно докачиваем фото на следующее. В Data они сменятся ровно в 06:00, 12:00 и 18:00
    static final long CHECK_MS = 60_000;
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            // Погода и курсы раз в минуту спрашиваются заново (качаются раз в 30 минут и 3 часа): раньше только при открытии экрана,
            // и экран блокировки, висевший часами, показывал старую погоду
            data.refresh();
            if (!active) {
                data.prefetch();
                Data.Photo next = Walls.current(c, data);
                if (next != null && (shownPhoto == null || !next.id.equals(shownPhoto.id))) showPhoto(next, true);
                if (data.nextVerse() && note == null && flashText == null) swapVerse(true);
            }
            h.postDelayed(this, CHECK_MS);
        }
    };

    void newVerseNow() {
        data.nextVerse();
        swapVerse(false);
    }

    // Что стоит на месте цитаты: записка с телефона, если она есть, иначе цитата или стих
    private String leadText() { return flashText != null ? flashText : note != null ? (note.draw() ? "" : note.text) : data.verse; }

    private String leadRef() { return flashText != null ? flashRef : note != null ? note.caption() : Typo.ref(data.verseRef); }

    // Записка «Сторожа» на месте цитаты на ms миллисекунд
    void showGuardNote(String text, String ref, long ms) {
        flashText = text;
        flashRef = ref;
        flashUntil = android.os.SystemClock.uptimeMillis() + ms;
        h.removeCallbacks(endFlash);
        h.postDelayed(endFlash, ms);
        applySetup();
        noteArtSync(false);
        swapVerse(true);
    }

    // Сколько ещё держать записку «Сторожа» (0: её нет): экран проснулся, остаток покажет плашка главного экрана
    long guardNoteLeft() { return flashText == null ? 0 : Math.max(0, flashUntil - android.os.SystemClock.uptimeMillis()); }

    String guardNoteText() { return flashText; }

    void clearGuardNote(boolean animated) {
        if (flashText == null) return;
        h.removeCallbacks(endFlash);
        flashText = null;
        flashRef = null;
        applySetup();
        swapVerse(animated);
        noteArtSync(animated);
    }

    private final Runnable endFlash = () -> clearGuardNote(true);

    // Новый стих поверх старого: старый тает, новый проявляется с лёгким подъёмом
    private void swapVerse(boolean animated) {
        TextView old = verse[vis], next = verse[1 - vis];
        next.setText(Typo.fix(leadText()));
        String r = leadRef();
        if (active) {
            // Главный экран: цитата спрятана, просто подменить текст; выедет при возврате в простой
            next.setAlpha(0f);
            next.setTranslationY(old.getTranslationY());
            old.setAlpha(0f);
            ref.setText(r);
        } else if (!animated) {
            next.setAlpha(1f);
            old.setAlpha(0f);
            ref.setText(r);
        } else {
            old.animate().alpha(0f).setDuration(M.t(450)).setInterpolator(EASE).start();
            next.setAlpha(0f);
            next.setTranslationY(px(8));
            next.animate().alpha(1f).translationY(0).setStartDelay(M.t(250)).setDuration(M.t(700)).setInterpolator(EASE).start();
            ref.animate().alpha(0f).setDuration(M.t(300)).withEndAction(() -> {
                ref.setText(r);
                ref.animate().alpha(1f).setDuration(M.t(600)).start();
            }).start();
        }
        vis = 1 - vis;
    }

    // ===== Записка с телефона (10.11) =====

    // Место рисунка: там же, где цитата, низ на её последней строке. «Vision» по центру, «Графит» у левого поля
    private FrameLayout.LayoutParams noteParams() {
        int w = Math.round(300 * dp), hh = Math.round(169 * dp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hh,
                Gravity.BOTTOM | (vision ? Gravity.CENTER_HORIZONTAL : Gravity.START));
        if (!vision) lp.leftMargin = Math.round(Grid.MARGIN * dp);
        lp.bottomMargin = Math.round((Grid.H - (vision ? 470 : 476)) * dp);
        return lp;
    }

    // Рисунок читается в фоне, в размере места на экране и в 16 битах
    private void loadNoteArt(final long at) {
        final java.io.File f = Notes.drawFile(c);
        final int want = Math.round(300 * dp);
        DECODE.execute(() -> {
            Bitmap b = null;
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options();
                d.inPreferredConfig = Bitmap.Config.RGB_565;
                d.inSampleSize = 1;
                while (o.outWidth / (d.inSampleSize * 2) >= want) d.inSampleSize *= 2;
                b = android.graphics.BitmapFactory.decodeFile(f.getPath(), d);
            } catch (Throwable ignored) { }
            final Bitmap fb = b;
            h.post(() -> {
                if (note == null || !note.draw() || note.at != at) return;
                noteArt.setImageBitmap(fb);
                noteArtSync(true);
            });
        });
    }

    // Рисунок виден только на экране блокировки и только у записки-рисунка
    private void noteArtSync(boolean animated) {
        boolean show = note != null && note.draw() && noteArt.getDrawable() != null && flashText == null;
        if (!show) {
            noteArt.animate().cancel();
            noteArt.setVisibility(View.GONE);
            return;
        }
        noteArt.setVisibility(View.VISIBLE);
        noteArt.animate().cancel();
        if (active) { noteArt.setAlpha(0f); return; }
        if (!animated) { noteArt.setAlpha(1f); noteArt.setTranslationY(0); return; }
        noteArt.setAlpha(0f);
        noteArt.setTranslationY(px(8));
        noteArt.animate().alpha(1f).translationY(0).setStartDelay(M.t(250)).setDuration(M.t(700)).setInterpolator(EASE).start();
    }

    // Записка пришла, ушла или сменилась: на месте цитаты сразу она (или снова цитата)
    void noteChanged() {
        Notes.Note n = Notes.current(c);
        boolean same = n == null ? note == null : note != null && note.at == n.at && note.kind.equals(n.kind);
        if (same) return;
        note = n;
        applySetup();
        if (n == null) data.nextVerse();
        if (n != null && n.draw()) {
            noteArt.setImageDrawable(null);
            noteArtSync(false);
            loadNoteArt(n.at);
        } else {
            noteArt.setImageDrawable(null);
            noteArtSync(false);
        }
        swapVerse(true);
    }

    // ===== Состояния главного экрана =====

    // Разблокировка: фон размывается и темнеет, стих уходит вверх, справа проявляется курс
    // Размытая копия уже затемнена на треть: в активном режиме на экране один слой фона, а не пять
    static final float SOFT_BRIGHT = 0.37f;   // 27.10: темнее (было 0,64), вечерний контраст для плиток; края ещё темнее (виньетка в Blur)

    void setActive(boolean on) {
        if (active == on) return;
        active = on;
        if (!on) {
            // Назад к резкому фото: сначала вернуть его под размытую копию, потом копию растворить
            sharp[front].setVisibility(View.VISIBLE);
            top.setVisibility(View.VISIBLE);
            bottom.setVisibility(View.VISIBLE);
        }
        animateBlur(on ? 1f : 0f, on ? 700 : 900);
        if (!vision) particles.show(on);
        for (View g : new View[]{top, bottom})
            g.animate().alpha(on ? 0f : 1f).setStartDelay(0).setDuration(M.t(on ? 500 : 900)).setInterpolator(EASE).start();
        TextView shownVerse = verse[vis];
        if (on) h.postDelayed(() -> {
            // Пока стих не виден, готовим следующий: при возвращении в простой выедет уже новый
            if (!active) return;
            data.nextVerse();
            shownVerse.setText(Typo.fix(leadText()));
            ref.setText(leadRef());
        }, 700);
        for (View t : new View[]{shownVerse, ref, noteArt}) {
            t.animate().cancel();
            if (on) {
                t.animate().alpha(0f).translationY(-px(24)).setStartDelay(M.t(t == ref ? 80 : 0)).setDuration(M.t(500)).setInterpolator(EASE).start();
            } else {
                t.setTranslationY(px(24));
                t.animate().alpha(1f).translationY(0).setStartDelay(M.t(t == ref ? 330 : 250)).setDuration(M.t(700)).setInterpolator(EASE).start();
            }
        }
        caption.animate().alpha(on ? 0f : 1f).setStartDelay(M.t(on ? 0 : 300)).setDuration(M.t(on ? 300 : 600)).start();
        animateGroup(on ? 1f : LOCK, on ? 1000 : 1100);
        rates.animate().alpha(on ? 1f : 0f).setStartDelay(M.t(on ? 200 : 0)).setDuration(M.t(400)).setInterpolator(EASE).start();
        if (on) pauseDrift(); else resumeDrift();
    }

    // Сразу на экран блокировки, без переходов: ТВ выключили или он выключен. При включении первый же кадр чистый,
    // без вспышки размытого фона и выезда стиха
    void setIdleNow() {
        if (!active) return;
        active = false;
        if (blurAnim != null) blurAnim.cancel();
        blurLevel = 0f;
        soft[front].setAlpha(0f);
        sharp[front].setVisibility(View.VISIBLE);
        for (View g : new View[]{top, bottom}) {
            g.animate().cancel();
            g.setVisibility(View.VISIBLE);
            g.setAlpha(1f);
        }
        for (View t : new View[]{verse[vis], ref, noteArt}) {
            t.animate().cancel();
            t.setTranslationY(0);
            t.setAlpha(1f);
        }
        caption.animate().cancel();
        caption.setAlpha(1f);
        rates.animate().cancel();
        rates.setAlpha(0f);
        if (groupAnim != null) groupAnim.cancel();
        applyGroup(vision ? LOCK : 1f);
        if (!vision && !lite) particles.level(Particles.LOCK, 0);
        resumeDrift();
    }

    // Сразу активный вид, без переходов (10.11, вход в «Оформление»): пока главный экран уменьшается в карточку, внутри
    // него ничего не должно двигаться, тогда он сжимается одной готовой картинкой видеокарты
    void setActiveNow() {
        if (active) return;
        active = true;
        if (blurAnim != null) blurAnim.cancel();
        blurLevel = 1f;
        soft[front].setAlpha(1f);
        sharp[front].setVisibility(View.INVISIBLE);
        for (View g : new View[]{top, bottom}) {
            g.animate().cancel();
            g.setAlpha(0f);
            g.setVisibility(View.INVISIBLE);
        }
        for (View t : new View[]{verse[vis], ref, noteArt}) {
            t.animate().cancel();
            t.setAlpha(0f);
            t.setTranslationY(-px(24));
        }
        caption.animate().cancel();
        caption.setAlpha(0f);
        rates.animate().cancel();
        rates.setAlpha(1f);
        if (groupAnim != null) groupAnim.cancel();
        applyGroup(1f);
        if (!vision && !lite) particles.level(1f, 0);
        pauseDrift();
    }

    // Другие часы (10.11, «Оформление»): прежние тают, новые проявляются на том же месте
    void applyClock(boolean animated) {
        if (!styled) return;
        final View out = glass.getVisibility() == View.VISIBLE ? glass : clock;
        if (!animated) { layoutText(); return; }
        out.animate().cancel();
        out.animate().alpha(0f).setStartDelay(0).setDuration(M.t(140)).setInterpolator(EASE).withEndAction(() -> {
            layoutText();
            View in = glass.getVisibility() == View.VISIBLE ? glass : clock;
            if (out != in) out.setAlpha(1f);
            in.setAlpha(0f);
            in.animate().alpha(1f).setStartDelay(0).setDuration(M.t(280)).setInterpolator(EASE).start();
        }).start();
    }

    // Сетка «Все приложения»: фон ещё темнее, часы и погода гаснут.
    // Темнее делается сама размытая картинка (фильтр цвета при рисовании), а не чёрный слой поверх:
    // так на экране остаётся один проход фона вместо двух
    private float gridDim;
    private android.animation.ValueAnimator gridAnim;

    void setGrid(boolean on) {
        if (gridAnim != null) gridAnim.cancel();
        gridAnim = android.animation.ValueAnimator.ofFloat(gridDim, on ? 0.25f : 0f);
        gridAnim.setDuration(M.t(320));
        gridAnim.setInterpolator(EASE);
        gridAnim.addUpdateListener(an -> {
            gridDim = (float) an.getAnimatedValue();
            tintSoft();
        });
        gridAnim.start();
        for (View t : new View[]{clock, glass, date, tempRow, place, rates})
            t.animate().alpha(on ? 0f : (t == rates && !active ? 0f : 1f)).setStartDelay(M.t(0)).setDuration(M.t(260)).start();
        // Под сеткой частицы тише, чтобы не спорить со значками
        if (active) {
            particles.animate().alpha(on ? 0.55f : 1f).setStartDelay(0).setDuration(M.t(320)).start();
        }
    }

    private void tintSoft() {
        int a = Math.round(255 * gridDim);
        for (ImageView s : soft) {
            if (a == 0) s.clearColorFilter();
            else s.setColorFilter(android.graphics.Color.argb(a, 0, 0, 0), android.graphics.PorterDuff.Mode.SRC_ATOP);
        }
    }

    private android.animation.ValueAnimator blurAnim;

    private void animateBlur(float to, long ms) {
        if (blurAnim != null) blurAnim.cancel();
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(blurLevel, to);
        blurAnim = a;
        a.setDuration(M.t(ms));
        a.setInterpolator(EASE);
        a.addUpdateListener(an -> {
            blurLevel = (float) an.getAnimatedValue();
            soft[front].setAlpha(blurLevel);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                // Размытая копия закрыла экран целиком: резкое фото и подложки под ней не рисуем
                if (active && blurLevel >= 0.999f) {
                    sharp[front].setVisibility(View.INVISIBLE);
                    top.setVisibility(View.INVISIBLE);
                    bottom.setVisibility(View.INVISIBLE);
                }
            }
        });
        a.start();
    }

    // ===== Данные =====

    @Override public void onData() {
        long now = System.currentTimeMillis();
        if (Setup.weatherOn(c)) {
            String w = data.weatherText(), city = Setup.city(c);
            int st = data.weatherState(now);
            if (st == Fresh.NO_LINK) {
                // Без связи давно: градусы полусуточной давности хуже, чем честное «нет связи» (план Codex, блок 6)
                icon.set(-1, true);
                temp.setText("");
                String why = data.weatherProblem();
                place.setText(city.isEmpty() ? "Погода: " + why : city + " · " + why);
            } else {
                icon.set(data.code, data.day);
                temp.setText(Data.deg(data.temp));
                // Город из настроек на ТВ; пустой город: только погода, без названия
                String s = city.isEmpty() ? w : w.isEmpty() ? city : city + " · " + w;
                if (st == Fresh.SHOW_WITH_TIME)
                    s += " · на " + new SimpleDateFormat("H:mm", Locale.forLanguageTag("ru")).format(new Date(data.weatherAt()));
                place.setText(s);
            }
        }
        if (Setup.ratesOn(c)) {
            // Знак и курс в своих деньгах через точку (Rates): «$ 2,99 · € 3,34 · ₽ 3,66 за 100»
            StringBuilder b = new StringBuilder();
            for (Rates.Item it : data.rates) {
                if (b.length() > 0) b.append("  ·  ");
                b.append(it.sym).append('\u2009').append(Rates.format(it.v)).append(it.tail);
            }
            String r = b.toString();
            int st = data.ratesState(now);
            if (st == Fresh.NO_LINK) r = "Курсы: " + data.ratesProblem();
            else if (st == Fresh.SHOW_WITH_TIME)
                r += "  ·  на " + new SimpleDateFormat("d.MM", Locale.forLanguageTag("ru")).format(new Date(data.ratesAt()));
            rates.setText(r);
        }
        if ((Setup.versesOn(c) || note != null) && verse[0].getText().length() == 0 && verse[1].getText().length() == 0
                && ref.getText().length() == 0) {
            verse[vis].setText(Typo.fix(leadText()));
            ref.setText(leadRef());
        }
        if (shown == null) showPhoto(Walls.current(c, data), true);
    }

    // Смена фото через растворение и медленный наплыв без разворота (эффект Кена Бёрнса)
    private static final java.util.concurrent.ExecutorService DECODE = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void wakeTo(Data.Photo ph) {
        wake.animate().cancel();
        wake.setAlpha(1f);
        wake.setVisibility(View.VISIBLE);
        final Runnable reveal = () -> wake.animate().alpha(0f).setDuration(M.t(700)).setInterpolator(EASE)
                .withEndAction(() -> wake.setVisibility(View.GONE)).start();
        DECODE.execute(() -> {
            Bitmap bm = ph.load(sample());
            Bitmap blurred = bm == null ? null : softOf(bm, ph.still);
            Bitmap fr = frostOf(blurred);
            Bitmap lk = lockOf(bm);
            h.post(() -> {
                if (bm != null) applyPhoto(ph, bm, lk, blurred, false, 0, fr);
                reveal.run();
            });
        });
    }

    // Фото читается и размывается в фоне: на главном потоке это дёргало бы анимации
    private void showPhoto(Data.Photo ph, boolean fade) { showPhoto(ph, fade, 2200); }

    // Матовое стекло шаров Liquid Glass: то, что видно за рядом в «Vision» (копия под ряд), размытое в маленькую картинку
    private Bitmap frostOf(Bitmap soft) { return vision && soft != null ? Blur.of(soft, 3, 1f) : null; }

    // fadeMs: сколько длится растворение (смена по времени суток медленная, примерка обоев в «Оформлении» быстрая)
    private void showPhoto(Data.Photo ph, boolean fade, long fadeMs) {
        if (ph == null) return;
        if (!fade) {   // первый кадр нужен сразу, без чёрного экрана
            Bitmap bm = ph.load(sample());
            if (bm != null) { Bitmap s = softOf(bm, ph.still); applyPhoto(ph, bm, lockOf(bm), s, false, fadeMs, frostOf(s)); }
            return;
        }
        final int ticket = ++wallTicket;
        DECODE.execute(() -> {
            if (ticket != wallTicket) return;   // пока ждали очереди, выбрали другие обои
            Bitmap bm = ph.load(sample());
            if (bm == null) return;
            Bitmap blurred = softOf(bm, ph.still);
            Bitmap fr = frostOf(blurred);
            Bitmap lk = lockOf(bm);
            h.post(() -> { if (ticket == wallTicket) applyPhoto(ph, bm, lk, blurred, true, fadeMs, fr); });
        });
    }

    private volatile int wallTicket;

    // «Оформление»: показать выбранные (или примеряемые) обои сразу, растворением за fadeMs
    void showWall(long fadeMs) {
        Data.Photo ph = Walls.current(c, data);
        if (ph == null || (shownPhoto != null && ph.id.equals(shownPhoto.id))) return;
        showPhoto(ph, true, fadeMs);
    }

    // Что сейчас на экране: резкая картинка (для фона «Оформления» и стекла Liquid Glass)
    Bitmap shownBitmap() { return shown; }

    Data.Photo shownPhoto() { return shownPhoto; }

    private void applyPhoto(Data.Photo ph, Bitmap bm, Bitmap lock, Bitmap blurred, boolean fade, long fadeMs, Bitmap frost) {
        Liquid.setFrost(frost, 1f);
        int back = 1 - front;
        sharp[back].setImageBitmap(lock != null ? lock : bm);
        // Плитки открыты и размытая копия закрывает экран целиком: резкое фото под ней не рисовать. Раньше оно
        // оставалось видимым, если фото сменилось при открытых плитках, и стоило видеоядру лишний проход 1080p (27.10)
        sharp[back].setVisibility(active && blurLevel >= 0.999f ? View.INVISIBLE : View.VISIBLE);
        soft[back].setImageBitmap(blurred);
        soft[back].setAlpha(blurLevel);
        FrameLayout next = frame[back], cur = frame[front];
        // Прошлая смена могла ещё идти: её растворение перебило бы этот кадр
        next.animate().cancel();
        cur.animate().cancel();
        next.setScaleX(1f);
        next.setScaleY(1f);
        // Тёмная подложка корня нужна только пока фото растворяются друг в друге (оба полупрозрачны). Когда новое
        // фото встало, она убирается: под непрозрачным фото это лишняя заливка 1080p в каждом кадре (27.10)
        // Прежнее фото после смены больше не нужно: отпускаем его картинки из памяти (около 10 МБ в «Vision»). На ТВ с
        // занятой памятью лёгкий «Дом» реже выгружается системой, а первое пробуждение после выгрузки самое дёрганое (07.11)
        final int old = 1 - back;
        final Runnable drop = () -> {
            if (front != back) return;   // пока растворялось, началась новая смена: её картинки не трогаем
            sharp[old].setImageDrawable(null);
            soft[old].setImageDrawable(null);
        };
        if (fade) {
            root.setBackgroundColor(0xFF07070A);
            next.animate().alpha(1f).setDuration(M.t(fadeMs)).setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> { root.setBackground(null); drop.run(); }).start();
            cur.animate().alpha(0f).setDuration(M.t(fadeMs)).setInterpolator(new AccelerateDecelerateInterpolator()).start();
        } else {
            next.setAlpha(1f);
            cur.setAlpha(0f);
            root.setBackground(null);
        }
        front = back;
        if (!fade) drop.run();
        shown = bm;
        shownPhoto = ph;
        caption.setText(captionText(ph));
        if (ph.still) {
            // Градиент: наплыв не нужен, экран блокировки стоит и не перерисовывается
            stopDrift();
            next.setScaleX(1f);
            next.setScaleY(1f);
        } else drift(next);
        if (onWall != null) onWall.run();
    }

    Runnable onWall;   // обои сменились: «Оформление» обновляет свой фон, ряд Liquid Glass своё стекло

    // Подпись: место, а после точки мелким и тусклым автор с лицензией
    private CharSequence captionText(Data.Photo ph) {
        if (ph.credit.isEmpty()) return ph.caption;
        SpannableString t = new SpannableString(ph.caption + "  ·  " + ph.credit);
        int from = ph.caption.length();
        t.setSpan(new RelativeSizeSpan(0.85f), from, t.length(), 0);
        t.setSpan(new ForegroundColorSpan(0x66FFFFFF), from, t.length(), 0);
        return t;
    }

    // Наплыв фото без остановки: 1,0 ↔ 1,06 туда и обратно по 10 минут (фото висит часами, без движения экран выглядел
    // застывшим). На главном экране с плитками стоит. 05.11: шаг раз в 0,4 с, а не каждый кадр. За кадр фото сдвигалось
    // на тысячную долю точки, а экран блокировки из-за этого перерисовывался целиком 60 раз в секунду и держал треть
    // процессора ТВ. За 0,4 с край кадра уходит меньше чем на десятую долю точки: шагов не видно
    static final long DRIFT_MS = 10 * 60_000, DRIFT_STEP_MS = 400;

    private void drift(View f) {
        stopDrift();
        if (lite) return;   // облегчённый режим: фото стоит, экран не перерисовывается
        driftView = f;
        float from = f.getScaleX();
        driftA = from;
        driftB = 1.06f;
        driftMs = Math.max(20_000, Math.round(DRIFT_MS * (1.06f - from) / 0.06f));
        driftDone = 0;
        if (!active) resumeDrift();
    }

    private void resumeDrift() {
        if (driftView == null || driftMark != 0) return;
        driftMark = android.os.SystemClock.uptimeMillis();
        h.removeCallbacks(driftTick);
        h.post(driftTick);
    }

    private void pauseDrift() {
        if (driftView != null) Liquid.bgScale = driftView.getScaleX();   // за рядом фото стоит с этим наплывом
        if (driftMark == 0) return;
        driftDone += android.os.SystemClock.uptimeMillis() - driftMark;
        driftMark = 0;
        h.removeCallbacks(driftTick);
    }

    private void stopDrift() {
        pauseDrift();
        driftView = null;
    }

    private final Runnable driftTick = new Runnable() {
        @Override public void run() {
            if (driftView == null || driftMark == 0) return;
            long now = android.os.SystemClock.uptimeMillis();
            long done = driftDone + now - driftMark;
            if (done >= driftMs) {
                // Дошли до края: обратно, и так бесконечно
                driftA = driftB;
                driftB = driftB > 1.03f ? 1f : 1.06f;
                driftMs = DRIFT_MS;
                driftDone = 0;
                driftMark = now;
                done = 0;
            }
            float x = done / (float) driftMs;
            float e = (float) (0.5 - 0.5 * Math.cos(Math.PI * x));   // разгон и торможение
            float s = driftA + (driftB - driftA) * e;
            driftView.setScaleX(s);
            driftView.setScaleY(s);
            h.postDelayed(this, DRIFT_STEP_MS);
        }
    };
}
