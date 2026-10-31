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
    final boolean dream;
    final FrameLayout root;
    private final FrameLayout[] frame = new FrameLayout[2];
    private final ImageView[] sharp = new ImageView[2], soft = new ImageView[2];
    private int front;
    private ObjectAnimator kenBurns;
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

    Ambient(Context c, boolean dream) {
        this.c = c;
        this.dream = dream;
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
            FrameLayout f = new FrameLayout(c);
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
        top = new View(c);
        GradientDrawable topShade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0x4D000000, 0x00000000});
        if (android.os.Build.VERSION.SDK_INT >= 29) topShade.setColors(new int[]{0x66000000, 0x4D000000, 0x00000000}, new float[]{0f, 0.62f, 1f});
        top.setBackground(topShade);
        root.addView(top, new FrameLayout.LayoutParams(-1, px(dream ? 0 : 190)));
        bottom = new View(c);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x33000000, 0x8C000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, px(dream ? 300 : 240), Gravity.BOTTOM));
        dim = new View(c);
        dim.setBackgroundColor(0xFF000000);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        // Частицы как на PS5: над фоном, под текстом и плитками; в заставке их нет
        particles = dream ? null : new Particles(c);
        if (particles != null) root.addView(particles, new FrameLayout.LayoutParams(-1, -1));

        int clockBase = dream ? 468 : 92, dateBase = dream ? Grid.BOTTOM : 124;
        // В заставке часы мельче и чуть прозрачнее: главное там фото
        clock = Fonts.text(c, dream ? 48 : 64, Fonts.light28, dream ? 0xD9FFFFFF : Fonts.WHITE);
        clock.setLetterSpacing(-0.02f);
        clock.setTranslationX(-px(4));   // у цифр свой боковой зазор: видимый край на линии 56 dp
        root.addView(clock, Grid.at(clock, dp, Grid.MARGIN, clockBase, false, 0));
        date = Fonts.text(c, Fonts.BODY, Fonts.reg18, dream ? Fonts.WHITE_60 : Fonts.WHITE_72);
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
        root.addView(rates, Grid.at(rates, dp, Grid.RIGHT, Data.WEATHER_ON ? 148 : 124, true, Grid.span(5)));

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

        caption = Fonts.text(c, Fonts.CAPTION, Fonts.reg18, dream ? 0x73FFFFFF : Fonts.WHITE_60);
        caption.setGravity(Gravity.END);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(caption, Grid.at(caption, dp, Grid.RIGHT, Grid.BOTTOM, true, Grid.span(7)));

        // Тени у текста нет: читаемость дают подложки сверху и снизу
        if (dream) {
            // В заставке нет погоды, курса и стиха: только время и место на фото
            for (View v : new View[]{tempRow, place, rates, verse[0], verse[1], ref}) v.setVisibility(View.GONE);
        }
        // Настройки сборки (Config): чего нет, того на экране нет совсем, остальное стоит на своих местах
        if (!Data.WEATHER_ON) for (View v : new View[]{tempRow, place}) v.setVisibility(View.GONE);
        if (!Data.RATES_ON) rates.setVisibility(View.GONE);
        if (!Data.VERSES_ON) for (View v : new View[]{verse[0], verse[1], ref}) v.setVisibility(View.GONE);
    }

    int px(float v) { return Math.round(v * dp); }

    // ===== Жизненный цикл =====

    void start() {
        Now.attach(c, nowChanged);
        data.addListener(this);
        data.refresh();
        // Заставка это тот же экран блокировки, поэтому и фото то же самое (27.10), а не следующее в очереди
        Data.Photo first = data.currentPhoto();
        if (shown == null || dream) showPhoto(first, false);
        else {
            drift(frame[front]);   // вернулись на экран: наплыв продолжается с текущего кадра
            if (first != null && shownPhoto != null && !first.id.equals(shownPhoto.id)) showPhoto(first, true);   // пока нас не было, началось новое время суток
        }
        onData();
        h.post(tick);
        h.postDelayed(rotate, CHECK_MS);
        if (particles != null) particles.level(active ? 1f : Particles.LOCK, 0);
        if (dream) {
            // Экран гаснет мягко: общее затемнение лёгкое, низ под часами темнее (подложка снизу)
            dim.animate().alpha(0.22f).setDuration(M.t(6000)).setInterpolator(new AccelerateDecelerateInterpolator()).start();
            h.postDelayed(shift, 5 * 60_000);
            // Через 15 минут простоя картинка вдвое темнее, как в энергосбережении; любая кнопка вернёт всё сразу
            h.postDelayed(() -> dim.animate().alpha(0.5f).setDuration(M.t(8000)).start(), Dream.SAVER_MS);
        }
    }

    void stop() {
        Now.detach(c, nowChanged);
        data.removeListener(this);
        h.removeCallbacksAndMessages(null);
        if (kenBurns != null) kenBurns.cancel();
        if (particles != null) particles.stop();
    }

    Runnable onNow;   // главный экран обновляет карточку «Играет», когда «Волна» сообщает о треке
    final Runnable nowChanged = () -> { if (onNow != null) onNow.run(); };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            clock.setText(new SimpleDateFormat("H:mm", Locale.forLanguageTag("ru")).format(now));
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
            if (!active) {
                data.prefetch();
                Data.Photo next = data.currentPhoto();
                if (next != null && (shownPhoto == null || !next.id.equals(shownPhoto.id))) showPhoto(next, true);
                if (!dream && data.nextVerse()) swapVerse(true);
            }
            h.postDelayed(this, CHECK_MS);
        }
    };

    // Заставка: раз в 5 минут часы чуть смещаются, чтобы панель не запоминала картинку
    private int shiftStep;
    private final Runnable shift = new Runnable() {
        @Override public void run() {
            int[][] spots = {{0, 0}, {24, -16}, {-12, -8}, {16, 8}, {-20, 12}};
            int[] s = spots[++shiftStep % spots.length];
            for (View v : new View[]{clock, date}) v.animate().translationX(px(s[0]) - (v == clock ? px(4) : 0))
                    .translationY(px(s[1])).setDuration(M.t(3000)).setInterpolator(new AccelerateDecelerateInterpolator()).start();
            h.postDelayed(this, 5 * 60_000);
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
        if (particles != null) particles.show(on);
        for (View g : new View[]{top, bottom})
            g.animate().alpha(on ? 0f : 1f).setStartDelay(0).setDuration(M.t(on ? 500 : 900)).setInterpolator(EASE).start();
        TextView shownVerse = verse[vis];
        if (on && !dream) h.postDelayed(() -> {
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
        if (kenBurns != null) { if (on) kenBurns.pause(); else kenBurns.resume(); }
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
        if (particles != null && active) {
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

    private void animateBlur(float to, long ms) {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(blurLevel, to);
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
        if (Data.WEATHER_ON) {
            icon.set(data.code, data.day);
            temp.setText(Data.deg(data.temp));
            String w = data.weatherText();
            // Город из Config; пустой город: только погода, без названия
            place.setText(Config.CITY.isEmpty() ? w : w.isEmpty() ? Config.CITY : Config.CITY + " · " + w);
        }
        if (Data.RATES_ON) {
            // Один шаблон для всех трёх: знак и курс к белорусскому рублю, рубль за сотню
            rates.setText("$\u2009" + Data.rate2(data.usd) + "  ·  €\u2009" + Data.rate2(data.eur)
                    + "  ·  ₽\u2009" + Data.rate2(data.rub) + " за\u00A0100");
        }
        if (Data.VERSES_ON && !dream && verse[0].getText().length() == 0 && verse[1].getText().length() == 0) {
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
            Bitmap bm = Data.decode(ph.file);
            if (bm != null) applyPhoto(ph, bm, Blur.of(bm, 4, SOFT_BRIGHT), false);
            return;
        }
        DECODE.execute(() -> {
            Bitmap bm = Data.decode(ph.file);
            if (bm == null) return;
            Bitmap blurred = Blur.of(bm, 4, SOFT_BRIGHT);
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
        if (fade) {
            root.setBackgroundColor(0xFF07070A);
            next.animate().alpha(1f).setDuration(M.t(2200)).setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> root.setBackground(null)).start();
            cur.animate().alpha(0f).setDuration(M.t(2200)).setInterpolator(new AccelerateDecelerateInterpolator()).start();
        } else {
            next.setAlpha(1f);
            cur.setAlpha(0f);
            root.setBackground(null);
        }
        front = back;
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
        t.setSpan(new ForegroundColorSpan(dream ? 0x4DFFFFFF : 0x66FFFFFF), from, t.length(), 0);
        return t;
    }

    // Медленный наплыв фото от текущего масштаба до 1,05 (в заставке до 1,06)
    // Очень медленный наплыв без остановки: 1,0 ↔ 1,06 туда и обратно по 10 минут (фото теперь висит часами,
    // а прошлый наплыв за 2 минуты останавливался, и экран выглядел застывшим). На главном экране с плитками стоит
    static final long DRIFT_MS = 10 * 60_000;

    private void drift(View f) {
        if (kenBurns != null) kenBurns.cancel();
        float from = f.getScaleX();
        kenBurns = ObjectAnimator.ofPropertyValuesHolder(f,
                PropertyValuesHolder.ofFloat(View.SCALE_X, from, 1.06f), PropertyValuesHolder.ofFloat(View.SCALE_Y, from, 1.06f));
        kenBurns.setDuration(M.t(Math.max(20_000, Math.round(DRIFT_MS * (1.06f - from) / 0.06f))));
        kenBurns.setInterpolator(new AccelerateDecelerateInterpolator());
        kenBurns.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) return;
                // Дошли до 1,06: обратно к 1,0 и снова вперёд, бесконечно
                ObjectAnimator back = ObjectAnimator.ofPropertyValuesHolder(f,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1.06f, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.06f, 1f));
                back.setDuration(M.t(DRIFT_MS));
                back.setInterpolator(new AccelerateDecelerateInterpolator());
                back.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                back.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                kenBurns = back;
                back.start();
                if (active) back.pause();
            }
        });
        kenBurns.start();
        if (active) kenBurns.pause();
    }
}
