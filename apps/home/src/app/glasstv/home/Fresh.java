package app.glasstv.home;

// Свежесть погоды и курсов без связи (план Codex, блок 6), без Android: проверяет тест на ПК (Home/test/FreshTest.java).
// На экране последнее, что удалось получить, но старое видно. Погода при сбое: старше 2 часов с временем обновления,
// старше 12 часов (или её нет вовсе) вместо градусов «нет связи». Курс при сбое: старше полутора суток с датой, нет вовсе: «нет связи».
// Пока сбоя нет (данные просто ждут своего часа), ничего не помечается. После неудачи новая попытка не раньше чем через 5 минут
final class Fresh {
    private Fresh() { }

    static final long WEATHER_EVERY = 30 * 60_000L, RATES_EVERY = 3 * 3600_000L, RETRY_AFTER_FAIL = 5 * 60_000L;
    // Крипта меняется за минуты, раз в три часа для неё мало (10.11)
    static final long CRYPTO_EVERY = 30 * 60_000L;
    static final long WEATHER_STALE = 2 * 3600_000L, WEATHER_DEAD = 12 * 3600_000L, RATES_STALE = 36 * 3600_000L;

    static final int SHOW = 0, SHOW_WITH_TIME = 1, NO_LINK = 2;

    // Сбой: последняя попытка позже последней удачи
    static boolean failing(long okAt, long failAt) { return failAt > okAt; }

    // Возраст по времени удачи; 0 (не было или стёрто сменой города) считается «очень давно»
    static long age(long now, long okAt) { return okAt > 0 ? Math.max(0, now - okAt) : Long.MAX_VALUE; }

    static int weather(long now, long okAt, long failAt, boolean empty) {
        if (!failing(okAt, failAt)) return SHOW;
        long age = age(now, okAt);
        if (empty || age > WEATHER_DEAD) return NO_LINK;
        return age > WEATHER_STALE ? SHOW_WITH_TIME : SHOW;
    }

    static int rates(long now, long okAt, long failAt, boolean empty) {
        if (!failing(okAt, failAt)) return SHOW;
        if (empty) return NO_LINK;
        return age(now, okAt) > RATES_STALE ? SHOW_WITH_TIME : SHOW;
    }

    // Пора ли идти в сеть: не раньше 5 минут после неудачи, пусто или старше своего срока
    static boolean due(long now, long okAt, long failAt, long every, boolean empty) {
        if (now - failAt < RETRY_AFTER_FAIL) return false;
        return empty || now - okAt > every;
    }
}
