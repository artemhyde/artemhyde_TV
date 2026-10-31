package app.glasstv.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

// Невидимый вход в полосу таблеток для Key Mapper («Launch app» на пакет «Дома»): явный запуск полосы и сразу закрыться.
// Открытая полоса получает этот запуск в onNewIntent и закрывается, так шестерёнка работает переключателем
public class PillsLaunch extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            startActivity(new Intent(this, PillsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
