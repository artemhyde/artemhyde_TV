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
    final TextView clock, date, temp, place, rates, ref, caption;
    private final TextView[] verse = new TextView[2];
    private int vis;   // какой из двух слоёв стиха сейчас виден
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
        root.addView(top, new FrameLayout.LayoutParams(-1, px(190)));
        bottom = new FlatView(c);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x33000000, 0x8C000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, px(240), Gravity.BOTTOM));
        dim = new FlatView(c);
        dim.setBackgroundColor(0xFF000000);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        // Частицы как на PS5: над фоном, под текстом и плитками
        particles = new Particles(c);
        root.addView(particles, new FrameLayout.LayoutParams(-1, -1));

        int clockBase = 92, dateBase = 124;
        clock = Fonts.text(c, 64, Fonts.light28, Fonts.WHITE);
        clock.setLetterSpacing(-0.02f);
        clock.setTranslationX(-px(4));   // у цифр свой боковой зазор: видимый край на линии 56 dp
        root.addView(clock, Grid.at(clock, dp, Grid.MARGIN, clockBase, false, 0));
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
        root.addView(rates, Grid.at(rates, dp, Grid.RIGHT, Setup.weatherOn(c) ? 148 : 124, true, Grid.span(5)));

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
        boolean w = Setup.weatherOn(c), r = Setup.ratesOn(c), v = Setup.versesOn(c);
        for (View x : new View[]{tempRow, place}) x.setVisibility(w ? View.VISIBLE : View.GONE);
        rates.setVisibility(r ? View.VISIBLE : View.GONE);
        for (View x : new View[]{verse[0], verse[1], ref}) x.setVisibility(v ? View.VISIBLE : View.GONE);
        if (styled) layoutText();
        boolean l = Setup.lite(c);
        if (l != lite) {
            lite = l;
            showParticles();
            if (lite) stopDrift();
            if (shownPhoto != null) showPhoto(shownPhoto, false);   // фото в нужном размере сразу
        }
    }

    // Частицы только в «Графите» и не в облегчённом режиме
    private void showParticles() {
        boolean off = vision || lite;
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

    private void layoutText() {
        boolean v = vision;
        clock.setTextSize(v ? 96 : 64);
        clock.setTranslationX(v ? 0 : -px(4));
        clock.setLayoutParams(v ? Grid.center(clock, dp, V_CLOCK) : Grid.at(clock, dp, Grid.MARGIN, 92, false, 0));
        date.setLayoutParams(v ? Grid.center(date, dp, V_DATE) : Grid.at(date, dp, Grid.MARGIN, 124, false, 0));
        tempRow.setLayoutParams(v ? Grid.center(temp, dp, V_TEMP) : Grid.at(temp, dp, Grid.RIGHT, 92, true, 0));
        place.setGravity(v ? Gravity.CENTER_HORIZONTAL : Gravity.END);
        place.setLayoutParams(v ? Grid.center(place, dp, V_PLACE) : Grid.at(place, dp, Grid.RIGHT, 124, true, Grid.span(5)));
        rates.setGravity(v ? Gravity.CENTER_HORIZONTAL : Gravity.END);
        boolean w = Setup.weatherOn(c);
        rates.setLayoutParams(v ? Grid.center(rates, dp, w ? V_RATES : V_PLACE)
                : Grid.at(rates, dp, Grid.RIGHT, w ? 148 : 124, true, Grid.span(5)));
        for (TextView t : verse) {
            t.setGravity(v ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            t.setLayoutParams(v ? Grid.aboveCenter(t, dp, 466, 680) : Grid.above(t, dp, Grid.MARGIN, 472, Grid.span(6)));
        }
        ref.setLayoutParams(v ? Grid.center(ref, dp, Grid.BOTTOM) : Grid.at(ref, dp, Grid.MARGIN, Grid.BOTTOM, false, 0));
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

    private Bitmap softOf(Bitmap bm) { return vision ? bake(bm) : Blur.of(bm, 4, SOFT_BRIGHT); }

    // ===== Жизненный цикл =====

    private boolean running;

    void start() {
        running = true;
        Now.attach(c, nowChanged);
        data.addListener(this);
        data.refresh();
        Data.Photo first = data.currentPhoto();
        if (shown == null) showPhoto(first, false);
        else {
            drift(frame[front]);   // вернулись на экран: наплыв продолжается с текущего кадра
            if (first != null && shownPhoto != null && !first.id.equals(shownPhoto.id)) showPhoto(first, true);   // пока нас не было, началось новое время суток
        }
        onData();
        h.post(tick);
        h.postDelayed(rotate, CHECK_MS);
        if (!vision && !lite) particles.level(active ? 1f : Particles.LOCK, 0);
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
                Data.Photo next = data.currentPhoto();
                if (next != null && (shownPhoto == null || !next.id.equals(shownPhoto.id))) showPhoto(next, true);
                if (data.nextVerse()) swapVerse(true);
            }
            h.postDelayed(this, CHECK_MS);
        }
    };

    void newVerseNow() {
        data.nextVerse();
        swapVerse(false);
    }

    // Новый стих поверх старого: старый тает, новый проявляется с лёгким подъёмом
    private void swapVerse(boolean animated) {
        TextView old = verse[vis], next = verse[1 - vis];
        next.setText(Typo.fix(data.verse));
        String r = Typo.ref(data.verseRef);
        if (!animated) {
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
            shownVerse.setText(Typo.fix(data.verse));
            ref.setText(Typo.ref(data.verseRef));
        }, 700);
        for (View t : new View[]{shownVerse, ref}) {
            t.animate().cancel();
            if (on) {
                t.animate().alpha(0f).translationY(-px(24)).setStartDelay(M.t(t == ref ? 80 : 0)).setDuration(M.t(500)).setInterpolator(EASE).start();
            } else {
                t.setTranslationY(px(24));
                t.animate().alpha(1f).translationY(0).setStartDelay(M.t(t == ref ? 330 : 250)).setDuration(M.t(700)).setInterpolator(EASE).start();
            }
        }
        caption.animate().alpha(on ? 0f : 1f).setStartDelay(M.t(on ? 0 : 300)).setDuration(M.t(on ? 300 : 600)).start();
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
        for (View t : new View[]{verse[vis], ref}) {
            t.animate().cancel();
            t.setTranslationY(0);
            t.setAlpha(1f);
        }
        caption.animate().cancel();
        caption.setAlpha(1f);
        rates.animate().cancel();
        rates.setAlpha(0f);
        if (!vision && !lite) particles.level(Particles.LOCK, 0);
        resumeDrift();
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
        for (View t : new View[]{clock, date, tempRow, place, rates})
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
                place.setText(city.isEmpty() ? "Погода: нет связи" : city + " · нет связи");
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
            // Один шаблон для всех трёх: знак и курс к белорусскому рублю, рубль за сотню
            String r = "$\u2009" + Data.rate2(data.usd) + "  ·  €\u2009" + Data.rate2(data.eur)
                    + "  ·  ₽\u2009" + Data.rate2(data.rub) + " за\u00A0100";
            int st = data.ratesState(now);
            if (st == Fresh.NO_LINK) r = "Курсы: нет связи";
            else if (st == Fresh.SHOW_WITH_TIME)
                r += "  ·  на " + new SimpleDateFormat("d.MM", Locale.forLanguageTag("ru")).format(new Date(data.ratesAt()));
            rates.setText(r);
        }
        if (Setup.versesOn(c) && verse[0].getText().length() == 0 && verse[1].getText().length() == 0) {
            verse[vis].setText(Typo.fix(data.verse));
            ref.setText(Typo.ref(data.verseRef));
        }
        if (shown == null) showPhoto(data.currentPhoto(), true);
    }

    // Смена фото через растворение и медленный наплыв без разворота (эффект Кена Бёрнса)
    private static final java.util.concurrent.ExecutorService DECODE = java.util.concurrent.Executors.newSingleThreadExecutor();

    // Фото читается и размывается в фоне: на главном потоке это дёргало бы анимации
    private void showPhoto(Data.Photo ph, boolean fade) {
        if (ph == null) return;
        if (!fade) {   // первый кадр нужен сразу, без чёрного экрана
            Bitmap bm = Data.decode(ph.file, lite ? 2 : 1);
            if (bm != null) applyPhoto(ph, bm, softOf(bm), false);
            return;
        }
        DECODE.execute(() -> {
            Bitmap bm = Data.decode(ph.file, lite ? 2 : 1);
            if (bm == null) return;
            Bitmap blurred = softOf(bm);
            h.post(() -> applyPhoto(ph, bm, blurred, true));
        });
    }

    private void applyPhoto(Data.Photo ph, Bitmap bm, Bitmap blurred, boolean fade) {
        int back = 1 - front;
        sharp[back].setImageBitmap(bm);
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
            next.animate().alpha(1f).setDuration(M.t(2200)).setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> { root.setBackground(null); drop.run(); }).start();
            cur.animate().alpha(0f).setDuration(M.t(2200)).setInterpolator(new AccelerateDecelerateInterpolator()).start();
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
        drift(next);
    }

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
