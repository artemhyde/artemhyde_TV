package app.glasstv.home;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

// Смена фото и стиха по времени суток: утро 06:00–12:00, день 12:00–18:00, вечер и ночь 18:00–06:00.
// Здесь только счёт, без Android: так его можно проверить отдельной программой.
// У каждого времени суток своя очередь фото: круг из всех фото в случайном порядке, потом новый круг.
// Начало нового круга никогда не совпадает с концом прошлого, одно фото два раза подряд не бывает
final class Rotation {
    static final int MORNING = 0, DAY = 1, EVENING = 2;
    private static final int MIN_QUEUE = 8;   // столько фото вперёд всегда известно заранее

    static int slotOf(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY);
        return h >= 6 && h < 12 ? MORNING : h >= 12 && h < 18 ? DAY : EVENING;
    }

    // «Выдача»: дата начала времени суток и его номер. Ночь до 06:00 относится к вечеру, который начался накануне
    static String keyOf(Calendar c) {
        Calendar d = (Calendar) c.clone();
        if (slotOf(d) == EVENING && d.get(Calendar.HOUR_OF_DAY) < 6) d.add(Calendar.DAY_OF_MONTH, -1);
        return String.format(Locale.US, "%04d%02d%02d-%d", d.get(Calendar.YEAR), d.get(Calendar.MONTH) + 1,
                d.get(Calendar.DAY_OF_MONTH), slotOf(c));
    }

    // Ближайшая граница времени суток строго после c: 06:00, 12:00 или 18:00
    static Calendar nextStart(Calendar c) {
        Calendar t = (Calendar) c.clone();
        int h = t.get(Calendar.HOUR_OF_DAY);
        if (h >= 18) t.add(Calendar.DAY_OF_MONTH, 1);
        t.set(Calendar.HOUR_OF_DAY, h < 6 || h >= 18 ? 6 : h < 12 ? 12 : 18);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        return t;
    }

    // Ключ ближайшего начала времени суток slot после c
    static String nextKey(Calendar c, int slot) {
        Calendar t = c;
        for (int i = 0; i < 4; i++) {
            t = nextStart(t);
            if (slotOf(t) == slot) return keyOf(t);
        }
        return "";
    }

    private final List<String> ids;                     // вся подборка этого времени суток
    private final List<String> q = new ArrayList<>();   // очередь: q[0] показывается сейчас
    private final Random rnd;
    private String key;

    Rotation(List<String> ids, String savedQueue, String savedKey, Random rnd) {
        this.ids = ids;
        this.rnd = rnd;
        key = savedKey == null ? "" : savedKey;
        if (savedQueue != null && !savedQueue.isEmpty())
            for (String s : savedQueue.split(",")) if (ids.contains(s)) q.add(s);   // подборка могла измениться
        topUp();
    }

    // Началась новая выдача (утро, день или вечер): прошлое фото снимается с очереди. true, если состояние изменилось
    boolean ensure(String now) {
        if (now.equals(key)) return false;
        boolean first = key.isEmpty();
        key = now;
        if (!first && !q.isEmpty()) q.remove(0);
        topUp();
        return true;
    }

    String current() { return peek(0); }

    String peek(int i) { return q.isEmpty() ? null : q.get(i % q.size()); }

    // Фото, которое будет показано в ближайшее начало этого времени суток (nextKey его ключ)
    String upcoming(String nextKey) { return peek(key.isEmpty() || key.equals(nextKey) ? 0 : 1); }

    String saved() {
        StringBuilder b = new StringBuilder();
        for (String s : q) b.append(b.length() > 0 ? "," : "").append(s);
        return b.toString();
    }

    String key() { return key; }

    // Дописывать круги, пока впереди меньше MIN_QUEUE фото. В новом круге сначала идут те, чего в очереди нет,
    // потом остаток прошлого круга: так фото из хвоста не вернётся сразу
    private void topUp() {
        if (ids.isEmpty()) return;
        while (q.size() < MIN_QUEUE) {
            List<String> rest = new ArrayList<>(), left = new ArrayList<>();
            for (String s : ids) (q.contains(s) ? left : rest).add(s);
            Collections.shuffle(rest, rnd);
            Collections.shuffle(left, rnd);
            rest.addAll(left);
            if (ids.size() > 1 && !q.isEmpty() && rest.get(0).equals(q.get(q.size() - 1))) Collections.swap(rest, 0, 1);
            q.addAll(rest);
        }
    }
}
