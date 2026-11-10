package app.glasstv.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Папки в ряду (10.11, раздел «Оформление», «Ряд и папки»; просьба первого пользователя: «папку «Сериалы», куда можно
// самому добавлять нужные приложения»). Папка стоит в ряду как обычная плитка (в порядке ряда запись «folder:<id>»),
// внутри приложения по порядку. Приложение лежит в одном месте: в ряду, в одной папке или во «Всех приложениях».
// Хранится в настройках ряда (dock), ключ folders, JSON: [{"id":"f1a2","name":"Сериалы","apps":["пакет", …]}].
// Едет в файле переноса вместе с порядком ряда (Transfer)
final class Folders {
    private Folders() {}

    static final String KEY = "folders", PREFIX = "folder:";

    static final class Folder {
        final String id;
        String name;
        final List<String> apps = new ArrayList<>();
        Folder(String id, String name) { this.id = id; this.name = name; }
        String key() { return PREFIX + id; }
    }

    // Названия на выбор при создании; своё можно ввести с пульта
    static final String[] NAMES = {"Сериалы", "Фильмы", "Музыка", "Дети", "Спорт", "Игры", "Новости", "Разное"};

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(HomeActivity.DOCK_PREFS, Context.MODE_PRIVATE); }

    static String raw(Context c) { return sp(c).getString(KEY, "[]"); }

    static List<Folder> all(Context c) {
        List<Folder> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw(c));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Folder f = new Folder(o.getString("id"), o.optString("name", "Папка"));
                JSONArray ap = o.optJSONArray("apps");
                if (ap != null) for (int k = 0; k < ap.length(); k++) {
                    String p = ap.optString(k, "");
                    if (!p.isEmpty() && !f.apps.contains(p)) f.apps.add(p);
                }
                out.add(f);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static Folder get(Context c, String id) {
        for (Folder f : all(c)) if (f.id.equals(id)) return f;
        return null;
    }

    static void save(Context c, List<Folder> list) {
        JSONArray a = new JSONArray();
        try {
            for (Folder f : list) {
                JSONObject o = new JSONObject();
                o.put("id", f.id).put("name", f.name);
                JSONArray ap = new JSONArray();
                for (String p : f.apps) ap.put(p);
                o.put("apps", ap);
                a.put(o);
            }
        } catch (Exception ignored) { }
        sp(c).edit().putString(KEY, a.toString()).apply();
    }

    static Folder create(Context c, String name, List<String> apps) {
        List<Folder> list = all(c);
        Folder f = new Folder(Long.toString(System.currentTimeMillis(), 36), name);
        // Приложение лежит в одной папке: из прежних его убираем
        for (Folder o : list) o.apps.removeAll(apps);
        f.apps.addAll(new LinkedHashSet<>(apps));
        list.add(f);
        save(c, list);
        return f;
    }

    static void update(Context c, Folder f) {
        List<Folder> list = all(c);
        for (int i = 0; i < list.size(); i++) {
            Folder o = list.get(i);
            if (o.id.equals(f.id)) list.set(i, f);
            else o.apps.removeAll(f.apps);
        }
        save(c, list);
    }

    static void delete(Context c, String id) {
        List<Folder> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        save(c, list);
    }

    static void clear(Context c) { sp(c).edit().remove(KEY).apply(); }

    // Все приложения во всех папках: во «Всех приложениях» их нет, они на главном экране
    static Set<String> apps(Context c) {
        Set<String> out = new LinkedHashSet<>();
        for (Folder f : all(c)) out.addAll(f.apps);
        return out;
    }

    static boolean isFolder(String dockKey) { return dockKey != null && dockKey.startsWith(PREFIX); }

    static String idOf(String dockKey) { return dockKey.substring(PREFIX.length()); }
}
