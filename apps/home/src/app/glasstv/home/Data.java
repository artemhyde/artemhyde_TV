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
import java.net.URLEncoder;
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

// Данные для главного экрана и заставки: погода города, курсы своей страны (Rates) и стих (если включены) и фото.
// Фото и стих меняются три раза в день: утро 06:00–12:00, день 12:00–18:00, вечер и ночь 18:00–06:00.
// Подборка фото лежит в приложении (res/raw/photos.json), сами файлы качаются с Wikimedia Commons.
// Всё кешируется: без интернета показывается последнее, что удалось получить
final class Data {
    interface Listener { void onData(); }

    static final class Photo {
        final String id, url, caption, credit;
        final File file;
        // Обои без подборки (10.11, «Оформление»): градиент рисуется сам, своё фото читается из своего файла (Walls)
        final Walls.Maker maker;
        final boolean still;   // неподвижные обои (градиент): без медленного наплыва, экрану нечего перерисовывать
        Photo(String id, String url, String caption, String credit, File file) {
            this(id, url, caption, credit, file, null, false);
        }
        Photo(String id, String url, String caption, String credit, File file, Walls.Maker maker, boolean still) {
            this.id = id; this.url = url; this.caption = caption; this.credit = credit; this.file = file;
            this.maker = maker; this.still = still;
        }
        boolean ready() { return maker != null || (file != null && file.length() > MIN_BYTES); }
        Bitmap load(int sample) { return maker != null ? maker.make(sample) : decode(file, sample); }
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
    // Курсы своей страны (Rates, с 1.1.1): позиции строки и источник, из которого они
    volatile List<Rates.Item> rates = new ArrayList<>();
    volatile String ratesSrc = "";
    // Стих
    String verse = "", verseRef = "";

    private final List<String[]> verses = new ArrayList<>(), quotes = new ArrayList<>();

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

    // Подборки грузятся всегда (они в самом приложении): стих или цитату можно включить на ТВ без перезапуска.
    // 10.11: вторая подборка, цитаты о жизни и поддержке (отзыв: «библейский стих нетипично для обычных людей»)
    private void loadVerses() {
        read(R.raw.verses, verses, "стихи");
        read(R.raw.quotes, quotes, "цитаты");
    }

    private void read(int res, List<String[]> into, String what) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(app.getResources().openRawResource(res), StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            JSONArray a = new JSONArray(b.toString());
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                into.add(new String[]{o.getString("text"), o.optString("ref", ""), o.optString("slot", "")});
            }
        } catch (Exception e) {
            Log.w("home", what + " не прочитались", e);
        }
    }

    // Стих меняется вместе с фото: утром, днём и вечером. Внутри одного времени суток вызов ничего не меняет,
    // так что его можно звать когда угодно. true, если стих на экране теперь другой.
    // Стих берётся под настроение времени суток (поле slot в verses.json: morning, day, evening; 27.10):
    // утром про надежду и силу на день, днём про путь и мудрость, вечером про мир, отдых и защиту
    static final String[] SLOT_NAMES = {"morning", "day", "evening"};

    boolean nextVerse() {
        // Какая подборка на экране: цитаты или стихи (Setup.verses). У каждой свой номер и своё время выбора
        boolean q = Setup.quotes(app);
        List<String[]> list = q ? quotes : verses;
        if (list.isEmpty()) return false;
        String ik = q ? "quote" : "verse", kk = ik + "Key";
        Calendar now = Calendar.getInstance();
        String key = Rotation.keyOf(now);
        int idx = p.getInt(ik, -1);
        String slot = SLOT_NAMES[Rotation.slotOf(now)];
        // Стих не своего времени суток (выбран до разметки) тоже меняется сразу
        boolean wrong = idx >= 0 && idx < list.size() && !list.get(idx)[2].isEmpty() && !slot.equals(list.get(idx)[2]);
        if (!key.equals(p.getString(kk, "")) || idx < 0 || idx >= list.size() || wrong) {
            List<Integer> pool = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) if (slot.equals(list.get(i)[2]) && i != idx) pool.add(i);
            if (pool.isEmpty()) for (int i = 0; i < list.size(); i++) if (i != idx) pool.add(i);   // без разметки: любой
            idx = pool.isEmpty() ? 0 : pool.get(rnd.nextInt(pool.size()));
            p.edit().putInt(ik, idx).putString(kk, key).apply();
        }
        boolean changed = !list.get(idx)[0].equals(verse);
        verse = list.get(idx)[0];
        verseRef = list.get(idx)[1];
        return changed;
    }

    // ===== Обновление из интернета =====

    // Человек сменил на ТВ город или курсы (Setup): цифры прежнего города не показываем, грузим заново
    void setupChanged() {
        temp = feels = tMax = tMin = Double.NaN;
        code = -1;
        rates = new ArrayList<>();
        ratesSrc = "";
        p.edit().remove("weatherAt").remove("ratesAt").remove("weatherFailAt").remove("ratesFailAt")
                .remove("weatherWhy").remove("ratesWhy").apply();
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
                long every = Rates.CRYPTO.equals(Setup.rates(app)) ? Fresh.CRYPTO_EVERY : Fresh.RATES_EVERY;
                if (Setup.ratesOn(app) && Fresh.due(now, ratesAt(), p.getLong("ratesFailAt", 0), every, rates.isEmpty())) { loadRates(); any = true; }
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
    int ratesState(long now) { return Fresh.rates(now, ratesAt(), p.getLong("ratesFailAt", 0), rates.isEmpty()); }

    // Причина последнего сбоя (10.11: у первого пользователя «нет связи», а интернет на ТВ был). Первая строка для экрана
    // («нет связи», «проверьте время на ТВ», «сервер недоступен»), вторая для «Настроек «Дома»» и команды с ПК
    String weatherProblem() { return part(p.getString("weatherWhy", ""), 0, "нет связи"); }
    String weatherDetail() { return part(p.getString("weatherWhy", ""), 1, ""); }
    String ratesProblem() { return part(p.getString("ratesWhy", ""), 0, "нет связи"); }
    String ratesDetail() { return part(p.getString("ratesWhy", ""), 1, ""); }
    long weatherFailAt() { return p.getLong("weatherFailAt", 0); }
    long ratesFailAt() { return p.getLong("ratesFailAt", 0); }

    private static String part(String s, int i, String def) {
        String[] a = s.split("\n", 2);
        return a.length > i && !a[i].isEmpty() ? a[i] : def;
    }

    // Сбой словами: что видно на экране и почему. Сначала причины в цепочке (Android прячет их внутрь IOException)
    static String[] why(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException)
                return new String[]{"нет связи", "адрес сервера не находится: нет интернета или не работает DNS"};
            if (t instanceof javax.net.ssl.SSLException || t instanceof java.security.cert.CertificateException)
                return new String[]{"проверьте время на ТВ", "защищённое соединение не сложилось, чаще всего из-за неверных даты и времени на ТВ ("
                        + t.getClass().getSimpleName() + ")"};
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.net.ConnectException || t instanceof java.net.NoRouteToHostException)
                return new String[]{"нет связи", "сервер не отвечает, сеть до него не доходит (" + t.getClass().getSimpleName() + ")"};
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.startsWith("HTTP ")) { String[] f = m.split(" "); return new String[]{"сервер недоступен", "сервер ответил кодом " + (f.length > 1 ? f[1] : "?")}; }
        if (e instanceof JSONException) return new String[]{"сервер недоступен", "сервер прислал непонятный ответ"};
        return new String[]{"нет связи", e.getClass().getSimpleName() + (m.isEmpty() ? "" : ": " + m)};
    }

    private void failed(String key, Exception e) {
        String[] w = why(e);
        p.edit().putLong(key + "FailAt", System.currentTimeMillis()).putString(key + "Why", w[0] + "\n" + w[1]).apply();
    }

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
            p.edit().putLong("weatherAt", System.currentTimeMillis()).remove("weatherWhy").apply();
            return true;
        } catch (Exception e) {
            Log.w("home", "погода не загрузилась", e);
            failed("weather", e);
            return false;
        }
    }

    private boolean loadRates() {
        try {
            String setting = Setup.rates(app);
            String cc = Rates.AUTO.equals(setting) ? countryOf() : Setup.country(app);
            String src = Rates.source(setting, cc);
            List<Rates.Item> l;
            switch (src) {
                case Rates.NBRB: l = Rates.nbrb(nbrb("USD"), nbrb("EUR"), nbrb("RUB")); break;
                case Rates.CBR: l = Rates.cbr(new String(getBytes(Rates.CBR_URL), StandardCharsets.ISO_8859_1)); break;   // windows-1251, нужны только латиница и цифры
                case Rates.NBU:
                    try { l = Rates.nbu(get(Rates.NBU_URL)); if (l.isEmpty()) throw new JSONException("НБУ: в ответе нет курсов"); }
                    catch (Exception e) { Log.w("home", "НБУ не ответил, курсы monobank", e); l = Rates.monobank(get(Rates.MONO_URL)); }
                    break;
                case Rates.NBK: l = Rates.nbk(get(Rates.NBK_URL)); break;
                case Rates.CRYPTO:
                    try { l = Rates.binance(get(Rates.BINANCE_URL)); if (l.isEmpty()) throw new JSONException("Binance: в ответе нет цен"); }
                    catch (Exception e) { Log.w("home", "лента цен Binance не ответила, запасной адрес", e); l = Rates.binance(get(Rates.BINANCE_URL2)); }
                    break;
                default: l = Rates.ecb(get(Rates.ECB_URL), cc);
            }
            if (l.isEmpty()) throw new JSONException("в ответе нет нужных валют");
            rates = l;
            ratesSrc = src;
            p.edit().putLong("ratesAt", System.currentTimeMillis()).remove("ratesWhy").apply();
            return true;
        } catch (Exception e) {
            Log.w("home", "курсы не загрузились", e);
            failed("rates", e);
            return false;
        }
    }

    private double nbrb(String cur) throws Exception {
        JSONObject j = new JSONObject(get(String.format(Locale.US, Rates.NBRB_URL, cur)));
        return j.getDouble("Cur_OfficialRate");
    }

    // Страна города для курсов «по стране». Город выбран до 1.1.1 (страны нет): один раз спросить поиск Open-Meteo
    // по тому же названию и взять результат, ближайший к сохранённым координатам. Не вышло: пусто, тогда ЕЦБ
    private String countryOf() {
        String cc = Setup.country(app), city = Setup.city(app);
        if (!cc.isEmpty() || !Setup.weatherOn(app) || city.isEmpty()) return cc;
        try {
            JSONObject j = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name="
                    + URLEncoder.encode(city, "UTF-8") + "&count=10&language=ru&format=json"));
            JSONArray a = j.optJSONArray("results");
            double la = Setup.lat(app), lo = Setup.lon(app), best = 1.0;
            String code = "";
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                double d = Math.abs(o.optDouble("latitude", 999) - la) + Math.abs(o.optDouble("longitude", 999) - lo);
                if (d < best) { best = d; code = o.optString("country_code", ""); }
            }
            if (!code.isEmpty()) Setup.setCountry(app, code);
            return code;
        } catch (Exception e) {
            Log.w("home", "страна города не определилась", e);
            return "";
        }
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
            j.put("rl", Rates.encode(rates)).put("rs", ratesSrc);
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
            if (j.has("rl")) {
                rates = Rates.decode(j.optString("rl"));
                ratesSrc = j.optString("rs", "");
            } else if (j.has("usd")) {   // кэш до 1.1.1: только Нацбанк Беларуси
                rates = Rates.nbrb(j.optDouble("usd", Double.NaN), j.optDouble("eur", Double.NaN), j.optDouble("rub", Double.NaN));
                ratesSrc = Rates.NBRB;
            }
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
