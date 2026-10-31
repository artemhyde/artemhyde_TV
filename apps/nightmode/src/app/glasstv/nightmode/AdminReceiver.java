package app.glasstv.nightmode;

import android.app.admin.DeviceAdminReceiver;

// Нужен только ради DevicePolicyManager.lockNow(): им телевизор засыпает по таймеру
public class AdminReceiver extends DeviceAdminReceiver {
}
