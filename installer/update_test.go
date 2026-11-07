package main

// Обновление поверх стоящего выпуска и откат установок по очереди (план Codex, этап 2: «обновление сохраняет
// настройки, сбой посередине восстанавливается»). Подставной ТВ, к настоящему ничего не подключается

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// installThenUpdate: установка, потом «выпуск поновее» (на ТВ версии старше описи) поверх неё
func installThenUpdate(t *testing.T) (*fakeTV, string, ApplyResult, ApplyResult) {
	t.Helper()
	f, m, rec := applyEnv(t, false)
	root := t.TempDir()
	first := runApply(f, nil, rec, m, noKM(true), ApplyOpts{TVFree: true, BackupRoot: root})
	if !first.Finished {
		t.Fatalf("установка: %+v", first)
	}
	if !f.grants["app.glasstv.nightmode/.AdminReceiver"] {
		t.Fatal("после установки у «Ночного режима» нет прав администратора")
	}
	for p := range f.pkgs {
		if strings.HasPrefix(p, "app.glasstv.") {
			f.pkgs[p] = 9999
		}
	}
	f.ran = nil
	second := runApply(f, nil, rec, m, noKM(true), ApplyOpts{TVFree: true, BackupRoot: root})
	if !second.Finished || second.Backup == first.Backup || second.Resumed {
		t.Fatalf("обновление: %+v (установка в %s)", second, first.Backup)
	}
	return f, root, first, second
}

func TestApplyUpdateKeepsPriorState(t *testing.T) {
	f, _, _, second := installThenUpdate(t)
	// В журнале обновления только сами обновления: права, белый список и главный экран уже были и не пишутся
	for _, e := range ledgerOf(t, second.Backup) {
		if !strings.HasPrefix(e.What, "обновлён ") || e.Undo != "" {
			t.Fatalf("в журнале обновления лишнее: %q (%s → %s)", e.What, e.Do, e.Undo)
		}
	}
	unadmin, admin := -1, -1
	for i, c := range f.ran {
		if strings.HasPrefix(c, "am start -n app.glasstv.nightmode/.MainActivity --ez unadmin true") {
			unadmin = i
		}
		if strings.HasPrefix(c, "dpm set-active-admin") && strings.Contains(c, "app.glasstv.nightmode") {
			admin = i
		}
	}
	if unadmin < 0 || admin < unadmin || !f.grants["app.glasstv.nightmode/.AdminReceiver"] {
		t.Fatalf("права администратора при обновлении: снятие %d, возврат %d, сейчас %v", unadmin, admin, f.grants["app.glasstv.nightmode/.AdminReceiver"])
	}
	for _, c := range f.ran {
		if strings.HasPrefix(c, "pm grant ") || strings.HasPrefix(c, "appops set ") {
			t.Fatalf("обновление заново выдаёт то, что уже было: %s", c)
		}
	}

	// Откат только обновления ничего не отнимает: приложения, права и главный экран как после установки
	r := runRestore(f, second.Backup, nil)
	if r.Error != "" || r.Fail != 0 || r.Lost || r.Done != 0 {
		t.Fatalf("откат обновления: %+v", r)
	}
	if _, ok := f.pkgs["app.glasstv.guard"]; !ok || !f.grants["app.glasstv.guard android.permission.WRITE_SECURE_SETTINGS"] ||
		!f.grants["app.glasstv.nightmode/.AdminReceiver"] || f.home != "app.glasstv.home" {
		t.Fatalf("после отката одного обновления пропало то, что было до него: главный %s, права %v", f.home, f.grants)
	}
}

func TestRestoreAllSessions(t *testing.T) {
	f, root, first, second := installThenUpdate(t)
	ds := sessionsOf(root, "abc123", true)
	if len(ds) != 2 || ds[0] != second.Backup || ds[1] != first.Backup {
		t.Fatalf("порядок журналов для отката: %v", ds)
	}
	ra := runRestoreAll(f, ds, nil)
	if !ra.OK() || len(ra.Results) != 2 {
		t.Fatalf("полный откат: %+v", ra)
	}
	for p := range f.pkgs {
		if strings.HasPrefix(p, "app.glasstv.") {
			t.Fatalf("после полного отката остался %s", p)
		}
	}
	if f.home != "com.google.android.apps.tv.launcherx" || f.settings["secure:screensaver_enabled"] != "1" ||
		f.settings["secure:default_input_method"] != "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME" {
		t.Fatalf("после полного отката: главный %s, настройки %v", f.home, f.settings)
	}
	if left := sessionsOf(root, "abc123", true); len(left) != 0 {
		t.Fatalf("после полного отката остались не откаченные журналы: %v", left)
	}
	if ds := sessionsOf(root, "другой", true); len(ds) != 0 {
		t.Fatal("журналы чужого ТВ попали в откат")
	}
}

func TestVerifyAcrossSessions(t *testing.T) {
	f, root, _, _ := installThenUpdate(t)
	cs, err := runVerify(f, sessionsOf(root, "abc123", true)...)
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for _, c := range cs {
		if !c.OK {
			t.Fatalf("после обновления не держится: %s (%s)", c.What, c.Detail)
		}
		if seen[c.What] {
			t.Fatalf("проверка дважды: %s", c.What)
		}
		seen[c.What] = true
	}
	if len(cs) < 15 {
		t.Fatalf("проверок мало: %d", len(cs))
	}
}

// План на живом ТВ помечает то, что уже действует (права Key Mapper, поставленного раньше): установка это пропустит,
// откат не тронет, и план не должен обещать обратного
func TestPlanMarksHeld(t *testing.T) {
	f, m, rec := applyEnv(t, false)
	f.pkgs["io.github.sds100.keymapper"] = 1
	f.grants["io.github.sds100.keymapper android.permission.WRITE_SECURE_SETTINGS"] = true
	pl := buildPlan(rec, m, parsePassport(f.passport()), Choices{}, false)
	markHeld(&pl, heldSteps(f, &pl, nil))
	km, guard := false, false
	for _, s := range pl.Steps {
		if s.Group == "Key Mapper" && strings.HasPrefix(s.What, "право менять защищённые настройки") {
			km = strings.HasSuffix(s.What, "уже так, не меняем") && s.Run && s.Do == "" && s.Undo == ""
		}
		if s.Group == "«Сторож»" && strings.HasPrefix(s.What, "право менять защищённые настройки") {
			guard = s.Do != "" && !s.Run
		}
	}
	if !km || !guard {
		t.Fatalf("пометка «уже так»: Key Mapper %v, «Сторож» остаётся изменением %v", km, guard)
	}
}

// TCL после обновления прошивки включил главный экран Google TV обратно, роль осталась у «Дома»: повторная установка
// отключает его снова, а откат этой установки включает обратно
func TestApplyTCLFirmwareReenabledLauncher(t *testing.T) {
	f, m, rec := applyEnv(t, true)
	root := t.TempDir()
	if r := runApply(f, nil, rec, m, noKM(true), ApplyOpts{TVFree: true, BackupRoot: root}); !r.Finished {
		t.Fatalf("установка: %+v", r)
	}
	delete(f.disabled, "com.google.android.apps.tv.launcherx") // «прошивка» включила обратно
	second := runApply(f, nil, rec, m, noKM(true), ApplyOpts{TVFree: true, BackupRoot: root})
	if !second.Finished || !f.disabled["com.google.android.apps.tv.launcherx"] {
		t.Fatalf("повторная установка: %+v, отключены %v", second, f.disabled)
	}
	es := ledgerOf(t, second.Backup)
	if len(es) != 1 || es[0].Undo != "pm enable --user 0 com.google.android.apps.tv.launcherx" {
		t.Fatalf("журнал повторной установки: %+v", es)
	}
	if r := runRestore(f, second.Backup, nil); r.Fail != 0 || f.disabled["com.google.android.apps.tv.launcherx"] {
		t.Fatalf("откат повторной установки: %+v, отключены %v", r, f.disabled)
	}
}

// «Дом» уже главный экран: без явного выбора план оставляет его главным (и чинит «Домой» на TCL), а не молчит
func TestPlanKeepsHomeDefault(t *testing.T) {
	f, m, rec := applyEnv(t, true)
	f.home = "app.glasstv.home"
	f.pkgs["app.glasstv.home"] = 10000
	pl := buildPlan(rec, m, parsePassport(f.passport()), Choices{}, false)
	if !pl.Home {
		t.Fatal("«Дом» уже главный, а план по умолчанию его не держит")
	}
	fresh := buildPlan(rec, m, parsePassport(newFakeTV(true).passport()), Choices{}, false)
	if fresh.Home {
		t.Fatal("на новом ТВ главный экран по умолчанию меняться не должен")
	}
}

// Повторная установка того же выпуска, где всё уже так: пустой журнал не остаётся
func TestApplyNoopLeavesNoJournal(t *testing.T) {
	f, m, rec := applyEnv(t, false)
	root := t.TempDir()
	first := runApply(f, nil, rec, m, noKM(true), ApplyOpts{TVFree: true, BackupRoot: root})
	again := runApply(f, nil, rec, m, noKM(true), ApplyOpts{TVFree: true, BackupRoot: root})
	if !again.Finished || again.Backup != "" || again.Done != 0 {
		t.Fatalf("повтор: %+v", again)
	}
	if ds := sessionsOf(root, "abc123", false); len(ds) != 1 || ds[0] != first.Backup {
		t.Fatalf("журналы после повтора: %v", ds)
	}
}

// Key Mapper со своего адреса (подставной сервер): в журнале «установлен», а не «обновлён», откат его не удаляет,
// а службу кнопок и права снимает (07.11, живой Key Mapper с GitHub подписывался «обновлён… данные на месте»)
func TestApplyKeyMapperFreshInstall(t *testing.T) {
	f, m, rec := applyEnv(t, false)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("km apk")) }))
	defer srv.Close()
	tp := &m.ThirdParty[0]
	tp.Download = srv.URL + "/keymapper-4.6.2-foss.apk"
	old := certOf
	certOf = func(p string) (string, error) {
		if strings.HasSuffix(p, "keymapper-4.6.2-foss.apk") {
			return tp.CertSha256, nil
		}
		return "cafe", nil
	}
	defer func() { certOf = old }()
	f.files["keymapper-4.6.2-foss.apk"] = "io.github.sds100.keymapper"
	res := runApply(f, nil, rec, m, Choices{}, ApplyOpts{AllowDownload: true, BackupRoot: t.TempDir()})
	if !res.Finished {
		t.Fatalf("установка с Key Mapper: %+v", res)
	}
	var km *LedgerEntry
	for _, e := range ledgerOf(t, res.Backup) {
		if strings.Contains(e.What, "io.github.sds100.keymapper") && reInstalled.MatchString(e.What) {
			e := e
			km = &e
		}
	}
	if km == nil || !strings.HasPrefix(km.What, "установлен ") || !strings.Contains(km.What, "откат его не удаляет") || km.Undo != "" {
		t.Fatalf("запись о Key Mapper: %+v", km)
	}
	r := runRestore(f, res.Backup, nil)
	if r.Fail != 0 {
		t.Fatalf("откат: %+v", r)
	}
	if _, ok := f.pkgs["io.github.sds100.keymapper"]; !ok {
		t.Fatal("откат удалил Key Mapper")
	}
	if f.grants["io.github.sds100.keymapper android.permission.WRITE_SECURE_SETTINGS"] {
		t.Fatal("откат не снял право Key Mapper")
	}
	manual := strings.Join(r.Manual, "\n")
	if !strings.Contains(manual, "установлен io.github.sds100.keymapper") || strings.Contains(manual, "обновлён io.github.sds100.keymapper") {
		t.Fatalf("список «делайте руками»: %q", manual)
	}
}

// Установка только основному пользователю: у TCL есть служебный второй пользователь, и поставленное всем приложение
// после отката «pm uninstall --user 0» осталось бы за ним (07.11, настоящий TCL 65C6K)
func TestInstallStepsMainUserOnly(t *testing.T) {
	f, m, rec := applyEnv(t, true)
	pl := buildPlan(rec, m, parsePassport(f.passport()), Choices{}, false)
	n := 0
	for _, s := range pl.Steps {
		if s.Kind == "install" || s.Kind == "install3p" {
			n++
			if !strings.HasPrefix(s.Do, "adb install --user 0 ") {
				t.Fatalf("установка не только основному пользователю: %s", s.Do)
			}
		}
	}
	if n == 0 {
		t.Fatal("в плане нет установок")
	}
}
