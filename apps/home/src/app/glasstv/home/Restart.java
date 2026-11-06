package app.glasstv.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

// Перезапуск «Дома» после смены цвета стекла: Theme считает числа один раз при старте процесса.
// Живёт в отдельном процессе :restart (как ProcessPhoenix): гасит основной процесс и открывает главный экран,
// который поднимается уже в новом процессе с новой темой. Окно прозрачное, на экране только короткая смена кадра
public class Restart extends Activity {
    static void now(Context c) {
        c.startActivity(new Intent(c, Restart.class).putExtra("pid", android.os.Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pid = getIntent().getIntExtra("pid", -1);
        if (pid > 0 && pid != android.os.Process.myPid()) android.os.Process.killProcess(pid);
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
