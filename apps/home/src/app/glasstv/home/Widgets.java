package app.glasstv.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Настройка виджетов прямо в «Оформлении» (10.11, просьба: «чтобы погода, нацбанк и прочее настраивались сразу же
// в выборе этих виджетов»). OK на виджете открывает стеклянную панель с выбором: город для погоды (поиск тут же, с пульта
// или клавиатуры), банк или криптовалюта для курсов, цитаты или Библия, приложение для музыки, записки с телефона.
// В каждой панели есть «Не показывать». Выбор сразу на экране, панель закрывается
final class Widgets {
    private Widgets() { }

    interface Done { void done(); }

    static final String HINT = "OK: выбрать  ·  «Назад»: закрыть";

    static void open(HomeActivity a, String which, Done done) {
        switch (which) {
            case "weather": weather(a, done); break;
            case "rates": rates(a, done); break;
            case "verse": verse(a, done); break;
            case "music": music(a, done); break;
            case "notes": Notes.sheet(a, done); break;
            default: break;
        }
    }

    // ===== Общее =====

    static Sheet.Item chip(String key, String label) { return new Sheet.Item(key, label).chip(); }

    // Панель из кнопок-надписей: у нынешнего выбора отметка, фокус сразу на нём
    static Sheet chips(HomeActivity a, String title, String note, List<Sheet.Item> items, int cols, float cellW, String cur) {
        int at = 0;
        for (int i = 0; i < items.size(); i++) {
            Sheet.Item it = items.get(i);
            if (it.key.equals(cur)) { it.checkable(true); at = i; }
        }
        Sheet s = new Sheet(a, title, HINT, items, cols, a.vision, cellW, 0);
        s.focusAt = at;
        if (note != null) s.content = note(a, note);
        return s;
    }

    // Пояснение под заголовком панели
    static TextView note(HomeActivity a, String text) {
        TextView t = Fonts.text(a, 15, Fonts.reg18, 0xB3FFFFFF);
        t.setText(text);
        t.setFontFeatureSettings("");
        t.setLineSpacing(0, 1.12f);
        t.setMaxWidth(Math.round(560 * a.dp));
        return t;
    }

    // ===== Погода =====

    static void weather(HomeActivity a, Done done) {
        final boolean on = Setup.weatherOn(a);
        String city = Setup.city(a);
        List<Sheet.Item> items = new ArrayList<>();
        if (!city.isEmpty() || on) items.add(chip("keep", city.isEmpty() ? "Как сейчас" : city));
        items.add(chip("find", "Другой город…"));
        items.add(chip("off", "Не показывать"));
        Sheet s = chips(a, "Погода", "Город для погоды на главном экране и на блокировке", items, 1, 380, on ? "keep" : "off");
        s.onPick = (sh, it, i) -> {
            if (it == null) return;
            sh.dismiss(false);
            switch (it.key) {
                case "keep":
                    if (!on) { Setup.sp(a).edit().putBoolean("hasPlace", true).apply(); Setup.bump(a); done.done(); }
                    break;
                case "off":
                    if (on) { Setup.noPlace(a); done.done(); }
                    break;
                default:
                    a.askText("Город для погоды", "", "Например: Казань",
                            "Наберите название и нажмите «Готово». Оно уйдёт в Open-Meteo только для поиска", q -> search(a, q, done));
            }
        };
        a.showSheetBlurred(s);
    }

    private static void search(HomeActivity a, String q, Done done) {
        a.showFlash("Ищу «" + q + "»…");
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<String[]> found = new ArrayList<>();
            String err = null;
            try {
                JSONObject j = new JSONObject(SetupActivity.get("https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(q, "UTF-8") + "&count=8&language=ru&format=json"));
                JSONArray arr = j.optJSONArray("results");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String where = android.text.TextUtils.join(", ", SetupActivity.nonEmpty(o.optString("admin1"), o.optString("country")));
                    found.add(new String[]{o.getString("name"), where, String.valueOf(o.getDouble("latitude")),
                            String.valueOf(o.getDouble("longitude")), o.optString("country_code", "")});
                }
            } catch (Exception e) {
                err = "Open-Meteo не ответил. Проверьте интернет и попробуйте ещё раз";
            }
            final String fe = err;
            h.post(() -> {
                if (a.isDestroyed()) return;
                if (fe != null) { a.showFlash(fe); return; }
                if (found.isEmpty()) { a.showFlash("Ничего не нашлось по «" + q + "». Попробуйте иначе"); return; }
                List<Sheet.Item> items = new ArrayList<>();
                for (int i = 0; i < found.size(); i++) {
                    String[] f = found.get(i);
                    items.add(chip(String.valueOf(i), f[1].isEmpty() ? f[0] : f[0] + ", " + f[1]));
                }
                Sheet s = new Sheet(a, "Город: " + q, HINT, items, 1, a.vision, 560, 0);
                s.content = note(a, "Выберите нужный: у одинаковых названий разные области");
                s.onPick = (sh, it, k) -> {
                    if (it == null) return;
                    sh.dismiss(false);
                    String[] f = found.get(Integer.parseInt(it.key));
                    Setup.setPlace(a, f[0], Double.parseDouble(f[2]), Double.parseDouble(f[3]), f[4]);
                    done.done();
                };
                a.showSheetBlurred(s);
            });
        }, "home-geo").start();
    }

    // ===== Курсы =====

    static void rates(HomeActivity a, Done done) {
        String cc = Setup.country(a);
        List<Sheet.Item> items = new ArrayList<>();
        items.add(chip(Rates.AUTO, cc.isEmpty() ? "По стране города" : "По стране: " + Studio.bankShort(Rates.source(Rates.AUTO, cc))));
        items.add(chip(Rates.NBRB, "Беларусь"));
        items.add(chip(Rates.CBR, "Россия"));
        items.add(chip(Rates.NBU, "Украина"));
        items.add(chip(Rates.NBK, "Казахстан"));
        items.add(chip(Rates.ECB, "Евро"));
        items.add(chip(Rates.CRYPTO, "Криптовалюта"));
        items.add(chip(Rates.NONE, "Не показывать"));
        final String cur = Setup.ratesOn(a) ? Setup.rates(a) : Rates.NONE;
        Sheet s = chips(a, "Курсы", "Сколько своих денег за доллар, евро и ещё одну валюту. Криптовалюта: биткоин, эфир и солана в долларах",
                items, 2, 280, cur);
        s.onPick = (sh, it, i) -> {
            if (it == null) return;
            sh.dismiss(false);
            if (it.key.equals(cur)) return;
            Setup.put(a, "rates", it.key);
            done.done();
        };
        a.showSheetBlurred(s);
    }

    // ===== Цитата =====

    static void verse(HomeActivity a, Done done) {
        List<Sheet.Item> items = new ArrayList<>();
        items.add(chip("quotes", "Цитаты о жизни"));
        items.add(chip("bible", "Библия"));
        items.add(chip("off", "Не показывать"));
        String v = Setup.verses(a);
        final String cur = "custom".equals(v) ? "bible" : v;
        Sheet s = chips(a, "Цитата на блокировке", "Меняется утром, днём и вечером вместе с фото. Утром бодрая, вечером спокойная",
                items, 1, 380, cur);
        s.onPick = (sh, it, i) -> {
            if (it == null) return;
            sh.dismiss(false);
            if (it.key.equals(cur)) return;
            Setup.put(a, "verses", it.key);
            done.done();
        };
        a.showSheetBlurred(s);
    }

    // ===== Музыка =====

    static final Set<String> MUSIC = SetupActivity.MUSIC;

    static void music(HomeActivity a, Done done) {
        List<Sheet.Item> items = new ArrayList<>();
        if (Setup.launchable(a, Setup.VOLNA)) items.add(chip("volna", "«Волна»"));
        Set<String> byCategory = new HashSet<>();
        for (ResolveInfo r : a.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0))
            byCategory.add(r.activityInfo.packageName);
        List<String[]> all = launchable(a);
        for (String[] app : all) if (MUSIC.contains(app[0]) || byCategory.contains(app[0])) items.add(chip(app[0], app[1]));
        items.add(chip("other", "Другое приложение…"));
        items.add(chip("off", "Не показывать"));
        String m = Setup.music(a), app = Setup.musicApp(a);
        final String cur = "off".equals(m) ? "off" : app == null ? "" : Setup.VOLNA.equals(app) ? "volna" : app;
        // Выбрано приложение не из списка музыки: оно тоже в панели, с отметкой
        boolean listed = false;
        for (Sheet.Item it : items) if (it.key.equals(cur)) listed = true;
        if (!listed && !cur.isEmpty()) items.add(0, chip(cur, a.appLabel(cur)));
        Sheet s = chips(a, "Музыка", "Что открывают капсула на главном экране и таблетка в полосе шестерёнки", items, 2, 280, cur);
        s.onPick = (sh, it, i) -> {
            if (it == null) return;
            sh.dismiss(false);
            if ("other".equals(it.key)) { otherApp(a, all, cur, done); return; }
            if (it.key.equals(cur)) return;
            Setup.setMusic(a, it.key);
            done.done();
        };
        a.showSheetBlurred(s);
    }

    private static void otherApp(HomeActivity a, List<String[]> all, String cur, Done done) {
        List<Sheet.Item> items = new ArrayList<>();
        for (String[] app : all) if (!app[0].equals(Setup.VOLNA)) items.add(chip(app[0], app[1]));
        if (items.isEmpty()) { a.showFlash("Других приложений нет"); return; }
        Sheet s = chips(a, "Музыка: приложение", null, items, 3, 220, cur);
        s.onPick = (sh, it, i) -> {
            if (it == null) return;
            sh.dismiss(false);
            if (it.key.equals(cur)) return;
            Setup.setMusic(a, it.key);
            done.done();
        };
        a.showSheetBlurred(s);
    }

    // Приложения, которые открываются с пульта, по названию: без самого «Дома» и без плиток-ярлыков. {пакет, подпись}
    static List<String[]> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> apps = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), PackageManager.GET_META_DATA)) {
                String p = r.activityInfo.packageName;
                Bundle md = r.activityInfo.metaData;
                boolean isTile = md != null && md.getString("target") != null;
                if (isTile || p.equals(c.getPackageName()) || apps.containsKey(p)) continue;
                apps.put(p, String.valueOf(r.loadLabel(pm)));
            }
        }
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, String> e : apps.entrySet()) out.add(new String[]{e.getKey(), e.getValue()});
        Collections.sort(out, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        return out;
    }
}
