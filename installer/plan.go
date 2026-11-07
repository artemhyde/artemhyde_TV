package main

import (
	"fmt"
	"net/url"
	"path"
	"strings"
)

// План изменений (план Codex, этап 2, операция plan): что установщик сделает на этом ТВ и как это вернуть. Строится по
// рецепту, описи выпуска, паспорту ТВ и выбору человека; на ТВ ничего не меняет. Шаг плана повторяет строку журнала
// ledger.tsv (CLAUDE.md, шаг 4): что, команда, обратная команда

type Step struct {
	Group string `json:"group"`
	What  string `json:"what"`
	Do    string `json:"do,omitempty"`
	Undo  string `json:"undo,omitempty"` // "-": откатом не покрывается, делать руками
	Host  bool   `json:"host,omitempty"` // команда на компьютере (установка файла, скачивание), а не в оболочке ТВ
	Run   bool   `json:"run,omitempty"`  // действие без следа (запуск, ускорение), в журнал не идёт
	Note  string `json:"note,omitempty"`
	// Для установки: вид шага (install, download, install3p, unadmin, home_disable, home_role, пусто: команда ТВ),
	// файл и пакет, адрес и сертификат скачивания
	Kind    string `json:"kind,omitempty"`
	File    string `json:"file,omitempty"`
	Package string `json:"package,omitempty"`
	URL     string `json:"url,omitempty"`
	Cert    string `json:"cert,omitempty"`
	// Скачивание: сумма и размер файла со страницы релиза (если есть); установка стороннего: от чьего имени
	// (app.glasstv.guard: тогда «Сторож» сам обновляет его дальше)
	Sha256    string `json:"sha256,omitempty"`
	Size      int64  `json:"size,omitempty"`
	Installer string `json:"installer,omitempty"`
}

type ModuleChoice struct {
	Module    string `json:"module"`
	Title     string `json:"title"`
	What      string `json:"what"`
	Available bool   `json:"available"`
	Chosen    bool   `json:"chosen"`
	Required  bool   `json:"required"`
	Why       string `json:"why,omitempty"`
	State     string `json:"state,omitempty"`
	Note      string `json:"note,omitempty"`  // оговорка из рецепта (серая зона, эксперимент)
	Group     string `json:"group,omitempty"` // «apps»: приложения по желанию
	Third     bool   `json:"third,omitempty"` // чужое приложение: качается со страницы автора
}

// Choices: выбор человека. Модуля нет в списке: как в рецепте по умолчанию. Home nil: как в рецепте
type Choices struct {
	Modules map[string]bool `json:"modules"`
	Home    *bool           `json:"home"`
}

type Plan struct {
	Release   string         `json:"release"`
	Commit    string         `json:"commit"`
	Caps      Caps           `json:"caps"`
	Modules   []ModuleChoice `json:"modules"`
	Home      bool           `json:"home"`
	HomeTitle string         `json:"home_title"`
	HomeWhat  string         `json:"home_what"`
	Steps     []Step         `json:"steps"`
	Notes     []string       `json:"notes"`
	Problems  []string       `json:"problems"`
	InstallMB float64        `json:"install_mb"`
}

// shq: значение в одинарных кавычках для оболочки ТВ
func shq(s string) string { return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'" }

func undoSetting(ns, key, cur string) string {
	if cur == "" {
		return fmt.Sprintf("settings delete %s %s", ns, key)
	}
	return fmt.Sprintf("settings put %s %s %s", ns, key, shq(cur))
}

func mb(n int64) string {
	v := float64(n) / 1048576
	if v < 0.1 {
		return fmt.Sprintf("%d КБ", (n+1023)/1024)
	}
	if v < 10 {
		return strings.Replace(fmt.Sprintf("%.1f МБ", v), ".", ",", 1)
	}
	return fmt.Sprintf("%.0f МБ", v)
}

func anyIn(need, have []string) bool {
	for _, n := range need {
		if contains(have, n) {
			return true
		}
	}
	return false
}

func buildPlan(rec Recipe, m *Manifest, p Passport, ch Choices, checkFiles bool) Plan {
	c := capsOf(p)
	pl := Plan{Release: m.Release, Commit: short(m.BuiltFrom.Commit), Caps: c, HomeTitle: rec.HomeSwitch.Title, HomeWhat: rec.HomeSwitch.What}
	if c.Profile == profileNone {
		pl.Problems = append(pl.Problems, "Этот ТВ не подходит: "+c.Why)
		return pl
	}

	// Выбор модулей: доступность по паспорту и выпуску, затем выбор человека или как в рецепте
	chosen := map[string]bool{}
	for _, rm := range rec.Modules {
		mc := ModuleChoice{Module: rm.Module, Title: rm.Title, What: rm.What, Required: rm.Required, Available: true,
			Note: rm.Note, Group: rm.Group, Third: rm.ThirdParty != ""}
		app := m.App(rm.Module)
		switch {
		case rm.ThirdParty != "":
			tp := m.Third(rm.ThirdParty)
			switch {
			case tp == nil:
				mc.Available, mc.Why = false, "нет в списке сторонних приложений выпуска"
			case p.Has(rm.ThirdParty):
				mc.State = "уже стоит"
			case tp.FileFor(c.ABI) == nil:
				mc.Available, mc.Why = false, "у автора нет сборки под процессор этого ТВ ("+strings.Join(c.ABI, ", ")+")"
			}
		case app == nil:
			mc.Available, mc.Why = false, "нет в этом выпуске"
		case rm.Needs != "" && !c.Has(rm.Needs):
			mc.Available, mc.Why = false, rm.NeedsWhy
		case app.MinSdk > c.SDK:
			mc.Available, mc.Why = false, "нужен Android новее, чем на ТВ"
		case len(app.ABI()) > 0 && !anyIn(app.ABI(), c.ABI):
			mc.Available, mc.Why = false, "собран для "+strings.Join(app.ABI(), ", ")+", а ТВ умеет "+strings.Join(c.ABI, ", ")
		}
		if app != nil && mc.Available {
			if vc, ok := p.Packages[app.Package]; ok {
				mc.State = fmt.Sprintf("уже стоит (versionCode %d)", vc)
			}
		}
		want := rm.Default
		if v, ok := ch.Modules[rm.Module]; ok {
			want = v
		}
		if rm.Required {
			want = true
		}
		mc.Chosen = mc.Available && want
		chosen[rm.Module] = mc.Chosen
		pl.Modules = append(pl.Modules, mc)
	}
	// Часть другого модуля (плеер «Волны»): ставится вместе с ним; без плеера «Волна» не играет
	for i := range pl.Modules {
		rm := rec.Module(pl.Modules[i].Module)
		if rm.PartOf == "" {
			continue
		}
		pl.Modules[i].Chosen = pl.Modules[i].Available && chosen[rm.PartOf]
		chosen[rm.Module] = pl.Modules[i].Chosen
		if chosen[rm.PartOf] && !pl.Modules[i].Available {
			chosen[rm.PartOf] = false
			for j := range pl.Modules {
				if pl.Modules[j].Module == rm.PartOf {
					pl.Modules[j].Chosen = false
					pl.Modules[j].Why = "без своего плеера не играет, а плеер на этом ТВ не встаёт: " + pl.Modules[i].Why
				}
			}
		}
	}
	// Пакеты, которые поставит этот план (для плиток: их цель может появиться вместе с планом)
	willHave := map[string]bool{}
	for _, rm := range rec.Modules {
		if a := m.App(rm.Module); a != nil && chosen[rm.Module] {
			willHave[a.Package] = true
		}
		if rm.ThirdParty != "" && chosen[rm.Module] {
			willHave[rm.ThirdParty] = true // Lampa, SmartTube, Material Files: их плитки ставятся вместе с ними
		}
	}

	add := func(s Step) { pl.Steps = append(pl.Steps, s) }
	for _, rm := range rec.Modules {
		if !chosen[rm.Module] {
			continue
		}
		g := rm.Title
		if rm.ThirdParty != "" {
			tp := m.Third(rm.ThirdParty)
			if p.Has(tp.Package) {
				add(Step{Group: g, What: "уже стоит, не трогаем" + prefixed(": ", rm.Installed), Run: true})
			} else {
				f := tp.FileFor(c.ABI)
				size := ""
				if f.Size > 0 {
					size = ", " + mb(f.Size)
				}
				add(Step{Group: g, What: fmt.Sprintf("скачать %s %s со страницы автора (%s%s) и сверить подпись (SHA-256 сертификата %s…)", tp.Name, tp.PinnedVersion, hostOf(f.URL), size, prefix(tp.CertSha256, 12)),
					Do: f.URL, Host: true, Run: true, Kind: "download", URL: f.URL, Cert: tp.CertSha256, Package: tp.Package, Sha256: f.Sha256, Size: f.Size})
				st := Step{Group: g, What: "поставить " + tp.Name, Do: "adb install --user 0 " + path.Base(f.URL), Undo: "pm uninstall --user 0 " + tp.Package, Host: true,
					Kind: "install3p", File: path.Base(f.URL), Package: tp.Package, Cert: tp.CertSha256}
				if rm.Updates == "guard" && chosen["guard"] {
					// От имени «Сторожа»: тогда он сам ставит новые версии без нажатий (Android даёт тихое обновление только
					// тому, кто числится установщиком)
					st.Installer = "app.glasstv.guard"
					st.Do = "adb install --user 0 -i app.glasstv.guard " + path.Base(f.URL)
					st.Note = "новые версии потом ставит «Сторож» сам"
				}
				if rm.Keep != "" {
					st.Undo, st.Note = "-", rm.Keep
				}
				add(st)
			}
		} else {
			addInstall(&pl, add, g, m, m.App(rm.Module), p, rm.Admin, checkFiles, guardBy(chosen))
		}
		for _, st := range rm.Steps {
			if !c.Has(st.When) {
				continue
			}
			switch st.Kind {
			case "a11y":
				a11ySteps(add, g, p, st)
			case "ime":
				cur := p.Setting("secure:default_input_method")
				if cur == st.IME {
					add(Step{Group: g, What: st.What + ": уже стоит", Run: true})
					break
				}
				undo := "ime set " + cur
				if cur == "" {
					undo = "ime reset"
				}
				add(Step{Group: g, What: st.What + " (прежняя: " + nonEmpty(cur, "по умолчанию") + ")", Do: "ime enable " + st.IME + "; ime set " + st.IME, Undo: undo})
			default:
				add(Step{Group: g, What: st.What, Do: st.Do, Undo: st.Undo, Run: st.Run})
			}
		}
		if rm.Module == "guard" && !c.TCL && rec.NightOff.Do != "" {
			add(Step{Group: g, What: rec.NightOff.What, Do: rec.NightOff.Do, Undo: rec.NightOff.Undo})
		}
		if a := m.App(rm.Module); a != nil && rm.ThirdParty == "" {
			add(Step{Group: g, What: "ускорить запуск (заранее скомпилировать)", Do: "pm compile -m speed -f " + a.Package, Run: true})
		}
	}

	// Плитки: только к приложениям, которые на ТВ есть или появятся с этим планом (сама цель или её вариант)
	var skipped []string
	for i := range m.Apps {
		a := &m.Apps[i]
		if !strings.HasPrefix(a.Module, "tile-") || len(a.Depends) == 0 {
			continue
		}
		target, by := a.Depends[0], ""
		for _, cand := range append([]string{target}, rec.TileAlts[target]...) {
			if (p.Has(cand) && !p.Disabled[cand]) || willHave[cand] {
				by = cand
				break
			}
		}
		name := strings.TrimPrefix(a.Module, "tile-")
		if by == "" {
			skipped = append(skipped, name+" (нет "+target+")")
			continue
		}
		g := "Плитки на главном экране"
		addInstall(&pl, add, g, m, a, p, false, checkFiles, guardBy(chosen))
		if by != target {
			pl.Steps[len(pl.Steps)-1].Note = "откроет " + by + " вместо " + target
		}
	}
	if len(skipped) > 0 {
		pl.Notes = append(pl.Notes, "Плитки без приложения на ТВ не ставятся, чтобы на главном экране не было пустых: "+strings.Join(skipped, ", ")+".")
	}

	// Главный экран: «Дом» вместо прежнего (по выбору человека, CLAUDE.md, шаг 9)
	// «Дом» уже главный экран (ставили раньше): по умолчанию так и оставляем, тогда в план попадёт и починка «Домой»
	// после обновления прошивки TCL
	home := rec.HomeSwitch.Default || c.HomeHolder == "app.glasstv.home"
	if ch.Home != nil {
		home = *ch.Home
	}
	pl.Home = home && chosen["home"]
	if pl.Home {
		g := rec.HomeSwitch.Title
		if c.HomeHolder == "app.glasstv.home" {
			add(Step{Group: g, What: "«Дом» уже главный экран", Run: true})
			// TCL после обновления прошивки включает главный экран Google TV и его страховку обратно, роль остаётся у
			// «Дома», а «Домой» уходит к ним: отключить снова (CLAUDE.md, режим «пройди чистку заново»)
			if c.TCL {
				for _, d := range rec.HomeSwitch.TCLDisable {
					if p.Has(d.Package) && !p.Disabled[d.Package] {
						add(Step{Group: g, What: "отключить снова " + d.Why + ": прошивка включила его обратно, «Домой» уходит к нему",
							Do: "pm disable-user --user 0 " + d.Package, Undo: "pm enable --user 0 " + d.Package})
					}
				}
			}
		} else {
			// Главный экран Google TV (launcherx) и его страховка забирают кнопку «Домой». На TCL роль даже не меняется,
			// пока они включены: отключаются до смены. На Google TV других марок роль меняется, но «Домой» всё равно
			// открывает launcherx (07.11, виртуальный Google TV): отключаются после смены, только если «Домой» перехвачен
			var gtv []Step
			for _, d := range rec.HomeSwitch.TCLDisable {
				if p.Has(d.Package) && !p.Disabled[d.Package] {
					gtv = append(gtv, Step{Group: g, What: "отключить " + d.Why, Do: "pm disable-user --user 0 " + d.Package, Undo: "pm enable --user 0 " + d.Package,
						Kind: "home_disable", Package: d.Package})
				}
			}
			if c.TCL {
				for _, s := range gtv {
					s.What += ": на TCL без этого главный экран не сменится"
					add(s)
				}
			}
			undo := "-"
			if c.HomeHolder != "" {
				undo = "cmd role add-role-holder --user 0 android.app.role.HOME " + c.HomeHolder
			}
			add(Step{Group: g, What: "кнопка «Домой» открывает «Дом» (прежний главный экран: " + nonEmpty(c.HomeHolder, "не определён") + ")",
				Do: "cmd package set-home-activity app.glasstv.home/.HomeActivity", Undo: undo, Kind: "home_role", Package: "app.glasstv.home",
				Note: "установщик откроет «Дом», нажмёт «Домой» и проверит, что открылся он; не вышло: всё вернётся как было"})
			if !c.TCL {
				for _, s := range gtv {
					s.Kind, s.What = "home_disable_if", s.What+": только если «Домой» всё равно откроет Google TV"
					add(s)
				}
				// Обычный Android TV: роль меняется, а «Домой» открывает его главный экран (07.11, виртуальный Android TV 13)
				for _, d := range rec.HomeSwitch.Intercept {
					if p.Has(d.Package) && !p.Disabled[d.Package] {
						// Отключается, только если «Домой» перехватил именно он: на Google TV 14 этот экран тоже есть, но
						// «Домой» там уходит к launcherx, и трогать его незачем (07.11, круг из архива на hyde_tv)
						add(Step{Group: g, What: "отключить " + d.Why + ": только если «Домой» всё равно откроет его",
							Do: "pm disable-user --user 0 " + d.Package, Undo: "pm enable --user 0 " + d.Package,
							Kind: "home_disable_top", Package: d.Package})
					}
				}
			}
		}
		for _, s := range rec.HomeSwitch.Settings {
			cur := p.Setting(s.NS + ":" + s.Key)
			if cur == s.Value {
				continue
			}
			add(Step{Group: g, What: s.What, Do: fmt.Sprintf("settings put %s %s %s", s.NS, s.Key, s.Value), Undo: undoSetting(s.NS, s.Key, cur)})
		}
	}

	// Выданные права сразу на диск: Android пишет appops с задержкой до 30 минут, и при резком выключении ТВ после установки
	// пропадали согласие на VPN и окно поверх других у «Сторожа» (07.11, находка сессии ByeByeDPI на виртуальном Google TV)
	for _, s := range pl.Steps {
		if strings.HasPrefix(s.Do, "appops set") {
			add(Step{Group: "Завершение", What: "записать выданные права на диск сразу, а не через полчаса", Do: "appops write-settings", Run: true})
			break
		}
	}

	// Заметки и помехи
	switch c.Level {
	case levelVerified:
		pl.Notes = append(pl.Notes, "TCL "+verifiedSeries+" с проверенной прошивкой: всё, что есть в HYDE, проверено на таком ТВ.")
	case levelExperimental:
		pl.Notes = append(pl.Notes, "TCL другой серии или прошивки: функции TCL (подсветка, «Картинка», Dolby) работают экспериментально.")
	default:
		pl.Notes = append(pl.Notes, "ТВ не TCL: подсветки TCL, «Картинки» и панели TCL нет, «Ночной режим» затемняет экран вуалью, ночная подсветка «Сторожа» выключена. Кнопки пульта назначаются записью настоящей кнопкой.")
	}
	if c.ABI32 {
		pl.Notes = append(pl.Notes, "ТВ 32-битный: чужие приложения без 32-битной сборки на нём не встанут.")
	}
	if !pl.Home && chosen["home"] {
		pl.Notes = append(pl.Notes, "Главный экран остаётся прежним: «Дом» открывается из списка приложений. Сделать его главным можно потом.")
	}
	pl.Notes = append(pl.Notes, "Это только план: на ТВ ничего не менялось. Каждое изменение при установке пишется в журнал с обратной командой, откат собирается из него.")
	need := int64(pl.InstallMB*2) + 200
	if c.DataFreeMB > 0 && c.DataFreeMB < need {
		pl.Problems = append(pl.Problems, fmt.Sprintf("На ТВ мало места: свободно %d МБ, нужно около %d МБ.", c.DataFreeMB, need))
	}
	return pl
}

// heldSteps: какие команды плана на ТВ уже действуют (права Key Mapper, поставленного раньше, всё при обновлении).
// cache: уже проверенные команды этого ТВ (окно мастера перестраивает план на каждый переключатель)
func heldSteps(tv TV, pl *Plan, cache map[string]bool) map[string]bool {
	if cache == nil {
		cache = map[string]bool{}
	}
	for _, s := range pl.Steps {
		if s.Kind != "" || s.Run || s.Do == "" {
			continue
		}
		if _, ok := cache[s.Do]; ok {
			continue
		}
		ok, known, _ := (&tvLook{tv: tv}).holds(s.Do)
		cache[s.Do] = known && ok
	}
	return cache
}

// markHeld: только для показа плана: что уже так, установка не выполнит и в журнал не запишет, поэтому и откат это
// не тронет. Сама установка проверяет то же своим путём (runApply), этот вид плана ей не передаётся
func markHeld(pl *Plan, held map[string]bool) {
	for i, s := range pl.Steps {
		if s.Kind == "" && !s.Run && held[s.Do] {
			pl.Steps[i].What += ": уже так, не меняем"
			pl.Steps[i].Run, pl.Steps[i].Do, pl.Steps[i].Undo = true, "", ""
		}
	}
}

// addInstall: установка приложения из выпуска с правильной обратной командой (как inst в tools/tv.sh): новое
// удаляется откатом, обновление нет (прежняя версия поверх не ставится); приложение с правами администратора перед
// обновлением снимает их само, иначе система зависает
// guardBy: от чьего имени ставить приложения HYDE: «Сторожа», если он в плане (обязателен, но план без него бывает в проверках)
func guardBy(chosen map[string]bool) string {
	if chosen["guard"] {
		return "app.glasstv.guard"
	}
	return ""
}

func addInstall(pl *Plan, add func(Step), g string, m *Manifest, a *ManifestApp, p Passport, admin, checkFiles bool, by string) {
	if checkFiles {
		if bad := m.CheckFile(a); bad != "" {
			pl.Problems = append(pl.Problems, "Файл выпуска: "+bad)
		}
	}
	vc, installed := p.Packages[a.Package]
	// От имени «Сторожа» (by): тогда новые версии HYDE он ставит сам, без компьютера и без нажатий (1.0.1). Android
	// записывает установщиком только уже стоящее приложение: сам «Сторож» при первой установке получил бы installer=null,
	// поэтому он ставится второй раз тем же файлом (проверено 07.11 на виртуальном Google TV 14)
	viaGuard := func(st Step) Step {
		if by != "" && (a.Package != by || installed) {
			st.Installer = by
			st.Do = strings.Replace(st.Do, "adb install --user 0 -r ", "adb install --user 0 -r -i "+by+" ", 1)
		}
		return st
	}
	switch {
	case !installed:
		pl.InstallMB += float64(a.Size) / 1048576
		add(viaGuard(Step{Group: g, What: fmt.Sprintf("поставить %s (%s)", a.File, mb(a.Size)), Do: "adb install --user 0 -r " + a.File, Undo: "pm uninstall --user 0 " + a.Package, Host: true,
			Kind: "install", File: a.File, Package: a.Package}))
		if by != "" && a.Package == by {
			add(Step{Group: g, What: "поставить тот же файл ещё раз от имени самого «Сторожа»: тогда свои новые версии он ставит сам",
				Do: "adb install --user 0 -r -i " + by + " " + a.File, Undo: "-", Host: true, Kind: "install", File: a.File, Package: a.Package, Installer: by})
		}
	case vc > a.VersionCode:
		add(Step{Group: g, What: fmt.Sprintf("на ТВ версия новее (versionCode %d), старую Android поставить не даст: оставляем как есть", vc), Run: true, Package: a.Package})
	case vc == a.VersionCode:
		add(Step{Group: g, What: "уже стоит версия " + a.VersionName + ", ставить заново не нужно", Run: true, Package: a.Package})
	default:
		pl.InstallMB += float64(a.Size) / 1048576
		if admin && p.AdminActive(a.Package) {
			add(Step{Group: g, What: "снять права администратора перед обновлением (иначе система зависнет на минуту и перезагрузится)",
				Do: "am start -n " + a.Package + "/.MainActivity --ez unadmin true; sleep 5", Run: true, Kind: "unadmin", Package: a.Package})
		}
		add(viaGuard(Step{Group: g, What: fmt.Sprintf("обновить до %s (стоит versionCode %d)", a.VersionName, vc), Do: "adb install --user 0 -r " + a.File, Undo: "-", Host: true,
			Kind: "install", File: a.File, Package: a.Package,
			Note: "откат обновление не снимает: прежняя версия поверх не ставится (docs/rollback.md). Если стоящая сборка подписана своим ключом, Android поверх не поставит: переход по docs/release.md, «Переход с личной сборки»"}))
	}
}

func a11ySteps(add func(Step), g string, p Passport, st RecipeStep) {
	cur := p.Setting("secure:enabled_accessibility_services")
	on := false
	for _, s := range strings.Split(cur, ":") {
		if s == st.Service {
			on = true
		}
	}
	if on {
		add(Step{Group: g, What: st.What + ": уже включена", Run: true})
	} else {
		nw := st.Service
		if cur != "" {
			nw = cur + ":" + st.Service
		}
		add(Step{Group: g, What: st.What, Do: "settings put secure enabled_accessibility_services " + shq(nw), Undo: undoSetting("secure", "enabled_accessibility_services", cur)})
	}
	if ae := p.Setting("secure:accessibility_enabled"); ae != "1" {
		add(Step{Group: g, What: "спецвозможности включены", Do: "settings put secure accessibility_enabled 1", Undo: undoSetting("secure", "accessibility_enabled", ae)})
	}
}

func short(s string) string { return prefix(s, 7) }

func prefix(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

// prefixed: «p+s», если s непустая
func prefixed(p, s string) string {
	if s == "" {
		return ""
	}
	return p + s
}

// hostOf: имя сайта из адреса (github.com) для слов «откуда качаем»
func hostOf(raw string) string {
	if u, err := url.Parse(raw); err == nil && u.Host != "" {
		return u.Hostname()
	}
	return raw
}

func nonEmpty(s, def string) string {
	if strings.TrimSpace(s) == "" {
		return def
	}
	return s
}

// Text: план словами для терминала (Claude и человек в окне команд)
func (pl Plan) Text() string {
	var b strings.Builder
	c := pl.Caps
	fmt.Fprintf(&b, "ТВ: %s, Android %s, %s, уровень: %s\n", nonEmpty(c.Model, "?"), nonEmpty(c.Android, "?"), c.Profile, c.Level)
	if len(pl.Problems) > 0 {
		b.WriteString("\nМЕШАЕТ УСТАНОВКЕ:\n")
		for _, x := range pl.Problems {
			b.WriteString("  " + x + "\n")
		}
	}
	if c.Profile == profileNone {
		return b.String()
	}
	fmt.Fprintf(&b, "\nВыпуск HYDE TV %s (коммит %s). Что ставим:\n", pl.Release, pl.Commit)
	for _, mc := range pl.Modules {
		mark := "да "
		if !mc.Chosen {
			mark = "нет"
		}
		line := fmt.Sprintf("  [%s] %s: %s", mark, mc.Title, mc.What)
		if !mc.Available {
			line += " (недоступно: " + mc.Why + ")"
		} else if mc.Why != "" {
			line += " (" + mc.Why + ")"
		} else if mc.State != "" {
			line += " (" + mc.State + ")"
		}
		b.WriteString(line + "\n")
	}
	home := "нет, главный экран прежний"
	if pl.Home {
		home = "да"
	}
	fmt.Fprintf(&b, "  %s: %s\n", pl.HomeTitle, home)
	b.WriteString("\nШаги по порядку (обратная команда: так это вернёт откат):\n")
	group, n := "", 0
	for _, s := range pl.Steps {
		if s.Group != group {
			group = s.Group
			b.WriteString("\n" + group + "\n")
		}
		n++
		fmt.Fprintf(&b, "  %2d. %s\n", n, s.What)
		if s.Do != "" {
			where := "ТВ"
			if s.Host {
				where = "компьютер"
			}
			fmt.Fprintf(&b, "      %s: %s\n", where, s.Do)
		}
		switch {
		case s.Run:
		case s.Undo == "-":
			b.WriteString("      откат: не покрывается\n")
		case s.Undo != "":
			fmt.Fprintf(&b, "      откат: %s\n", s.Undo)
		}
		if s.Note != "" {
			fmt.Fprintf(&b, "      %s\n", s.Note)
		}
	}
	if len(pl.Notes) > 0 {
		b.WriteString("\n")
		for _, x := range pl.Notes {
			b.WriteString("* " + x + "\n")
		}
	}
	return b.String()
}
