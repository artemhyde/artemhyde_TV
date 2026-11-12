package app.glasstv.guard;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// Отчёт для обслуживания (план Codex, блок 6): версии, права, состояние «Сторожа» и его журнал, чтобы человек мог сообщить
// о воспроизводимой проблеме без паролей, cookie, ключей и полного дампа ТВ. Всё проходит через Scrub.
// Кнопка «Отчёт» на экране «Сторожа» кладёт файл в «Загрузки» (оттуда на телефон через LocalSend или «Файлы»);
// с ПК: am broadcast -n app.glasstv.guard/.Cmd --es do report. Не из главного потока: паспорт ТВ опрашивает службу Dolby
final class Report {
    private Report() { }

    // Чужие приложения, версии которых важны для разбора (свои app.glasstv.* берутся все)
    private static final String[] OTHERS = {"top.rootu.lampa", "app.glasstv.browser", "com.phlox.tvwebbrowser.foss", "org.smarttube.stable",
            "com.liskovsoft.smarttubetv.beta", "io.github.sds100.keymapper", "com.google.android.webview", "com.brouken.player",
            "me.zhanghai.android.files", "org.localsend.localsend_app", ByeRules.PKG};

    static String build(Context c) {
        StringBuilder b = new StringBuilder();
        SimpleDateFormat df = new SimpleDateFormat("d MMMM yyyy, H:mm", Locale.forLanguageTag("ru"));
        b.append("Отчёт HYDE TV, ").append(df.format(new Date())).append('\n');
        b.append("ТВ: ").append(Caps.device()).append("; уровень поддержки: ").append(Caps.level()).append('\n');

        b.append("\nПриложения\n");
        PackageManager pm = c.getPackageManager();
        List<String> pkgs = new ArrayList<>();
        for (PackageInfo pi : pm.getInstalledPackages(0)) if (pi.packageName.startsWith("app.glasstv.")) pkgs.add(pi.packageName);
        Collections.sort(pkgs);
        for (String p : OTHERS) pkgs.add(p);
        SimpleDateFormat dd = new SimpleDateFormat("d.MM.yyyy", Locale.US);
        for (String p : pkgs) {
            try {
                PackageInfo pi = pm.getPackageInfo(p, 0);
                String on = pi.applicationInfo.enabled ? "" : ", отключено";
                b.append("  ").append(pm.getApplicationLabel(pi.applicationInfo)).append(" (").append(p).append(") ").append(pi.versionName)
                        .append(", сборка ").append(pi.getLongVersionCode()).append(", обновлено ").append(dd.format(new Date(pi.lastUpdateTime))).append(on).append('\n');
            } catch (Exception ignored) { }
        }

        b.append("\nСистема\n");
        GuardService g = GuardService.running;
        b.append("  главный экран: ").append(homeHolder(c)).append('\n');
        b.append("  клавиатура: ").append(secure(c, Settings.Secure.DEFAULT_INPUT_METHOD)).append('\n');
        b.append("  заставка Android: ").append("0".equals(secure(c, "screensaver_enabled")) ? "выключена" : "включена").append(", экран гаснет через ")
                .append(Settings.System.getString(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT)).append(" мс\n");
        SharedPreferences sp = c.getSharedPreferences("guard", Context.MODE_PRIVATE);
        b.append("  кнопки пульта (Key Mapper): ").append(sp.getString("keys", "?")).append('\n');
        b.append("  отключённые установщиком: ").append(Health.listStatus(c)).append('\n');
        b.append("  права «Сторожа»: DUMP ").append(granted(c, "android.permission.DUMP")).append(", WRITE_SECURE_SETTINGS ")
                .append(granted(c, "android.permission.WRITE_SECURE_SETTINGS")).append(", слушатель уведомлений ")
                .append(String.valueOf(secure(c, "enabled_notification_listeners")).contains(c.getPackageName()) ? "да" : "нет").append('\n');
        long[] m = memory();
        if (m != null) b.append("  память: свободно ").append(m[1] / 1024).append(" МБ из ").append(m[0] / 1024).append('\n');

        b.append("\n«Сторож»\n");
        if (g == null) {
            b.append("  служба не запущена\n");
        } else {
            b.append("  отладка по сети: ").append(g.adbOn() ? "включена" : "выключена").append(", режим ").append(g.adbMode()).append('\n');
            b.append("  записки с телефона: ").append(GuardService.notesEnabled(c) ? "включены" : "выключены").append('\n');
            if (g.dns != null) b.append("  DNS: стоит ").append(g.dns.current()).append(", выбранный ").append(g.dns.pref())
                    .append(", интернет ").append(DnsWatch.ipUp() ? "есть" : "нет").append('\n');
            b.append("  простой: ").append(g.idleReport()).append('\n');
            if (g.bye != null && (has(c, ByeRules.PKG) || sp.getBoolean("byeWatch", false)))
                b.append("  ByeByeDPI: ").append(g.bye.status()).append('\n');
            if (g.upd != null) b.append("  обновления: ").append(g.upd.status()).append('\n');
            if (g.upd != null) b.append("  обновления HYDE: ").append(g.upd.hyde.status()).append('\n');
            if (g.upd != null) b.append("  отчёты автору: ").append(g.upd.ping.status()).append('\n');
        }
        b.append("  возможности ТВ: ").append(Caps.report(c)).append('\n');
        // Погода и курсы «Дома» (12.11): пришли ли и почему нет, без города и страны
        String[] hs = PingRules.homeState(Ping.homeState(c));
        if (hs[0] != null) b.append("  погода «Дома»: ").append(hs[0]).append('\n');
        if (hs[1] != null) b.append("  курсы «Дома»: ").append(hs[1]).append('\n');

        b.append("\nЖурнал «Сторожа» (последние записи)\n");
        for (String line : sp.getString("log", "").split("\n")) if (!line.trim().isEmpty()) b.append("  ").append(line).append('\n');
        return Scrub.clean(b.toString());
    }

    // Положить отчёт в «Загрузки». Ответ: имя файла или текст ошибки
    static String save(Context c, String text) {
        String name = "hyde-report-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".txt";
        if (android.os.Build.VERSION.SDK_INT < 29) return saveFile(c, name, text);
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return "не сохранился: «Загрузки» недоступны";
            try (OutputStream o = c.getContentResolver().openOutputStream(u)) {
                o.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return "Загрузки/" + name;
        } catch (Exception e) {
            return "не сохранился (" + e.getClass().getSimpleName() + ")";
        }
    }

    // Android 9: «Загрузки» через MediaStore появились только в Android 10. Пишем файлом в общую папку «Загрузки»
    // (если у «Сторожа» есть право на запись), иначе в папку «Сторожа» на том же диске
    private static String saveFile(Context c, String name, String text) {
        File[] dirs = {android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                c.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)};
        String err = "«Загрузки» недоступны";
        for (int k = 0; k < dirs.length; k++) {
            if (dirs[k] == null) continue;
            try {
                dirs[k].mkdirs();
                File f = new File(dirs[k], name);
                try (OutputStream o = new FileOutputStream(f)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
                return k == 0 ? "Загрузки/" + name : f.getAbsolutePath();
            } catch (Exception e) { err = e.getClass().getSimpleName(); }
        }
        return "не сохранился (" + err + ")";
    }

    private static boolean has(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    private static String homeHolder(Context c) {
        android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(
                new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return r == null || r.activityInfo == null ? "?" : r.activityInfo.packageName;
    }

    private static String secure(Context c, String key) {
        try { return String.valueOf(Settings.Secure.getString(c.getContentResolver(), key)); } catch (Exception e) { return "?"; }
    }

    private static String granted(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED ? "да" : "нет";
    }

    private static long[] memory() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            long total = 0, avail = 0;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("MemTotal:")) total = Long.parseLong(line.replaceAll("\\D+", ""));
                if (line.startsWith("MemAvailable:")) avail = Long.parseLong(line.replaceAll("\\D+", ""));
            }
            return new long[]{total, avail};
        } catch (Exception e) {
            return null;
        }
    }
}
