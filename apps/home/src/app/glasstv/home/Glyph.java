package app.glasstv.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Map;

// Значки-линии в духе SF Symbols (10.11, раздел «Оформление» и материал Liquid Glass): белые линии без логотипов.
// Каждый значок задан на поле 24x24 строками пути, как в SVG (те же, что в макетах), и рисуется вектором любого размера.
// Разбор пути свой и маленький: M L H V C S Q T A Z, большие и маленькие буквы. Дуги переводятся в кривые Безье
final class Glyph {
    private Glyph() {}

    private static final class Shape {
        final Path stroke = new Path(), fill = new Path();
    }

    private static final Map<String, Shape> ALL = new HashMap<>();

    // Части значка: «s:путь» линия, «f:путь» заливка, «c:x,y,r» круг линией, «C:x,y,r» круг заливкой, «r:x,y,w,h,rx» прямоугольник линией
    private static final String[][] DEF = {
            {"play", "f:M9 7.2v9.6l7.6-4.8z"},
            {"globe", "c:12,12,9", "s:M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18"},
            {"grid", "C:6,6,1.7", "C:12,6,1.7", "C:18,6,1.7", "C:6,12,1.7", "C:12,12,1.7", "C:18,12,1.7", "C:6,18,1.7", "C:12,18,1.7", "C:18,18,1.7"},
            {"film", "r:4,5,16,14,2", "s:M8 5v14M16 5v14M4 9h4M4 15h4M16 9h4M16 15h4"},
            {"tv", "r:3.5,5,17,11.5,2", "s:M9 20h6"},
            {"playrect", "r:3.5,5.5,17,13,3.2", "f:M10.4 9.3v5.4l4.6-2.7z"},
            {"live", "s:M5.5 4.5h13v9.2l-3.8 3.8h-3.6l-2.6 2.6v-2.6H5.5z", "s:M11 8.6v3.6M14.6 8.6v3.6"},
            {"note", "s:M9 17V6l10-2v11", "c:7,17,2.2", "c:17,15,2.2"},
            {"gear", "c:12,12,3.2", "s:M12 3v2.4M12 18.6V21M3 12h2.4M18.6 12H21M5.6 5.6l1.7 1.7M16.7 16.7l1.7 1.7M5.6 18.4l1.7-1.7M16.7 7.3l1.7-1.7"},
            {"moon", "s:M19 14.5A7.5 7.5 0 1 1 9.5 5a6 6 0 0 0 9.5 9.5z"},
            {"plus", "s:M12 6v12M6 12h12"},
            {"cloud", "s:M7.5 18.5h9.2a4 4 0 0 0 .5-7.97 5.5 5.5 0 0 0-10.6 1.47A3.3 3.3 0 0 0 7.5 18.5z"},
            {"coin", "c:12,12,8.5", "s:M14.6 9.2c-.6-.8-1.6-1.2-2.6-1.2-1.6 0-2.8.8-2.8 2s1.2 1.7 2.8 2 2.8.8 2.8 2-1.2 2-2.8 2c-1.1 0-2.1-.4-2.7-1.3M12 6.5v11"},
            {"quote", "s:M9.5 9.5H6.8a.8.8 0 0 0-.8.8v2.5c0 .4.4.8.8.8h2v.9c0 1-.6 1.9-1.6 2.3M17.5 9.5h-2.7a.8.8 0 0 0-.8.8v2.5c0 .4.4.8.8.8h2v.9c0 1-.6 1.9-1.6 2.3"},
            {"folder", "s:M3.5 7.5a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"},
            {"move", "s:M12 4v16M4 12h16M12 4l-2.5 2.5M12 4l2.5 2.5M12 20l-2.5-2.5M12 20l2.5-2.5M4 12l2.5-2.5M4 12l2.5 2.5M20 12l-2.5-2.5M20 12l-2.5 2.5"},
            {"undo", "s:M9 7 5 11l4 4", "s:M5 11h9a5 5 0 0 1 0 10h-2"},
            {"photo", "r:3.5,5,17,14,2.5", "c:9,10,1.6", "s:M5 17l4.5-4.5 3 3 2.5-2.5L19 17"},
            {"check", "s:M6 12.5l4 4 8-9"},
            {"phone", "r:7,3,10,18,2.4", "s:M11 18h2"},
            {"circle", "c:12,12,8"},
            {"square", "r:4.5,4.5,15,15,4"},
            {"drop", "s:M12 3.5c3.2 4 5.6 7.1 5.6 10.1a5.6 5.6 0 0 1-11.2 0c0-3 2.4-6.1 5.6-10.1z", "s:M9.6 14.4a2.6 2.6 0 0 0 2.4 2.5"},
            {"palette", "s:M12 3.5a8.5 8.5 0 1 0 0 17c1.3 0 1.9-.9 1.9-1.8 0-1.2-.9-1.6-.9-2.6 0-1 .8-1.6 1.8-1.6h2.1a3.6 3.6 0 0 0 3.6-3.6c0-4.2-3.8-7.4-8.5-7.4z", "C:7.6,11.6,1.15", "C:9.6,7.8,1.15", "C:14,7.4,1.15", "C:16.8,10.6,1.15"},
            // Записка с телефона (10.11): карандаш
            {"pen", "s:M15.2 4.8l4 4L9 19H5v-4z", "s:M13.2 6.8l4 4"},
    };

    static {
        for (String[] d : DEF) {
            Shape s = new Shape();
            for (int i = 1; i < d.length; i++) {
                String part = d[i];
                char k = part.charAt(0);
                String v = part.substring(2);
                if (k == 's') parse(v, s.stroke);
                else if (k == 'f') parse(v, s.fill);
                else if (k == 'c' || k == 'C') {
                    float[] n = nums(v);
                    (k == 'c' ? s.stroke : s.fill).addCircle(n[0], n[1], n[2], Path.Direction.CW);
                } else if (k == 'r') {
                    float[] n = nums(v);
                    s.stroke.addRoundRect(new RectF(n[0], n[1], n[0] + n[2], n[1] + n[3]), n[4], n[4], Path.Direction.CW);
                }
            }
            ALL.put(d[0], s);
        }
    }

    static boolean has(String name) { return name != null && ALL.containsKey(name); }

    // Значок по центру (cx, cy) размером size точек; stroke: толщина линии в единицах поля 24 (1,6–1,8 как в макетах)
    static void draw(Canvas c, String name, float cx, float cy, float size, int color, float stroke) {
        Shape s = ALL.get(name);
        if (s == null) return;
        float k = size / 24f;
        c.save();
        c.translate(cx - size / 2f, cy - size / 2f);
        c.scale(k, k);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        if (!s.fill.isEmpty()) {
            p.setStyle(Paint.Style.FILL);
            c.drawPath(s.fill, p);
        }
        if (!s.stroke.isEmpty()) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(s.stroke, p);
        }
        c.restore();
    }

    static Bitmap bitmap(String name, int px, int color, float stroke) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, px), Math.max(1, px), Bitmap.Config.ARGB_8888);
        draw(new Canvas(b), name, px / 2f, px / 2f, px, color, stroke);
        return Tile.fixed(b);
    }

    // ===== Разбор пути SVG =====

    private static float[] nums(String s) {
        String[] a = s.split(",");
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) out[i] = Float.parseFloat(a[i].trim());
        return out;
    }

    private static final class Reader {
        final String s;
        int i;
        Reader(String s) { this.s = s; }
        void skip() { while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\n')) i++; }
        boolean number() {
            skip();
            if (i >= s.length()) return false;
            char ch = s.charAt(i);
            return ch == '-' || ch == '+' || ch == '.' || (ch >= '0' && ch <= '9');
        }
        float num() {
            skip();
            int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false, exp = false;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch >= '0' && ch <= '9') { i++; continue; }
                if (ch == '.' && !dot && !exp) { dot = true; i++; continue; }
                if ((ch == 'e' || ch == 'E') && !exp) { exp = true; i++; if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++; continue; }
                break;
            }
            return Float.parseFloat(s.substring(st, i));
        }
        // Флаг дуги может стоять слитно со следующим числом («0 0 1.5»): ровно одна цифра
        boolean flag() {
            skip();
            char ch = s.charAt(i++);
            return ch == '1';
        }
    }

    static void parse(String d, Path p) {
        Reader r = new Reader(d);
        float x = 0, y = 0, sx = 0, sy = 0, cx2 = 0, cy2 = 0;   // текущая точка, начало контура, вторая опора прошлой кривой
        char last = ' ';
        char cmd = ' ';
        while (true) {
            r.skip();
            if (r.i >= d.length()) break;
            char ch = d.charAt(r.i);
            if (Character.isLetter(ch) && ch != 'e' && ch != 'E') { cmd = ch; r.i++; }
            else if (cmd == 'M') cmd = 'L';
            else if (cmd == 'm') cmd = 'l';
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': x = ox + r.num(); y = oy + r.num(); p.moveTo(x, y); sx = x; sy = y; break;
                case 'L': x = ox + r.num(); y = oy + r.num(); p.lineTo(x, y); break;
                case 'H': x = ox + r.num(); p.lineTo(x, y); break;
                case 'V': y = oy + r.num(); p.lineTo(x, y); break;
                case 'C': {
                    float x1 = ox + r.num(), y1 = oy + r.num(), x2 = ox + r.num(), y2 = oy + r.num(), ex = ox + r.num(), ey = oy + r.num();
                    p.cubicTo(x1, y1, x2, y2, ex, ey);
                    cx2 = x2; cy2 = y2; x = ex; y = ey;
                    break;
                }
                case 'S': {
                    boolean prevC = "CcSs".indexOf(last) >= 0;
                    float x1 = prevC ? 2 * x - cx2 : x, y1 = prevC ? 2 * y - cy2 : y;
                    float x2 = ox + r.num(), y2 = oy + r.num(), ex = ox + r.num(), ey = oy + r.num();
                    p.cubicTo(x1, y1, x2, y2, ex, ey);
                    cx2 = x2; cy2 = y2; x = ex; y = ey;
                    break;
                }
                case 'Q': {
                    float x1 = ox + r.num(), y1 = oy + r.num(), ex = ox + r.num(), ey = oy + r.num();
                    p.quadTo(x1, y1, ex, ey);
                    cx2 = x1; cy2 = y1; x = ex; y = ey;
                    break;
                }
                case 'T': {
                    boolean prevQ = "QqTt".indexOf(last) >= 0;
                    float x1 = prevQ ? 2 * x - cx2 : x, y1 = prevQ ? 2 * y - cy2 : y;
                    float ex = ox + r.num(), ey = oy + r.num();
                    p.quadTo(x1, y1, ex, ey);
                    cx2 = x1; cy2 = y1; x = ex; y = ey;
                    break;
                }
                case 'A': {
                    float rx = r.num(), ry = r.num(), rot = r.num();
                    boolean large = r.flag(), sweep = r.flag();
                    float ex = ox + r.num(), ey = oy + r.num();
                    arc(p, x, y, rx, ry, rot, large, sweep, ex, ey);
                    x = ex; y = ey;
                    break;
                }
                case 'Z': p.close(); x = sx; y = sy; break;
                default: return;   // неизвестная буква: значок без хвоста лучше, чем падение
            }
            last = cmd;
            if (Character.toUpperCase(cmd) == 'Z') { r.skip(); if (r.number()) cmd = 'L'; }
        }
    }

    // Дуга SVG по концам (спецификация SVG, приложение F.6.5) кусками кубических кривых не больше 90°
    private static void arc(Path p, float x1, float y1, float rx, float ry, float rotDeg, boolean large, boolean sweep, float x2, float y2) {
        if (rx == 0 || ry == 0) { p.lineTo(x2, y2); return; }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(rotDeg), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x1 - x2) / 2.0, dy = (y1 - y2) / 2.0;
        double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        double lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lam > 1) { double s = Math.sqrt(lam); rx *= s; ry *= s; }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double co = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
        double cxp = co * rx * y1p / ry, cyp = -co * ry * x1p / rx;
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0, cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;
        double t1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double dt = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && dt > 0) dt -= 2 * Math.PI;
        else if (sweep && dt < 0) dt += 2 * Math.PI;
        int n = (int) Math.ceil(Math.abs(dt) / (Math.PI / 2) - 1e-6);
        n = Math.max(1, n);
        double step = dt / n, k = 4.0 / 3.0 * Math.tan(step / 4);
        double a = t1;
        for (int i = 0; i < n; i++) {
            double a2 = a + step;
            double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(a2), sb = Math.sin(a2);
            double px1 = rx * (ca - k * sa), py1 = ry * (sa + k * ca);
            double px2 = rx * (cb + k * sb), py2 = ry * (sb - k * cb);
            double px3 = rx * cb, py3 = ry * sb;
            p.cubicTo((float) (cx + cos * px1 - sin * py1), (float) (cy + sin * px1 + cos * py1),
                    (float) (cx + cos * px2 - sin * py2), (float) (cy + sin * px2 + cos * py2),
                    (float) (cx + cos * px3 - sin * py3), (float) (cy + sin * px3 + cos * py3));
            a = a2;
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double d = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        return d;
    }

    // Глиф для своих плиток ряда и служебных мест (материал Liquid Glass). Чужое приложение: null, его значок
    // превращает в белый силуэт Liquid.silhouette
    static String forPackage(String pkg) {
        if (pkg == null) return null;
        switch (pkg) {
            case "all": return "grid";
            case "app.glasstv.tile.youtube": return "play";
            case "app.glasstv.tile.browser": return "globe";
            case "app.glasstv.tile.lampa": return "film";
            case "app.glasstv.tile.serials": return "tv";
            case "app.glasstv.tile.vk": return "playrect";
            case "app.glasstv.tile.files": return "folder";
            case "app.glasstv.tile.twitch": return "live";
            case "app.glasstv.tile.settings": return "gear";
            case "app.glasstv.volna": return "note";
            case "app.glasstv.nightmode": return "moon";
            default: return null;
        }
    }
}
