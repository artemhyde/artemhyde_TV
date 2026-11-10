package main

import (
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Выбор человека в командной строке: явный ключ важнее прошлого выбора этого ТВ, без обоих всё включено
func TestConsentFromFlags(t *testing.T) {
	parse := func(args ...string) *flag.FlagSet {
		fs := flag.NewFlagSet("x", flag.ContinueOnError)
		_ = fs.Bool("report", true, "")
		_ = fs.Bool("no-report", false, "")
		_ = fs.Bool("no-auto-update", false, "")
		if err := fs.Parse(args); err != nil {
			t.Fatal(err)
		}
		return fs
	}
	off := &Consent{}
	cases := []struct {
		args []string
		last *Consent
		want Consent
	}{
		{nil, nil, Consent{Updates: true, Reports: true}},
		{nil, off, Consent{}},
		{[]string{"--no-report"}, nil, Consent{Updates: true}},
		{[]string{"--no-auto-update"}, nil, Consent{Reports: true}},
		{[]string{"--no-report=false"}, off, Consent{Reports: true}},
		{[]string{"--report"}, off, Consent{Reports: true}},
	}
	for _, c := range cases {
		if got := consentFromFlags(parse(c.args...), c.last); got != c.want {
			t.Fatalf("%v при прошлом выборе %+v: %+v, ждали %+v", c.args, c.last, got, c.want)
		}
	}
}

// Команды «Сторожу»: автообновление и отчёты отдельно; номер устройства уходит в команду ТВ, только если он нужного вида
func TestGuardConsentCmds(t *testing.T) {
	cs := guardConsentCmds(Consent{Updates: true}, "0123456789abcdef0123")
	if len(cs) != 2 || !strings.HasSuffix(cs[0], "--es do hyde --ez on true") ||
		!strings.HasSuffix(cs[1], "--es do ping --ez on false --es id 0123456789abcdef0123") {
		t.Fatalf("команды: %q", cs)
	}
	for _, bad := range []string{"", "0123456789abcde", "ABCDEF0123456789AB", "0123456789abcdef; reboot"} {
		if cs := guardConsentCmds(defaultConsent(), bad); strings.Contains(cs[1], "--es id") {
			t.Fatalf("номер %q ушёл «Сторожу»: %q", bad, cs[1])
		}
	}
	if id := newDeviceID(); !validDeviceID(id) || id == newDeviceID() {
		t.Fatalf("новый номер не того вида или не случайный: %q", id)
	}
}

// Номер из ответа «Сторожа» 1.2.0 на do ping (договор с apps/guard, Ping.status и Cmd): служба работает и служба ещё
// запускается; «Сторож» до 1.2.0 и сборка не из выпуска номера не называют
func TestGuardIDFrom(t *testing.T) {
	id := "0123456789abcdef0123456789abcdef"
	for _, out := range []string{
		"Broadcasting: Intent { flg=0x400000 cmp=app.glasstv.guard/.Cmd (has extras) }\n" +
			`Broadcast completed: result=0, data="включены; номер ` + id + `; последний принят: ещё не было"`,
		`Broadcast completed: result=0, data="служба «Сторожа» запускается; отчёты автору выключены; номер ` + id + `"`,
	} {
		if got := guardIDFrom(out); got != id {
			t.Fatalf("номер %q из ответа %q", got, out)
		}
	}
	for _, out := range []string{
		`Broadcast completed: result=0, data="сборка не из выпуска HYDE: отчётов автору нет"`,
		`Broadcast completed: result=0`,
		`Broadcast completed: result=0, data="включены; номер 0123456789abcdef0123456789abcdef0123456789; последний принят: ещё не было"`,
	} {
		if got := guardIDFrom(out); got != "" {
			t.Fatalf("лишний номер %q из ответа %q", got, out)
		}
	}
}

// Номер устройства и выбор человека берутся из самой свежей установки этого ТВ, где они записаны; чужой ТВ не в счёт
func TestDeviceIDAndConsentFromSessions(t *testing.T) {
	root := t.TempDir()
	save := func(name, tv, id string, c *Consent) {
		d := filepath.Join(root, name)
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := saveSession(d, &Session{Label: name, TVID: tv, DeviceID: id, Consent: c}); err != nil {
			t.Fatal(err)
		}
	}
	save("20261101-1000", "tv1", "aaaaaaaaaaaaaaaa1111", &Consent{Updates: true})
	save("20261105-1000", "tv1", "", nil) // журнал до 1.2.0: номера и выбора нет
	save("20261106-1000", "tv2", "bbbbbbbbbbbbbbbb2222", &Consent{Updates: true, Reports: true})
	if id := deviceIDFromSessions(root, "tv1"); id != "aaaaaaaaaaaaaaaa1111" {
		t.Fatalf("номер: %q", id)
	}
	if c := lastConsentOf(root, "tv1"); c == nil || *c != (Consent{Updates: true}) {
		t.Fatalf("прошлый выбор: %+v", c)
	}
	if id := deviceIDFromSessions(root, ""); id != "" {
		t.Fatalf("без android_id взят номер чужого ТВ: %q", id)
	}
	if c := lastConsentOf(root, "tv3"); c != nil {
		t.Fatalf("у нового ТВ прошлого выбора нет: %+v", c)
	}
	// запуск, где менять нечего, журнала не оставляет: выбор пишется в самую свежую установку этого ТВ, номер не меняется
	rememberConsent(root, "tv1", &Consent{Reports: true}, "cccccccccccccccc3333")
	if c := lastConsentOf(root, "tv1"); c == nil || *c != (Consent{Reports: true}) {
		t.Fatalf("новый выбор не запомнился: %+v", c)
	}
	if id := deviceIDFromSessions(root, "tv1"); id != "cccccccccccccccc3333" {
		t.Fatalf("номер в журнале без номера: %q", id)
	}
	if s, _ := loadSession(filepath.Join(root, "20261106-1000")); s.Consent == nil || !s.Consent.Reports || !s.Consent.Updates {
		t.Fatalf("выбор чужого ТВ изменился: %+v", s.Consent)
	}
}

// Номер устройства уходит в отчёт и виден человеку в списке полей; номер не того вида не уходит
func TestReportDeviceID(t *testing.T) {
	dir := t.TempDir()
	ss := &Session{Label: "x", TVID: "a1b2c3d4e5f60718", Release: "1.2.0", Started: "2026-11-10 12:00:00",
		Finished: "2026-11-10 12:05:00", DeviceID: "0123456789abcdef01234567"}
	if err := saveSession(dir, ss); err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(filepath.Join(dir, "passport.txt"), []byte("##props\nro.product.manufacturer=TCL\nro.build.version.sdk=34\n##end\n"), 0o644)
	r, err := reportFromSession(dir, "apply", "cli", "", nil, false)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, f := range r.Fields() {
		found = found || (f.Label == "Номер устройства" && f.Value == ss.DeviceID)
	}
	b, _ := json.Marshal(r)
	if r.DeviceID != ss.DeviceID || !found || !strings.Contains(string(b), `"device_id":"0123456789abcdef01234567"`) {
		t.Fatalf("номер устройства в отчёте: %+v, поля %+v", r, r.Fields())
	}
	if strings.Contains(string(b), "a1b2c3d4e5f60718") {
		t.Fatalf("android_id ушёл в отчёт: %s", b)
	}
	ss.DeviceID = "плохой номер"
	_ = saveSession(dir, ss)
	if r, _ = reportFromSession(dir, "apply", "cli", "", nil, false); r.DeviceID != "" {
		t.Fatalf("номер не того вида ушёл: %q", r.DeviceID)
	}
}
