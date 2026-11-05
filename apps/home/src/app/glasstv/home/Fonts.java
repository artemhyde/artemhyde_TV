package app.glasstv.home;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

// Единая типографика: одна семья Inter, три начертания и шесть кеглей с шагом 1,25.
// Оптический размер файла по кеглю: 13 и 16 берут 18pt, от 20 до 32 берут 24pt, часы 28pt
final class Fonts {
    static final float CAPTION = 13, BODY = 16, LEAD = 20, HEAD = 25, TITLE = 32, CLOCK = 60;
    // Три уровня белого на весь интерфейс
    static final int WHITE = 0xFFFFFFFF, WHITE_92 = 0xEBFFFFFF, WHITE_72 = 0xB8FFFFFF, WHITE_60 = 0x99FFFFFF;
    static final int ACCENT = 0xFF5ED3B4;

    static Typeface reg18, med18, light24, reg24, med24, light28;

    static void init(Context c) {
        M.init(c);
        if (reg18 != null) return;
        reg18 = load(c, "Inter_18pt-Regular");
        med18 = load(c, "Inter_18pt-Medium");
        light24 = load(c, "Inter_24pt-Light");
        reg24 = load(c, "Inter_24pt-Regular");
        med24 = load(c, "Inter_24pt-Medium");
        light28 = load(c, "Inter_28pt-Light");
    }

    private static Typeface load(Context c, String name) {
        try { return Typeface.createFromAsset(c.getAssets(), "fonts/" + name + ".ttf"); }
        catch (Exception e) { return Typeface.DEFAULT; }
    }

    // Текст нужного кегля, начертания и цвета; цифры табличные, чтобы время и курсы не «дышали»
    // Прозрачность наносится прямо на буквы, без отдельного холста (05.11). У текста с цветными кусками (Spannable)
    // система иначе на каждом кадре рисовала его в свой холст: две полупрозрачные капсулы стоили видеоядру 10 мс
    // из 14, и ряд при зажатой стрелке шёл рывками. Буквы друг на друга не ложатся, так что на вид разницы нет
    static TextView text(Context c, float sp, Typeface tf, int color) {
        TextView t = new TextView(c) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        t.setTextSize(sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    // Подходящий файл для обычного текста этого кегля
    static Typeface regular(float sp) { return sp >= 20 ? reg24 : reg18; }

    static Typeface medium(float sp) { return sp >= 20 ? med24 : med18; }
}
