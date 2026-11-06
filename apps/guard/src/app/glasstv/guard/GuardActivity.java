package app.glasstv.guard;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.Inet4Address;

// Экран «Сторож»: что сейчас в порядке и что он делал
public class GuardActivity extends Activity {
    private float dp;
    private Typeface regular, medium, semibold;
    private TextView adb, keys, notes, mem, night, log;
    private TextView soundBtn, adbBtn, notesBtn, reportRow;
    private int soundColor = 0xFFF5F5F7;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (GuardService.running == null) GuardService.start(this);
        dp = getResources().getDisplayMetrics().density;
        regular = Typeface.createFromAsset(getAssets(), "fonts/Inter_18pt-Regular.ttf");
        medium = Typeface.createFromAsset(getAssets(), "fonts/Inter_18pt-Medium.ttf");
        semibold = Typeface.createFromAsset(getAssets(), "fonts/Inter_18pt-SemiBold.ttf");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(64), px(48), px(64), px(40));
        root.setBackgroundColor(0xFF0B0B0E);

        TextView title = text("Сторож", 32, 0xFFF5F5F7, semibold);
        title.setLetterSpacing(-0.01f);
        root.addView(title);
        TextView sub = text("Держит в порядке отладку, кнопки пульта и принимает записки с телефона", 15, 0x99F5F5F7, regular);
        sub.setPadding(0, px(6), 0, px(28));
        root.addView(sub);

        adb = row(root, "Отладка по сети");
        keys = row(root, "Кнопки пульта (Key Mapper)");
        notes = row(root, "Записки с телефона");
        mem = row(root, "Память");
        night = row(root, "Ночью подсветка ниже");
        reportRow = row(root, "Отчёт для помощи");
        reportRow.setText("кнопка «Отчёт» сохранит его в «Загрузки», без личных данных");
        reportRow.setTextColor(0xFFF5F5F7);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, px(24), 0, px(12));
        View check = button(buttons, "Проверить сейчас", v -> {
            GuardService g = GuardService.running;
            if (g != null) { g.keepAdb("вручную"); g.checkKeys.run(); }
            h.postDelayed(this::refresh, 800);
        });
        button(buttons, "Пробная записка", v -> {
            GuardService g = GuardService.running;
            if (g != null) g.showNote("Так будет выглядеть записка с телефона");
        });
        button(buttons, "Ночная подсветка вкл/выкл", v -> {
            GuardService g = GuardService.running;
            if (g != null) g.setNightEnabled(!g.nightEnabled());
            h.postDelayed(this::refresh, 300);
        });
        soundBtn = (TextView) button(buttons, "Звук: выкл", v -> {
            android.content.SharedPreferences sp = getSharedPreferences("guard", MODE_PRIVATE);
            boolean want = !SmartSound.wanted(sp);
            GuardService g = GuardService.running;
            if (g != null && g.sound != null) g.sound.setEnabled(want);
            else {                                             // служба ещё поднимается: выбор запомнить, она его прочтёт
                sp.edit().putBoolean(SmartSound.P_ON, want).apply();
                GuardService.start(this);
            }
            h.removeCallbacksAndMessages(null);                // одна цепочка обновления, а не по одной на каждое нажатие
            h.postDelayed(this::refresh, 700);
        });
        soundBtn.setContentDescription("Умный звук: чёткие диалоги, без громких взрывов");
        soundBtn.setOnFocusChangeListener((v, f) -> ((TextView) v).setTextColor(f ? 0xFF0B0B0E : soundColor));
        root.addView(buttons);

        // Выключатели (06.11): отладка по сети по кругу «всегда», «2 часа обслуживания», «выключена»; записки вкл и выкл
        LinearLayout switches = new LinearLayout(this);
        switches.setPadding(0, 0, 0, px(24));
        adbBtn = (TextView) button(switches, "Отладка: всегда", v -> {
            GuardService g = GuardService.running;
            if (g == null) return;
            String m = g.adbMode();
            g.setAdbMode(GuardService.ADB_ALWAYS.equals(m) ? GuardService.ADB_SERVICE
                    : GuardService.ADB_SERVICE.equals(m) ? GuardService.ADB_OFF : GuardService.ADB_ALWAYS, "экран «Сторож»");
            h.removeCallbacksAndMessages(null);
            h.postDelayed(this::refresh, 500);
        });
        notesBtn = (TextView) button(switches, "Записки: вкл", v -> {
            GuardService g = GuardService.running;
            if (g != null) g.setNotes(!GuardService.notesEnabled(this));
            h.removeCallbacksAndMessages(null);
            h.postDelayed(this::refresh, 500);
        });
        // Отчёт для обслуживания (06.11, план Codex, блок 6): версии, права, состояние и журнал без личных данных, файлом в «Загрузки»
        button(switches, "Отчёт", v -> {
            reportRow.setText("собираю…");
            final android.content.Context app = getApplicationContext();
            new Thread(() -> {
                String where = Report.save(app, Report.build(app));
                h.post(() -> reportRow.setText(where.startsWith("Загрузки/")
                        ? "сохранён: " + where + " · на телефон: LocalSend или «Файлы»" : where));
            }, "guard-report").start();
        });
        root.addView(switches);

        TextView logTitle = text("Что делал", 13, 0x80F5F5F7, medium);
        logTitle.setLetterSpacing(0.08f);
        root.addView(logTitle);
        log = text("", 14, 0xB3F5F5F7, regular);
        log.setLineSpacing(0, 1.35f);
        log.setPadding(0, px(8), 0, 0);
        root.addView(log);

        // Экран прокручивается (06.11): с выключателями отладки и записок всё не помещалось, нижний ряд сплющивался
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundColor(0xFF0B0B0E);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(root, new android.widget.FrameLayout.LayoutParams(-1, -2));
        setContentView(scroll);
        check.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onPause() {
        h.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void refresh() {
        GuardService g = GuardService.running;
        boolean adbOn = g != null && g.adbOn();
        String mode = g == null ? GuardService.ADB_ALWAYS : g.adbMode();
        String until = g == null ? "" : new java.text.SimpleDateFormat("H:mm", java.util.Locale.US).format(new java.util.Date(g.adbUntil()));
        adb.setText((adbOn ? "включена" : "выключена") + (GuardService.ADB_SERVICE.equals(mode) ? " · обслуживание до " + until
                : GuardService.ADB_OFF.equals(mode) ? " · «Сторож» не включает" : " · держит всегда"));
        adb.setTextColor(adbOn ? 0xFF5ED3B4 : 0xFFFFB454);
        adbBtn.setText(GuardService.ADB_SERVICE.equals(mode) ? "Отладка: 2 часа" : GuardService.ADB_OFF.equals(mode) ? "Отладка: выключена" : "Отладка: всегда");
        notesBtn.setText(GuardService.notesEnabled(this) ? "Записки: вкл" : "Записки: выкл");
        String k = getSharedPreferences("guard", MODE_PRIVATE).getString("keys", "проверяю");
        keys.setText(k);
        keys.setTextColor(k.startsWith("не") ? 0xFFFFB454 : 0xFF5ED3B4);
        String ip = ip();
        notes.setText(!GuardService.notesEnabled(this) ? "выключены" : ip == null ? "нет сети" : "http://" + ip + ":" + GuardService.NOTE_PORT);
        notes.setTextColor(0xFFF5F5F7);
        long[] m = memory();
        mem.setText(m == null ? "?" : "свободно " + m[1] / 1024 + " МБ из " + m[0] / 1024);
        mem.setTextColor(0xFFF5F5F7);
        android.content.SharedPreferences sp = getSharedPreferences("guard", MODE_PRIVATE);
        boolean nightEnabled = sp.getBoolean("nightEnabled", true);
        String how = sp.getString("nightHow", "");
        int day = sp.getInt("blDay", -1), lvl = sp.getInt("nightLevel", GuardService.NIGHT_LEVEL);
        night.setText(!nightEnabled ? "выключено"
                : "вуаль".equals(how) ? "сейчас темнее вуалью: подсветку ведёт датчик света"
                : day > 0 ? "подсветка " + day + " → " + lvl + " до " + GuardService.nt()
                : "в " + GuardService.nf() + " станет " + lvl + " из 100, если яркость за день не меняли");
        night.setTextColor(0xFFF5F5F7);
        refreshSound(g);
        log.setText(getSharedPreferences("guard", MODE_PRIVATE).getString("log", ""));
        h.postDelayed(this::refresh, 3000);
    }

    private String ip() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp == null) return null;
            for (LinkAddress a : lp.getLinkAddresses())
                if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        } catch (Exception ignored) { }
        return null;
    }

    // {всего, доступно} в килобайтах
    private static long[] memory() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            long total = 0, avail = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) total = Long.parseLong(line.replaceAll("\\D+", ""));
                if (line.startsWith("MemAvailable:")) avail = Long.parseLong(line.replaceAll("\\D+", ""));
            }
            return new long[]{total, avail};
        } catch (Exception e) {
            return null;
        }
    }

    // ===== Умный звук: состояние на кнопке (подробности в журнале logcat -s guard) =====

    private void refreshSound(GuardService g) {
        boolean want = SmartSound.wanted(getSharedPreferences("guard", MODE_PRIVATE));
        SmartSound s = g == null ? null : g.sound;
        String t;
        int col = 0xFF5ED3B4;                                  // зелёный, как у «Отладка по сети: включена»
        if (!want) { t = "Звук: выкл"; col = 0xFFF5F5F7; }
        else if (s == null) { t = "Звук: запуск"; col = 0xFFFFB454; }
        else if (s.state() == SmartSound.ST_OK) t = "Звук: вкл";
        else if (s.state() == SmartSound.ST_WAIT) t = "Звук: ждёт";
        else if (s.state() == SmartSound.ST_NO_CONTROL) { t = "Звук: занят"; col = 0xFFFFB454; }
        else { t = "Звук: сбой"; col = 0xFFFFB454; }
        soundBtn.setText(t);
        soundColor = col;
        if (!soundBtn.isFocused()) soundBtn.setTextColor(col);
    }

    private int px(float v) { return Math.round(v * dp); }

    private TextView text(String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView row(LinearLayout parent, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(12), 0, px(12));
        TextView l = text(label, 18, 0x99F5F5F7, regular);
        r.addView(l, new LinearLayout.LayoutParams(px(330), -2));
        TextView v = text("", 18, 0xFFF5F5F7, medium);
        r.addView(v);
        parent.addView(r);
        View line = new View(this);
        line.setBackgroundColor(0x14FFFFFF);
        parent.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, px(0.5f))));
        return v;
    }

    private View button(LinearLayout parent, String s, View.OnClickListener l) {
        TextView t = text(s, 16, 0xFFF5F5F7, medium);
        t.setPadding(px(22), px(12), px(22), px(12));
        t.setFocusable(true);
        t.setClickable(true);
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(px(12));
        on.setColor(0xFFF5F5F7);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(px(12));
        off.setColor(0x1FFFFFFF);
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{}, off);
        t.setBackground(sl);
        t.setOnFocusChangeListener((v, f) -> ((TextView) v).setTextColor(f ? 0xFF0B0B0E : 0xFFF5F5F7));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (parent.getChildCount() > 0) lp.leftMargin = px(12);
        parent.addView(t, lp);
        return t;
    }
}
