package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Отключённые установщиком пакеты из журналов: только pm disable-user с одним пакетом нужного вида, без повторов, по алфавиту
func TestDisabledByInstaller(t *testing.T) {
	root := t.TempDir()
	mk := func(name string, dos ...string) string {
		d := filepath.Join(root, name)
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatal(err)
		}
		for _, x := range dos {
			if err := appendLedger(filepath.Join(d, "ledger.tsv"), LedgerEntry{Time: "10:00", What: "шаг", Do: x, Undo: "обратно"}); err != nil {
				t.Fatal(err)
			}
		}
		return d
	}
	a := mk("a", "pm disable-user --user 0 com.google.android.tungsten.setupwraith", "appops set app.glasstv.guard AUTO_START allow",
		"pm disable-user --user 0 com.google.android.apps.tv.launcherx")
	b := mk("b", "pm disable-user --user 0 com.google.android.apps.tv.launcherx", "pm disable-user --user 0 bad;reboot",
		"pm disable-user --user 0 com.example.tv лишнее")
	got := strings.Join(disabledByInstaller([]string{a, b}), ",")
	if got != "com.google.android.apps.tv.launcherx,com.google.android.tungsten.setupwraith" {
		t.Fatalf("список: %q", got)
	}
	if l := disabledByInstaller(nil); len(l) != 0 {
		t.Fatalf("без журналов: %q", l)
	}
	if c := guardDisabledCmd([]string{"a.b", "c.d"}); c != "am broadcast -n app.glasstv.guard/.Cmd --es do disabled --es list a.b,c.d" {
		t.Fatalf("команда: %q", c)
	}
	if c := guardDisabledCmd(nil); !strings.HasSuffix(c, `--es do disabled --es list ""`) {
		t.Fatalf("пустой список: %q", c)
	}
}

// Ответ «Сторожа» на do disabled (договор с apps/guard): итог словами, снова включённые предупреждением, старый «Сторож» молча
func TestGuardDisabledReply(t *testing.T) {
	root := t.TempDir()
	d := filepath.Join(root, "20261110-1200")
	_ = os.MkdirAll(d, 0o755)
	_ = appendLedger(filepath.Join(d, "ledger.tsv"), LedgerEntry{Time: "12:00", What: "отключён", Do: "pm disable-user --user 0 com.google.android.apps.tv.launcherx", Undo: "pm enable --user 0 com.google.android.apps.tv.launcherx"})
	cmd := "am broadcast -n app.glasstv.guard/.Cmd --es do disabled --es list com.google.android.apps.tv.launcherx"
	run := func(guard bool, reply string) (*scriptTV, []Event) {
		r := map[string][]string{cmd: {reply}}
		if guard {
			r["pm path app.glasstv.guard"] = []string{"package:/data/app/app.glasstv.guard/base.apk"}
		}
		tv := &scriptTV{replies: r}
		var evs []Event
		guardDisabled(tv, []string{d}, func(e Event) { evs = append(evs, e) })
		return tv, evs
	}
	if _, evs := run(true, bcast("отключённых установщиком 1, снова включено 0")); len(evs) != 1 || evs[0].Status != "info" || !strings.HasSuffix(evs[0].What, ": 1") {
		t.Fatalf("всё отключено: %+v", evs)
	}
	if _, evs := run(true, bcast("отключённых установщиком 1, снова включено 1, com.google.android.apps.tv.launcherx")); len(evs) != 1 || evs[0].Status != "warn" ||
		!strings.HasSuffix(evs[0].What, "com.google.android.apps.tv.launcherx") {
		t.Fatalf("снова включён: %+v", evs)
	}
	if _, evs := run(true, "Broadcast completed: result=0"); len(evs) != 0 {
		t.Fatalf("старый «Сторож»: %+v", evs)
	}
	if tv, evs := run(false, ""); len(evs) != 0 || len(tv.ran) != 1 {
		t.Fatalf("без «Сторожа» команда ушла: %q %+v", tv.ran, evs)
	}
}
