package app.glasstv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Данные для главного экрана и заставки: погода города из Config, курсы Нацбанка и стих (если включены в Config) и фото.
// Фото и стих меняются три раза в день: утро 06:00–12:00, день 12:00–18:00, вечер и ночь 18:00–06:00.
// Подборка фото лежит в приложении (res/raw/photos.json), сами файлы качаются с Wikimedia Commons.
// Всё кешируется: без интернета показывается последнее, что удалось получить
final class Data {
    interface Listener { void onData(); }

    static final class Photo {
        final String id, url, caption, credit;
        final File file;
        Photo(String id, String url, String caption, String credit, File file) {
            this.id = id; this.url = url; this.caption = caption; this.credit = credit; this.file = file;
        }
        boolean ready() { return file.length() > MIN_BYTES; }
    }

    private static final int MIN_BYTES = 50_000;            // меньше: скачалась ошибка, а не фото
    private static final int MAX_BYTES = 8_000_000;
    private static final int MAX_FILES = 6;                 // больше фото на диске ТВ не держим
    private static final long RETRY_MS = 10 * 60_000L;      // после неудачи то же фото пробуем не раньше чем через 10 минут

    private static Data instance;

    static Data get(Context c) {
        if (instance == null) instance = new Data(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences p;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Random rnd = new Random();

    // Погода
    double temp = Double.NaN, feels = Double.NaN, tMax = Double.NaN, tMin = Double.NaN;
    int code = -1;
    boolean day = true;
    // Курсы: рублей за 1 USD, 1 EUR, 100 RUB
    double usd = Double.NaN, eur = Double.NaN, rub = Double.NaN;
    // Стих
    String verse = "", verseRef = "";

    private final List<String[]> verses = new ArrayList<>();

    // Что включено, решают настройки на ТВ (Setup, 06.11; значения сборки Config там только начальные):
    // без города нет погоды, без курсов нет строки курсов, без стихов нет стиха

    // Фото: три списка (утро, день, вечер) и у каждого своя очередь показа
    private final List<List<Photo>> bySlot = new ArrayList<>();
    private final Rotation[] rot = new Rotation[3];
    private final Object photoLock = new Object();
    private final Map<String, Long> failedAt = new HashMap<>();

    private Data(Context c) {
        app = c;
        p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        loadVerses();
        loadPhotoList();
        restore();
        nextVerse();
        refresh();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() { main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); }); }

    // ===== Стихи: подборка из Синодального перевода лежит в приложении =====

    // Подборка грузится всегда (она в самом приложении): стих можно включить на ТВ без перезапуска
    private void loadVerses() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(app.getResources().openRawResource(R.raw.verses), StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            JSONArray a = new JSONArray(b.toString());
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                verses.add(new String[]{o.getString("text"), o.getString("ref"), o.optString("slot", "")});
            }
        } catch (Exception e) {
            Log.w("home", "стихи не прочитались", e);
        }
    }

    // Стих меняется вместе с фото: утром, днём и вечером. Внутри одного времени суток вызов ничего не меняет,
    // так что его можно звать когда угодно. true, если стих на экране теперь другой.
    // Стих берётся под настроение времени суток (поле slot в verses.json: morning, day, evening; 27.10):
    // утром про надежду и силу на день, днём про путь и мудрость, вечером про мир, отдых и защиту
    static final String[] SLOT_NAMES = {"morning", "day", "evening"};

    boolean nextVerse() {
        if (verses.isEmpty()) return false;
        Calendar now = Calendar.getInstance();
        String key = Rotation.keyOf(now);
        int idx = p.getInt("verse", -1);
        String slot = SLOT_NAMES[Rotation.slotOf(now)];
        // Стих не своего времени суток (выбран до разметки) тоже меняется сразу
        boolean wrong = idx >= 0 && idx < verses.size() && !verses.get(idx)[2].isEmpty() && !slot.equals(verses.get(idx)[2]);
        if (!key.equals(p.getString("verseKey", "")) || idx < 0 || idx >= verses.size() || wrong) {
            List<Integer> pool = new ArrayList<>();
            for (int i = 0; i < verses.size(); i++) if (slot.equals(verses.get(i)[2]) && i != idx) pool.add(i);
            if (pool.isEmpty()) for (int i = 0; i < verses.size(); i++) if (i != idx) pool.add(i);   // без разметки: любой
            idx = pool.isEmpty() ? 0 : pool.get(rnd.nextInt(pool.size()));
            p.edit().putInt("verse", idx).putString("verseKey", key).apply();
        }
        boolean changed = !verses.get(idx)[0].equals(verse);
        verse = verses.get(idx)[0];
        verseRef = verses.get(idx)[1];
        return changed;
    }

    // ===== Обновление из интернета =====

    // Человек сменил на ТВ город или курсы (Setup): цифры прежнего города не показываем, грузим заново
    void setupChanged() {
        temp = feels = tMax = tMin = Double.NaN;
        code = -1;
        usd = eur = rub = Double.NaN;
        p.edit().remove("weatherAt").remove("ratesAt").remove("weatherFailAt").remove("ratesFailAt").apply();
        refresh();
        changed();
    }

    // Без связи (план Codex, блок 6): сроки и пометки в Fresh. Экран зовёт refresh() раз в минуту, в сеть идём по Fresh.due
    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();

    void refresh() {
        if (!refreshing.compareAndSet(false, true)) return;   // прошлый заход ещё ждёт сеть
        long now = System.currentTimeMillis();
        ex.execute(() -> {
            try {
                boolean any = false;
                if (Setup.weatherOn(app) && Fresh.due(now, weatherAt(), p.getLong("weatherFailAt", 0), Fresh.WEATHER_EVERY, Double.isNaN(temp))) { loadWeather(); any = true; }
                if (Setup.ratesOn(app) && Fresh.due(now, ratesAt(), p.getLong("ratesFailAt", 0), Fresh.RATES_EVERY, Double.isNaN(usd))) { loadRates(); any = true; }
                any |= loadPhotos();
                if (any) { save(); changed(); }
            } finally {
                refreshing.set(false);
            }
        });
    }

    long weatherAt() { return p.getLong("weatherAt", 0); }
    long ratesAt() { return p.getLong("ratesAt", 0); }
    int weatherState(long now) { return Fresh.weather(now, weatherAt(), p.getLong("weatherFailAt", 0), Double.isNaN(temp)); }
    int ratesState(long now) { return Fresh.rates(now, ratesAt(), p.getLong("ratesFailAt", 0), Double.isNaN(usd)); }

    // Только фото: дёшево, если всё уже скачано. Экран зовёт это раз в минуту, чтобы фото следующего
    // времени суток успело лечь на диск заранее
    void prefetch() {
        ex.execute(() -> { if (loadPhotos()) changed(); });
    }

    private boolean loadWeather() {
        try {
            // Часовой пояс Open-Meteo определяет сам по координатам
            JSONObject j = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude="
                    + String.format(Locale.US, "%.4f", Setup.lat(app)) + "&longitude=" + String.format(Locale.US, "%.4f", Setup.lon(app))
                    + "&current=temperature_2m,apparent_temperature,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                    + "&timezone=auto&forecast_days=1"));
            JSONObject cur = j.getJSONObject("current");
            temp = cur.getDouble("temperature_2m");
            feels = cur.getDouble("apparent_temperature");
            code = cur.getInt("weather_code");
            day = cur.optInt("is_day", 1) == 1;
            JSONObject d = j.getJSONObject("daily");
            tMax = d.getJSONArray("temperature_2m_max").getDouble(0);
            tMin = d.getJSONArray("temperature_2m_min").getDouble(0);
            p.edit().putLong("weatherAt", System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            Log.w("home", "погода не загрузилась", e);
            p.edit().putLong("weatherFailAt", System.currentTimeMillis()).apply();
            return false;
        }
    }

    private boolean loadRates() {
        try {
            usd = rate("USD");
            eur = rate("EUR");
            rub = rate("RUB");
            p.edit().putLong("ratesAt", System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            Log.w("home", "курсы не загрузились", e);
            p.edit().putLong("ratesFailAt", System.currentTimeMillis()).apply();
            return false;
        }
    }

    private double rate(String cur) throws Exception {
        JSONObject j = new JSONObject(get("https://api.nbrb.by/exrates/rates/" + cur + "?parammode=2"));
        return j.getDouble("Cur_OfficialRate");
    }

    // ===== Фото: подборка по времени суток =====

    private File photoDir() {
        File dir = new File(app.getFilesDir(), "photos");
        dir.mkdirs();
        return dir;
    }

    // Подборка из res/raw/photos.json: id, slot (morning, day, evening), url, caption, author, license
    private void loadPhotoList() {
        for (int i = 0; i < 3; i++) bySlot.add(new ArrayList<>());
        try (BufferedReader r = new BufferedReader(new InputStreamReader(app.getResources().openRawResource(R.raw.photos), StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            JSONArray a = new JSONObject(b.toString()).getJSONArray("photos");
            File dir = photoDir();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                int s = slotIndex(o.optString("slot"));
                if (s < 0) continue;
                String id = o.getString("id");
                String author = o.optString("author"), lic = o.optString("license");
                String credit = author.isEmpty() ? "" : "Фото: " + author + (lic.isEmpty() ? "" : ", " + lic);
                bySlot.get(s).add(new Photo(id, o.getString("url"), o.optString("caption"), credit, new File(dir, id + ".jpg")));
            }
        } catch (Exception e) {
            Log.w("home", "подборка фото не прочиталась", e);
        }
        for (int s = 0; s < 3; s++) {
            List<String> ids = new ArrayList<>();
            for (Photo ph : bySlot.get(s)) ids.add(ph.id);
            rot[s] = new Rotation(ids, p.getString("phQ" + s, ""), p.getString("phK" + s, ""), rnd);
        }
    }

    private static int slotIndex(String name) {
        return "morning".equals(name) ? Rotation.MORNING : "day".equals(name) ? Rotation.DAY : "evening".equals(name) ? Rotation.EVENING : -1;
    }

    private Photo byId(int slot, String id) {
        if (id == null) return null;
        for (Photo ph : bySlot.get(slot)) if (ph.id.equals(id)) return ph;
        return null;
    }

    // Привести очередь нынешнего времени суток к «сейчас»; в начале утра, дня или вечера она сдвигается на одно фото
    private int syncSlot() {
        Calendar c = Calendar.getInstance();
        int s = Rotation.slotOf(c);
        if (rot[s].ensure(Rotation.keyOf(c))) p.edit().putString("phQ" + s, rot[s].saved()).putString("phK" + s, rot[s].key()).apply();
        return s;
    }

    // Фото для нынешнего времени суток. Если файл ещё не скачан, даётся любое готовое фото этого же времени суток,
    // потом других (на случай без сети); совсем пусто: null
    private Photo pick(int offset) {
        synchronized (photoLock) {
            int s = syncSlot();
            Photo want = byId(s, rot[s].peek(offset));
            if (want != null && want.ready()) return want;
            for (int k = 0; k < 3; k++)
                for (Photo ph : bySlot.get((s + k) % 3)) if (ph.ready()) return ph;
            return null;
        }
    }

    // Фото нынешнего времени суток: до начала следующего оно не меняется
    Photo currentPhoto() { return pick(0); }

    // Старое имя: листать фото больше нечем, оно привязано ко времени суток
    Photo nextPhoto() { return pick(0); }

    // Другое фото того же времени суток (для заставки с отдельной картинкой)
    Photo peekPhoto(int offset) { return pick(offset); }

    // Что должно лежать на диске: фото сейчас и по одному на следующие два времени суток
    private List<Photo> wanted() {
        Calendar c = Calendar.getInstance();
        int s = syncSlot();
        List<Photo> w = new ArrayList<>();
        Photo cur = byId(s, rot[s].current());
        if (cur != null) w.add(cur);
        for (int k = 1; k <= 2; k++) {
            int o = (s + k) % 3;
            Photo up = byId(o, rot[o].upcoming(Rotation.nextKey(c, o)));
            if (up != null && !w.contains(up)) w.add(up);
        }
        return w;
    }

    private boolean loadPhotos() {
        List<Photo> want;
        synchronized (photoLock) { want = wanted(); }
        boolean got = false;
        long now = System.currentTimeMillis();
        for (Photo ph : want) {
            if (ph.ready()) continue;
            Long bad = failedAt.get(ph.id);
            if (bad != null && now - bad < RETRY_MS) continue;
            if (download(ph)) { failedAt.remove(ph.id); got = true; } else failedAt.put(ph.id, now);
        }
        trim(want);
        return got;
    }

    // Файл качается рядом под другим именем и ложится на место целиком: оборванная загрузка не оставит битое фото
    private boolean download(Photo ph) {
        File part = new File(ph.file.getPath() + ".part");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(ph.url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", Config.USER_AGENT);
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            long total = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > MAX_BYTES) throw new Exception("слишком большой файл");
                }
            }
            boolean jpeg;
            try (FileInputStream head = new FileInputStream(part)) { jpeg = head.read() == 0xFF && head.read() == 0xD8; }
            if (!jpeg || total < MIN_BYTES) throw new Exception("это не фото (" + total + " байт)");
            if (ph.file.exists()) ph.file.delete();
            if (!part.renameTo(ph.file)) throw new Exception("не удалось переименовать");
            return true;
        } catch (Exception e) {
            Log.w("home", "фото " + ph.id + " не скачалось: " + e.getMessage());
            part.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // Не больше MAX_FILES фото на диске: нужные остаются всегда, остальное по свежести как запас на случай без сети.
    // Файлы от прежней ленты Bing (их имён нет в подборке) и обрывки загрузки удаляются
    private void trim(List<Photo> want) {
        File[] fs = photoDir().listFiles();
        if (fs == null) return;
        Set<String> all = new HashSet<>(), keep = new HashSet<>();
        for (List<Photo> l : bySlot) for (Photo ph : l) all.add(ph.file.getName());
        for (Photo ph : want) keep.add(ph.file.getName());
        List<File> spare = new ArrayList<>();
        int kept = 0;
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".part")) { f.delete(); continue; }
            if (keep.contains(n)) { if (f.length() > MIN_BYTES) kept++; continue; }
            if (all.contains(n)) spare.add(f);
        }
        Collections.sort(spare, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        int room = Math.max(0, MAX_FILES - kept);
        for (int i = room; i < spare.size(); i++) spare.get(i).delete();
        for (File f : fs) if (!all.contains(f.getName())) f.delete();
    }

    static Bitmap decode(File f) { return decode(f, 1); }

    // sample 2: вдвое меньше по каждой стороне, вчетверо меньше памяти (облегчённый режим)
    static Bitmap decode(File f, int sample) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // вдвое меньше памяти, для фото незаметно
            o.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== Кеш =====

    // Числа без значения (нет города, курсы выключены) остаются NaN, а put(ключ, NaN) бросает исключение и роняет запись всего кеша.
    // Поэтому пишем только конечные: пропущенный ключ restore() прочитает как NaN, то есть как «нет данных»
    private static void putFinite(JSONObject j, String key, double v) throws JSONException {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) j.put(key, v);
    }

    private void save() {
        try {
            JSONObject j = new JSONObject();
            putFinite(j, "temp", temp);
            putFinite(j, "feels", feels);
            putFinite(j, "tMax", tMax);
            putFinite(j, "tMin", tMin);
            j.put("code", code).put("day", day);
            putFinite(j, "usd", usd);
            putFinite(j, "eur", eur);
            putFinite(j, "rub", rub);
            p.edit().putString("cache", j.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void restore() {
        try {
            JSONObject j = new JSONObject(p.getString("cache", "{}"));
            temp = j.optDouble("temp", Double.NaN);
            feels = j.optDouble("feels", Double.NaN);
            tMax = j.optDouble("tMax", Double.NaN);
            tMin = j.optDouble("tMin", Double.NaN);
            code = j.optInt("code", -1);
            day = j.optBoolean("day", true);
            usd = j.optDouble("usd", Double.NaN);
            eur = j.optDouble("eur", Double.NaN);
            rub = j.optDouble("rub", Double.NaN);
        } catch (Exception ignored) { }
    }

    // ===== Подписи =====

    String weatherText() {
        if (code < 0) return "";
        return describe(code);
    }

    static String describe(int c) {
        if (c == 0) return "ясно";
        if (c == 1) return "почти ясно";
        if (c == 2) return "переменная облачность";
        if (c == 3) return "пасмурно";
        if (c == 45 || c == 48) return "туман";
        if (c >= 51 && c <= 57) return "морось";
        if (c >= 61 && c <= 67) return "дождь";
        if (c >= 71 && c <= 77) return "снег";
        if (c >= 80 && c <= 82) return "ливень";
        if (c == 85 || c == 86) return "снегопад";
        if (c >= 95) return "гроза";
        return "";
    }

    static String deg(double t) {
        if (Double.isNaN(t)) return "";
        long r = Math.round(t);
        return (r > 0 ? "+" : r < 0 ? "−" : "") + Math.abs(r) + "°";
    }

    // Курс для экрана: два знака, «2,95»
    static String rate2(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.forLanguageTag("ru"), "%.2f", v);
    }

    static String money(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.forLanguageTag("ru"), "%.3f", v);
    }

    // ===== Сеть =====

    private static String get(String url) throws Exception {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", Config.USER_AGENT);
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode() + " " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
