package app.glasstv.volna;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

// Команды без экрана: для кнопок Key Mapper («Отправить интент») и для проверки с ПК.
// adb shell am broadcast -n app.glasstv.volna/.Cmd --es do my|new|next|toggle|like|dislike|stop|state
// Профиль: --es do profile --es id second|main; имя второго профиля: --es do profile_name --es name Имя
public class Cmd extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Engine e = Engine.get(c);
        String what = i.getStringExtra("do");
        if (what == null) what = "state";
        // «Дом» открыт или закрыт: только плашка, к Aura не подключаемся
        if (what.equals("home_on") || what.equals("home_off")) { e.homeShown(what.equals("home_on")); return; }
        switch (what) {
            case "my": e.start(Brain.MY); break;
            case "new": e.start(Brain.NEW); break;
            case "next": e.next(); break;
            case "prev": e.prev(); break;
            case "fwd": e.seekBy(15_000); break;
            case "rew": e.seekBy(-15_000); break;
            case "toggle": e.toggle(); break;
            case "like": e.like(); break;
            case "dislike": e.dislike(); break;
            case "stop": e.stop(); break;
            case "whoami": e.whoami(); break;
            case "playid": e.play(new Track(i.getStringExtra("id"), "проверка", "", 0), true); break;   // конкретный трек по id
            case "seekend": e.seekNearEnd(); break;   // для проверки смены треков: за 6 с до конца
            case "fakestall": e.fakeStall = true; break;   // для проверки сторожа: как будто музыка встала, через 7 с он сработает
            case "restart": e.restartAura("проверка с ПК"); break;
            case "profile": e.setProfile(Store.SECOND.equals(i.getStringExtra("id")) ? Store.SECOND : Store.MAIN); break;
            case "profile_name": e.ui.edit().putString("name_" + Store.SECOND, i.getStringExtra("name")).apply(); break;
            // Посмотреть, что волна подберёт, без звука: --es do peek --es mode new|my
            case "peek": {
                int m = "new".equals(i.getStringExtra("mode")) ? Brain.NEW : Brain.MY;
                e.brain.refillIfLow(m);
                Brain b = e.brain;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    for (Track t : b.upcoming(m, 10)) Log.i("volna", "в очереди: " + t.title + " — " + t.artist + " [" + t.badge + "]");
                }, 20_000);
                break;
            }
            default: break;
        }
        Track nx = e.brain.peek(e.mode);
        Log.i("volna", "состояние: волна=" + (e.driving ? Brain.MODE_NAMES[e.mode] : "нет") + " aura=" + e.state
                + " трек=" + e.curId + " «" + e.curTitle + "» — " + e.curArtist + " [" + e.badge + "]"
                + " план=" + (e.planned == null ? null : e.planned.id) + " далее=" + (nx == null ? null : nx.title + " [" + nx.badge + "]")
                + " статус=" + e.status + " лайк=" + e.liked() + " профиль=" + e.profileName(e.store.profile)
                + " позиция=" + e.position() / 1000);
    }
}
