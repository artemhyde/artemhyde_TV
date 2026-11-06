package app.glasstv.guard;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

// Настройки в JSON и обратно с типом значения: «b:true», «i:5», «l:…», «f:54.3», «s:текст». Перенос настроек
// (06.11, план Codex, блок 3: экспорт и импорт). Такой же класс в «Стороже» и «Волне»
final class Prefs {
    private Prefs() { }

    static Set<String> set(String... k) { return new HashSet<>(Arrays.asList(k)); }

    // only: только эти ключи (null: все), skip: без этих
    static JSONObject dump(SharedPreferences p, Set<String> only, Set<String> skip) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (only != null && !only.contains(k)) continue;
            if (skip != null && skip.contains(k)) continue;
            String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l"
                    : v instanceof Float ? "f" : v instanceof String ? "s" : null;
            if (t != null) o.put(k, t + ":" + v);
        }
        return o;
    }

    static int load(SharedPreferences.Editor ed, JSONObject o, Set<String> only, Set<String> skip) {
        int n = 0;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (only != null && !only.contains(k)) continue;
            if (skip != null && skip.contains(k)) continue;
            String s = o.optString(k, "");
            if (s.length() < 2 || s.charAt(1) != ':') continue;
            String v = s.substring(2);
            try {
                switch (s.charAt(0)) {
                    case 'b': ed.putBoolean(k, Boolean.parseBoolean(v)); break;
                    case 'i': ed.putInt(k, Integer.parseInt(v)); break;
                    case 'l': ed.putLong(k, Long.parseLong(v)); break;
                    case 'f': ed.putFloat(k, Float.parseFloat(v)); break;
                    case 's': ed.putString(k, v); break;
                    default: continue;
                }
                n++;
            } catch (Exception ignored) { }
        }
        return n;
    }
}
