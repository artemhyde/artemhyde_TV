package main

import (
	"encoding/json"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync/atomic"
	"testing"
)

// Текст ошибки уходит без адресов, серийных номеров, путей с именем пользователя и названий сетей, не длиннее 500 знаков
func TestReportCleanError(t *testing.T) {
	in := `не подключился к 192.168.1.20:41235 (adb-1A2B3C4D5E6F70819-xYzAbC._adb-tls-connect._tcp), файл C:\Users\ivan\.glasstv\backup\x, ` +
		`/home/vasya/hyde, сеть «Дом Васи» и SSID "MyHome5G", ключ 3fa9c1b27e88d0aa, fe80::1c2b:3a4d:5e6f:7a8b, app.glasstv.guard`
	out := cleanError(in)
	for _, bad := range []string{"192.168", "1A2B3C4D5E6F70819", "ivan", "vasya", "Дом Васи", "MyHome5G", "3fa9c1b27e88d0aa", "fe80::"} {
		if strings.Contains(out, bad) {
			t.Fatalf("в ошибке осталось %q: %s", bad, out)
		}
	}
	if !strings.Contains(out, "app.glasstv.guard") {
		t.Fatalf("имя пакета не должно пропадать: %s", out)
	}
	if n := len([]rune(cleanError(strings.Repeat("ошибка ", 200)))); n > 500 {
		t.Fatalf("длина %d больше 500", n)
	}
}

// Отчёт собирается из журнала установки: модель, Android, модули, итог, шаг и ошибка; без android_id и адресов
func TestReportFromSession(t *testing.T) {
	dir := t.TempDir()
	home := true
	ss := &Session{Label: "x", TVID: "a1b2c3d4e5f60718", Model: "TCL", Release: "1.0.1", Modules: map[string]bool{"guard": true, "home": true, "volna": false},
		Home: &home, Started: "2026-11-07 15:00:00", LastError: "«Ночной режим»: уведомления: не прошло у 192.168.1.20:5555", FailedStep: "«Ночной режим»: уведомления"}
	if err := saveSession(dir, ss); err != nil {
		t.Fatal(err)
	}
	pass := "##props\nro.product.manufacturer=TCL\nro.product.brand=TCL\nro.product.model=Smart TV Pro\nro.boot.product=65C6K\nro.build.version.sdk=34\n" +
		"ro.build.version.release=14\nro.product.cpu.abilist=armeabi-v7a,armeabi\nro.tcl.product_type=GTV\n##features\nfeature:android.software.leanback\n##end\n"
	_ = os.WriteFile(filepath.Join(dir, "passport.txt"), []byte(pass), 0o644)
	_ = os.WriteFile(filepath.Join(dir, "packages-all.txt"), []byte("package:com.google.android.apps.tv.launcherx\n"), 0o644)
	r, err := reportFromSession(dir, "apply", "wizard", "ru-RU", nil, false)
	if err != nil {
		t.Fatal(err)
	}
	if r.Result != "failed" || r.FailedStep != "«Ночной режим»: уведомления" || r.TVBrand != "TCL" || r.Android != "14" || r.SDK != 34 ||
		strings.Join(r.Modules, ",") != "guard,home" || r.Path != "wizard" || r.ReportID == "" {
		t.Fatalf("отчёт: %+v", r)
	}
	b, _ := json.Marshal(r)
	for _, bad := range []string{"a1b2c3d4e5f60718", "192.168"} {
		if strings.Contains(string(b), bad) {
			t.Fatalf("в отчёте %q: %s", bad, b)
		}
	}
	// закончена, всё держится, до установки HYDE уже стоял: обновление
	ss.Finished, ss.LastError, ss.FailedStep = "2026-11-07 15:05:00", "", ""
	_ = saveSession(dir, ss)
	_ = os.WriteFile(filepath.Join(dir, "packages-all.txt"), []byte("package:app.glasstv.guard\npackage:app.glasstv.home\n"), 0o644)
	r, _ = reportFromSession(dir, "apply", "cli", "", []Check{{OK: true}, {OK: true}}, false)
	if r.Result != "update_ok" || !r.HomeSwitched || r.VerifyOK != 2 || r.VerifyTotal != 2 || r.Error != "" {
		t.Fatalf("обновление: %+v", r)
	}
	r, _ = reportFromSession(dir, "apply", "cli", "", []Check{{OK: true}, {OK: false}}, false)
	if r.Result != "partial" {
		t.Fatalf("не всё держится: %+v", r)
	}
	r, _ = reportFromSession(dir, "restore", "cli", "", nil, true)
	if r.Result != "restored" || r.HomeSwitched {
		t.Fatalf("возврат: %+v", r)
	}
	// журнал 1.0.1: в отчёт идёт то, что поставил план (выбор человека шире: модуль мог быть недоступен),
	// а главный экран по плану, а не по выбору
	ss.Installed, ss.HomePlanned, ss.Home = []string{"home", "guard", "nightmode"}, true, nil
	_ = saveSession(dir, ss)
	r, _ = reportFromSession(dir, "apply", "wizard", "", nil, false)
	if strings.Join(r.Modules, ",") != "guard,home,nightmode" || !r.HomeSwitched {
		t.Fatalf("что поставил план: %+v", r)
	}
	// ничего не выбрано: в JSON пустой список, а не null
	ss.Installed, ss.Modules, ss.HomePlanned = nil, nil, false
	_ = saveSession(dir, ss)
	r, _ = reportFromSession(dir, "apply", "wizard", "", nil, false)
	if b, _ := json.Marshal(r); !strings.Contains(string(b), `"modules":[]`) || r.HomeSwitched {
		t.Fatalf("пустой список: %s", b)
	}
}

// Отправка: один POST с JSON и заголовками; без адреса не уходит ничего; send=false (галочка отчётов снята) не шлёт
func TestReportSendOnlyOnYes(t *testing.T) {
	var hits atomic.Int32
	var got Report
	var key string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		key = r.Header.Get("apikey")
		b, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(b, &got)
		w.WriteHeader(201)
	}))
	defer srv.Close()

	t.Setenv("HYDE_REPORT_URL", "")
	if reportEnabled() {
		t.Fatal("без адреса отчёт не должен включаться")
	}
	if err := sendReport(Report{ReportID: "x"}); err == nil || hits.Load() != 0 {
		t.Fatal("без адреса ушёл запрос")
	}

	t.Setenv("HYDE_REPORT_URL", srv.URL)
	t.Setenv("HYDE_REPORT_HEADERS", "apikey: test-key; Prefer: return=minimal")
	s := &uiServer{}
	rep := Report{ReportID: "abcdef1234567890", Release: "1.0.1", Result: "ok"}
	s.rep = &rep
	req := httptest.NewRequest("POST", "/api/report/send", strings.NewReader(`{"send":false}`))
	if out, err := s.reportSend(req); err != nil || hits.Load() != 0 {
		t.Fatalf("«Не отправлять» отправил: %v %v", out, err)
	}
	s.rep = &rep
	req = httptest.NewRequest("POST", "/api/report/send", strings.NewReader(`{"send":true}`))
	out, err := s.reportSend(req)
	if err != nil || hits.Load() != 1 || got.ReportID != "abcdef1234567890" || key != "test-key" {
		t.Fatalf("отправка: %v %v hits=%d got=%+v key=%q", out, err, hits.Load(), got, key)
	}
	if m, _ := out.(map[string]any); m["sent"] != true || m["id"] != "abcdef1234567890" {
		t.Fatalf("ответ окну: %v", out)
	}
	// повторно тот же отчёт не уходит
	req = httptest.NewRequest("POST", "/api/report/send", strings.NewReader(`{"send":true}`))
	if _, err := s.reportSend(req); err == nil || hits.Load() != 1 {
		t.Fatal("отчёт ушёл второй раз")
	}
}

// Приложения HYDE ставятся от имени «Сторожа»; сам «Сторож» при первой установке ставится второй раз тем же файлом
func TestPlanInstallsViaGuard(t *testing.T) {
	rec, err := loadRecipe()
	if err != nil {
		t.Fatal(err)
	}
	m := testManifest(t)
	p := fixture(t, "google-tv.txt")
	pl := buildPlan(rec, m, p, Choices{Modules: map[string]bool{}}, false)
	guardSteps := 0
	for _, s := range pl.Steps {
		if s.Kind != "install" {
			continue
		}
		if s.Installer != "app.glasstv.guard" && s.Package != "app.glasstv.guard" {
			t.Fatalf("%s ставится не от имени «Сторожа»: %+v", s.Package, s)
		}
		if s.Package == "app.glasstv.guard" {
			guardSteps++
		}
	}
	if guardSteps != 2 {
		t.Fatalf("«Сторож»: ожидал установку и повтор от своего имени, шагов %d", guardSteps)
	}
}

// Отчёт проходит проверки приёмника автора (Google-таблица): номер из 16–40 строчных букв и цифр, система одним словом,
// итог из списка, числа в пределах. Иначе таблица отвечает «неверное поле» и отчёт пропадает
func TestReportMatchesSheet(t *testing.T) {
	dir := t.TempDir()
	home := true
	ss := &Session{Label: "x", TVID: "a1", Model: "TCL", Release: "1.0.1", Modules: map[string]bool{"guard": true}, Home: &home,
		Started: "2026-11-07 15:00:00", Finished: "2026-11-07 15:05:00", Installed: []string{"guard", "home"}, HomePlanned: true,
		DeviceID: newDeviceID()}
	if err := saveSession(dir, ss); err != nil {
		t.Fatal(err)
	}
	pass := "##props\nro.product.manufacturer=TCL\nro.product.model=Smart TV Pro\nro.build.version.sdk=34\nro.build.version.release=14\n" +
		"ro.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi\nro.tcl.product_type=GTV\n##features\nfeature:android.software.leanback\n##end\n"
	_ = os.WriteFile(filepath.Join(dir, "passport.txt"), []byte(pass), 0o644)
	oneOf := func(vals ...string) func(any) bool {
		return func(v any) bool {
			s, _ := v.(string)
			for _, x := range vals {
				if s == x {
					return true
				}
			}
			return false
		}
	}
	intIn := func(lo, hi float64) func(any) bool {
		return func(v any) bool { f, ok := v.(float64); return ok && f == math.Trunc(f) && f >= lo && f <= hi }
	}
	strMax := func(n int, need bool) func(any) bool {
		return func(v any) bool { s, ok := v.(string); return ok && len(s) <= n && (!need || s != "") }
	}
	reID := regexp.MustCompile(`^[a-z0-9]{16,40}$`)
	rules := map[string]func(any) bool{
		"report_id":     func(v any) bool { s, _ := v.(string); return reID.MatchString(s) },
		"release":       strMax(20, true),
		"pc_os":         oneOf("windows", "macos", "linux", "other"),
		"path":          oneOf("wizard", "claude", "cli"),
		"sdk":           intIn(1, 100),
		"modules":       func(v any) bool { a, ok := v.([]any); return ok && len(a) <= 40 },
		"home_switched": func(v any) bool { _, ok := v.(bool); return ok },
		"result":        oneOf("ok", "failed", "partial", "restored", "update_ok", "update_failed", "test"),
		"verify_ok":     intIn(0, 1000),
		"verify_total":  intIn(0, 1000),
		"lang":          strMax(10, false),
		"device_id":     func(v any) bool { s, _ := v.(string); return reID.MatchString(s) }, // 1.2.0, необязательное
	}
	for _, kind := range []string{"apply", "restore"} {
		r, err := reportFromSession(dir, kind, "claude", "ru_RU.UTF-8", []Check{{OK: true}}, true)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := json.Marshal(r)
		if len(b) > 8000 {
			t.Fatalf("%s: отчёт длиннее 8000 знаков", kind)
		}
		var m map[string]any
		_ = json.Unmarshal(b, &m)
		for k, ok := range rules {
			v, has := m[k]
			if !has {
				if k == "report_id" || k == "release" || k == "result" {
					t.Fatalf("%s: нет поля %s: %s", kind, k, b)
				}
				continue
			}
			if !ok(v) {
				t.Fatalf("%s: таблица не примет %s=%v: %s", kind, k, v, b)
			}
		}
		if r.Lang != "ru_RU" {
			t.Fatalf("язык: %q", r.Lang)
		}
	}
	// паспорт не прочитан: SDK 0 таблица не примет, поэтому его нет в отчёте
	_ = os.WriteFile(filepath.Join(dir, "passport.txt"), nil, 0o644)
	r, _ := reportFromSession(dir, "apply", "cli", "", nil, false)
	if b, _ := json.Marshal(r); strings.Contains(string(b), `"sdk"`) {
		t.Fatalf("SDK 0 ушёл в отчёт: %s", b)
	}
}

// Таблица отвечает 200 и на отказ, а закрытый приём отдаёт страницу входа: отправленным считается только принятый отчёт
func TestReportAccepted(t *testing.T) {
	for _, c := range []struct {
		ctype, body string
		ok          bool
	}{
		{"application/json", `{"ok":true,"report":"0123456789abcdef"}`, true},
		{"application/json; charset=utf-8", `{"ok":false,"error":"неверное поле pc_os"}`, false},
		{"", "", true}, // 201 без тела: свой приёмник или Supabase
		{"text/html; charset=utf-8", "<html>Вход в аккаунт Google</html>", false},
	} {
		if err := reportAccepted(c.ctype, []byte(c.body)); (err == nil) != c.ok {
			t.Fatalf("%q %q: %v", c.ctype, c.body, err)
		}
	}
}

// Тесты никогда не шлют в настоящую таблицу автора: адрес из установщика на время тестов пуст
var realReportURL string

func TestMain(m *testing.M) {
	realReportURL, reportURL = reportURL, ""
	os.Exit(m.Run())
}

// Адрес в установщике: веб-приложение Apps Script (tools/reports/collector.gs), по https
func TestReportURLIsSheet(t *testing.T) {
	if !strings.HasPrefix(realReportURL, "https://script.google.com/macros/s/") || !strings.HasSuffix(realReportURL, "/exec") {
		t.Fatalf("адрес приёма: %q", realReportURL)
	}
}

// Поля в пределах таблицы: длинное режется, а не уходит на отказ всего отчёта
func TestReportClip(t *testing.T) {
	long := strings.Repeat("ж", 300)
	r := Report{Release: "1.0.1", TVBrand: long, TVModel: long, Android: long, Profile: long, ABI: long, FailedStep: long, Error: long + long,
		Lang: long, Modules: []string{long}, SDK: 300, Path: "что-то"}.clip()
	for _, c := range []struct {
		name string
		got  string
		max  int
	}{{"tv_brand", r.TVBrand, 60}, {"tv_model", r.TVModel, 80}, {"android", r.Android, 10}, {"profile", r.Profile, 30}, {"abi", r.ABI, 30},
		{"failed_step", r.FailedStep, 200}, {"error", r.Error, 500}, {"lang", r.Lang, 10}, {"modules[0]", r.Modules[0], 30}} {
		if n := len([]rune(c.got)); n != c.max {
			t.Fatalf("%s: %d знаков, нужно %d", c.name, n, c.max)
		}
	}
	if r.SDK != 0 || r.Path != "cli" {
		t.Fatalf("sdk %d, path %q", r.SDK, r.Path)
	}
}
