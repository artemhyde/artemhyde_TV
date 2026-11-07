package main

// Установка, проверка и откат против подставного ТВ: его пакеты, главный экран, экран сверху, настройки и связь
// живут в памяти. Ничего не подключается к настоящему ТВ

import (
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
)

type fakeTV struct {
	tcl       bool
	online    bool
	pkgs      map[string]int64
	disabled  map[string]bool
	home      string
	top       string
	settings  map[string]string // «secure:key» → значение
	ran       []string
	failOnce  map[string]bool
	failAll   map[string]bool
	loseAfter string // после этой команды связь пропадает
	roleStuck bool   // роль «Домой» не меняется (как на TCL, пока включены launcherx и setupwraith)
	intercept bool   // «Домой» открывает launcherx, пока он включён (Google TV других марок)
	files     map[string]string
	grants    map[string]bool // «пакет право», «пакет OP», «+пакет» белого списка, компонент администратора и слушателя
	guardUp   bool            // служба «Сторожа» уже отвечает
	nightOff  bool            // ночная подсветка «Сторожа» выключена
}

func newFakeTV(tcl bool) *fakeTV {
	f := &fakeTV{tcl: tcl, online: true, pkgs: map[string]int64{"com.google.android.apps.tv.launcherx": 1, "com.google.android.tungsten.setupwraith": 1,
		"com.android.tv.settings": 1, "com.google.android.youtube.tv": 1, "com.google.android.inputmethod.latin": 1},
		disabled: map[string]bool{}, home: "com.google.android.apps.tv.launcherx", top: "com.google.android.apps.tv.launcherx",
		settings: map[string]string{"secure:screensaver_enabled": "1", "secure:screensaver_activate_on_sleep": "1", "secure:screensaver_activate_on_dock": "1",
			"system:screen_off_timeout": "600000", "secure:default_input_method": "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
			"secure:android_id": "abc123", "global:adb_wifi_enabled": "1"},
		failOnce: map[string]bool{}, failAll: map[string]bool{}, files: map[string]string{}, grants: map[string]bool{}}
	return f
}

func (f *fakeTV) Serial() string { return "fake:5555" }

func (f *fakeTV) State() string {
	if f.online {
		return "device"
	}
	return "offline"
}

func (f *fakeTV) passport() string {
	var b strings.Builder
	man, typ, lib, dolby := "Google", "", "", ""
	if f.tcl {
		man, typ, lib, dolby = "TCL", "GTV", "library:com.tcl.tv.display\n", "package:com.dolby.android.audio.service versionCode:1\n"
	}
	fmt.Fprintf(&b, "##props\nro.product.manufacturer=%s\nro.product.brand=%s\nro.product.model=Test TV\nro.boot.product=X1\nro.tcl.product_type=%s\n", man, man, typ)
	b.WriteString("ro.product.cpu.abilist=armeabi-v7a,armeabi\nro.build.version.sdk=34\nro.build.version.release=14\nro.build.fingerprint=test/fp\n")
	b.WriteString("##features\nfeature:android.software.leanback\n##libraries\n" + lib + "##packages\n" + dolby)
	var ps []string
	for p, v := range f.pkgs {
		ps = append(ps, fmt.Sprintf("package:%s versionCode:%d", p, v))
	}
	sort.Strings(ps)
	b.WriteString(strings.Join(ps, "\n") + "\n##disabled\n")
	for p := range f.disabled {
		b.WriteString("package:" + p + "\n")
	}
	b.WriteString("##home\n" + f.home + "\n##display_service\n##quickpanel\nNo services found\n##tkl\n##settings\n")
	for k, v := range f.settings {
		b.WriteString(k + "=" + v + "\n")
	}
	b.WriteString("##admins\n")
	for k, v := range f.grants {
		if v && strings.Contains(k, "/.") {
			b.WriteString("    " + k + ":\n")
		}
	}
	b.WriteString("##df\nFilesystem 1K-blocks Used Available Use% Mounted on\n/dev/x 100 10 5000000 1% /data\n##end\n")
	return b.String()
}

func (f *fakeTV) settingsList(ns string) string {
	var ls []string
	for k, v := range f.settings {
		if n, key, _ := strings.Cut(k, ":"); n == ns {
			ls = append(ls, key+"="+v)
		}
	}
	sort.Strings(ls)
	return strings.Join(ls, "\n") + "\n"
}

func (f *fakeTV) Shell(cmd string, _ time.Duration) (string, error) {
	if !f.online {
		return "error: device offline", errors.New("offline")
	}
	if cmd == passportScript {
		return f.passport(), nil
	}
	w := strings.Fields(cmd)
	read := strings.HasPrefix(cmd, "settings get") || strings.HasPrefix(cmd, "settings list") || strings.HasPrefix(cmd, "pm path") ||
		strings.HasPrefix(cmd, "pm list") || strings.HasPrefix(cmd, "cmd role get") || cmd == homeHolderCmd || strings.HasPrefix(cmd, "dumpsys") || strings.HasPrefix(cmd, "cmd package resolve") ||
		strings.HasPrefix(cmd, "appops get") || strings.Contains(cmd, "echo \"home_role_holder")
	if !read {
		f.ran = append(f.ran, cmd)
	}
	if f.failAll[cmd] {
		return "Error: test failure", errors.New("exit 1")
	}
	if f.failOnce[cmd] {
		delete(f.failOnce, cmd)
		return "Error: test failure once", errors.New("exit 1")
	}
	defer func() {
		if f.loseAfter != "" && cmd == f.loseAfter {
			f.online = false
		}
	}()
	pkgOK := func(p string) (string, error) {
		if _, ok := f.pkgs[p]; ok {
			return "", nil
		}
		return "Error: Unknown package: " + p, errors.New("exit 255")
	}
	switch {
	case strings.HasPrefix(cmd, "settings get "):
		if v, ok := f.settings[w[2]+":"+w[3]]; ok {
			return v + "\n", nil
		}
		return "null\n", nil
	case strings.HasPrefix(cmd, "settings list "):
		return f.settingsList(w[2]), nil
	case strings.HasPrefix(cmd, "settings put "):
		f.settings[w[2]+":"+w[3]] = unquote(strings.Join(w[4:], " "))
	case strings.HasPrefix(cmd, "settings delete "):
		delete(f.settings, w[2]+":"+w[3])
	case strings.HasPrefix(cmd, "pm path "):
		if _, ok := f.pkgs[w[2]]; ok {
			return "package:/data/app/" + w[2] + "/base.apk\n", nil
		}
		return "", errors.New("exit 1")
	case strings.HasPrefix(cmd, "pm list packages -d"):
		var b strings.Builder
		for p := range f.disabled {
			b.WriteString("package:" + p + "\n")
		}
		return b.String(), nil
	case strings.HasPrefix(cmd, "cmd role get-role-holders") || cmd == homeHolderCmd:
		return f.home + "\n", nil
	case strings.HasPrefix(cmd, "dumpsys activity activities"):
		return fmt.Sprintf("  topResumedActivity=ActivityRecord{abc u0 %s/.Main t1}\n", f.top), nil
	case strings.HasPrefix(cmd, "dumpsys activity services app.glasstv.guard"):
		if _, ok := f.pkgs["app.glasstv.guard"]; ok {
			return "  * ServiceRecord{x u0 app.glasstv.guard/.GuardService}\n", nil
		}
		return "", nil
	case strings.HasPrefix(cmd, "cmd package resolve-activity"):
		if _, ok := f.pkgs[w[len(w)-1]]; ok {
			return "priority=0 preferredOrder=0\n" + w[len(w)-1] + "/.HomeActivity\n", nil
		}
		return "No activity found\n", nil
	case strings.HasPrefix(cmd, "am start -n app.glasstv.home"):
		f.top = "app.glasstv.home"
	case strings.HasPrefix(cmd, "am start -n ") && strings.Contains(cmd, "--ez unadmin true"):
		p, _, _ := strings.Cut(w[3], "/")
		for k := range f.grants {
			if strings.HasPrefix(k, p+"/.") {
				delete(f.grants, k)
			}
		}
	case strings.HasPrefix(cmd, "cmd package set-home-activity "):
		if !f.roleStuck {
			p, _, _ := strings.Cut(w[3], "/")
			f.home = p
		}
		return "Success: default home activity set\n", nil
	case strings.HasPrefix(cmd, "cmd role add-role-holder"):
		f.home = w[len(w)-1]
	case cmd == "input keyevent KEYCODE_HOME":
		f.top = f.home
		if _, ok := f.pkgs["com.google.android.apps.tv.launcherx"]; ok && f.intercept && !f.disabled["com.google.android.apps.tv.launcherx"] {
			f.top = "com.google.android.apps.tv.launcherx"
		}
	case strings.HasPrefix(cmd, "pm disable-user"):
		f.disabled[w[len(w)-1]] = true
	case strings.HasPrefix(cmd, "pm enable"):
		delete(f.disabled, w[len(w)-1])
	case strings.HasPrefix(cmd, "pm uninstall"):
		p := w[len(w)-1]
		if _, ok := f.pkgs[p]; !ok {
			return "Failure [DELETE_FAILED_INTERNAL_ERROR]\n", errors.New("exit 1")
		}
		delete(f.pkgs, p)
		return "Success\n", nil
	case strings.HasPrefix(cmd, "dumpsys package ") && len(w) >= 7:
		perm := strings.TrimSuffix(strings.TrimPrefix(w[5], "'"), ":")
		if f.grants[w[2]+" "+perm] {
			return perm + ": granted=true\n", nil
		}
		return "", nil
	case strings.HasPrefix(cmd, "appops get "):
		if f.grants[w[2]+" "+w[3]] {
			return w[3] + ": allow\n", nil
		}
		return w[3] + ": default\n", nil
	case strings.HasPrefix(cmd, "dumpsys deviceidle whitelist "):
		f.grants[w[3]] = strings.HasPrefix(w[3], "+")
		if !f.grants[w[3]] {
			delete(f.grants, "+"+strings.TrimPrefix(w[3], "-"))
		}
		f.ran = append(f.ran, cmd)
	case cmd == "dumpsys deviceidle whitelist":
		var b strings.Builder
		for k, v := range f.grants {
			if v && strings.HasPrefix(k, "+") {
				b.WriteString("user," + strings.TrimPrefix(k, "+") + ",10100\n")
			}
		}
		return b.String(), nil
	case strings.HasPrefix(cmd, "dpm set-active-admin"):
		f.grants[w[len(w)-1]] = true
	case cmd == "dumpsys device_policy":
		var b strings.Builder
		for k, v := range f.grants {
			if v && strings.Contains(k, "/.") {
				b.WriteString("    " + k + ":\n")
			}
		}
		return b.String(), nil
	case strings.HasPrefix(cmd, "cmd notification allow_listener"):
		// Как в Android: слушатели списком через двоеточие, новый дописывается
		const k = "secure:enabled_notification_listeners"
		if cur := f.settings[k]; cur == "" {
			f.settings[k] = w[3]
		} else if !strings.Contains(":"+cur+":", ":"+w[3]+":") {
			f.settings[k] = cur + ":" + w[3]
		}
	case strings.HasPrefix(cmd, "cmd notification disallow_listener"):
		const k = "secure:enabled_notification_listeners"
		var keep []string
		for _, c := range strings.Split(f.settings[k], ":") {
			if c != "" && c != w[3] {
				keep = append(keep, c)
			}
		}
		if len(keep) == 0 {
			delete(f.settings, k)
		} else {
			f.settings[k] = strings.Join(keep, ":")
		}
	case strings.HasPrefix(cmd, "appops set "):
		f.grants[w[2]+" "+w[3]] = w[4] == "allow"
		return pkgOK(w[2])
	case strings.HasPrefix(cmd, "pm grant "):
		f.grants[w[2]+" "+w[3]] = true
		return pkgOK(w[2])
	case strings.HasPrefix(cmd, "pm revoke "):
		delete(f.grants, w[2]+" "+w[3])
		return pkgOK(w[2])
	case strings.HasPrefix(cmd, "ime enable "):
		f.settings["secure:default_input_method"] = strings.TrimSuffix(w[2], ";")
	case strings.HasPrefix(cmd, "ime set "):
		f.settings["secure:default_input_method"] = w[2]
	case strings.HasPrefix(cmd, "pm compile"):
		return "Success\n", nil
	case strings.HasPrefix(cmd, "am broadcast -n app.glasstv.guard/.Cmd --es do nighthours"):
		if _, ok := f.pkgs["app.glasstv.guard"]; !ok || !f.guardUp {
			f.guardUp = f.pkgs["app.glasstv.guard"] != 0 // служба поднимается только к следующему запросу, как на ТВ
			return "Broadcast completed: result=0, data=\"служба «Сторожа» не запущена\"\n", nil
		}
		if strings.HasSuffix(cmd, "enabled false") {
			f.nightOff = true
		} else if strings.HasSuffix(cmd, "enabled true") {
			f.nightOff = false
		}
		if f.nightOff {
			return "Broadcast completed: result=0, data=\"ночная подсветка выключена\"\n", nil
		}
		return "Broadcast completed: result=0, data=\"ночная подсветка с 22:00 до 7:00\"\n", nil
	}
	return "", nil
}

func (f *fakeTV) Install(apk, installer string) (string, error) {
	if !f.online {
		return "error: device offline", errors.New("offline")
	}
	pkg := f.files[filepath.Base(apk)]
	if pkg == "" {
		return "Failure [unknown file " + apk + "]", errors.New("exit 1")
	}
	f.ran = append(f.ran, "install "+pkg+prefixed(" от имени ", installer))
	f.pkgs[pkg] = 10000
	return "Performing Streamed Install\nSuccess\n", nil
}

func (f *fakeTV) Pull(remote, local string) error { return os.WriteFile(local, []byte("apk"), 0o644) }

// applyEnv: выпуск во временной папке, подпись файлов как у ключа выпуска, подставной ТВ знает имена файлов
func applyEnv(t *testing.T, tcl bool) (*fakeTV, *Manifest, Recipe) {
	t.Helper()
	m := testManifest(t)
	m.SignerCert = "cafe"
	old := certOf
	certOf = func(string) (string, error) { return "cafe", nil }
	homeWait, retryPause = 0, 0
	t.Cleanup(func() { certOf, homeWait, retryPause = old, 3*time.Second, 2*time.Second })
	f := newFakeTV(tcl)
	for _, a := range m.Apps {
		f.files[a.File] = a.Package
	}
	return f, m, recipe(t)
}

func noKM(home bool) Choices {
	return Choices{Modules: map[string]bool{"keymapper": false}, Home: &home}
}

func ledgerOf(t *testing.T, dir string) []LedgerEntry {
	t.Helper()
	es, err := readLedger(filepath.Join(dir, "ledger.tsv"))
	if err != nil {
		t.Fatal(err)
	}
	return es
}

func TestApplyNeedsDownloadConsent(t *testing.T) {
	f, m, rec := applyEnv(t, false)
	root := t.TempDir()
	res := runApply(f, nil, rec, m, Choices{}, ApplyOpts{TVFree: true, BackupRoot: root})
	if res.Error == "" || !strings.Contains(res.Error, "согласие") || len(f.ran) != 0 {
		t.Fatalf("без согласия на скачивание: %q, команд %d", res.Error, len(f.ran))
	}
	if ents, _ := os.ReadDir(root); len(ents) != 0 {
		t.Fatal("журнал создан до проверок")
	}
}

func TestApplyNeedsTVFreeForHome(t *testing.T) {
	f, m, rec := applyEnv(t, false)
	res := runApply(f, nil, rec, m, noKM(true), ApplyOpts{BackupRoot: t.TempDir()})
	if !strings.Contains(res.Error, "ТВ свободен") || len(f.ran) != 0 {
		t.Fatalf("смена главного экрана без «ТВ свободен»: %q", res.Error)
	}
}

func TestApplyGoogleTVAndRestore(t *testing.T) {
	f, m, rec := applyEnv(t, false)
	root := t.TempDir()
	res := runApply(f, nil, rec, m, noKM(true), ApplyOpts{TVFree: true, BackupRoot: root})
	if !res.Finished || res.Error != "" {
		t.Fatalf("установка: %+v", res)
	}
	for _, p := range []string{"app.glasstv.guard", "app.glasstv.home", "app.glasstv.nightmode", "app.glasstv.keyboard", "app.glasstv.tile.settings"} {
		if _, ok := f.pkgs[p]; !ok {
			t.Fatalf("не стоит %s", p)
		}
	}
	if f.home != "app.glasstv.home" || f.top != "app.glasstv.home" {
		t.Fatalf("главный экран %s, сверху %s", f.home, f.top)
	}
	if f.settings["secure:screensaver_enabled"] != "0" || f.settings["secure:default_input_method"] != "app.glasstv.keyboard/.Kbd" {
		t.Fatalf("настройки: %v", f.settings)
	}
	es := ledgerOf(t, res.Backup)
	seen := map[string]int{}
	for _, e := range es {
		seen[e.Do]++
		if seen[e.Do] > 1 {
			t.Fatalf("строка журнала дважды: %s", e.Do)
		}
	}
	for _, fn := range []string{"settings-secure.txt", "packages-third.txt", "state.txt", "session.json"} {
		if _, err := os.Stat(filepath.Join(res.Backup, fn)); err != nil {
			t.Fatalf("нет %s в папке журнала", fn)
		}
	}
	cs, err := runVerify(f, res.Backup)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range cs {
		if !c.OK {
			t.Fatalf("проверка: %s (%s)", c.What, c.Detail)
		}
	}
	if len(cs) < 15 {
		t.Fatalf("проверок мало: %d", len(cs))
	}

	r := runRestore(f, res.Backup, nil)
	if r.Error != "" || r.Fail != 0 || r.Lost {
		t.Fatalf("откат: %+v", r)
	}
	for p := range f.pkgs {
		if strings.HasPrefix(p, "app.glasstv.") {
			t.Fatalf("после отката остался %s", p)
		}
	}
	// «Сторож» ставится второй раз от своего имени («обновлён»), но откат удаляет его целиком: руками делать нечего
	for _, m := range r.Manual {
		if strings.HasPrefix(m, "обновлён app.glasstv.") {
			t.Fatalf("лишнее «делайте руками» после отката чистой установки: %s", m)
		}
	}
	if f.home != "com.google.android.apps.tv.launcherx" || f.settings["secure:screensaver_enabled"] != "1" ||
		f.settings["secure:default_input_method"] != "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME" {
		t.Fatalf("после отката: главный %s, настройки %v", f.home, f.settings)
	}
	r2 := runRestore(f, res.Backup, nil)
	if r2.Fail != 0 || r2.Gone == 0 {
		t.Fatalf("повторный откат: %+v", r2)
	}
}

func TestApplyResumeAfterLoss(t *testing.T) {
	f, m, rec := applyEnv(t, false)
	root := t.TempDir()
	f.loseAfter = "appops set app.glasstv.home GET_USAGE_STATS allow"
	res := runApply(f, nil, rec, m, noKM(false), ApplyOpts{BackupRoot: root})
	if !res.Lost || res.Finished {
		t.Fatalf("обрыв: %+v", res)
	}
	f.online, f.loseAfter = true, ""
	res2 := runApply(f, nil, rec, m, noKM(false), ApplyOpts{BackupRoot: root})
	if !res2.Resumed || !res2.Finished || res2.Backup != res.Backup {
		t.Fatalf("продолжение: %+v", res2)
	}
	seen := map[string]int{}
	for _, e := range ledgerOf(t, res.Backup) {
		if seen[e.Do]++; seen[e.Do] > 1 {
			t.Fatalf("после продолжения строка журнала дважды: %s", e.Do)
		}
	}
	installs := 0
	for _, c := range f.ran {
		if c == "install app.glasstv.guard" {
			installs++
		}
	}
	if installs != 1 {
		t.Fatalf("«Сторож» ставился %d раз", installs)
	}
}

func TestApplyHomeSwitchFailsAndReverts(t *testing.T) {
	f, m, rec := applyEnv(t, true)
	f.roleStuck = true
	res := runApply(f, nil, rec, m, noKM(true), ApplyOpts{TVFree: true, BackupRoot: t.TempDir()})
	if res.Error == "" || !strings.Contains(res.Error, "Вернул как было") {
		t.Fatalf("неудачная смена главного экрана: %+v", res)
	}
	if len(f.disabled) != 0 || f.home != "com.google.android.apps.tv.launcherx" {
		t.Fatalf("после неудачи: отключены %v, главный %s", f.disabled, f.home)
	}
	var dis, role int = -1, -1
	for i, c := range f.ran {
		if strings.HasPrefix(c, "pm disable-user") && dis < 0 {
			dis = i
		}
		if strings.HasPrefix(c, "cmd package set-home-activity") {
			role = i
		}
	}
	if dis < 0 || role < 0 || dis > role {
		t.Fatalf("на TCL отключение до смены роли: %d %d", dis, role)
	}
}

func TestApplyRetriesOnce(t *testing.T) {
	f, m, rec := applyEnv(t, false)
	f.failOnce["appops set app.glasstv.guard WRITE_SETTINGS allow"] = true
	res := runApply(f, nil, rec, m, noKM(false), ApplyOpts{BackupRoot: t.TempDir()})
	if !res.Finished {
		t.Fatalf("одиночный сбой команды: %+v", res)
	}
}

func TestApplyStopsOnRealFailure(t *testing.T) {
	f, m, rec := applyEnv(t, false)
	f.failAll["pm grant app.glasstv.guard android.permission.DUMP"] = true
	res := runApply(f, nil, rec, m, noKM(false), ApplyOpts{BackupRoot: t.TempDir()})
	if res.Finished || !strings.Contains(res.Error, "DUMP") && !strings.Contains(res.Error, "состояние системы") {
		t.Fatalf("ошибка шага должна остановить установку: %+v", res)
	}
	if _, ok := f.pkgs["app.glasstv.home"]; ok {
		t.Fatal("после ошибки установка пошла дальше")
	}
}

func TestRestoreOrderRolesLast(t *testing.T) {
	es := []LedgerEntry{
		{What: "отключён a", Do: "pm disable-user --user 0 a", Undo: "pm enable --user 0 a"},
		{What: "отключён b", Do: "pm disable-user --user 0 b", Undo: "pm enable --user 0 b"},
		{What: "главный экран", Do: "cmd package set-home-activity h/.H", Undo: "cmd role add-role-holder --user 0 android.app.role.HOME old"},
		{What: "обновлён x", Do: "adb install -r x.apk", Undo: ""},
	}
	undo, manual := restoreOrder(es)
	var got []string
	for _, e := range undo {
		got = append(got, e.Undo)
	}
	want := []string{"pm enable --user 0 b", "pm enable --user 0 a", "cmd role add-role-holder --user 0 android.app.role.HOME old"}
	if strings.Join(got, "|") != strings.Join(want, "|") || len(manual) != 1 {
		t.Fatalf("порядок отката: %v, руками %d", got, len(manual))
	}
}

func TestRestoreWarnsAdmin(t *testing.T) {
	es := []LedgerEntry{
		{What: "установлен p", Do: "adb install -r p.apk", Undo: "pm uninstall --user 0 p"},
		{What: "админ", Do: "dpm set-active-admin --user 0 p/.Admin", Undo: "pm enable --user 0 q"},
	}
	undo, _ := restoreOrder(es)
	if w := restoreWarnings(es, undo); len(w) == 0 || !strings.Contains(w[0], "администратора") {
		t.Fatalf("нет предупреждения про права администратора: %v", w)
	}
}

func TestDownloadThirdParty(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("apk bytes")) }))
	defer srv.Close()
	old := certOf
	defer func() { certOf = old }()
	certOf = func(string) (string, error) { return "good", nil }
	dir := t.TempDir()
	tp := &ThirdParty{Name: "Тест", Download: srv.URL + "/km.apk", CertSha256: "good"}
	p, err := downloadThirdParty(tp, tp.Download, "", dir, nil)
	if err != nil || filepath.Base(p) != "km.apk" {
		t.Fatalf("скачивание: %v %s", err, p)
	}
	// Сумма файла со страницы релиза: совпала, ставим; другая, файл удаляется ещё до сверки подписи
	good := "4b0f7ae5fd2a5e2e1db0c5b3d0e4b3c0fd8c8e7d3d6a2f55a4ae4f4c1a0c2fd1"
	if h, _ := sha256File(p); h != "" {
		good = h
	}
	if _, err := downloadThirdParty(tp, tp.Download, good, dir, nil); err != nil {
		t.Fatalf("сумма совпала, а файл отклонён: %v", err)
	}
	if _, err := downloadThirdParty(tp, tp.Download, strings.Repeat("0", 64), dir, nil); err == nil || !strings.Contains(err.Error(), "не тот") {
		t.Fatalf("файл с чужой суммой должен отклоняться: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "km.apk")); err == nil {
		t.Fatal("файл с чужой суммой не удалён")
	}
	tp.CertSha256 = "other"
	if _, err := downloadThirdParty(tp, tp.Download, "", dir, nil); err == nil || !strings.Contains(err.Error(), "не совпала") {
		t.Fatalf("чужая подпись должна отклоняться: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "km.apk")); err == nil {
		t.Fatal("файл с чужой подписью не удалён")
	}
	if _, err := downloadThirdParty(tp, "http://example.org/km.apk", "", dir, nil); err == nil {
		t.Fatal("скачивание не по https")
	}
}

// Файл под процессор ТВ: сначала основной процессор ТВ, потом общий; у записи без files одна ссылка download
func TestThirdPartyFileFor(t *testing.T) {
	tp := &ThirdParty{Files: []TPFile{{ABI: "armeabi-v7a", URL: "u32"}, {ABI: "arm64-v8a", URL: "u64"}, {ABI: "any", URL: "uall"}}}
	for abis, want := range map[string]string{"armeabi-v7a,armeabi": "u32", "arm64-v8a,armeabi-v7a,armeabi": "u64", "x86_64,x86": "uall"} {
		if f := tp.FileFor(strings.Split(abis, ",")); f == nil || f.URL != want {
			t.Fatalf("%s: %+v, ждали %s", abis, f, want)
		}
	}
	only := &ThirdParty{Files: []TPFile{{ABI: "armeabi-v7a", URL: "u32"}}}
	if f := only.FileFor([]string{"x86_64"}); f != nil {
		t.Fatalf("сборки под x86_64 нет, а файл нашёлся: %+v", f)
	}
	km := &ThirdParty{Download: "https://github.com/x/km.apk"}
	if f := km.FileFor([]string{"armeabi-v7a"}); f == nil || f.URL != km.Download {
		t.Fatalf("старая запись с одной ссылкой: %+v", f)
	}
}

// Google TV другой марки: роль меняется, но «Домой» открывает launcherx, пока он включён (07.11, виртуальный Google TV)
func TestApplyGoogleTVHomeIntercept(t *testing.T) {
	f, m, rec := applyEnv(t, false)
	f.intercept = true
	res := runApply(f, nil, rec, m, noKM(true), ApplyOpts{TVFree: true, BackupRoot: t.TempDir()})
	if !res.Finished || f.top != "app.glasstv.home" || !f.disabled["com.google.android.apps.tv.launcherx"] || !f.disabled["com.google.android.tungsten.setupwraith"] {
		t.Fatalf("перехват «Домой»: %+v, сверху %s, отключены %v", res, f.top, f.disabled)
	}
	r := runRestore(f, res.Backup, nil)
	if r.Fail != 0 || len(f.disabled) != 0 || f.home != "com.google.android.apps.tv.launcherx" {
		t.Fatalf("откат после перехвата: %+v, отключены %v, главный %s", r, f.disabled, f.home)
	}
}
