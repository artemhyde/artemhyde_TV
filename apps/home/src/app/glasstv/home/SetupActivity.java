package app.glasstv.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Экран «Настройки «Дома»» (06.11): всё, что раньше вшивалось при сборке, теперь меняется пультом на самом ТВ.
// Город для погоды (поиск по названию в Open-Meteo), курсы, стих, стиль, какие приложения открывают плитки-ярлыки,
// возврат ряда по умолчанию. Один список на экране, OK открывает пункт, «Назад» возвращает на шаг назад.
// Открывается плиткой «Настройки «Дома»» в конце «Все приложения» (с ПК: am start -n app.glasstv.home/.SetupActivity)
public class SetupActivity extends Activity {
    float dp;
    TextView title, sub;
    LinearLayout list;
    ScrollView scroll;
    final ArrayDeque<Runnable> back = new ArrayDeque<>();
    Runnable current;
    final Handler h = new Handler(Looper.getMainLooper());

    interface Pick { void set(String value); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        Fonts.init(this);
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF17181C, 0xFF0B0B0D});
        root.setBackground(bg);
        title = Fonts.text(this, Fonts.TITLE, Fonts.light28, Fonts.WHITE);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.leftMargin = px(Grid.MARGIN);
        tl.topMargin = px(56);
        root.addView(title, tl);
        sub = Fonts.text(this, Fonts.BODY, Fonts.reg18, Fonts.WHITE_60);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(px(760), -2, Gravity.TOP | Gravity.START);
        sl.leftMargin = px(Grid.MARGIN);
        sl.topMargin = px(104);
        root.addView(sub, sl);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(8), 0, px(40));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(px(760), -1, Gravity.TOP | Gravity.START);
        ll.leftMargin = px(Grid.MARGIN);
        ll.topMargin = px(144);
        root.addView(scroll, ll);
        setContentView(root);
        open(this::main);
    }

    int px(float v) { return Math.round(v * dp); }

    // ===== Переходы =====

    void open(Runnable page) {
        if (current != null) back.push(current);
        current = page;
        page.run();
    }

    void goBack() {
        if (back.isEmpty()) { finish(); return; }
        current = back.pop();
        current.run();
    }

    void toMain() {
        back.clear();
        current = this::main;
        main();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) goBack();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    void page(String t, String s) {
        title.setText(t);
        sub.setText(s);
        sub.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
        list.removeAllViews();
        scroll.scrollTo(0, 0);
    }

    void focusFirst() {
        list.post(() -> { if (list.getChildCount() > 0) list.getChildAt(0).requestFocus(); });
    }

    // Строка списка: подпись слева, значение справа; в фокусе белая плашка и тёмный текст, как в меню плиток
    View row(String label, String value, Runnable act) {
        final GradientDrawable on = new GradientDrawable();
        on.setColor(0xFFFFFFFF);
        on.setCornerRadius(px(16));
        final TextView l = Fonts.text(this, 20, Fonts.med24, Fonts.WHITE_92);
        final TextView v = Fonts.text(this, Fonts.BODY, Fonts.reg18, Fonts.WHITE_60);
        LinearLayout r = new LinearLayout(this) {
            @Override protected void onFocusChanged(boolean f, int d, android.graphics.Rect rect) {
                super.onFocusChanged(f, d, rect);
                setBackground(f ? on : null);
                l.setTextColor(f ? 0xFF141416 : Fonts.WHITE_92);
                v.setTextColor(f ? 0xFF4A4A50 : Fonts.WHITE_60);
            }
        };
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(22), px(14), px(22), px(14));
        r.setFocusable(true);
        r.setClickable(true);
        l.setText(label);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null && !value.isEmpty()) {
            v.setText(value);
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setGravity(Gravity.END);
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
            vl.leftMargin = px(24);
            r.addView(v, vl);
        }
        r.setOnClickListener(x -> act.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(4);
        list.addView(r, lp);
        return r;
    }

    // ===== Страницы =====

    void main() {
        page("Настройки «Дома»", "Меняются здесь же, без компьютера и пересборки. «Назад»: выйти");
        row("Город для погоды", Setup.weatherOn(this) ? (Setup.city(this).isEmpty() ? "по координатам" : Setup.city(this)) : "не показывать",
                () -> open(this::cityPage));
        row("Курсы валют", Setup.ratesOn(this) ? "Нацбанк Беларуси" : "не показывать", () -> open(() -> choice(
                "Курсы валют", "Доллар, евро и сто российских рублей к белорусскому рублю, раз в несколько часов",
                new String[]{"Нацбанк Беларуси", "Не показывать"}, new String[]{"nbrb", "none"}, Setup.rates(this),
                v -> Setup.put(this, "rates", v))));
        row("Стих на экране блокировки", Setup.versesOn(this) ? "Библия" : "не показывать", () -> open(() -> choice(
                "Стих на экране блокировки", "Стих меняется утром, днём и вечером вместе с фото",
                new String[]{"Библия (Синодальный перевод)", "Не показывать"}, new String[]{"bible", "off"}, Setup.verses(this),
                v -> Setup.put(this, "verses", v))));
        row("Стиль главного экрана", Style.title(Style.get(this)), () -> open(() -> choice(
                "Стиль главного экрана", "«Графит»: ряд плиток. «Vision»: сферы по центру",
                new String[]{"Графит", "Vision"}, new String[]{Style.GRAPHITE, Style.V1}, Style.get(this),
                v -> Style.set(this, v))));
        row("Ночью экран темнее", Setup.nightTitle(Setup.night(this)), () -> open(() -> choice(
                "Ночью экран темнее", "«Сторож» убавляет подсветку вечером и возвращает утром, если яркость за день не меняли",
                new String[]{"С 22:00 до 7:00", "С 21:00 до 7:00", "С 23:00 до 7:00", "С 22:00 до 8:00", "Не затемнять"},
                new String[]{"22-7", "21-7", "23-7", "22-8", "off"}, Setup.night(this),
                v -> { Setup.put(this, "night", v); Setup.sendNight(this, v); })));
        row("Облегчённый режим", Setup.lite(this) ? "включён" : "выключен", () -> open(() -> choice(
                "Облегчённый режим", "Для слабых телевизоров: меньше движения и памяти. Часы, погода, стих и плитки те же",
                new String[]{"Выключен: все эффекты", "Включён: без частиц и наплыва фото, фото легче"}, new String[]{"off", "on"},
                Setup.lite(this) ? "on" : "off", v -> Setup.put(this, "lite", v))));
        row("Какие приложения открывают плитки", "", () -> open(this::tilesPage));
        row("Вернуть ряд главного экрана как было", "", () -> open(this::resetPage));
        focusFirst();
    }

    // Выбор одного из вариантов: выбранный помечен справа, OK запоминает и возвращает назад
    void choice(String t, String s, String[] labels, String[] values, String cur, Pick pick) {
        page(t, s);
        View first = null;
        for (int i = 0; i < labels.length; i++) {
            final String v = values[i];
            View r = row(labels[i], v.equals(cur) ? "выбрано" : "", () -> { pick.set(v); goBack(); });
            if (first == null || v.equals(cur)) first = r;
        }
        final View f = first;
        list.post(() -> { if (f != null) f.requestFocus(); });
    }

    // ===== Город =====

    void cityPage() {
        boolean on = Setup.weatherOn(this);
        page("Город для погоды", on ? "Сейчас: " + Setup.city(this) + ". Погода берётся у Open-Meteo по координатам города"
                : "Сейчас погоды на экране нет");
        row("Найти город", "", () -> open(this::searchPage));
        if (on) row("Не показывать погоду", "", () -> { Setup.noPlace(this); goBack(); });
        focusFirst();
    }

    void searchPage() {
        page("Найти город", "Название уйдёт в Open-Meteo только для поиска. Например: Казань");
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setTextSize(22);
        in.setTypeface(Fonts.reg24);
        in.setTextColor(Fonts.WHITE);
        in.setHintTextColor(0x66FFFFFF);
        in.setHint("Название города");
        in.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(0x1FFFFFFF);
        fb.setCornerRadius(px(16));
        fb.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
        in.setBackground(fb);
        in.setPadding(px(22), px(14), px(22), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        list.addView(in, lp);
        in.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                search(in.getText().toString());
                return true;
            }
            return false;
        });
        row("Искать", "", () -> search(in.getText().toString()));
        in.post(in::requestFocus);
    }

    void search(String q) {
        q = q.trim();
        if (q.isEmpty()) { sub.setText("Сначала напишите название города"); return; }
        sub.setText("Ищу «" + q + "»…");
        final String query = q;
        new Thread(() -> {
            List<String[]> found = new ArrayList<>();
            String err = null;
            try {
                JSONObject j = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(query, "UTF-8") + "&count=8&language=ru&format=json"));
                JSONArray a = j.optJSONArray("results");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String where = TextUtils.join(", ", nonEmpty(o.optString("admin1"), o.optString("country")));
                    found.add(new String[]{o.getString("name"), where,
                            String.valueOf(o.getDouble("latitude")), String.valueOf(o.getDouble("longitude"))});
                }
            } catch (Exception e) {
                err = "Open-Meteo не ответил. Проверьте интернет и попробуйте ещё раз";
            }
            final String fe = err;
            h.post(() -> {
                if (isFinishing()) return;
                if (fe != null) { sub.setText(fe); return; }
                if (found.isEmpty()) { sub.setText("Ничего не нашлось по «" + query + "». Попробуйте иначе"); return; }
                open(() -> results(query, found));
            });
        }, "home-geo").start();
    }

    void results(String q, List<String[]> found) {
        page("Город: " + q, "Выберите нужный, у одинаковых названий разные области");
        for (String[] f : found) {
            row(f[0], f[1], () -> {
                Setup.setPlace(this, f[0], Double.parseDouble(f[2]), Double.parseDouble(f[3]));
                toMain();
            });
        }
        focusFirst();
    }

    static List<String> nonEmpty(String... s) {
        List<String> out = new ArrayList<>();
        for (String x : s) if (x != null && !x.isEmpty() && !out.contains(x)) out.add(x);
        return out;
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("User-Agent", Config.USER_AGENT);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int k; (k = in.read(buf)) > 0; ) b.write(buf, 0, k);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    // ===== Плитки-ярлыки =====

    // Ярлыки, которые открывают приложение целиком (у «Фильмов» и «Сериалов» свой раздел Lampa: их не меняем)
    Map<String, String[]> shortcuts() {
        Map<String, String[]> out = new LinkedHashMap<>();   // пакет ярлыка: {подпись, приложение из ярлыка}
        PackageManager pm = getPackageManager();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), PackageManager.GET_META_DATA)) {
                Bundle md = r.activityInfo.metaData;
                String tg = md == null ? null : md.getString("target");
                String comp = md == null ? null : md.getString("component"), lampa = md == null ? null : md.getString("lampaActivity");
                if (tg == null || tg.isEmpty() || (comp != null && !comp.isEmpty()) || (lampa != null && !lampa.isEmpty())) continue;
                if (!out.containsKey(r.activityInfo.packageName))
                    out.put(r.activityInfo.packageName, new String[]{String.valueOf(r.loadLabel(pm)), tg});
            }
        }
        return out;
    }

    String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "нет на ТВ";
        }
    }

    void tilesPage() {
        page("Какие приложения открывают плитки", "Например, «Файлы» могут открывать ваш файловый менеджер");
        for (Map.Entry<String, String[]> e : shortcuts().entrySet()) {
            final String tile = e.getKey(), def = e.getValue()[1];
            String ov = Setup.tileApp(this, tile);
            row(e.getValue()[0], label(ov != null ? ov : def), () -> open(() -> appPicker(tile, e.getValue()[0], def)));
        }
        if (list.getChildCount() == 0) sub.setText("Плиток-ярлыков на этом ТВ нет");
        focusFirst();
    }

    void appPicker(String tile, String tileLabel, String def) {
        page("Плитка «" + tileLabel + "» открывает", "OK выбирает приложение");
        String cur = Setup.tileApp(this, tile);
        View sel = row("Как было: " + label(def), cur == null ? "выбрано" : "", () -> { Setup.setTileApp(this, tile, null); goBack(); });
        PackageManager pm = getPackageManager();
        Map<String, String> apps = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), PackageManager.GET_META_DATA)) {
                String p = r.activityInfo.packageName;
                Bundle md = r.activityInfo.metaData;
                boolean isTile = md != null && md.getString("target") != null;
                if (isTile || p.equals(getPackageName()) || p.equals(def) || apps.containsKey(p)) continue;
                apps.put(p, String.valueOf(r.loadLabel(pm)));
            }
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(apps.entrySet());
        sorted.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        for (Map.Entry<String, String> a : sorted) {
            View r = row(a.getValue(), a.getKey().equals(cur) ? "выбрано" : "", () -> { Setup.setTileApp(this, tile, a.getKey()); goBack(); });
            if (a.getKey().equals(cur)) sel = r;
        }
        final View f = sel;
        list.post(f::requestFocus);
    }

    // ===== Ряд по умолчанию =====

    void resetPage() {
        page("Вернуть ряд как было?", "Плитки главного экрана встанут в исходном порядке. Скрытые приложения останутся скрытыми");
        row("Нет, оставить", "", this::goBack);
        row("Да, вернуть", "", () -> {
            getSharedPreferences(HomeActivity.DOCK_PREFS, MODE_PRIVATE).edit().remove(HomeActivity.DOCK_KEY).apply();
            sub.setText("Готово: ряд вернётся при выходе на главный экран");
            toMain();
        });
        focusFirst();
    }

}
