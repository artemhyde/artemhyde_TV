package app.glasstv.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Телевизор включился или Сторож обновился: запустить службу
public class Boot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) { GuardService.start(c); }
}
