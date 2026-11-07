package main

// Проверки установщика без ТВ: паспорт из обезличенных ответов настоящих ТВ (testdata: TCL 65C6K и виртуальный Google TV),
// правила, план по маленькому выпуску во временной папке, разбор ответов adb. Запуск: go test ./... в папке installer

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

func fixtureRaw(t *testing.T, name string) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func fixture(t *testing.T, name string) Passport {
	t.Helper()
	p := parsePassport(fixtureRaw(t, name))
	if !p.Complete {
		t.Fatalf("%s: нет конца ответа ##end", name)
	}
	return p
}

// edit: правка ответа ТВ до разбора (убрать или заменить строки)
func edit(raw string, pairs ...string) string {
	for i := 0; i+1 < len(pairs); i += 2 {
		raw = strings.ReplaceAll(raw, pairs[i], pairs[i+1])
	}
	return raw
}

type tApp struct {
	module, pkg string
	depends     []string
}

var testApps = []tApp{
	{"guard", "app.glasstv.guard", nil}, {"home", "app.glasstv.home", nil}, {"nightmode", "app.glasstv.nightmode", nil},
	{"sound", "app.glasstv.sound", nil}, {"picture", "app.glasstv.picture", nil}, {"clip", "app.glasstv.clip", nil},
	{"keyboard", "app.glasstv.keyboard", nil}, {"aura", "app.glasstv.aura", nil}, {"volna", "app.glasstv.volna", nil},
	{"browser", "app.glasstv.browser", nil},
	{"tile-browser", "app.glasstv.tile.browser", []string{"app.glasstv.browser"}},
	{"tile-files", "app.glasstv.tile.files", []string{"me.zhanghai.android.files"}},
	{"tile-lampa", "app.glasstv.tile.lampa", []string{"top.rootu.lampa"}},
	{"tile-settings", "app.glasstv.tile.settings", []string{"com.android.tv.settings"}},
	{"tile-youtube", "app.glasstv.tile.youtube", []string{"org.smarttube.stable"}},
}

// testManifest: маленький выпуск во временной папке, файлы с настоящими суммами
func testManifest(t *testing.T) *Manifest {
	t.Helper()
	dir := t.TempDir()
	var apps []any
	for _, a := range testApps {
		file := "hyde-" + a.module + "-1.0.0.apk"
		data := []byte("apk " + a.module)
		if err := os.WriteFile(filepath.Join(dir, file), data, 0o644); err != nil {
			t.Fatal(err)
		}
		sum := sha256.Sum256(data)
		var abi any = "any"
		if a.module == "aura" {
			abi = []string{"armeabi-v7a"}
		}
		apps = append(apps, map[string]any{"module": a.module, "title": a.module, "file": file, "package": a.pkg, "versionCode": 10000,
			"versionName": "1.0.0", "minSdk": 31, "abi": abi, "sha256": hex.EncodeToString(sum[:]), "size": len(data), "depends": a.depends})
	}
	m := map[string]any{"product": "HYDE TV", "release": "1.0.0", "versionCode": 10000, "built_from": map[string]any{"commit": "0123456789abcdef"},
		"apps": apps, "third_party": []any{map[string]any{"name": "Key Mapper", "package": "io.github.sds100.keymapper", "pinned_version": "4.6.2-foss",
			"download":    "https://github.com/keymapperorg/KeyMapper/releases/download/v4.6.2/keymapper-4.6.2-foss.apk",
			"cert_sha256": "2f41a816b8d60aa1d7cd4976b4452b0335e583d4f4ab14071f303177850ce460"},
			map[string]any{"name": "Lampa", "package": "top.rootu.lampa", "pinned_version": "1.13.5",
				"files": []any{map[string]any{"abi": "any", "url": "https://github.com/lampa-app/LAMPA/releases/download/v1.13.5/app-lite-release.apk",
					"size": 11741704, "sha256": ""}},
				"cert_sha256": "8adaaa27bf99293ec260fe372e77d142ac9166fe339f348f1148a3bf987d9872"},
			map[string]any{"name": "LocalSend", "package": "org.localsend.localsend_app", "pinned_version": "1.18.2",
				"files": []any{map[string]any{"abi": "arm64-v8a", "url": "https://github.com/localsend/localsend/releases/download/v1.18.2/LocalSend-1.18.2-android-arm64v8.apk"}},
				"cert_sha256": "3220c353a73cfbd0c2f3052471c445324cf452bcba26de1c473a52fe5c44e1d6"}}}
	b, _ := json.Marshal(m)
	if err := os.WriteFile(filepath.Join(dir, "manifest.json"), b, 0o644); err != nil {
		t.Fatal(err)
	}
	mf, err := loadManifest(dir)
	if err != nil {
		t.Fatal(err)
	}
	return mf
}

func recipe(t *testing.T) Recipe {
	t.Helper()
	r, err := loadRecipe()
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func yes() *bool { v := true; return &v }

func findStep(pl Plan, sub string) (int, *Step) {
	for i := range pl.Steps {
		if strings.Contains(pl.Steps[i].Do, sub) || strings.Contains(pl.Steps[i].What, sub) {
			return i, &pl.Steps[i]
		}
	}
	return -1, nil
}

func module(pl Plan, name string) ModuleChoice {
	for _, m := range pl.Modules {
		if m.Module == name {
			return m
		}
	}
	return ModuleChoice{}
}

func TestCapsTCL(t *testing.T) {
	c := capsOf(fixture(t, "tcl-c6k.txt"))
	if c.Profile != profileTCL || c.Level != levelVerified || !c.TCL {
		t.Fatalf("TCL C6K: профиль %s, уровень %s", c.Profile, c.Level)
	}
	if !c.DisplayLib || !c.DisplayService || !c.Dolby || !c.QuickPanel || !c.TKL || !c.ABI32 || !c.KeyMapper {
		t.Fatalf("TCL C6K: не все отметки: %+v", c)
	}
	if c.SDK != 34 || c.HomeHolder != "org.example.home" || c.DataFreeMB == 0 {
		t.Fatalf("TCL C6K: sdk %d, главный %s, место %d", c.SDK, c.HomeHolder, c.DataFreeMB)
	}
}

func TestCapsGoogleTV(t *testing.T) {
	c := capsOf(fixture(t, "google-tv.txt"))
	if c.Profile != profileGeneric || c.Level != levelOther || c.TCL {
		t.Fatalf("Google TV: профиль %s, уровень %s", c.Profile, c.Level)
	}
	if c.DisplayLib || c.Dolby || c.QuickPanel || c.TKL || !c.Leanback || !c.LauncherX {
		t.Fatalf("Google TV: отметки %+v", c)
	}
	if c.HomeHolder != "com.google.android.apps.tv.launcherx" {
		t.Fatalf("Google TV: главный экран %s", c.HomeHolder)
	}
}

func TestCapsUnsupported(t *testing.T) {
	raw := fixtureRaw(t, "google-tv.txt")
	c := capsOf(parsePassport(edit(raw, "ro.build.version.sdk=34", "ro.build.version.sdk=30", "ro.build.version.release=14", "ro.build.version.release=11")))
	if c.Profile != profileNone || !strings.Contains(c.Why, "Android 12") {
		t.Fatalf("Android 11: %s (%s)", c.Profile, c.Why)
	}
	var keep []string
	for _, ln := range strings.Split(raw, "\n") {
		if !strings.Contains(ln, "leanback") {
			keep = append(keep, ln)
		}
	}
	c = capsOf(parsePassport(strings.Join(keep, "\n")))
	if c.Profile != profileNone || !strings.Contains(c.Why, "не Android TV") {
		t.Fatalf("без leanback: %s (%s)", c.Profile, c.Why)
	}
}

func TestTCLOtherFirmwareExperimental(t *testing.T) {
	c := capsOf(parsePassport(edit(fixtureRaw(t, "tcl-c6k.txt"), "AU02:user", "AU09:user")))
	if c.Level != levelExperimental || c.Profile != profileTCL {
		t.Fatalf("другая прошивка: %s %s", c.Level, c.Profile)
	}
}

// Правила уровня TCL одни с «Сторожем»: серия и прошивки из CapsRules.java
func TestCapsRulesMatchGuard(t *testing.T) {
	b, err := os.ReadFile(filepath.Join("..", "apps", "guard", "src", "app", "glasstv", "guard", "CapsRules.java"))
	if err != nil {
		t.Skip("нет исходников «Сторожа» рядом: " + err.Error())
	}
	src := string(b)
	m := regexp.MustCompile(`VERIFIED_SERIES = "([^"]+)"`).FindStringSubmatch(src)
	if m == nil || m[1] != verifiedSeries {
		t.Fatalf("серия у «Сторожа» %v, у установщика %s", m, verifiedSeries)
	}
	fw := regexp.MustCompile(`VERIFIED_FW = Arrays\.asList\(([^;]*)\);`).FindStringSubmatch(src)
	if fw == nil {
		t.Fatal("не нашёл VERIFIED_FW в CapsRules.java")
	}
	got := regexp.MustCompile(`"([^"]+)"`).FindAllStringSubmatch(fw[1], -1)
	if len(got) != len(verifiedFW) {
		t.Fatalf("прошивок у «Сторожа» %d, у установщика %d", len(got), len(verifiedFW))
	}
	for i, g := range got {
		if g[1] != verifiedFW[i] {
			t.Fatalf("прошивка %d: %s и %s", i, g[1], verifiedFW[i])
		}
	}
}

func TestPlanGoogleTV(t *testing.T) {
	pl := buildPlan(recipe(t), testManifest(t), fixture(t, "google-tv.txt"), Choices{Home: yes()}, true)
	if len(pl.Problems) > 0 {
		t.Fatalf("помехи: %v", pl.Problems)
	}
	if m := module(pl, "sound"); m.Available || m.Chosen {
		t.Fatal("«Звук» без Dolby не должен быть доступен")
	}
	if m := module(pl, "picture"); m.Available || m.Chosen {
		t.Fatal("«Картинка» без подсветки TCL не должна быть доступна")
	}
	if m := module(pl, "volna"); m.Chosen {
		t.Fatal("«Волна» по умолчанию не ставится")
	}
	if _, s := findStep(pl, "AUTO_START"); s != nil {
		t.Fatal("автозапуск TCL на чужом ТВ")
	}
	if _, s := findStep(pl, "nighthours --ez enabled false"); s == nil {
		t.Fatal("на чужом ТВ ночная подсветка должна выключаться")
	}
	if _, s := findStep(pl, "keymapper-4.6.2-foss.apk"); s == nil || !s.Host {
		t.Fatal("нет скачивания Key Mapper")
	}
	if _, s := findStep(pl, "enabled_accessibility_services"); s == nil || s.Undo == "" {
		t.Fatal("нет включения службы Key Mapper с обратной командой")
	}
	for _, tile := range []string{"hyde-tile-settings", "hyde-tile-youtube"} {
		if _, s := findStep(pl, tile); s == nil {
			t.Fatalf("нет плитки %s", tile)
		}
	}
	for _, tile := range []string{"hyde-tile-browser", "hyde-tile-files", "hyde-tile-lampa"} {
		if _, s := findStep(pl, tile); s != nil {
			t.Fatalf("плитка %s без приложения", tile)
		}
	}
	// На Google TV другой марки главный экран Google TV отключается только если «Домой» перехвачен, и после смены роли
	idis, dis := findStep(pl, "pm disable-user --user 0 com.google.android.apps.tv.launcherx")
	irole, _ := findStep(pl, "set-home-activity app.glasstv.home")
	if dis == nil || dis.Kind != "home_disable_if" || idis < irole {
		t.Fatalf("отключение главного экрана Google TV: %+v (%d, роль %d)", dis, idis, irole)
	}
	_, role := findStep(pl, "set-home-activity app.glasstv.home")
	if role == nil || role.Undo != "cmd role add-role-holder --user 0 android.app.role.HOME com.google.android.apps.tv.launcherx" {
		t.Fatalf("смена главного экрана: %+v", role)
	}
	for _, s := range pl.Steps {
		if !s.Run && s.Undo == "" {
			t.Fatalf("шаг без обратной команды: %+v", s)
		}
	}
}

func TestPlanTCLHomeSwitch(t *testing.T) {
	raw := fixtureRaw(t, "tcl-c6k.txt")
	// Главный экран Google TV включён и он же владелец роли: как на ТВ из магазина
	raw = edit(raw, "##disabled\n", "##disabled\nx\n", "org.example.home\n##display_service", "com.google.android.apps.tv.launcherx\n##display_service")
	var keep []string
	sec := ""
	for _, ln := range strings.Split(raw, "\n") {
		if strings.HasPrefix(ln, "##") {
			sec = ln
		}
		if sec == "##disabled" && (strings.Contains(ln, "launcherx") || strings.Contains(ln, "setupwraith")) {
			continue
		}
		keep = append(keep, ln)
	}
	p := parsePassport(strings.Join(keep, "\n"))
	pl := buildPlan(recipe(t), testManifest(t), p, Choices{Home: yes()}, true)
	i1, d1 := findStep(pl, "pm disable-user --user 0 com.google.android.apps.tv.launcherx")
	i2, d2 := findStep(pl, "pm disable-user --user 0 com.google.android.tungsten.setupwraith")
	ir, role := findStep(pl, "set-home-activity app.glasstv.home")
	if d1 == nil || d2 == nil || role == nil || i1 > ir || i2 > ir {
		t.Fatalf("на TCL сначала отключить launcherx и setupwraith, потом сменить роль: %d %d %d", i1, i2, ir)
	}
	if d1.Undo != "pm enable --user 0 com.google.android.apps.tv.launcherx" {
		t.Fatalf("обратная команда: %s", d1.Undo)
	}
	if _, s := findStep(pl, "appops set app.glasstv.guard AUTO_START allow"); s == nil {
		t.Fatal("на TCL нужен автозапуск «Сторожа»")
	}
	if _, s := findStep(pl, "nighthours"); s != nil {
		t.Fatal("на TCL ночная подсветка остаётся")
	}
	if !module(pl, "sound").Chosen || !module(pl, "picture").Chosen {
		t.Fatal("на TCL с Dolby и подсветкой «Звук» и «Картинка» ставятся")
	}
	if _, s := findStep(pl, "keymapper-4.6.2-foss.apk"); s != nil {
		t.Fatal("Key Mapper уже стоит: скачивать не нужно")
	}
}

func TestPlanUpdateWithAdmin(t *testing.T) {
	raw := edit(fixtureRaw(t, "google-tv.txt"),
		"##packages\n", "##packages\npackage:app.glasstv.nightmode versionCode:9000\n",
		"##admins\n", "##admins\n  Enabled Device Admins (User 0, provisioningState: 3):\n    app.glasstv.nightmode/.AdminReceiver:\n")
	pl := buildPlan(recipe(t), testManifest(t), parsePassport(raw), Choices{}, true)
	iu, unadmin := findStep(pl, "снять права администратора")
	iup, upd := findStep(pl, "обновить до 1.0.0")
	if unadmin == nil || upd == nil || iu > iup {
		t.Fatalf("перед обновлением «Ночного режима» снять права: %d %d", iu, iup)
	}
	if upd.Undo != "-" {
		t.Fatalf("обновление откатом не снимается, а тут %q", upd.Undo)
	}
}

func TestPlanNewerOnTV(t *testing.T) {
	raw := edit(fixtureRaw(t, "google-tv.txt"), "##packages\n", "##packages\npackage:app.glasstv.home versionCode:20000\n")
	pl := buildPlan(recipe(t), testManifest(t), parsePassport(raw), Choices{}, true)
	if _, s := findStep(pl, "hyde-home-1.0.0.apk"); s != nil {
		t.Fatal("старую версию поверх новой ставить нельзя")
	}
	if _, s := findStep(pl, "версия новее"); s == nil {
		t.Fatal("нет пометки про более новую версию")
	}
}

func TestPlanVolnaNeedsPlayer(t *testing.T) {
	raw := edit(fixtureRaw(t, "google-tv.txt"), "ro.product.cpu.abilist=x86,armeabi-v7a,armeabi", "ro.product.cpu.abilist=x86_64")
	pl := buildPlan(recipe(t), testManifest(t), parsePassport(raw), Choices{Modules: map[string]bool{"volna": true}}, true)
	if m := module(pl, "volna"); m.Chosen || !strings.Contains(m.Why, "плеер") {
		t.Fatalf("«Волна» без плеера: %+v", m)
	}
	pl = buildPlan(recipe(t), testManifest(t), fixture(t, "google-tv.txt"), Choices{Modules: map[string]bool{"volna": true}}, true)
	if !module(pl, "volna").Chosen || !module(pl, "aura").Chosen {
		t.Fatal("«Волна» с плеером ставится вместе")
	}
	ia, _ := findStep(pl, "hyde-aura-1.0.0.apk")
	iv, _ := findStep(pl, "hyde-volna-1.0.0.apk")
	if ia < 0 || iv < 0 || ia > iv {
		t.Fatalf("плеер ставится до «Волны»: %d %d", ia, iv)
	}
}

func TestPlanFileProblems(t *testing.T) {
	m := testManifest(t)
	if err := os.WriteFile(filepath.Join(m.Dir, "hyde-guard-1.0.0.apk"), []byte("apk GUARD"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(m.Dir, "hyde-home-1.0.0.apk")); err != nil {
		t.Fatal(err)
	}
	pl := buildPlan(recipe(t), m, fixture(t, "google-tv.txt"), Choices{}, true)
	all := strings.Join(pl.Problems, "\n")
	if !strings.Contains(all, "hyde-guard-1.0.0.apk: сумма не совпадает") || !strings.Contains(all, "нет файла hyde-home-1.0.0.apk") {
		t.Fatalf("помехи с файлами: %s", all)
	}
}

func TestPlanUnsupportedStops(t *testing.T) {
	raw := edit(fixtureRaw(t, "google-tv.txt"), "ro.build.version.sdk=34", "ro.build.version.sdk=29")
	pl := buildPlan(recipe(t), testManifest(t), parsePassport(raw), Choices{}, true)
	if len(pl.Steps) != 0 || len(pl.Problems) == 0 {
		t.Fatalf("неподходящий ТВ: шагов %d, помех %d", len(pl.Steps), len(pl.Problems))
	}
}

func TestPassportIncomplete(t *testing.T) {
	raw := fixtureRaw(t, "google-tv.txt")
	if p := parsePassport(strings.Replace(raw, "##end", "", 1)); p.Complete {
		t.Fatal("ответ без ##end считается полным")
	}
}

// В рецепте у каждого изменения есть обратная команда; действие без следа помечено run
func TestRecipeEveryChangeHasUndo(t *testing.T) {
	r := recipe(t)
	for _, m := range r.Modules {
		for _, s := range m.Steps {
			if s.Kind == "" && !s.Run && s.Undo == "" {
				t.Fatalf("%s: шаг без обратной команды: %s", m.Module, s.What)
			}
		}
	}
	for _, s := range r.HomeSwitch.Settings {
		if s.NS == "" || s.Key == "" {
			t.Fatalf("настройка главного экрана без имени: %+v", s)
		}
	}
}

func TestParseMdnsAndDevices(t *testing.T) {
	ms := parseMdns("List of discovered mdns services\nadb-ABC-x\t_adb-tls-connect._tcp\t192.168.1.20:37123\nadb-ABC\t_adb._tcp\t192.168.1.20:5555\nmusor\n")
	if len(ms) != 2 || ms[0].Type != "_adb-tls-connect._tcp" || ms[0].Addr != "192.168.1.20:37123" {
		t.Fatalf("mdns: %+v", ms)
	}
	ds := parseDevices("List of devices attached\n192.168.1.20:37123\tdevice product:x model:Smart_TV_Pro device:y transport_id:2\nemulator-5554\tunauthorized\n\n")
	if len(ds) != 2 || ds[0].Model != "Smart TV Pro" || ds[1].State != "unauthorized" {
		t.Fatalf("devices: %+v", ds)
	}
}

func TestClassifyConnect(t *testing.T) {
	cases := []struct{ out, state, want string }{
		{"connected to 1.2.3.4:5555", "device", "ok"},
		{"already connected to 1.2.3.4:5555", "device\n", "ok"},
		{"connected to 1.2.3.4:5555", "unauthorized", "allow"},
		{"failed to authenticate to 1.2.3.4:37123", "error: device offline", "pair"},
		{"failed to connect to 1.2.3.4:37123: Connection refused", "error: no devices", "noreply"},
	}
	for _, c := range cases {
		if got, _ := classifyConnect(c.out, c.state); got != c.want {
			t.Fatalf("%q / %q: %s вместо %s", c.out, c.state, got, c.want)
		}
	}
}

func TestADBVersion(t *testing.T) {
	if n, v := adbMajor("Android Debug Bridge version 1.0.41\nVersion 37.0.1-14089765\nInstalled as x"); n != 37 || v != "37.0.1" {
		t.Fatalf("версия adb: %d %s", n, v)
	}
	if n, _ := adbMajor("Android Debug Bridge version 1.0.39\nRevision 3db08f2c6889-android"); n != 0 {
		t.Fatal("старый adb без строки Version")
	}
}

func TestShq(t *testing.T) {
	if got := shq("a'b c"); got != `'a'\''b c'` {
		t.Fatalf("кавычки: %s", got)
	}
	if undoSetting("secure", "x", "") != "settings delete secure x" {
		t.Fatal("пустое прежнее значение: delete")
	}
}
