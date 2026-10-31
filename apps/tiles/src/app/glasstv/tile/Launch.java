package app.glasstv.tile;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

// Ярлык с красивой плиткой: открывает настоящее приложение и сразу закрывается.
// Какое приложение открыть, написано в манифесте: meta-data "target".
// "component": открыть конкретный экран приложения. Для Lampa есть ещё "lampaActivity": экран, на котором она откроется (фильмы, сериалы). Lampa сама
// принимает его в паре с continueWatch=true и делает Lampa.Activity.push (MainActivity.handleContinueWatch)
public class Launch extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String target = null, lampa = null, component = null;
        try {
            ActivityInfo ai = getPackageManager().getActivityInfo(getComponentName(), PackageManager.GET_META_DATA);
            if (ai.metaData != null) {
                target = ai.metaData.getString("target");
                lampa = ai.metaData.getString("lampaActivity");
                component = ai.metaData.getString("component");
            }
        } catch (PackageManager.NameNotFoundException ignored) { }

        Intent i = null;
        if (target != null && component != null && !component.isEmpty()) {
            // Нужный экран приложения, а не его главный вход (например карточка «Волны» для кнопки пульта)
            i = new Intent().setClassName(target, component);
        } else if (target != null) {
            PackageManager pm = getPackageManager();
            i = pm.getLeanbackLaunchIntentForPackage(target);
            if (i == null) i = pm.getLaunchIntentForPackage(target);
        }
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (lampa != null && !lampa.isEmpty()) i.putExtra("continueWatch", true).putExtra("lampaActivity", lampa);
            try {
                startActivity(i);
            } catch (Exception e) {
                i = null;
            }
        }
        if (i == null) Toast.makeText(this, "Приложение не найдено: " + target, Toast.LENGTH_LONG).show();
        finish();
        overridePendingTransition(0, 0);
    }
}
