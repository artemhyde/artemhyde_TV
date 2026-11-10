package app.glasstv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewParent;

// Материал ряда Liquid Glass (10.11, «Оформление», как на утверждённых макетах): прозрачное стекло и белые знаки.
// Шар «Vision»: внутри размытые обои (матовое стекло), сверху светлая вуаль, блик сверху слева, светлая кромка,
// свечение у нижнего края изнутри, мягкая тень. Живого размытия нет: обои размываются один раз в маленькую картинку
// (frost) при смене обоев, а шар рисует её кругом через шейдер, совмещённым с местом шара на экране. Шар двигается
// (карусель, пружина, каскад): «Дом» перед кадром сверяет место каждого шара и просит перерисовать только сдвинутые.
// Плитка «Графита» в Liquid Glass: то же дымчатое стекло (оно и так на размытом фоне), но белый знак вместо цветного значка.
// Знак: свой (Glyph) для плиток HYDE, у чужого приложения белый силуэт его значка
final class Liquid {
    private Liquid() {}

    // ===== Матовое стекло: размытые обои под рядом =====

    static volatile Bitmap frost;       // размытая копия того, что видно за рядом (обычно 240x135)
    static volatile float bgScale = 1f; // наплыв фото на экране в момент снятия: фон чуть увеличен вокруг середины
    static volatile int version;        // номер копии: сменилась, шары перерисовываются

    static void setFrost(Bitmap b, float scale) {
        frost = b;
        bgScale = scale;
        version++;
    }

    // Из точек вида v в точки корня главного экрана: по цепочке родителей с их масштабом, сдвигом и прокруткой
    static boolean toRoot(View v, View root, Matrix out) {
        out.reset();
        View cur = v;
        while (cur != root) {
            Matrix m = cur.getMatrix();
            if (!m.isIdentity()) out.postConcat(m);
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) return false;
            View pv = (View) p;
            out.postTranslate(cur.getLeft() - pv.getScrollX(), cur.getTop() - pv.getScrollY());
            cur = pv;
        }
        return true;
    }

    // Шейдер матового стекла для вида: картинка frost ложится ровно туда, где на экране лежат обои.
    // lift: холст вида сдвинут вверх на столько точек (подъём шара в фокусе рисуется сдвигом холста)
    static boolean frostPaint(View v, View root, FrostPaint p, Matrix toRoot, Matrix tmp, float lift) {
        Bitmap f = frost;
        if (f == null || root == null || root.getWidth() == 0) return false;
        if (!toRoot(v, root, toRoot)) return false;
        toRoot.preTranslate(0, -lift);
        // frost → корень: растянуть на весь корень и увеличить вокруг середины на наплыв фото
        tmp.reset();
        tmp.setScale(root.getWidth() / (float) f.getWidth(), root.getHeight() / (float) f.getHeight());
        tmp.postScale(bgScale, bgScale, root.getWidth() / 2f, root.getHeight() / 2f);
        if (!toRoot.invert(p.inv)) return false;
        tmp.postConcat(p.inv);   // frost → корень → холст вида
        BitmapShader sh = p.getShader() instanceof BitmapShader && p.getTag() == f ? (BitmapShader) p.getShader() : null;
        if (sh == null) {
            sh = new BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            p.setShader(sh);
            p.setTag(f);
        }
        sh.setLocalMatrix(tmp);
        return true;
    }

    // Краска с пометкой, какой картинкой заряжена (шейдер пересоздаётся только при смене копии)
    static final class FrostPaint extends Paint {
        private Object tag;
        final Matrix inv = new Matrix();
        FrostPaint() { super(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); }
        Object getTag() { return tag; }
        void setTag(Object t) { tag = t; }
    }

    // ===== Стекло шара =====

    // Одно состояние шара (покой или фокус) с тенями, в тех же полях картинки, что у стекла «Графита» (Tile.PAD_*).
    // Числа из макета (.orb .ball): шар 232 точки экрана 1920x1080, k переводит их в настоящие
    static Bitmap orbGlass(int d, boolean f) {
        float k = d / 232f, kt = d / (Tile.W0 * 0.5f);
        int s = f ? 1 : 0;
        int px = Math.round(Tile.PAD_X[s] * kt), pt = Math.round(Tile.PAD_TOP[s] * kt), pb = Math.round(Tile.PAD_BOTTOM[s] * kt);
        Bitmap b = Bitmap.createBitmap(d + 2 * px, d + pt + pb, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(px, pt);
        float r = d / 2f;
        RectF card = new RectF(0, 0, d, d);
        Path shape = new Path();
        shape.addCircle(r, r, r, Path.Direction.CW);
        // Тень вниз (0 30 60 -18, 60 %) и в фокусе свечение вокруг (0 0 60, белый 18 %)
        if (f) {
            Tile.shadow(c, card, r, 0, 40 * k, 70 * k, -18 * k, 0xB3000000);
            Tile.shadow(c, card, r, 0, 0, 60 * k, 0, 0x2EFFFFFF);
        } else {
            Tile.shadow(c, card, r, 0, 30 * k, 60 * k, -18 * k, 0x99000000);
        }
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawPath(shape, clear);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Светлая вуаль сверху вниз (16 % → 6 %), в фокусе чуть светлее
        p.setShader(new LinearGradient(0, 0, 0, d, f ? 0x33FFFFFF : 0x29FFFFFF, f ? 0x14FFFFFF : 0x0FFFFFFF, Shader.TileMode.CLAMP));
        c.drawPath(shape, p);
        // Блик: эллипс 70 % на 60 % с центром в 30 % и 18 %
        float cx = 0.3f * d, cy = 0.18f * d, rx = 0.7f * d, ry = 0.6f * d;
        RadialGradient rg = new RadialGradient(cx, cy, rx, new int[]{f ? 0x4DFFFFFF : 0x42FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.6f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f, ry / rx, cx, cy);
        rg.setLocalMatrix(m);
        p.setShader(rg);
        c.drawPath(shape, p);
        p.setShader(null);
        c.save();
        c.clipPath(shape);
        // Свечение у нижнего края изнутри (inset 0 -18 30, белый 10 %)
        Tile.inset(c, card, r, 0, -18 * k, 30 * k, 0, f ? 0x24FFFFFF : 0x1AFFFFFF);
        c.restore();
        // Кромка: светлее сверху слева, как свет на стекле; в фокусе почти белая
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        float w = (f ? 3f : 2f) * k;
        rim.setStrokeWidth(w);
        rim.setShader(Tile.css(150, d, d, f ? new int[]{0xFFFFFFFF, 0xE6FFFFFF, 0xB3FFFFFF} : new int[]{0x8CFFFFFF, 0x47FFFFFF, 0x2EFFFFFF, 0x52FFFFFF},
                f ? null : new float[]{0f, 0.4f, 0.7f, 1f}));
        c.drawCircle(r, r, r - w / 2f, rim);
        return Tile.fixed(b);
    }

    // ===== Знаки =====

    // Белый знак для шара или плитки: свой для плиток HYDE, иначе силуэт значка приложения (null: нет значка)
    static Bitmap mark(String pkg, Drawable icon, String label, int size) {
        String g = Glyph.forPackage(pkg);
        if (g != null) return Glyph.bitmap(g, size, 0xF5FFFFFF, 1.6f);
        Bitmap sil = silhouette(icon, size);
        if (sil != null) return sil;
        return letter(label, size);
    }

    // Белый силуэт значка: у адаптивного значка его передний слой, у обычного то, что отличается от фона по краю
    static Bitmap silhouette(Drawable icon, int size) {
        if (icon == null) return null;
        int n = 96;
        Bitmap full = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(full);
        int[] px = new int[n * n];
        if (icon instanceof AdaptiveIconDrawable && ((AdaptiveIconDrawable) icon).getForeground() != null) {
            Drawable fg = ((AdaptiveIconDrawable) icon).getForeground();
            float k = n * 108f / 72f, o = (k - n) / 2f;
            fg.setBounds(Math.round(-o), Math.round(-o), Math.round(-o + k), Math.round(-o + k));
            fg.draw(c);
            full.getPixels(px, 0, n, 0, 0, n, n);
            int cover = 0;
            for (int i = 0; i < px.length; i++) { int a = px[i] >>> 24; px[i] = (a << 24) | 0xFFFFFF; if (a > 128) cover++; }
            float cv = cover / (float) (n * n);
            if (cv > 0.03f && cv < 0.7f) return fit(px, n, size);
            c.drawColor(0, PorterDuff.Mode.CLEAR);
        }
        icon.setBounds(0, 0, n, n);
        icon.draw(c);
        full.getPixels(px, 0, n, 0, 0, n, n);
        // Цвет фона: среднее по рамке шириной 6 точек
        long sr = 0, sg = 0, sb = 0, cnt = 0;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            if (x >= 6 && x < n - 6 && y >= 6 && y < n - 6) continue;
            int p = px[y * n + x];
            if ((p >>> 24) < 128) continue;
            sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255; cnt++;
        }
        if (cnt == 0) {
            // Прозрачный фон: силуэт по самой прозрачности
            for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0xFF000000) | 0xFFFFFF;
            return fit(px, n, size);
        }
        float br = sr / (float) cnt, bg = sg / (float) cnt, bb = sb / (float) cnt;
        int cover = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            float a = (p >>> 24) / 255f;
            float dr = ((p >> 16) & 255) - br, dg = ((p >> 8) & 255) - bg, db = (p & 255) - bb;
            float dist = (float) Math.sqrt(dr * dr + dg * dg + db * db);
            float al = Math.max(0f, Math.min(1f, (dist - 28f) / 70f)) * a;
            int ai = Math.round(al * 255);
            if (ai > 128) cover++;
            px[i] = (ai << 24) | 0xFFFFFF;
        }
        float cv = cover / (float) (n * n);
        if (cv < 0.03f || cv > 0.75f) return null;
        return fit(px, n, size);
    }

    // Силуэт без пустых полей, по центру квадрата size
    private static Bitmap fit(int[] px, int n, int size) {
        int l = n, t = n, r = -1, bt = -1;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            if ((px[y * n + x] >>> 24) < 20) continue;
            if (x < l) l = x;
            if (x > r) r = x;
            if (y < t) t = y;
            if (y > bt) bt = y;
        }
        if (r < 0) return null;
        Bitmap src = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        float w = r - l + 1, h = bt - t + 1, s = size * 0.92f / Math.max(w, h);
        float dw = w * s, dh = h * s;
        new Canvas(out).drawBitmap(src, new Rect(l, t, r + 1, bt + 1), new RectF((size - dw) / 2f, (size - dh) / 2f, (size + dw) / 2f, (size + dh) / 2f),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return Tile.fixed(out);
    }

    // Совсем без значка: первая буква названия тонким белым
    static Bitmap letter(String label, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        String s = label == null || label.isEmpty() ? "•" : label.substring(0, 1).toUpperCase();
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        t.setTypeface(Fonts.light28);
        t.setTextSize(size * 0.86f);
        t.setColor(0xF5FFFFFF);
        t.setTextAlign(Paint.Align.CENTER);
        Rect b = new Rect();
        t.getTextBounds(s, 0, s.length(), b);
        new Canvas(out).drawText(s, size / 2f, size / 2f + b.height() / 2f, t);
        return Tile.fixed(out);
    }

    // Содержимое плитки «Графита» в Liquid Glass: белый знак над подписью (как плитка приложения, но без цвета)
    static Bitmap tileContent(Context ctx, String pkg, Drawable icon, String label, int w, int h) {
        float k = w / Tile.W0;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int ms = Math.round(76 * k);
        Bitmap m = mark(pkg, icon, label, ms);
        Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        ip.setShadowLayer(Tile.cssBlur(10 * k), 0, 2 * k, 0x59000000);
        if (m != null) c.drawBitmap(m, 200 * k - ms / 2f, 80 * k - ms / 2f, ip);
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        t.setTypeface(Fonts.med18);
        t.setTextSize(32 * k);
        t.setLetterSpacing(-0.005f);
        t.setColor(0xF0FFFFFF);
        t.setTextAlign(Paint.Align.CENTER);
        CharSequence s = TextUtils.ellipsize(label, t, w - 40 * k, TextUtils.TruncateAt.END);
        Paint.FontMetrics fm = t.getFontMetrics();
        float base = 168 * k - (fm.ascent + fm.descent) / 2f;
        t.setShadowLayer(Tile.cssBlur(14 * k), 0, 4 * k, 0x80000000);
        c.drawText(s, 0, s.length(), w / 2f, base, t);
        return b;
    }
}
