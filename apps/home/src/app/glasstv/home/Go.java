package app.glasstv.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.util.regex.Pattern;

// Два способа открыть приложение:
// fresh: с плитки, с чистого листа, если ТВ гас после прошлого использования (приложение не встречает брошенным вчера местом);
// resume: капсулой «Продолжить», наоборот, на то же место, где остановились
final class Go {
    // С чистого листа не открываем: у браузера вкладки, Key Mapper настроен под своё место, «Волна» играет при погасшем экране,
    // остальные наши приложения и панели ничего не бросают на полпути
    private static final String[] KEEP = {"app.glasstv.", "com.phlox.tvwebbrowser", "io.github.sds100.keymapper", "com.auramusic."};
    // Экран Lampa «История» в том же строгом JSON, что у плиток «Фильмы» и «Сериалы» (Tiles/tiles.txt). Поля те же, что
    // Lampa сама собирает для истории в handleChannelIntent и start_page favorite@history: component favorite, type history
    private static final String LAMPA_HISTORY = "{\"title\":\"История\",\"component\":\"favorite\",\"type\":\"history\",\"url\":\"\",\"page\":1}";
    private static final Pattern YT = Pattern.compile("[A-Za-z0-9_-]{11}"), VKID = Pattern.compile("-?\\d+_\\d+");

    private Go() { }

    static Intent plain(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static boolean keep(String pkg) {
        for (String k : KEEP) if (pkg.startsWith(k)) return true;
        return false;
    }

    // Запуск плитки с чистого листа или null, если запускать надо как обычно. base: обычный запуск (копия, её можно менять).
    // Ярлык плитки (app.glasstv.tile.*) сам лишь перепоручает запуск настоящему приложению, и флаги, поставленные ему, до
    // приложения не доходят: поэтому его правила (куда, какой экран, какой экран Lampa) разворачиваем здесь
    static Intent fresh(Context c, String pkg, Intent base) {
        String target = pkg;
        Intent real = base;
        if (pkg.startsWith("app.glasstv.tile.")) {
            try {
                ActivityInfo ai = c.getPackageManager().getActivityInfo(base.getComponent(), PackageManager.GET_META_DATA);
                Bundle m = ai.metaData;
                target = m == null ? null : m.getString("target");
                if (target == null) return null;
                String comp = m.getString("component"), lampa = m.getString("lampaActivity");
                real = comp != null && !comp.isEmpty() ? new Intent().setClassName(target, comp) : plain(c, target);
                if (real == null) return null;
                if (lampa != null && !lampa.isEmpty()) real.putExtra("continueWatch", true).putExtra("lampaActivity", lampa);
            } catch (Exception e) {
                return null;
            }
        }
        if (keep(target) || !Usage.clean(target)) return null;
        Log.i(Usage.TAG, "плитка " + pkg + ": чистый запуск " + target);
        return real.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    // Капсула «Продолжить»: точно к тому же ролику, если умеем, иначе просто открыть приложение
    static void resume(Activity a, Last.Rec r, View from) {
        Bundle opts = from == null ? null
                : ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
        String app = Last.K_LAMPA.equals(r.kind) ? Last.LAMPA : r.pkg;
        Intent exact = null;
        try { exact = exact(a, r); } catch (Exception ignored) { }
        if (exact != null && start(a, exact, opts)) return;
        Intent plain = plain(a, app);
        // Файл: Just Player сам открывает свой последний файл на сохранённой секунде, но на паузе. «Играть» нажмёт Watch,
        // и только если в плеере открыт файл из записи (по названию), иначе плеер останется на паузе
        if (plain != null && start(a, plain, opts) && Last.K_FILE.equals(r.kind)) Watch.playSoon(r.pkg, r.title);
    }

    private static boolean start(Activity a, Intent i, Bundle opts) {
        try {
            a.startActivity(i, opts);
            return true;
        } catch (Exception e) {
            Log.w(Usage.TAG, "продолжить: запуск не вышел (" + e + "), открою приложение просто так");
            return false;
        }
    }

    // Точный возврат: SmartTube открывает ссылку на ролик с секундой, VK Видео ссылку на видео (если id похож на настоящий),
    // Lampa свою страницу «История» (там этот фильм первым, а продолжает с места сама Lampa). Файл: null, он идёт отдельно
    private static Intent exact(Context c, Last.Rec r) {
        switch (r.kind) {
            case Last.K_ST:
                if (!YT.matcher(r.id).matches()) return null;
                return view("https://www.youtube.com/watch?v=" + r.id + (r.pos >= 1000 ? "&t=" + r.pos / 1000 : ""), Last.ST);
            case Last.K_VK:
                if (!VKID.matcher(r.id).matches()) return null;
                return view("https://vk.com/video" + r.id, Last.VK);
            case Last.K_LAMPA: {
                Intent i = plain(c, Last.LAMPA);
                return i == null ? null : i.putExtra("continueWatch", true).putExtra("lampaActivity", LAMPA_HISTORY)
                        .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            }
            default:
                return null;
        }
    }

    private static Intent view(String url, String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
