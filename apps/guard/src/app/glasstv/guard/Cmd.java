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
// Дневная подсветка: днём ставится сразу, ночью запоминается до 7:00, ночной уровень не меняется
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do daylevel --ei v 45
// Главный экран, как при выключении ТВ (27.10, GuardService.homeStep): сразу, без ожидания 1,5 с, экран может быть и включён. Идёт видео (медиасессия «играет», кроме музыки «Волны»): ничего не делает, пишет об этом
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do home                                  итог: adb logcat -d -s guard:I ("команда home: на главный экран" или "...идёт видео...")
// Присмотр за локальной службой (GuardService.svcCheck): раз в 3 минуты спросить адрес, при молчании открыть приложение
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do svc --es pkg <пакет> --es url http://127.0.0.1:<порт>/<путь> --es name "Как назвать в журнале"
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do svc --es a check     проверить сейчас (один промах сразу считается вторым)
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do svc --es a off       выключить присмотр
// Присмотр за ByeByeDPI (Bye.java): без a только состояние; on включить (так делает установщик после загрузки списка приложений),
// off выключить (сам ByeByeDPI не трогает), check проверить сейчас и включить туннель, если его нет
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do vpn [--es a on|off|check]
// Самообновление HYDE (Hyde.java): без a состояние; on выключатель «Обновлять HYDE сам»; check проверить выпуск и сделать шаг
// установки (те же условия, что по расписанию; итог: logcat -s guard); url проверочный выпуск, только с ПК (пустой: убрать)
// adb shell am broadcast -n app.glasstv.guard/.Cmd --es do hyde [--ez on true|false] [--es a check]
// adb reverse tcp:8000 tcp:8000 && adb shell am broadcast -n app.glasstv.guard/.Cmd --es do hyde --es url http://127.0.0.1:8000/

public class Cmd extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if ("svc".equals(i.getStringExtra("do"))) {
            final android.content.SharedPreferences sp = c.getApplicationContext().getSharedPreferences("guard", Context.MODE_PRIVATE);
            final String a = i.getStringExtra("a");
            if ("off".equals(a)) {
                sp.edit().remove("svcUrl").remove("svcPkg").remove("svcName").apply();
                Log.i("guard", "svc: присмотр выключен");
                return;
            }
            if (i.getStringExtra("url") != null) {
                sp.edit().putString("svcUrl", i.getStringExtra("url")).putString("svcPkg", String.valueOf(i.getStringExtra("pkg")))
                        .putString("svcName", i.getStringExtra("name") == null ? "Служба" : i.getStringExtra("name")).apply();
                Log.i("guard", "svc: смотрю " + i.getStringExtra("url"));
            }
            final GuardService g = GuardService.running;
            if (g == null) { Log.i("guard", "svc: служба Сторожа не запущена"); return; }
            final PendingResult pr = goAsync();
            new Thread(() -> {
                String out = "top".equals(a) ? "сверху " + GuardService.topPackage() + ", процесс жив: " + GuardService.processAlive(g.p.getString("svcPkg", ""))
                        : "check".equals(a) ? g.svcCheck(true) : g.svcCheck(false);
                Log.i("guard", "svc: " + out);
                pr.setResultData(out);
                pr.finish();
            }, "guard-svc-cmd").start();
            return;
        }
        // Обновления приложений не из магазина (Updates, список в assets/updates.txt): a=status (что стоит и что на сайте);
        // check (проверить сейчас и сразу поставить, окно «Обновить» откроет сразу; итог в журнале: logcat -s guard),
        // --ez force true (поставить заново ту же версию, для проверки), --es pkg <пакет> (только одно приложение)
        if ("upd".equals(i.getStringExtra("do"))) {
            final GuardService g = GuardService.running;
            if (g == null || g.upd == null) { Log.i("guard", "upd: служба Сторожа не запущена"); return; }
            final String a = i.getStringExtra("a"), pkg = i.getStringExtra("pkg");
            final boolean force = i.getBooleanExtra("force", false);
            if ("check".equals(a)) {
                // Скачивание идёт минутами, а ответ на команду должен уйти быстро: итог пишется в журнал
                new Thread(() -> Log.i("guard", "upd: проверка: " + g.upd.checkNow(force, pkg)), "guard-upd-cmd").start();
                setResultData("проверка запущена, итог: logcat -s guard");
                return;
            }
            final PendingResult pr = goAsync();
            new Thread(() -> {
                String out = g.upd.status();
                Log.i("guard", "upd: " + out);
                pr.setResultData(out);
                pr.finish();
            }, "guard-upd-cmd").start();
            return;
        }
        if ("vpn".equals(i.getStringExtra("do"))) {
            final GuardService g = GuardService.running;
            if (g == null || g.bye == null) {
                // Службы ещё нет (сразу после установки или её выгрузили): выбор запомнить, служба прочтёт его при запуске
                String a = i.getStringExtra("a");
                android.content.SharedPreferences sp = c.getApplicationContext().getSharedPreferences("guard", Context.MODE_PRIVATE);
                if ("on".equals(a) || "off".equals(a)) sp.edit().putBoolean("byeWatch", "on".equals(a)).commit();
                GuardService.start(c);
                String out = "служба «Сторожа» запускается; присмотр за ByeByeDPI " + (sp.getBoolean("byeWatch", false) ? "включён" : "выключен");
                Log.i("guard", "vpn: " + out);
                setResultData(out);
                return;
            }
            final PendingResult pr = goAsync();
            g.bye.command(i.getStringExtra("a"), out -> {
                Log.i("guard", "vpn: " + out);
                pr.setResultData(out);
                pr.finish();
            });
            return;
        }
        if ("hyde".equals(i.getStringExtra("do"))) {
            final GuardService g = GuardService.running;
            if (g == null || g.upd == null) {
                if (i.hasExtra("on")) c.getApplicationContext().getSharedPreferences("guard", Context.MODE_PRIVATE).edit()
                        .putBoolean(Hyde.P_ON, i.getBooleanExtra("on", true)).commit();
                GuardService.start(c);
                setResultData("служба «Сторожа» запускается");
                return;
            }
            if (i.hasExtra("url")) {
                // Проверочный адрес только с ПК (Cmd, право DUMP): свои приложения через AppCmd его не меняют
                String out = this instanceof AppCmd ? "проверочный адрес задаётся только командой с ПК" : g.upd.hyde.setTestBase(i.getStringExtra("url"));
                Log.i("guard", "hyde: " + out);
                setResultData(out);
                return;
            }
            if (i.hasExtra("on")) g.upd.hyde.setOn(i.getBooleanExtra("on", true), "команда");
            if ("check".equals(i.getStringExtra("a"))) {
                new Thread(() -> { Log.i("guard", "hyde: " + g.upd.hydeNow()); g.updSoon(Hyde.CHAIN_MS); }, "guard-hyde-cmd").start();
                setResultData("проверка запущена, итог: logcat -s guard");
                return;
            }
            final PendingResult pr = goAsync();
            new Thread(() -> {
                String out = g.upd.hyde.status();
                Log.i("guard", "hyde: " + out);
                pr.setResultData(out);
                pr.finish();
            }, "guard-hyde-cmd").start();
            return;
        }
        // Отладка по сети и записки (06.11): --es do adb (состояние) или --es do adb --es m always|service|off;
        // --es do notes --ez on true|false. Выключение отладки отсюда закроет и этот путь к ТВ: off только по решению человека
        if ("adb".equals(i.getStringExtra("do")) || "notes".equals(i.getStringExtra("do"))) {
            final GuardService g = GuardService.running;
            if (g == null) { Log.i("guard", "служба Сторожа не запущена"); return; }
            String out;
            if ("notes".equals(i.getStringExtra("do"))) {
                if (i.hasExtra("on")) g.setNotes(i.getBooleanExtra("on", false));
                if (i.getBooleanExtra("newcode", false)) GuardService.newNotesCode(c);
                out = "записки " + (GuardService.notesEnabled(c) ? "включены, код " + GuardService.notesCode(c) : "выключены");
            } else {
                String m = i.getStringExtra("m");
                if (m != null) g.setAdbMode(m, "команда с ПК");
                out = "отладка " + (g.adbOn() ? "включена" : "выключена") + ", режим " + g.adbMode()
                        + (GuardService.ADB_SERVICE.equals(g.adbMode()) ? " до " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(g.adbUntil())) : "");
            }
            Log.i("guard", out);
            setResultData(out);
            return;
        }
        // Отчёт для обслуживания без личных данных (06.11, план Codex, блок 6): --es do report [--ez save true: ещё и в «Загрузки»]
        if ("report".equals(i.getStringExtra("do"))) {
            final PendingResult pr = goAsync();
            final Context app = c.getApplicationContext();
            final boolean save = i.getBooleanExtra("save", false);
            new Thread(() -> {
                String out = Report.build(app);
                if (save) out = "файл: " + Report.save(app, out) + "\n" + out;
                Log.i("guard", "отчёт: " + out.length() + " знаков");
                pr.setResultData(out);
                pr.finish();
            }, "guard-report-cmd").start();
            return;
        }
        // Перенос настроек (06.11, план Codex, блок 3): --es do export (JSON в ответе), --es do import --es json '{...}'.
        // Обычно шлёт «Дом» через AppCmd: он собирает общий файл (Home/Transfer.java)
        if ("export".equals(i.getStringExtra("do")) || "import".equals(i.getStringExtra("do"))) {
            String out;
            try {
                out = "export".equals(i.getStringExtra("do")) ? GuardService.exportJson(c)
                        : GuardService.importJson(c, String.valueOf(i.getStringExtra("json")));
            } catch (Exception e) {
                out = "ошибка: " + e.getMessage();
            }
            Log.i("guard", "перенос: " + (out.length() > 80 ? out.substring(0, 80) + "…" : out));
            setResultData(out);
            return;
        }
        // Паспорт ТВ (06.11, план Codex, блок 5): --es do caps, модель, прошивка, уровень и какие закрытые функции отвечают
        if ("caps".equals(i.getStringExtra("do"))) {
            final PendingResult pr = goAsync();
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                String out = Caps.report(app);
                Log.i("guard", "caps: " + out);
                pr.setResultData(out);
                pr.finish();
            }, "guard-caps-cmd").start();
            return;
        }
        // Простой (06.11): --es do idle, что видит «Сторож»: сколько без нажатий, кто держит экран, идёт ли видео
        if ("idle".equals(i.getStringExtra("do"))) {
            final GuardService g = GuardService.running;
            if (g == null) { Log.i("guard", "idle: служба Сторожа не запущена"); return; }
            final PendingResult pr = goAsync();
            new Thread(() -> {
                String out = g.idleReport();
                Log.i("guard", "idle: " + out);
                pr.setResultData(out);
                pr.finish();
            }, "guard-idle-cmd").start();
            return;
        }
        // Присмотр за DNS (DnsWatch): a=status (что стоит и работает ли), check (проверить и починить сразу),
        // pref --es host <имя> (выбранный DNS, к которому «Сторож» возвращается), probe (какие DNS отвечают напрямую)
        if ("dns".equals(i.getStringExtra("do"))) {
            final GuardService g = GuardService.running;
            if (g == null || g.dns == null) { Log.i("guard", "dns: служба Сторожа не запущена"); return; }
            final String a = i.getStringExtra("a"), host = i.getStringExtra("host");
            final PendingResult pr = goAsync();
            new Thread(() -> {
                String out;
                if ("pref".equals(a) && host != null) { g.dns.setPref(host); out = "выбранный DNS: " + host; }
                else if ("check".equals(a)) { g.dns.check(true); out = "проверено, стоит " + g.dns.current(); }
                else if ("probe".equals(a)) {
                    StringBuilder b = new StringBuilder();
                    for (DnsWatch.P x : DnsWatch.ALL) b.append(x.host).append(DnsWatch.probe(x) ? " отвечает; " : " молчит; ");
                    out = b.toString();
                } else out = "стоит " + g.dns.current() + ", выбранный " + g.dns.pref() + ", интернет " + (DnsWatch.ipUp() ? "есть" : "нет")
                        + ", имена " + (g.dns.namesWork() ? "разрешаются" : "НЕ разрешаются");
                Log.i("guard", "dns: " + out);
                pr.setResultData(out);
                pr.finish();
            }, "guard-dns-cmd").start();
            return;
        }
        if ("daylevel".equals(i.getStringExtra("do"))) {
            GuardService g = GuardService.running;
            if (g != null) g.setDayLevel(i.getIntExtra("v", 45));
            return;
        }
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
            boolean on = i.getBooleanExtra("on", false);
            if (g != null) { g.setSaver(on); return; }
            // Службы нет (её убила система или обновление): «хватит» всё равно возвращает подсветку с диска, служба поднимается (06.11)
            if (!on) GuardService.restoreSaver(c);
            GuardService.start(c);
            return;
        }
        // Расписание ночи (06.11, план Codex, блок 3): --es do nighthours [--ei from 18..23 --ei to 5..10] [--ez enabled true|false];
        // без extras только ответ. Шлёт «Настройки «Дома»» через AppCmd, с ПК через Cmd
        if ("nighthours".equals(i.getStringExtra("do"))) {
            GuardService g = GuardService.running;
            String out;
            if (g == null) {
                out = "служба «Сторожа» не запущена";
                GuardService.start(c);
            } else {
                String r = "";
                if (i.hasExtra("from") && i.hasExtra("to")) r = g.setNightHours(i.getIntExtra("from", 22), i.getIntExtra("to", 7)) + "; ";
                if (i.hasExtra("enabled")) g.setNightEnabled(i.getBooleanExtra("enabled", true));
                out = r + (g.nightEnabled() ? "ночная подсветка с " + GuardService.nf() + " до " + GuardService.nt() : "ночная подсветка выключена");
            }
            Log.i("guard", "nighthours: " + out);
            setResultData(out);
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
