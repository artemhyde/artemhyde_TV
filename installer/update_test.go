package main

// Обновление поверх стоящего выпуска и откат установок по очереди (план Codex, этап 2: «обновление сохраняет
// настройки, сбой посередине восстанавливается»). Подставной ТВ, к настоящему ничего не подключается

import (
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
