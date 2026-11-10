package main

import (
	"strings"
	"testing"
)

// Ответ «Сторожа» на do keys (договор с apps/guard): работает и старый «Сторож» молча, перезапуск словами, сбой предупреждением
func TestGuardKeys(t *testing.T) {
	run := func(keymapper bool, reply string) (*scriptTV, []Event) {
		r := map[string][]string{"pm path app.glasstv.guard": {"package:/data/app/app.glasstv.guard/base.apk"}, guardKeysCmd: {reply}}
		if keymapper {
			r["pm path io.github.sds100.keymapper"] = []string{"package:/data/app/io.github.sds100.keymapper/base.apk"}
		}
		tv := &scriptTV{replies: r}
		var evs []Event
		guardKeys(tv, func(e Event) { evs = append(evs, e) })
		return tv, evs
	}
	for _, quiet := range []string{bcast("работает"), bcast("не установлен"), "Broadcast completed: result=0", bcast("что-то новое")} {
		if _, evs := run(true, quiet); len(evs) != 0 {
			t.Fatalf("%q: лишние события %+v", quiet, evs)
		}
	}
	if _, evs := run(true, bcast("перезапущен")); len(evs) != 1 || evs[0].Status != "info" || !strings.Contains(evs[0].What, "работают") {
		t.Fatalf("перезапуск: %+v", evs)
	}
	for _, bad := range []string{"не поднялся", "нет прав перезапустить (WRITE_SECURE_SETTINGS)", "список спецвозможностей поменяли во время перезапуска, попробую позже"} {
		if _, evs := run(true, bcast(bad)); len(evs) != 1 || evs[0].Status != "warn" || evs[0].Detail != bad {
			t.Fatalf("%q: %+v", bad, evs)
		}
	}
	tv, evs := run(false, bcast("перезапущен"))
	for _, c := range tv.ran {
		if c == guardKeysCmd {
			t.Fatalf("без Key Mapper команда ушла: %q", tv.ran)
		}
	}
	if len(evs) != 0 {
		t.Fatalf("без Key Mapper события: %+v", evs)
	}
}
