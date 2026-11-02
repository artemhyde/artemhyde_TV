package app.glasstv.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

// Команды с ПК для проверки: подсветка TCL и ночная вуаль
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do bl                 прочитать
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do bl --ei v 20       применить без сохранения
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do bl --ei v 20 --ez save true
// Умный звук (выравнивание громкости, SmartSound.java). Итог смотреть: adb logcat -d -s guard
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do sound                          состояние
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do sound --ez on true             включить и запомнить (false: выключить)
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do sound --ei level 1             0 мягко, 1 обычно, 2 ночь
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do sound --ei mode 1              0 сессия 0, 1 запасной путь по сессиям плееров
// adb shell 'am broadcast -n app.glasstv.guard/.Cmd --es do sound --es tune "mid.boost=7"'   поправка на слух; reset сбрасывает
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do sound --es act dump            что реально стоит в движке (чтение обратно)
// act: dump, rebuild (пересоздать эффект), verify (жив ли эффект и у нас ли управление)
// Картинка TCL (Pq.java):
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do pq --es a dump [--ez full false]   снимок настроек, только чтение (logcat, тег guard, и files/pq-*.json)
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do pq --es a apply --es p evening     профиль «Вечер» без сохранения [--es set "backlight=18,lowBlueRay=1"] [--ez force true]
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do pq --es a restore                   вернуть снимок «Дня»; ещё status, forget, auto --ez on true|false (автоматика 22:00 и 7:00)
// Приложение «Картинка» (27.10, Pic.java, там протокол и правило приоритета; do=pq это автоматика «Вечер» и «День», do=pic это выбор пользователя):
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do pic                                  состояние, ответ в консоли: data={"v":1,"preset":"kino","backlight":18,...}
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do pic --es a set --es preset kino      режим: kino, day, hdr, soft (подсветка берётся под сигнал SDR или HDR)
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do pic --es a set --ei backlight 30 --ei black 2 --ei peak 1 --ei warm 3   ползунки: подсветка 5..100, чёрный 0..2, пик 0..3, тепло -5..5
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do pic --es a reset                     снять выбор: вернуть как было, дальше картинку ведёт автоматика
// Выбор держится до границы 22:00 или 7:00, пока он держится, «Вечер», «День» и ночной ход подсветки молчат (GuardService: applyPq, startNight, endNight)
// Главный экран, как при выключении ТВ (27.10, GuardService.homeStep): сразу, без ожидания 1,5 с, экран может быть и включён. Идёт видео (медиасессия «играет», кроме музыки «Волны»): ничего не делает, пишет об этом
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do home                                  итог: adb logcat -d -s guard:I ("команда home: на главный экран" или "...идёт видео...")

public class Cmd extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        // Картинка TCL. Работа идёт в очереди Pq, не в главном потоке: вызовы службы TCL и плавный ход до 45 секунд
        if ("pq".equals(i.getStringExtra("do"))) {
            final Context app = c.getApplicationContext();
            final String a = i.getStringExtra("a") == null ? "dump" : i.getStringExtra("a");
            if ("auto".equals(a)) {
                boolean on = i.getBooleanExtra("on", true);
                app.getSharedPreferences("guard", Context.MODE_PRIVATE).edit().putBoolean("pqAuto", on).apply();
                Log.i("guard", "pq auto: " + (on ? "включена (22:00 вечер, 7:00 день)" : "выключена"));
                GuardService g = GuardService.running;
                if (g != null) g.applyPq("команда");
                return;
            }
            final String p = i.getStringExtra("p"), set = i.getStringExtra("set");
            final boolean flag = i.getBooleanExtra(a.equals("dump") ? "full" : "force", a.equals("dump"));
            Pq.async(() -> Log.i("guard", "pq " + a + ": " + Pq.command(app, a, p, set, flag)));
            return;
        }
        // Скрытая служба Dolby в прошивке (проба). Все операции в отдельном потоке: bind и вызовы блокируют.
        // adb shell am broadcast -n app.glasstv.guard/.Cmd --es do dolby --es op dump                         прочитать всё (ничего не меняет)
        // adb shell am broadcast -n app.glasstv.guard/.Cmd --es do dolby --es op help                         список имён для set
        // adb shell am broadcast -n app.glasstv.guard/.Cmd --es do dolby --es op set --es key vlmode --es val 1
        // adb shell am broadcast -n app.glasstv.guard/.Cmd --es do dolby --es op set --es key de --es val 8
        // adb shell am broadcast -n app.glasstv.guard/.Cmd --es do dolby --es op restore                      откатить то, что менял этот процесс
        // adb shell am broadcast -n app.glasstv.guard/.Cmd --es do dolby --es op restore --es snap neutral    всё нейтральное явно
        // adb shell am broadcast -n app.glasstv.guard/.Cmd --es do dolby --es op set --es key flexr --es val force:1   эксперименты flexr и centermode только с приставкой force:
        // Ответ приходит и в консоль ("data=..."), и в лог: adb logcat -d -s guard:I
        if ("dolby".equals(i.getStringExtra("do"))) {
            final String op = i.getStringExtra("op"), key = i.getStringExtra("key"), val = i.getStringExtra("val"), snap = i.getStringExtra("snap");
            final PendingResult pr = goAsync();
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                String out;
                try {
                    out = DolbyProbe.run(app, op, key, val, snap);
                } catch (Throwable e) {
                    out = "dolby: сбой " + e;
                    Log.w("guard", out, e);
                }
                pr.setResultData(out);
                pr.finish();
            }, "dolby-probe").start();
            return;
        }
        // Панель «Звук» (приложение app.glasstv.sound, полный протокол в его Eq.java). a=state (по умолчанию) только читает,
        // a=set пишет и применяет пять величин. Работает и без службы: числа сохраняются в SharedPreferences и применятся при её запуске.
        // Ответ (плоский JSON, поля в Eq.java) кладётся в resultData упорядоченной рассылки (так шлёт и am broadcast) и в журнал.
        // Тяжёлое (эффект, служба Dolby) идёт в потоке SmartSound, здесь ничего не блокируется.
        // adb shell am broadcast -n app.glasstv.guard/.Cmd --es do eq                                   прочитать, ответ в консоли: data={"v":1,"preset":...}
        // adb shell am broadcast -n app.glasstv.guard/.Cmd --es do eq --es a set --es preset night       пресет: kino, music, night, clean
        // adb shell am broadcast -n app.glasstv.guard/.Cmd --es do eq --es a set --ei de 9 --ei vl 10 --ei bass -2 --ei treble 0   речь 0..12, выравнивание 0..10, бас и детали -5..5
        if ("eq".equals(i.getStringExtra("do"))) {
            android.content.SharedPreferences sp = c.getApplicationContext().getSharedPreferences("guard", Context.MODE_PRIVATE);
            GuardService g = GuardService.running;
            SmartSound s = g == null ? null : g.sound;
            if ("set".equals(i.getStringExtra("a"))) {
                SmartSound.eqSave(sp, i.getStringExtra("preset"), SmartSound.intExtra(i, "de"), SmartSound.intExtra(i, "vl"),
                        SmartSound.intExtra(i, "bass"), SmartSound.intExtra(i, "treble"));
                if (s != null) s.eqChanged();
            }
            String json = SmartSound.eqJson(sp, s);
            if (isOrderedBroadcast()) { setResultCode(android.app.Activity.RESULT_OK); setResultData(json); }
            Log.i("guard", "eq: " + json);
            return;
        }
        // Приложение «Картинка» (app.glasstv.picture): a=state (по умолчанию) читает, a=set записывает и применяет выбор, a=reset снимает его.
        // Всё в очереди Pq (вызовы службы TCL не в главном потоке), ответ плоским JSON в resultData упорядоченной рассылки (так шлёт и am broadcast)
        // и в журнал. Если очередь занята длинным ходом, через 8 секунд рассылка закрывается пустой: «Картинка» скажет «Сторож не отвечает»
        if ("pic".equals(i.getStringExtra("do"))) {
            final Context app = c.getApplicationContext();
            final android.content.SharedPreferences sp = app.getSharedPreferences("guard", Context.MODE_PRIVATE);
            final String a = i.getStringExtra("a") == null ? "state" : i.getStringExtra("a");
            final String preset = i.getStringExtra("preset");
            final int bl = SmartSound.intExtra(i, "backlight"), black = SmartSound.intExtra(i, "black"),
                    peak = SmartSound.intExtra(i, "peak"), warm = SmartSound.intExtra(i, "warm");
            // Сначала спросить, ждут ли ответа, и только потом goAsync: после него isOrderedBroadcast() всегда false,
            // и «Картинка» писала «Сторож не отвечает» (проверено на ТВ 27.10)
            final boolean ordered = isOrderedBroadcast();
            final PendingResult pr = goAsync();
            final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
            final Handler main = new Handler(Looper.getMainLooper());
            final Runnable late = () -> { if (done.compareAndSet(false, true)) pr.finish(); };
            main.postDelayed(late, 8_000);
            Pq.async(() -> {
                String json;
                try {
                    json = "set".equals(a) ? Pic.set(app, sp, preset, bl, black, peak, warm)
                            : "reset".equals(a) ? Pic.reset(app, sp) : Pic.json(app, sp);
                } catch (Throwable e) {
                    Log.w("guard", "pic " + a + ": сбой", e);
                    json = "{\"v\":1,\"st\":\"fail\"}";
                }
                if (done.compareAndSet(false, true)) {
                    main.removeCallbacks(late);
                    if (ordered) { pr.setResultCode(android.app.Activity.RESULT_OK); pr.setResultData(json); }
                    pr.finish();
                }
                Log.i("guard", "pic " + a + ": " + json);
            });
            return;
        }
        // Умный звук: все действия необязательны, без них команда только печатает состояние
        if ("sound".equals(i.getStringExtra("do"))) {
            GuardService g = GuardService.running;
            final SmartSound s = g == null ? null : g.sound;
            if (s == null) { Log.i("guard", "умный звук: служба не запущена"); return; }
            if (i.hasExtra("on")) s.setEnabled(i.getBooleanExtra("on", true));
            if (i.hasExtra("level")) s.setLevel(i.getIntExtra("level", 1));
            if (i.hasExtra("mode")) s.setMode(i.getIntExtra("mode", SmartSound.MODE_GLOBAL));
            if (i.hasExtra("tune")) s.tune(i.getStringExtra("tune"));
            String act = i.getStringExtra("act");
            if ("dump".equals(act)) s.dump();
            else if ("rebuild".equals(act)) s.rebuild();
            else if ("verify".equals(act)) s.verifySoon();
            // Эффект собирается в своём потоке: через 1,5 с печатаем итог (проверка verify ждёт 3 с, её итог виден со следующей команды)
            final PendingResult pr = goAsync();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.i("guard", "умный звук: " + (s.isWanted() ? "включён" : "выключен") + ", " + s.status()
                        + " | уровень " + s.levelText() + ", режим " + s.mode());
                pr.finish();
            }, 1_500);
            return;
        }
        // Звук без экрана (в том числе когда ТВ спит): --es do vol --es a unmute|mute (без a: прочитать)
        if ("vol".equals(i.getStringExtra("do"))) {
            android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
            String a = i.getStringExtra("a");
            if ("unmute".equals(a)) am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0);
            else if ("unmute-on-wake".equals(a)) {   // ТВ спит и звук не меняет: включить при следующем включении экрана
                c.getSharedPreferences("guard", Context.MODE_PRIVATE).edit().putBoolean("unmuteOnWake", true).apply();
            }
            else if ("mute".equals(a)) am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, 0);
            Log.i("guard", "звук: без звука=" + am.isStreamMute(android.media.AudioManager.STREAM_MUSIC) + " громкость=" + am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
            return;
        }
        // Ночная вуаль для проверки днём: --es do veil --ez on true|false
        if ("veil".equals(i.getStringExtra("do"))) {
            GuardService g = GuardService.running;
            if (g != null) { if (i.getBooleanExtra("on", true)) g.showVeil(3_000); else g.hideVeil(1_500); }
            return;
        }
        // «Ночной режим» сам ведёт подсветку (--es do nm --ez on true|false): не вмешиваться и не считать это пультом
        if ("nm".equals(i.getStringExtra("do"))) {
            GuardService g = GuardService.running;
            if (g != null) g.setNightModeActive(i.getBooleanExtra("on", false));
            return;
        }
        // Главный экран по тому же правилу, что при погасшем экране (проверка видео внутри): --es do home
        if ("home".equals(i.getStringExtra("do"))) {
            GuardService g = GuardService.running;
            if (g != null) g.homeNow();
            else Log.i("guard", "команда home: служба не запущена");
            return;
        }
        // Экран блокировки «Дома»: через 15 минут простоя подсветка вдвое (--es do saver --ez on true|false)
        if ("saver".equals(i.getStringExtra("do"))) {
            GuardService g = GuardService.running;
            if (g != null) g.setSaver(i.getBooleanExtra("on", false));
            return;
        }
        // Граница для проверки, как в 22:00 и 7:00: --es do night --ez on true|false
        if ("night".equals(i.getStringExtra("do"))) {
            GuardService g = GuardService.running;
            if (g != null) g.onBoundary(i.getBooleanExtra("on", true));
            Log.i("guard", "ночь: подсветка=" + Backlight.get() + " датчик=" + Backlight.lightSensor());
            return;
        }
        // Адаптивная яркость по датчику: --es do ls (прочитать), --es do ls --ez on false (выключить)
        if ("ls".equals(i.getStringExtra("do"))) {
            int before = Backlight.lightSensor();
            String res = i.hasExtra("on") ? " запись=" + Backlight.setLightSensor(i.getBooleanExtra("on", true)) : "";
            Log.i("guard", "датчик света TCL: было=" + before + res + " стало=" + Backlight.lightSensor() + " подсветка=" + Backlight.get());
            return;
        }
        if ("bl".equals(i.getStringExtra("do"))) {
            int before = Backlight.get();
            String res = "";
            if (i.hasExtra("v")) res = " запись=" + Backlight.set(i.getIntExtra("v", before), i.getBooleanExtra("save", false));
            Log.i("guard", "подсветка TCL: было=" + before + res + " стало=" + Backlight.get());
        }
    }
}
