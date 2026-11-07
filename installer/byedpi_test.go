package main

// Модуль ByeByeDPI (docs/byedpi.md): запись в рецепте и в списке источников, план, установка и откат на подставном ТВ;
// обновления и присмотр «Сторожа» (apps/guard/assets/updates.txt, Bye.java)

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

const byedpiPkg = "io.github.romanvht.byedpi"

func byedpiModule(t *testing.T, rec Recipe) RecipeModule {
	t.Helper()
	for _, m := range rec.Modules {
		if m.Module == "byedpi" {
			return m
		}
	}
	t.Fatal("в рецепте нет модуля byedpi")
	return RecipeModule{}
}

// Запись источника: файл автора с суммой и размером, отпечаток, который автор публикует сам, проверенная версия
func TestByeByeDPISource(t *testing.T) {
	raw, err := os.ReadFile("../data/third-party-sources.json")
	if err != nil {
		t.Fatal(err)
	}
	var src struct {
		Apps []ThirdParty `json:"apps"`
	}
	if err := json.Unmarshal(raw, &src); err != nil {
		t.Fatal(err)
	}
	var tp *ThirdParty
	for i := range src.Apps {
		if src.Apps[i].Package == byedpiPkg {
			tp = &src.Apps[i]
		}
	}
	if tp == nil {
		t.Fatal("нет ByeByeDPI в data/third-party-sources.json")
	}
	f := tp.FileFor([]string{"armeabi-v7a", "armeabi"})
	if f == nil || f.ABI != "any" || !strings.HasPrefix(f.URL, "https://github.com/romanvht/ByeByeDPI/releases/download/") ||
		f.Size != 3332888 || f.Sha256 != "3df9570313c9fb03de19fde7dbf7e56b943fd0845b2d57b687c5859aff4e0001" {
		t.Fatalf("файл ByeByeDPI: %+v", f)
	}
	if tp.CertSha256 != "77451075acea406406475d74d459883a49a64051faf32e42f718f3f9777a8dfb" || tp.PinnedVersion != "1.8.2" {
		t.Fatalf("отпечаток или версия ByeByeDPI: %+v", tp)
	}
}

// Шаг загрузки списка в рецепте совпадает с tools/byedpi-import.sh без комментариев; разрешения пишутся на диск сразу
func TestByeByeDPIRecipe(t *testing.T) {
	m := byedpiModule(t, recipe(t))
	if m.Default || m.ThirdParty != byedpiPkg || m.Updates != "guard" {
		t.Fatalf("модуль byedpi: по умолчанию выключен, обновляет «Сторож»: %+v", m)
	}
	raw, err := os.ReadFile("../tools/byedpi-import.sh")
	if err != nil {
		t.Fatal(err)
	}
	var keep []string
	for _, l := range strings.Split(strings.ReplaceAll(string(raw), "\r\n", "\n"), "\n") {
		if !strings.HasPrefix(l, "#") {
			keep = append(keep, l)
		}
	}
	want := strings.Trim(strings.Join(keep, "\n"), "\n")
	var imp, vpn, list, tcl *RecipeStep
	for i, s := range m.Steps {
		switch {
		case s.Run && strings.Contains(s.Do, "ToggleActivity"):
			imp = &m.Steps[i]
		case strings.Contains(s.Do, "ACTIVATE_VPN"):
			vpn = &m.Steps[i]
		case strings.Contains(s.Do, "ByeByeDPI-HYDE.json"):
			list = &m.Steps[i]
		case s.When == "tcl":
			tcl = &m.Steps[i]
		}
	}
	if imp == nil || imp.Do != want {
		t.Fatal("шаг загрузки списка в installer/recipe.json не совпадает с tools/byedpi-import.sh (без строк-комментариев)")
	}
	if vpn == nil || !strings.Contains(vpn.Do, "appops write-settings") || !strings.Contains(vpn.Undo, "ACTIVATE_VPN default") {
		t.Fatalf("согласие на VPN: %+v", vpn)
	}
	if tcl == nil || !strings.Contains(tcl.Do, "AUTO_START allow") || !strings.Contains(tcl.Do, "appops write-settings") {
		t.Fatalf("автозапуск на TCL: %+v", tcl)
	}
	if list == nil || list.Undo != "rm -rf '/sdcard/0 HYDE TV'" {
		t.Fatalf("файл списка: %+v", list)
	}
	// Кто выключил ByeByeDPI, «Сторож» видит по событиям окон: право на них даёт обязательный модуль guard (с откатом)
	usage := false
	for _, s := range recipe(t).Module("guard").Steps {
		usage = usage || strings.HasPrefix(s.Do, "appops set app.glasstv.guard GET_USAGE_STATS allow") &&
			strings.HasPrefix(s.Undo, "appops set app.glasstv.guard GET_USAGE_STATS default")
	}
	if !usage {
		t.Fatal("присмотру за ByeByeDPI нужно право «Сторожа» видеть окна: в модуле guard нет шага GET_USAGE_STATS")
	}
	// Присмотр «Сторожа» включается только после удачной загрузки списка
	if !strings.Contains(imp.Do, "[ $r -eq 0 ] && am broadcast -n app.glasstv.guard/.Cmd --es do vpn --es a on") {
		t.Fatal("присмотр «Сторожа» включается не только после удачной загрузки списка")
	}
	// Список внутри шага: JSON формата экспорта ByeByeDPI, только YouTube-клиенты и «Волна», белый список и автозапуск
	a, b := strings.Index(list.Do, "'{"), strings.LastIndex(list.Do, "}'")
	var l struct {
		App      string         `json:"app"`
		Apps     []string       `json:"apps"`
		Settings map[string]any `json:"settings"`
	}
	if a < 0 || b < a || json.Unmarshal([]byte(list.Do[a+1:b+1]), &l) != nil || l.App != byedpiPkg ||
		l.Settings["applist_type"] != "whitelist" || l.Settings["autostart"] != true || l.Settings["byedpi_mode"] != "vpn" {
		t.Fatalf("список для импорта: %q", list.Do)
	}
	for _, p := range l.Apps {
		if !strings.Contains(p, "youtube") && !strings.Contains(p, "smarttube") && p != "app.glasstv.volna" && p != "app.glasstv.aura" {
			t.Fatalf("в туннель попало лишнее приложение: %s", p)
		}
	}
}

// План и установка: скачать со страницы автора, сверить сумму и подпись, шаги ТВ; на TCL ещё автозапуск; откат всё убирает
func TestApplyByeByeDPI(t *testing.T) {
	for _, tcl := range []bool{false, true} {
		f, m, rec := applyEnv(t, tcl)
		body := []byte("byedpi apk")
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(body) }))
		sum := sha256.Sum256(body)
		file := "ByeByeDPI-v1.8.2-universal-release.apk"
		cert := "77451075acea406406475d74d459883a49a64051faf32e42f718f3f9777a8dfb"
		m.ThirdParty = append(m.ThirdParty, ThirdParty{Name: "ByeByeDPI", Package: byedpiPkg, PinnedVersion: "1.8.2", CertSha256: cert,
			Files: []TPFile{{ABI: "any", URL: srv.URL + "/" + file, Size: int64(len(body)), Sha256: hex.EncodeToString(sum[:])}}})
		old := certOf
		certOf = func(p string) (string, error) {
			if strings.HasSuffix(p, file) {
				return cert, nil
			}
			return "cafe", nil
		}
		f.files[file] = byedpiPkg
		ch := Choices{Modules: map[string]bool{"byedpi": true, "keymapper": false}}
		pl := buildPlan(rec, m, parsePassport(f.passport()), ch, false)
		var dl, inst, autostart *Step
		for i, s := range pl.Steps {
			switch {
			case s.Kind == "download" && s.Package == byedpiPkg:
				dl = &pl.Steps[i]
			case s.Kind == "install3p" && s.Package == byedpiPkg:
				inst = &pl.Steps[i]
			case strings.Contains(s.Do, byedpiPkg+" AUTO_START"):
				autostart = &pl.Steps[i]
			}
		}
		if dl == nil || dl.Sha256 == "" || inst == nil || inst.Installer != "app.glasstv.guard" || inst.Undo != "pm uninstall --user 0 "+byedpiPkg {
			t.Fatalf("скачивание и установка ByeByeDPI: %+v %+v", dl, inst)
		}
		if (autostart != nil) != tcl {
			t.Fatalf("автозапуск ByeByeDPI только на TCL (tcl=%v): %+v", tcl, autostart)
		}
		res := runApply(f, nil, rec, m, ch, ApplyOpts{AllowDownload: true, BackupRoot: t.TempDir()})
		if !res.Finished {
			srv.Close()
			certOf = old
			t.Fatalf("установка с ByeByeDPI (tcl=%v): %+v", tcl, res)
		}
		if !contains(f.ran, "install "+byedpiPkg+" от имени app.glasstv.guard") || !contains(f.ran, "appops set "+byedpiPkg+" ACTIVATE_VPN allow && appops write-settings") {
			t.Fatalf("ByeByeDPI не поставлен от имени «Сторожа» или нет согласия на VPN: %v", f.ran)
		}
		if !ranPrefix(f.ran, "appops set app.glasstv.guard GET_USAGE_STATS allow") {
			t.Fatalf("«Сторож» не получил право видеть окна: %v", f.ran)
		}
		if r := runRestore(f, res.Backup, nil); r.Fail != 0 {
			t.Fatalf("откат: %+v", r)
		}
		if _, ok := f.pkgs[byedpiPkg]; ok {
			t.Fatal("откат не удалил ByeByeDPI, которого поставил установщик")
		}
		if !contains(f.ran, "appops set "+byedpiPkg+" ACTIVATE_VPN default && appops write-settings") || !contains(f.ran, "rm -rf '/sdcard/0 HYDE TV'") ||
			!ranPrefix(f.ran, "appops set app.glasstv.guard GET_USAGE_STATS default") {
			t.Fatalf("откат не вернул согласие на VPN, право «Сторожа» на окна или не убрал файл списка: %v", f.ran)
		}
		srv.Close()
		certOf = old
	}
}

func ranPrefix(ran []string, prefix string) bool {
	for _, c := range ran {
		if strings.HasPrefix(c, prefix) {
			return true
		}
	}
	return false
}

// Модуль, который обещает «новые версии потом ставит «Сторож» сам» (updates: guard), должен быть в списке обновлений «Сторожа»:
// иначе приложение ставится от его имени, а обновлять его некому
func TestGuardUpdatesCoverModules(t *testing.T) {
	raw, err := os.ReadFile("../apps/guard/assets/updates.txt")
	if err != nil {
		t.Skip("нет исходников «Сторожа» рядом: " + err.Error())
	}
	listed := map[string]bool{}
	for _, l := range strings.Split(strings.ReplaceAll(string(raw), "\r\n", "\n"), "\n") {
		if f := strings.Fields(l); len(f) >= 3 && !strings.HasPrefix(f[0], "#") {
			listed[f[0]] = true
		}
	}
	n := 0
	for _, m := range recipe(t).Modules {
		if m.Updates != "guard" {
			continue
		}
		n++
		if m.ThirdParty == "" || !listed[m.ThirdParty] {
			t.Errorf("модуль %s обновляет «Сторож», а в apps/guard/assets/updates.txt нет %q", m.Module, m.ThirdParty)
		}
	}
	if n == 0 || !listed[byedpiPkg] {
		t.Fatalf("модулей с обновлениями «Сторожем» %d, ByeByeDPI в списке: %v", n, listed[byedpiPkg])
	}
}
