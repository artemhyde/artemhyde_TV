package app.glasstv.keyboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.util.Locale;

// Клавиатура для пульта (ноябрь). Жалоба: «ввожу пароль, потом цифры, что-то сбрасывается, начинают писаться только цифры, не всегда
// язык переключается». Поэтому здесь нет ни одного скрытого режима: цифры всегда в своём ряду, нужные полю знаки в ряду над ними, язык
// виден и на кнопке, и в строке с текстом. Сам меняется только регистр на одну букву. Язык помнится отдельно для обычных полей и для
// пароля, адреса и почты, и новое поле его не сбрасывает.
// Частое не дороже трёх нажатий: удержание OK на букве даёт заглавную, удержание «Назад» стирает на месте, удержание пробела меняет язык.
// Вверх с верхнего ряда встаёт на строку с текстом: там влево и вправо двигают курсор, OK стирает знак слева, крестик в конце строки
// стирает всё. Клавиши срабатывают на отпускании, кроме стирания и движения курсора: так работают удержания и не остаётся обрывков нажатия.
// Перехватываем только стрелки, OK и «Назад»: громкость, «Домой» и кнопки Key Mapper идут дальше как обычно.
// Набранное нигде не хранится и не пишется в журнал, интернета в манифесте нет, подсказок и автозамены нет.
public class Kbd extends InputMethodService implements KeyView.Source {
    static final int STRIP = -1;
    private static final long MOVE_MS = 150, SHOW_LAST_MS = 1000, DOUBLE_SHIFT_MS = 450, HOLD_MS = 500, HOLD_SPACE_MS = 700, HOLD_LANG_MS = 1500, SAME_FIELD_MS = 2000;
    private static final float START_X = 11;      // куда встаёт курсор в новом поле: середина букв, в числовом поле «5»
    private static final int START_ROW = 3;       // второй ряд букв
    private static final String START_ON_KEYS = "app.glasstv.keyboard.keys";   // приложение уже поставило курсор в тексте и просит сразу буквы

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private KeyView view;

    private int field = Layouts.F_TEXT;
    private int language = Layouts.RU;
    private int layer;                 // 0 буквы, 1 и 2 страницы знаков
    private boolean multiline, numberPassword;
    private boolean fromNumber;        // в числовом поле нажали ABC: буквы до конца этого поля
    private Layouts.Key[][] rows = Layouts.build(Layouts.F_TEXT, Layouts.RU, 0, false);

    private int fRow = START_ROW, fIndex = 5;
    private float stickX = START_X;    // куда целимся по вертикали: идя вверх и вниз, курсор не уезжает в сторону
    private int stripBack = -1;        // на какую клавишу верхнего ряда вернуться со строки с текстом; -1: на стартовую
    private boolean onClear;           // на строке с текстом курсор стоит на крестике

    private boolean shift, caps;
    private long shiftAt;
    private boolean pressedNow;
    private long lastStep;

    // Чьё нажатие идёт. OK, открывший клавиатуру и ещё зажатый, чужой: его повторы и отпускание ничего не наберут
    private int ownCode;
    private long ownDown;
    private boolean holdDone, erasedByBack, holdUpper;

    private int action;
    private String actionLabel = "Готово";
    private String placeholder = "";
    private String note = "";
    private String lastField = "";
    private long lastFinish;

    private boolean showPassword;
    private boolean lastVisible;
    private boolean hasClip;
    private String before = "", selected = "", after = "";
    private int selStart = -1, selEnd = -1;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("kb", MODE_PRIVATE);
        showPassword = prefs.getBoolean("pwd_show", true);
    }

    @Override public View onCreateInputView() {
        if (view == null) view = new KeyView(this, this);
        return view;
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    // Пульт по Bluetooth система считает настоящей клавиатурой и иначе экранную не показала бы
    @Override public boolean onEvaluateInputViewShown() { return true; }

    // Страница под клавиатурой не сжимается и не прыгает: набранное и так видно в строке с текстом
    @Override public void onComputeInsets(Insets out) {
        super.onComputeInsets(out);
        View d = getWindow().getWindow().getDecorView();
        out.contentTopInsets = d.getHeight();
        out.visibleTopInsets = d.getHeight();
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // То же поле начало ввод заново (страницы с живым поиском делают это после каждого знака): ничего не трогаем, иначе «что-то сбросилось»
        String id = info.packageName + "|" + info.fieldId + "|" + info.inputType + "|" + info.imeOptions + "|" + info.hintText;
        boolean same = id.equals(lastField) && (restarting || SystemClock.uptimeMillis() - lastFinish < SAME_FIELD_MS);
        lastField = id;
        readField(info);
        // Снимки и запись экрана в поле пароля дают чёрный кадр: пароль виден только на самом экране
        if (field == Layouts.F_PASSWORD || numberPassword) getWindow().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (same && fromNumber) field = Layouts.F_TEXT;
        if (!same) {
            fromNumber = false;
            layer = 0;
            shift = false;
            caps = false;
            language = languageOfField();
        }
        rebuild();
        selStart = info.initialSelStart;
        selEnd = info.initialSelEnd;
        if (!same) startFocus(info);
        readClip();
        refreshText();
        if (view != null) view.requestLayout();
        redraw();
    }

    @Override public void onFinishInputView(boolean finishing) {
        super.onFinishInputView(finishing);
        pressedNow = false;
        holdUpper = false;
        ownCode = 0;
        lastFinish = SystemClock.uptimeMillis();
        h.removeCallbacks(hideLast);
        h.removeCallbacks(refreshLater);
        // Текст поля не задерживается в памяти после закрытия
        before = "";
        selected = "";
        after = "";
    }

    private boolean techField() { return fromNumber || field == Layouts.F_PASSWORD || field == Layouts.F_URL || field == Layouts.F_EMAIL; }

    private int languageOfField() { return techField() ? prefs.getInt("lang_tech", Layouts.EN) : prefs.getInt("lang_text", Layouts.RU); }

    private void readField(EditorInfo info) {
        int type = info.inputType;
        int cls = type & InputType.TYPE_MASK_CLASS, var = type & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) {
            field = Layouts.F_NUMBER;
        } else if (cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) {
            field = Layouts.F_PASSWORD;
        } else if (cls == InputType.TYPE_CLASS_TEXT && var == InputType.TYPE_TEXT_VARIATION_URI) {
            field = Layouts.F_URL;
        } else if (cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) {
            field = Layouts.F_EMAIL;
        } else {
            field = Layouts.F_TEXT;
        }
        numberPassword = cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        multiline = (type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 || (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        action = multiline ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (info.actionLabel != null) actionLabel = info.actionLabel.toString();
        else {
            switch (action) {
                case EditorInfo.IME_ACTION_GO: actionLabel = "Перейти"; break;
                case EditorInfo.IME_ACTION_SEARCH: actionLabel = "Найти"; break;
                case EditorInfo.IME_ACTION_NEXT: actionLabel = "Далее"; break;
                case EditorInfo.IME_ACTION_SEND: actionLabel = "Отправить"; break;
                default: actionLabel = "Готово";
            }
        }
        if (info.hintText != null && info.hintText.length() > 0) placeholder = info.hintText.toString();
        else if (info.label != null && info.label.length() > 0) placeholder = info.label.toString();
        else placeholder = field == Layouts.F_URL ? "Адрес" : field == Layouts.F_EMAIL ? "Почта" : field == Layouts.F_PASSWORD ? "Пароль" : field == Layouts.F_NUMBER ? "Цифры" : "Текст";
    }

    // Новое поле, всегда одно и то же начало. Пустое: середина букв. С текстом: строка с текстом, раз его, скорее всего, будут править
    private void startFocus(EditorInfo info) {
        onClear = false;
        stripBack = -1;
        InputConnection ic = getCurrentInputConnection();
        boolean filled = ic != null && (notEmpty(ic.getTextBeforeCursor(1, 0)) || notEmpty(ic.getTextAfterCursor(1, 0)));
        boolean keys = info.privateImeOptions != null && info.privateImeOptions.contains(START_ON_KEYS);
        if (filled && !keys) {
            fRow = STRIP;
            stickX = START_X;
        } else {
            goStartKey();
        }
    }

    private void goStartKey() {
        stickX = START_X;
        fRow = field == Layouts.F_NUMBER ? 0 : START_ROW;
        fIndex = indexAt(rows[fRow], stickX);
    }

    private static boolean notEmpty(CharSequence s) { return s != null && s.length() > 0; }

    private void rebuild() {
        rows = Layouts.build(field, language, layer, multiline, numberPassword);
        if (fRow >= rows.length) fRow = rows.length - 1;
        if (fRow != STRIP) fIndex = indexAt(rows[fRow], stickX);
    }

    private void redraw() { if (view != null) view.invalidate(); }

    @Override public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd, int candStart, int candEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candStart, candEnd);
        selStart = newStart;
        selEnd = newEnd;
        refreshText();
        redraw();
    }

    // ===== Клавиши пульта =====

    private static boolean ours(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return true;
            default:
                return false;
        }
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private boolean owns(int code, KeyEvent e) { return code == ownCode && e.getDownTime() == ownDown; }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (!isInputViewShown() || !ours(code)) return super.onKeyDown(code, e);
        if (e.getRepeatCount() == 0) {
            ownCode = code;
            ownDown = e.getDownTime();
            holdDone = false;
            erasedByBack = false;
            holdUpper = false;
            keyDown(code, e);
        } else if (owns(code, e)) {
            keyHeld(code, e);
        }
        return true;
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (!isInputViewShown() || !ours(code)) return super.onKeyUp(code, e);
        if (owns(code, e)) {
            ownCode = 0;
            keyUp(code, e);
        }
        return true;
    }

    private void keyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(e, 0, -1); return;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(e, 0, 1); return;
            case KeyEvent.KEYCODE_DPAD_UP: move(e, -1, 0); return;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(e, 1, 0); return;
            case KeyEvent.KEYCODE_CHANNEL_UP: commit(" "); return;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: backspace(false); lastStep = e.getEventTime(); return;
            case KeyEvent.KEYCODE_BACK: return;
            default: break;
        }
        pressedNow = true;
        lastStep = e.getEventTime();
        if (fRow == STRIP) {
            if (!onClear) backspace(false);
        } else {
            Layouts.Key k = rows[fRow][fIndex];
            if (k.kind == Layouts.BACKSPACE) backspace(false);
            else if (k.kind == Layouts.LEFT) caret(-1, false);
            else if (k.kind == Layouts.RIGHT) caret(1, false);
        }
        redraw();
    }

    private void keyHeld(int code, KeyEvent e) {
        long held = e.getEventTime() - e.getDownTime();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(e, 0, -1); return;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(e, 0, 1); return;
            case KeyEvent.KEYCODE_DPAD_UP: move(e, -1, 0); return;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(e, 1, 0); return;
            case KeyEvent.KEYCODE_CHANNEL_UP: return;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: eraseHeld(e, held); return;
            case KeyEvent.KEYCODE_BACK: backHeld(e, held); return;
            default: break;
        }
        if (fRow == STRIP) {
            if (!onClear) eraseHeld(e, held);
            return;
        }
        Layouts.Key k = rows[fRow][fIndex];
        switch (k.kind) {
            case Layouts.BACKSPACE: eraseHeld(e, held); break;
            case Layouts.LEFT: caretHeld(e, held, -1); break;
            case Layouts.RIGHT: caretHeld(e, held, 1); break;
            case Layouts.SHIFT:
                if (held >= HOLD_MS && !holdDone) {
                    holdDone = true;
                    shift = true;
                    caps = true;
                    redraw();
                }
                break;
            case Layouts.SPACE:
                if (held >= HOLD_SPACE_MS && !holdDone) {
                    holdDone = true;
                    toggleLanguage();
                }
                break;
            case Layouts.LANG:
                if (held >= HOLD_LANG_MS && !holdDone) {
                    holdDone = true;
                    nextKeyboard();
                }
                break;
            case Layouts.TEXT:
                // Буква при удержании сразу показана заглавной: видно, что наберётся
                if (held >= HOLD_MS && !holdUpper && k.isLetter()) {
                    holdUpper = true;
                    redraw();
                }
                break;
            default:
                break;
        }
    }

    private void keyUp(int code, KeyEvent e) {
        long held = e.getEventTime() - e.getDownTime();
        if (code == KeyEvent.KEYCODE_BACK) {
            // Удержание стёрло знаки или, если повторов не пришло, стирает один. Короткое нажатие закрывает, текст остаётся
            if (erasedByBack) return;
            if (held >= HOLD_MS && hasTextToErase()) backspace(false);
            else requestHideSelf(0);
            return;
        }
        if (!isOk(code)) return;
        pressedNow = false;
        boolean upper = holdUpper;
        holdUpper = false;
        if (fRow == STRIP) {
            if (onClear) clearPress();
            redraw();
            return;
        }
        Layouts.Key k = rows[fRow][fIndex];
        switch (k.kind) {
            case Layouts.BACKSPACE:
            case Layouts.LEFT:
            case Layouts.RIGHT:
                break;   // сработали на нажатии
            case Layouts.SHIFT:
                if (!holdDone) pressShift();
                break;
            case Layouts.SPACE:
                if (holdDone) break;
                if (held >= HOLD_SPACE_MS) toggleLanguage();
                else commit(" ");
                break;
            case Layouts.LANG:
                if (!holdDone) toggleLanguage();
                break;
            case Layouts.TEXT:
                type(k.text, upper || held >= HOLD_MS);
                break;
            default:
                act(k);
                break;
        }
        redraw();
    }

    // Интервал стирания по времени удержания: сначала по знаку, потом быстрее, потом словами
    private static long eraseInterval(long held) { return held < 1500 ? 100 : held < 3500 ? 50 : 150; }

    private void eraseHeld(KeyEvent e, long held) {
        long now = e.getEventTime();
        if (held >= HOLD_MS && now - lastStep >= eraseInterval(held)) {
            lastStep = now;
            backspace(held >= 3500);
            redraw();
        }
    }

    // «Назад» при удержании: один знак за полсекунды, потом пауза, чтобы успеть отпустить, потом как ⌫
    private void backHeld(KeyEvent e, long held) {
        long now = e.getEventTime();
        if (!hasTextToErase()) return;
        if (!erasedByBack) {
            if (held >= HOLD_MS) {
                erasedByBack = true;
                lastStep = now;
                backspace(false);
                redraw();
            }
        } else if (held >= 900 && now - lastStep >= eraseInterval(held - 400)) {
            lastStep = now;
            backspace(held - 400 >= 3500);
            redraw();
        }
    }

    private boolean hasTextToErase() {
        InputConnection ic = getCurrentInputConnection();
        return ic != null && (notEmpty(ic.getTextBeforeCursor(1, 0)) || notEmpty(ic.getSelectedText(0)));
    }

    private void caretHeld(KeyEvent e, long held, int dir) {
        long now = e.getEventTime();
        if (held >= 400 && now - lastStep >= (held < 1000 ? 80 : 100)) {
            lastStep = now;
            caret(dir, held >= 1000);
            redraw();
        }
    }

    private void move(KeyEvent e, int dRow, int dX) {
        long now = e.getEventTime();
        boolean first = e.getRepeatCount() == 0;
        if (fRow == STRIP) {
            moveOnStrip(e, dRow, dX, first);
            return;
        }
        if (!first && now - lastStep < MOVE_MS) return;
        lastStep = now;
        Layouts.Key[] row = rows[fRow];
        if (dX != 0) {
            int n = fIndex + dX;
            if (n < 0 || n >= row.length) {
                if (!first) return;   // при удержании край останавливает, в круг не крутит
                n = dX > 0 ? 0 : row.length - 1;
            }
            fIndex = n;
            rememberX();
        } else if (dRow < 0 && fRow == 0) {
            if (!first) return;
            stripBack = fIndex;
            onClear = false;
            fRow = STRIP;
        } else if (dRow > 0 && fRow == rows.length - 1) {
            // Вниз с нижнего ряда: на верхний. От пробела до цифр два нажатия вместо четырёх
            if (!first) return;
            fRow = 0;
            fIndex = indexAt(rows[0], stickX);
        } else {
            fRow += dRow;
            fIndex = indexAt(rows[fRow], stickX);
        }
        redraw();
    }

    private void moveOnStrip(KeyEvent e, int dRow, int dX, boolean first) {
        if (dRow > 0) {
            if (first) leaveStrip();
        } else if (dX != 0 && onClear) {
            if (first && dX < 0) {
                onClear = false;
                collapseSelection();
                redraw();
            }
        } else if (dX > 0 && first && selected.isEmpty() && after.isEmpty() && !before.isEmpty()) {
            // Вправо из конца текста: крестик
            onClear = true;
            redraw();
        } else if (dX != 0) {
            if (first) {
                lastStep = e.getEventTime();
                caret(dX, false);
            } else {
                caretHeld(e, e.getEventTime() - e.getDownTime(), dX);
            }
            redraw();
        }
    }

    private void leaveStrip() {
        onClear = false;
        if (stripBack >= 0) {
            fRow = 0;
            fIndex = Math.min(stripBack, rows[0].length - 1);
            rememberX();
        } else {
            goStartKey();
        }
        redraw();
    }

    private void rememberX() {
        if (fRow == STRIP) return;
        Layouts.Key k = rows[fRow][fIndex];
        stickX = k.x + k.w / 2f;
    }

    private static int indexAt(Layouts.Key[] row, float x) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < row.length; i++) {
            if (x >= row[i].x && x < row[i].x + row[i].w) return i;
            float d = Math.abs(row[i].x + row[i].w / 2f - x);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    // ===== Действия =====

    private void act(Layouts.Key k) {
        switch (k.kind) {
            case Layouts.LAYER:
                layer = layer == 0 ? 1 : 0;
                rebuild();
                break;
            case Layouts.PAGE:
                layer = layer == 1 ? 2 : 1;
                rebuild();
                break;
            case Layouts.ABC:
                field = Layouts.F_TEXT;
                fromNumber = true;
                language = languageOfField();
                rebuild();
                goStartKey();
                if (view != null) view.requestLayout();
                break;
            case Layouts.EYE: eyeOrPaste(); break;
            case Layouts.NEWLINE:
                commit("\n");
                break;
            case Layouts.ENTER: enter(); break;
            default: break;
        }
    }

    private void toggleLanguage() {
        language = language == Layouts.RU ? Layouts.EN : Layouts.RU;
        prefs.edit().putInt(techField() ? "lang_tech" : "lang_text", language).apply();
        layer = 0;
        rebuild();
        redraw();
    }

    // Аварийный выход, если своя клавиатура где-то не годится
    private void nextKeyboard() {
        if (switchToPreviousInputMethod()) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showInputMethodPicker();
    }

    private void type(String s, boolean upper) {
        boolean letter = s.length() == 1 && Character.isLetter(s.charAt(0));
        String out = letter && (shift || upper) ? s.toUpperCase(Locale.ROOT) : s;
        // Домен после уже набранной «@» и окончание после точки не удваивают знак
        if (s.length() > 1 && (s.charAt(0) == '@' || s.charAt(0) == '.')) {
            InputConnection ic = getCurrentInputConnection();
            CharSequence left = ic == null ? null : ic.getTextBeforeCursor(1, 0);
            if (left != null && left.length() == 1 && left.charAt(0) == s.charAt(0)) out = s.substring(1);
        }
        commit(out);
        // Регистр держится до первой буквы: цифры и знаки его не гасят
        if (letter && shift && !caps) shift = false;
        if (field == Layouts.F_PASSWORD) showLastBriefly();
    }

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(s, 1);
        refreshText();
        refreshSoon();
    }

    private void backspace(boolean word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (notEmpty(sel)) {
            ic.commitText("", 1);
        } else if (word) {
            CharSequence b = ic.getTextBeforeCursor(64, 0);
            ic.deleteSurroundingTextInCodePoints(wordStep(b, true), 0);
        } else {
            ic.deleteSurroundingTextInCodePoints(1, 0);
        }
        refreshText();
        refreshSoon();
    }

    private void caret(int dir, boolean word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (selStart < 0) {
            sendDownUpKeyEvents(dir < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
            return;
        }
        // С выделения курсор встаёт на его край
        if (selEnd != selStart) {
            setCaret(ic, dir < 0 ? Math.min(selStart, selEnd) : Math.max(selStart, selEnd));
            return;
        }
        int step = 1;
        if (word) step = dir < 0 ? wordStep(ic.getTextBeforeCursor(64, 0), true) : wordStep(ic.getTextAfterCursor(64, 0), false);
        else if (dir > 0 && !notEmpty(ic.getTextAfterCursor(1, 0))) step = 0;
        setCaret(ic, Math.max(0, selStart + dir * step));
    }

    // Запоминаем сразу: на удержании следующий шаг приходит раньше, чем поле сообщит, где теперь курсор
    private void setCaret(InputConnection ic, int p) {
        ic.setSelection(p, p);
        selStart = p;
        selEnd = p;
        refreshText();
        refreshSoon();
    }

    private void collapseSelection() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && selEnd != selStart) setCaret(ic, Math.max(selStart, selEnd));
    }

    // Сколько знаков до границы слова: для ссылок границы это ещё и / . ? & = _ -
    private static int wordStep(CharSequence s, boolean backwards) {
        if (s == null || s.length() == 0) return 1;
        int n = s.length(), i = backwards ? n - 1 : 0, count = 0;
        boolean inWord = false;
        while (backwards ? i >= 0 : i < n) {
            char c = s.charAt(i);
            boolean sep = !Character.isLetterOrDigit(c);
            if (sep && inWord) break;
            if (!sep) inWord = true;
            count++;
            i += backwards ? -1 : 1;
        }
        return Math.max(1, count);
    }

    // Крестик: первый OK выделяет всё, второй стирает. Случайное нажатие ничего не теряет
    private void clearPress() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (notEmpty(ic.getSelectedText(0))) {
            ic.commitText("", 1);
            onClear = false;
        } else {
            ic.performContextMenuAction(android.R.id.selectAll);
        }
        refreshText();
        refreshSoon();
    }

    private void pressShift() {
        long now = SystemClock.uptimeMillis();
        if (caps) { caps = false; shift = false; }
        else if (shift && now - shiftAt < DOUBLE_SHIFT_MS) caps = true;
        else shift = !shift;
        shiftAt = now;
    }

    private void readClip() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        hasClip = cm != null && cm.hasPrimaryClip();
    }

    private void eyeOrPaste() {
        if (field == Layouts.F_PASSWORD || numberPassword) {
            showPassword = !showPassword;
            prefs.edit().putBoolean("pwd_show", showPassword).apply();
            refreshText();
            return;
        }
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        CharSequence t = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        if (notEmpty(t)) {
            commit(t.length() > 2000 ? t.subSequence(0, 2000).toString() : t.toString());
        } else {
            note = "буфер пуст";
            h.removeCallbacks(clearNote);
            h.postDelayed(clearNote, 2000);
        }
    }

    private final Runnable clearNote = () -> { note = ""; redraw(); };

    private void enter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
            if (action == EditorInfo.IME_ACTION_DONE) requestHideSelf(0);
        } else if (multiline) {
            requestHideSelf(0);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    private void showLastBriefly() {
        if (showPassword) return;
        lastVisible = true;
        refreshText();
        h.removeCallbacks(hideLast);
        h.postDelayed(hideLast, SHOW_LAST_MS);
    }

    private final Runnable hideLast = () -> {
        lastVisible = false;
        refreshText();
        redraw();
    };

    // Браузеры сообщают о правке с задержкой, а то и не сообщают: строку с текстом перечитываем чуть позже
    private void refreshSoon() {
        h.removeCallbacks(refreshLater);
        h.postDelayed(refreshLater, 60);
    }

    private final Runnable refreshLater = () -> {
        refreshText();
        redraw();
    };

    private void refreshText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) { before = ""; selected = ""; after = ""; return; }
        CharSequence b = ic.getTextBeforeCursor(400, 0), s = ic.getSelectedText(0), a = ic.getTextAfterCursor(200, 0);
        before = line(b, true);
        selected = s == null ? "" : s.toString().replace('\n', ' ');
        after = line(a, false);
        if (before.isEmpty() && selected.isEmpty() && after.isEmpty()) onClear = false;
        if ((field == Layouts.F_PASSWORD || numberPassword) && !showPassword) {
            before = mask(before, lastVisible);
            selected = mask(selected, false);
            after = mask(after, false);
        }
    }

    // В многострочном поле видно только текущую строку
    private static String line(CharSequence c, boolean tail) {
        if (c == null) return "";
        String s = c.toString();
        int i = tail ? s.lastIndexOf('\n') : s.indexOf('\n');
        if (i < 0) return s;
        return tail ? s.substring(i + 1) : s.substring(0, i);
    }

    private static String mask(String s, boolean keepLast) {
        if (s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) out.append(keepLast && i == s.length() - 1 ? s.charAt(i) : '•');
        return out.toString();
    }

    // ===== Что рисовать =====

    @Override public Layouts.Key[][] rows() { return rows; }
    @Override public int focusRow() { return fRow; }
    @Override public int focusIndex() { return fIndex; }
    @Override public boolean pressed() { return pressedNow; }
    @Override public boolean upperCase() { return shift && layer == 0; }
    @Override public boolean focusUpper() { return holdUpper; }
    @Override public boolean shiftOn() { return shift; }
    @Override public boolean capsOn() { return caps; }
    @Override public boolean languageRu() { return language == Layouts.RU; }
    @Override public boolean hasLanguage() { return field != Layouts.F_NUMBER; }
    @Override public boolean onClear() { return onClear; }
    @Override public boolean clearFont() { return field == Layouts.F_PASSWORD || field == Layouts.F_URL || field == Layouts.F_EMAIL; }
    @Override public String before() { return before; }
    @Override public String selected() { return selected; }
    @Override public String after() { return after; }
    @Override public String placeholder() { return placeholder; }

    @Override public String chip() {
        if (field == Layouts.F_NUMBER) return "123";
        if (layer > 0) return "?123";
        return language == Layouts.RU ? "РУС" : "ENG";
    }

    @Override public String note() {
        if (!note.isEmpty()) return note;
        if (fRow == STRIP) {
            if (onClear) return selected.isEmpty() ? "OK два раза: стереть всё" : "ещё OK: стереть всё";
            return "OK: стереть символ · Вниз: к буквам";
        }
        if (caps) return "ВСЕ ЗАГЛАВНЫЕ";
        if (shift) return "следующая заглавная";
        return "";
    }

    // Показывается, только пока строка почти пустая: когда текста много, она не мешает
    @Override public String hint() {
        return field == Layouts.F_NUMBER ? "зажать Назад: стереть · Назад: закрыть"
                : "зажать OK: заглавная · зажать Назад: стереть · зажать пробел: язык · Назад: закрыть";
    }

    @Override public String label(Layouts.Key k) {
        switch (k.kind) {
            case Layouts.LAYER: return layer > 0 ? (language == Layouts.RU ? "АБВ" : "ABC") : "?123";
            case Layouts.ENTER: return actionLabel;
            case Layouts.EYE:
                if (field == Layouts.F_PASSWORD || numberPassword) return showPassword ? "Скрыть" : "Показ";
                return "Вставить";
            case Layouts.SHIFT: return "⇧";
            default: return k.text;
        }
    }

    @Override public boolean dim(Layouts.Key k) {
        return k.kind == Layouts.EYE && field != Layouts.F_PASSWORD && !numberPassword && !hasClip;
    }
}
