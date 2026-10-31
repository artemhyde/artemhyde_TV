package app.glasstv.home;

// Палитра и материал стекла двух тем оформления. Тему выбирает Config.THEME: "volume" это светлый «Объём»
// (светлое матовое стекло, белая рамка фокуса, тёплое свечение у плитки), любое другое значение это «Графит»
// (тёмное дымчатое стекло, тонкая кромка-градиент, подсветка цвета значка). Всё final static и считается один раз
// при загрузке класса: в кадре ни ветвлений по теме, ни пересчёта, только чтение готовых чисел.
// Логика (блик, поля картинок, кэш, анимации, фокус) в Tile, Glass, Suggest, PillView и Blur общая для обеих тем,
// здесь только числа и цвета. Числа «Графита» это ровно те, что раньше стояли прямо в коде этих классов.
// Числа «Объёма» это числа сборки от 27.10 до перехода на «Графит»; светлая таблетка быстрой полосы (PILL_*) в той сборке
// не существовала и собрана из материала светлой капсулы с более плотной подложкой, чтобы белый текст читался на кадре фильма.
// Размеры слоёв даны в точках макета плитки 400x224 (их умножает на k сама плитка), у таблеток в dp.
final class Theme {
    private Theme() {}

    static final boolean VOLUME = "volume".equals(Config.THEME);

    // Как Color.argb, но без Android: класс остаётся чистой Java, его можно проверять на ПК
    private static int argb(int a, int r, int g, int b) { return (a << 24) | (r << 16) | (g << 8) | b; }

    // ===== Плитка ряда и сетки: ассеты и общие слои =====

    // Папка assets с готовыми картинками значка и подписи (собирает Home/tiles-src/build_tiles.py)
    static final String TILES_DIR = VOLUME ? "tiles-volume" : "tiles";
    // Подсветка цвета значка внутри плитки: у «Графита» её рисует сама плитка (в покое запечена в содержимое,
    // в фокусе прибавка поверх), у «Объёма» её нет, а у обычной плитки приложения она запечена в картинку (APP_GLOW)
    static final boolean TILE_GLOW = !VOLUME;
    // Пик бегущего блика (белая полоса при получении фокуса)
    static final int TILE_SHEEN = 0x4DFFFFFF;

    // Запас картинки стекла вокруг плитки: {покой, фокус}. В «Объёме» тени длиннее, поля шире
    static final float[] TILE_PAD_X = VOLUME ? new float[]{64, 84} : new float[]{48, 72};
    static final float[] TILE_PAD_TOP = {12, 80};
    static final float[] TILE_PAD_BOTTOM = VOLUME ? new float[]{128, 186} : new float[]{112, 170};

    // ===== Плитка: стекло, слои по порядку рисования. Первый индекс 0 покой, 1 фокус =====

    // Внешние тени и торец: {смещение x, смещение y, размытие, растяжение}; цвета в TILE_SHADOW_COLOR в том же порядке.
    // В CSS первая тень лежит сверху, поэтому здесь они идут с конца, как рисуются
    static final float[][][] TILE_SHADOW = VOLUME ? new float[][][]{
            {{0, 64, 90, -34}, {0, 30, 42, -14}, {0, 12, 16, -4}, {0, 7.5f, 0, 0}, {0, 6, 0, 0}, {0, 4, 0, 0}, {0, 2, 0, 0}},
            {{0, 100, 120, -40}, {0, 56, 70, -20}, {0, 20, 26, -6}, {0, 0, 70, 6}, {0, 0, 0, 8}, {0, 11, 0, 0}, {0, 9, 0, 0}, {0, 6, 0, 0}, {0, 3, 0, 0}}
    } : new float[][][]{
            {{0, 70, 96, -40}, {0, 34, 46, -16}, {0, 14, 18, -6}, {0, 3.5f, 0, 0}, {0, 1.5f, 0, 0}},
            {{0, 104, 124, -42}, {0, 58, 72, -22}, {0, 22, 28, -8}, {0, 0, 56, 2}, {0, 5, 0, 0}, {0, 2, 0, 0}}
    };
    static final int[][] TILE_SHADOW_COLOR = VOLUME ? new int[][]{
            {0x66000000, 0x6B000000, 0x4D000000, 0x33000000, 0x12FFFFFF, 0x26FFFFFF, 0x45FFFFFF},
            {0x80000000, 0x8C000000, 0x66000000, 0x33FFECDC, 0x1AFFFFFF, 0x38000000, 0x14FFFFFF, 0x2EFFFFFF, 0x52FFFFFF}
    } : new int[][]{
            {0x85000000, 0x85000000, 0x75000000, 0x8C06040A, 0x26FFFFFF},
            {0x8F000000, 0x9E000000, 0x85000000, 0x13FFECDA, 0x8C06040A, 0x33FFFFFF}
    };

    // Тёплый оттенок под заливкой (градиент 165°, два цвета). У «Графита» его нет
    static final int[][] TILE_TINT = VOLUME ? new int[][]{
            {argb(26, 255, 226, 206), argb(15, 120, 80, 80)},
            {argb(26, 255, 226, 206), argb(20, 90, 60, 70)}
    } : null;

    // Заливка сверху вниз: цвета и позиции. «Графит»: дымчатый графит, белого нет. «Объём»: белая вуаль
    static final int[][] TILE_FILL = VOLUME ? new int[][]{
            {0x45FFFFFF, 0x20FFFFFF, 0x12FFFFFF},
            {0x57FFFFFF, 0x26FFFFFF, 0x14FFFFFF}
    } : new int[][]{
            {argb(158, 68, 66, 80), argb(168, 42, 40, 51), argb(189, 24, 22, 31)},
            {argb(163, 82, 80, 95), argb(168, 52, 50, 62), argb(184, 32, 30, 41)}
    };
    static final float[][] TILE_FILL_POS = VOLUME ? new float[][]{{0f, 0.4f, 1f}, {0f, 0.42f, 1f}} : new float[][]{{0f, 0.5f, 1f}, {0f, 0.5f, 1f}};

    // Свечение изнутри (inset-тени): {смещение x, смещение y, размытие, растяжение}, цвета в том же порядке.
    // «Объём»: одно свечение у нижнего края (дыра шире плитки на 16 и поднята)
    static final float[][][] TILE_INSET = VOLUME ? new float[][][]{
            {{0, -16, 22, -16}},
            {{0, -18, 26, -16}}
    } : new float[][][]{
            {{0, 0, 30, -2}, {0, -12, 18, -12}},
            {{0, -16, 24, -14}, {0, 0, 38, 0}, {0, 0, 0, 1.5f}, {0, 2, 0, 0}}
    };
    static final int[][] TILE_INSET_COLOR = VOLUME ? new int[][]{
            {0x33FFFFFF},
            {0x47FFFFFF}
    } : new int[][]{
            {0x13FFFFFF, 0x17FFFFFF},
            {0x29FFFFFF, 0x24FFFFFF, 0x13FFFFFF, 0x33FFFFFF}
    };

    // Кромка: толщина в точках макета, цвета и позиции градиента 150°. «Объём» в фокусе: сплошная белая рамка 4 точки
    static final float[] TILE_RIM_W = VOLUME ? new float[]{1.6f, 4f} : new float[]{1.6f, 2.2f};
    static final int[][] TILE_RIM = VOLUME ? new int[][]{
            {0xF2FFFFFF, 0x61FFFFFF, 0x12FFFFFF, 0x17FFFFFF, 0x6BFFFFFF},
            {0xFFFFFFFF, 0xF5FFFFFF, 0xCCFFFFFF}
    } : new int[][]{
            {0xA3FFFFFF, 0x38FFFFFF, 0x0DFFFFFF, 0x12FFFFFF, 0x4DFFFFFF},
            {0xF2FFFFFF, 0x85FFFFFF, 0x29FFFFFF, 0x3DFFFFFF, 0xA8FFFFFF}
    };
    static final float[][] TILE_RIM_POS = VOLUME ? new float[][]{
            {0f, 0.16f, 0.42f, 0.66f, 1f},
            {0f, 0.5f, 1f}
    } : new float[][]{
            {0f, 0.18f, 0.44f, 0.68f, 1f},
            {0f, 0.22f, 0.48f, 0.70f, 1f}
    };

    // Блик сверху слева: {центр по x от ширины, радиус по x от ширины, радиус по y от высоты}, цвет {покой, фокус}
    static final float[] TILE_TOP = VOLUME ? new float[]{0.18f, 1.1f, 0.62f} : new float[]{0.2f, 1.1f, 0.58f};
    static final int[] TILE_TOP_COLOR = VOLUME ? new int[]{0x42FFFFFF, 0x4DFFFFFF} : new int[]{0x1DFFFFFF, 0x21FFFFFF};
    // Еле заметный диагональный отсвет (градиент 106°): середина {покой, фокус} и её позиции
    static final int[] TILE_DIAG = VOLUME ? new int[]{0x16FFFFFF, 0x2BFFFFFF} : new int[]{0x0AFFFFFF, 0x12FFFFFF};
    static final float[][] TILE_DIAG_POS = VOLUME ? new float[][]{
            {0.36f, 0.45f, 0.57f},
            {0.30f, 0.42f, 0.56f}
    } : new float[][]{
            {0.38f, 0.46f, 0.56f},
            {0.32f, 0.43f, 0.55f}
    };

    // ===== Плитка приложения без своей картинки (Tile.app) =====

    // Подсветка цвета значка, запечённая в картинку: доля непрозрачности в центре; 0 значит не рисовать (у «Графита» её даёт плитка)
    static final float APP_GLOW = VOLUME ? 0.38f : 0f;
    // Тень значка: три слоя
    static final int[] APP_ICON_SHADOW = VOLUME ? new int[]{0x6B000000, 0x57000000, 0x59000000} : new int[]{0x8C000000, 0x66000000, 0x73000000};
    // Блик стекла на значке: четыре цвета по позициям {0, 0.42, 0.6, 1}
    static final int[] APP_ICON_SHEEN = VOLUME ? new int[]{0x4DFFFFFF, 0x0FFFFFFF, 0x00FFFFFF, 0x1A000000}
            : new int[]{0x3DFFFFFF, 0x0DFFFFFF, 0x00FFFFFF, 0x1F000000};
    // Светлая полоска по верхнему краю значка внутри и его тонкая рамка
    static final int APP_ICON_TOP = VOLUME ? 0x8CFFFFFF : 0x73FFFFFF;
    static final int APP_ICON_RIM = VOLUME ? 0x33FFFFFF : 0x29FFFFFF;
    // Подпись рисуется дважды (две тени). «Графит»: 0,69 за проход дают в сумме 0,90 белого. «Объём»: чистый белый
    static final int APP_LABEL = VOLUME ? 0xFFFFFFFF : 0xAFFFFFFF;
    static final int[] APP_LABEL_SHADOW = VOLUME ? new int[]{0x61000000, 0x47000000} : new int[]{0x80000000, 0x66000000};

    // ===== Капсула под рядом (Glass), капсула «Продолжить» и «Играет» =====

    // Запас картинки под торец стекла, в точках макета (k = плотность / 2)
    static final float GLASS_BELOW = VOLUME ? 5f : 2f;
    // Торец под нижним краем: {смещение вниз} и цвета по порядку рисования, у обоих состояний. «Объём»: два тонких серпа
    static final float[][] GLASS_SLAB = VOLUME ? new float[][]{{4f, 2f}, {4f, 2f}} : new float[][]{{1.5f}, {1.5f}};
    static final int[][] GLASS_SLAB_COLOR = VOLUME ? new int[][]{{0x0FFFFFFF, 0x24FFFFFF}, {0x0FFFFFFF, 0x24FFFFFF}}
            : new int[][]{{0x24FFFFFF}, {0x33FFFFFF}};
    // Подложка под заливкой: тёплая тень (у «Графита» нет, alpha ноль)
    static final int GLASS_BASE = VOLUME ? 0x331E1418 : 0;
    // Заливка сверху вниз {покой, в фокусе}; позиции null значит поровну
    static final int[][] GLASS_FILL = VOLUME ? new int[][]{
            {0x33FFFFFF, 0x14FFFFFF, 0x0AFFFFFF},
            {0x4DFFFFFF, 0x24FFFFFF, 0x14FFFFFF}
    } : new int[][]{
            {argb(158, 68, 66, 80), argb(173, 36, 34, 45)},
            {argb(168, 82, 80, 95), argb(178, 46, 44, 56)}
    };
    static final float[] GLASS_FILL_POS = VOLUME ? new float[]{0f, 0.5f, 1f} : null;
    // Светлое свечение по краю изнутри {покой, фокус}; alpha ноль значит нет
    static final int[] GLASS_INSET = VOLUME ? new int[]{0, 0} : new int[]{0x14FFFFFF, 0x21FFFFFF};
    // Блик сверху слева {покой, фокус}
    static final int[] GLASS_SHINE = VOLUME ? new int[]{0x33FFFFFF, 0x33FFFFFF} : new int[]{0x1CFFFFFF, 0x24FFFFFF};
    // Кромка-градиент 160°: цвета {покой, фокус} и позиции
    static final int[][] GLASS_EDGE = VOLUME ? new int[][]{
            {0xCCFFFFFF, 0x38FFFFFF, 0x0FFFFFFF, 0x57FFFFFF},
            {0xCCFFFFFF, 0x38FFFFFF, 0x0FFFFFFF, 0x57FFFFFF}
    } : new int[][]{
            {0x94FFFFFF, 0x2EFFFFFF, 0x0DFFFFFF, 0x42FFFFFF},
            {0xD9FFFFFF, 0x4DFFFFFF, 0x1AFFFFFF, 0x66FFFFFF}
    };
    static final float[] GLASS_EDGE_POS = VOLUME ? new float[]{0f, 0.22f, 0.5f, 1f} : new float[]{0f, 0.24f, 0.52f, 1f};

    // ===== Кромка фокуса капсулы (Suggest) и таблетки (PillView) =====

    // Толщина в dp; цвета градиента 150° (null: сплошная белая) и позиции; непрозрачность в фокусе из 255
    static final float RING_W = VOLUME ? 2f : 1.1f;
    static final int[] RING = VOLUME ? null : new int[]{0xF2FFFFFF, 0x85FFFFFF, 0x29FFFFFF, 0x3DFFFFFF, 0xA8FFFFFF};
    static final float[] RING_POS = {0f, 0.22f, 0.48f, 0.70f, 1f};
    static final int RING_ALPHA = VOLUME ? 235 : 255;

    // ===== Таблетка быстрой полосы (PillView), размеры в dp =====

    // Тени снизу и торец: {смещение x, смещение y, размытие, растяжение}, цвета в том же порядке
    static final float[][] PILL_SHADOW = VOLUME ? new float[][]{{0, 7, 16, -3}, {0, 2, 5, 0}, {0, 2, 0, 0}, {0, 1, 0, 0}}
            : new float[][]{{0, 7, 16, -3}, {0, 2, 5, 0}, {0, 0.75f, 0, 0}};
    static final int[] PILL_SHADOW_COLOR = VOLUME ? new int[]{0x66000000, 0x55000000, 0x0FFFFFFF, 0x24FFFFFF}
            : new int[]{0x66000000, 0x55000000, 0x24FFFFFF};
    // Подложка сверху вниз. «Графит»: почти непрозрачный графит (при 0xD8 сквозь таблетку читался стих главного экрана).
    // «Объём»: тёплая тёмная подложка почти без просвета (сквозь 65 % читались подписи главного экрана), на ней вуаль PILL_VEIL; без неё белые значок и подпись терялись бы на светлом кадре
    static final int[] PILL_FILL = VOLUME ? new int[]{0xF21E1418, 0xF21E1418} : new int[]{argb(0xFF, 64, 62, 76), argb(0xFF, 32, 30, 41)};
    // Белая вуаль «Объёма» поверх подложки (цвета и позиции); у «Графита» нет
    static final int[] PILL_VEIL = VOLUME ? new int[]{0x33FFFFFF, 0x14FFFFFF, 0x0AFFFFFF} : null;
    static final float[] PILL_VEIL_POS = {0f, 0.5f, 1f};
    // Свечение по краю изнутри (alpha ноль значит нет) и блик сверху слева
    static final int PILL_INSET = VOLUME ? 0 : 0x14FFFFFF;
    static final int PILL_SHINE = VOLUME ? 0x33FFFFFF : 0x1CFFFFFF;
    // Тонкая кромка-градиент 160° в покое
    static final int[] PILL_EDGE = VOLUME ? new int[]{0xCCFFFFFF, 0x38FFFFFF, 0x0FFFFFFF, 0x57FFFFFF}
            : new int[]{0xA6FFFFFF, 0x38FFFFFF, 0x12FFFFFF, 0x4DFFFFFF};
    static final float[] PILL_EDGE_POS = VOLUME ? new float[]{0f, 0.22f, 0.5f, 1f} : new float[]{0f, 0.24f, 0.52f, 1f};
    // Слой подсветки при фокусе: вуаль сверху вниз и свечение по краю (alpha ноль значит нет)
    static final int[] PILL_LIT_VEIL = VOLUME ? new int[]{0x1AFFFFFF, 0x0AFFFFFF} : new int[]{0x20FFFFFF, 0x14FFFFFF};
    static final int PILL_LIT_INSET = VOLUME ? 0 : 0x1AFFFFFF;

    // ===== Размытый фон: виньетка и затемнение (Blur) =====

    // Виньетка: центр по высоте, цвета и позиции радиального градиента. «Графит»: овал 68 % на 78 %;
    // «Объём»: круг радиусом 62 % диагонали. Затемнение сверху и снизу линейным градиентом только у «Графита» (null: нет)
    static final boolean VIG_OVAL = !VOLUME;
    static final float VIG_CY = VOLUME ? 0.46f : 0.44f;
    static final int[] VIG_COLORS = VOLUME ? new int[]{0x00000000, 0x00000000, 0x73000000}
            : new int[]{0x0006040A, 0x0006040A, 0x4206040A, 0x99040208};
    static final float[] VIG_POS = VOLUME ? new float[]{0f, 0.5f, 1f} : new float[]{0f, 0.5f, 0.76f, 1f};
    static final int[] SCRIM_COLORS = VOLUME ? null : new int[]{0x2E08060E, 0x0008060E, 0x0008060E, 0x3D08060E};
    static final float[] SCRIM_POS = {0f, 0.26f, 0.58f, 1f};
}
