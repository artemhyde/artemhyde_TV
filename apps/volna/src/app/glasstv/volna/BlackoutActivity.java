package app.glasstv.volna;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;

// «Только звук»: чёрный экран с погашенной подсветкой, музыка играет. Любая кнопка возвращает картинку
public class BlackoutActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f;
        getWindow().setAttributes(lp);

        TextView hint = new TextView(this);
        hint.setText("Только звук. Любая кнопка вернёт экран");
        hint.setTextSize(20);
        hint.setTextColor(0x66FFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(Color.BLACK);
        setContentView(hint);
        hint.animate().alpha(0f).setStartDelay(3500).setDuration(1500).start();
    }

    // Пока экран погашен, плашка о смене трека не нужна
    @Override protected void onResume() {
        super.onResume();
        Engine.uiVisible++;
    }

    @Override protected void onPause() {
        Engine.uiVisible--;
        Engine.get(this).uiHidden();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        switch (e.getKeyCode()) {
            // Громкость и музыкальные кнопки работают, экран остаётся тёмным
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.dispatchKeyEvent(e);
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (e.getAction() == KeyEvent.ACTION_DOWN) Engine.get(this).toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (e.getAction() == KeyEvent.ACTION_DOWN) Engine.get(this).next();
                return true;
        }
        // Выходим на отпускании, чтобы нажатие не досталось приложению под нами
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) finish();
        return true;
    }
}
