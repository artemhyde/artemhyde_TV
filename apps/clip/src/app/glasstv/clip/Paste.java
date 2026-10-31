package app.glasstv.clip;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

// Кладёт присланный с ПК текст в буфер обмена телевизора, потом скрипт нажимает «Вставить».
// Текст приходит в base64 (extra "b64"): так через adb проходят кириллица, кавычки и любые знаки.
public class Paste extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String b64 = getIntent().getStringExtra("b64");
        if (b64 != null) {
            try {
                String text = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("ПК", text));
            } catch (IllegalArgumentException ignored) {
                // Испорченный base64: буфер не трогаем
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
