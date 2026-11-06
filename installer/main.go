// Установщик HYDE TV (план Codex, этап 2): одна программа без Java, Python и Git у человека, та же для Claude.
// Заход 1 (07.11.2026): найти ТВ, прочитать паспорт, показать план изменений. На ТВ ничего не меняет.
//
//	hyde-install                         окно мастера в браузере (только этот компьютер)
//	hyde-install devices                 ТВ в сети (mDNS) и уже подключённые
//	hyde-install connect адрес[:порт]    подключиться (адрес и порт с главного экрана «Беспроводная отладка»)
//	hyde-install pair адрес:порт код     сопряжение по коду из окна «Подключить устройство с помощью кода»
//	hyde-install inspect [--tv адрес] [--json] [--save файл] [--from файл]          паспорт ТВ, только чтение
//	hyde-install plan [--tv адрес] [--release папка] [--with м1,м2] [--without м3] [--home] [--json] [--from файл]
//	                                     план изменений, только чтение
//
// --from: паспорт из сохранённого ответа ТВ (--save), для проверок без ТВ. Выпуск по умолчанию: папка установщика
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const toolVersion = "0.1 (заход 1: поиск ТВ, паспорт, план)"

func main() {
	args := os.Args[1:]
	cmd := "ui"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}
	var err error
	switch cmd {
	case "ui":
		err = cmdUI(args)
	case "devices":
		err = cmdDevices()
	case "connect":
		err = cmdConnect(args)
	case "pair":
		err = cmdPair(args)
	case "inspect":
		err = cmdInspect(args)
	case "plan":
		err = cmdPlan(args)
	case "apply":
		err = cmdApply(args)
	case "verify":
		err = cmdVerify(args)
	case "restore":
		err = cmdRestore(args)
	case "version":
		fmt.Println("hyde-install " + toolVersion)
	case "help", "-h", "--help":
		fmt.Println(usage)
	default:
		err = fmt.Errorf("неизвестная команда %q\n\n%s", cmd, usage)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "ОШИБКА: "+err.Error())
		os.Exit(1)
	}
}

const usage = `hyde-install: установщик HYDE TV
  hyde-install                         окно мастера в браузере
  hyde-install devices                 ТВ в сети и уже подключённые
  hyde-install connect адрес[:порт]    подключиться к ТВ
  hyde-install pair адрес:порт код     сопряжение по коду с экрана ТВ
  hyde-install inspect [--tv адрес] [--json] [--save файл] [--from файл]
  hyde-install plan [--tv адрес] [--release папка] [--with м1,м2] [--without м3] [--home] [--json] [--from файл]
  hyde-install apply [как plan] [--backup папка] [--allow-download] [--tv-free] [--yes]   установка по плану
  hyde-install verify [--tv адрес] [--backup папка_журнала]          проверка после установки
  hyde-install restore [--tv адрес] [--backup папка_журнала] [--yes] откат по журналу
Модули: guard keymapper home nightmode sound picture clip keyboard volna browser
Повторный apply после обрыва продолжает незаконченную установку этого ТВ с того же места`

func cmdDevices() error {
	a, err := findADB()
	if err != nil {
		return err
	}
	ss, _ := a.Mdns()
	fmt.Println("ТВ, которые объявили беспроводную отладку в этой сети:")
	if len(ss) == 0 {
		fmt.Println("  никого (включите на ТВ «Беспроводную отладку»; компьютер и ТВ должны быть в одной сети)")
	}
	for _, s := range ss {
		kind := map[string]string{"_adb-tls-connect._tcp": "подключение", "_adb-tls-pairing._tcp": "окно сопряжения открыто", "_adb._tcp": "старая отладка"}[s.Type]
		fmt.Printf("  %-22s %s (%s)\n", s.Addr, kind, s.Name)
	}
	ds, _ := a.Devices()
	fmt.Println("Подключены:")
	if len(ds) == 0 {
		fmt.Println("  никого")
	}
	for _, d := range ds {
		fmt.Printf("  %-40s %s %s\n", d.Serial, d.State, d.Model)
	}
	return nil
}

func cmdConnect(args []string) error {
	if len(args) != 1 {
		return errors.New("нужно: hyde-install connect адрес[:порт]")
	}
	a, err := findADB()
	if err != nil {
		return err
	}
	r := a.Connect(args[0])
	fmt.Println(r.Say)
	if r.Result != "ok" {
		os.Exit(map[string]int{"allow": 2, "noreply": 3, "pair": 4}[r.Result])
	}
	fmt.Println("TV=" + r.Serial)
	return nil
}

func cmdPair(args []string) error {
	if len(args) != 2 {
		return errors.New("нужно: hyde-install pair адрес:порт_окна_сопряжения код")
	}
	a, err := findADB()
	if err != nil {
		return err
	}
	ok, out := a.Pair(args[0], args[1])
	if !ok {
		return errors.New("сопряжение не прошло: " + out)
	}
	fmt.Println("Сопряжение прошло. Теперь подключитесь с адресом и портом главного экрана «Беспроводная отладка».")
	return nil
}

// pickSerial: ТВ из --tv или единственный подключённый; несколько найденных устройств молча не выбираем
func pickSerial(a *ADB, tv string) (string, error) {
	if tv != "" {
		return tv, nil
	}
	ds, err := a.Devices()
	if err != nil {
		return "", err
	}
	var on []string
	for _, d := range ds {
		if d.State == "device" {
			on = append(on, d.Serial)
		}
	}
	switch len(on) {
	case 0:
		return "", errors.New("ни один ТВ не подключён: hyde-install connect адрес:порт")
	case 1:
		return on[0], nil
	}
	return "", fmt.Errorf("подключено несколько устройств (%s): укажите --tv", strings.Join(on, ", "))
}

type passportFlags struct {
	tv, from, save string
}

func (pf *passportFlags) bind(fs *flag.FlagSet) {
	fs.StringVar(&pf.tv, "tv", "", "адрес ТВ (как в adb devices)")
	fs.StringVar(&pf.from, "from", "", "паспорт из сохранённого ответа ТВ")
	fs.StringVar(&pf.save, "save", "", "сохранить ответ ТВ в файл")
}

func (pf *passportFlags) load() (Passport, error) {
	if pf.from != "" {
		b, err := os.ReadFile(pf.from)
		if err != nil {
			return Passport{}, err
		}
		p := parsePassport(string(b))
		if !p.Complete {
			return p, errors.New("в файле паспорта нет конца ответа (##end)")
		}
		return p, nil
	}
	a, err := findADB()
	if err != nil {
		return Passport{}, err
	}
	serial, err := pickSerial(a, pf.tv)
	if err != nil {
		return Passport{}, err
	}
	p, raw, err := readPassport(a, serial)
	if pf.save != "" && raw != "" {
		if werr := os.WriteFile(pf.save, []byte(raw), 0o644); werr != nil {
			return p, werr
		}
	}
	return p, err
}

func cmdInspect(args []string) error {
	fs := flag.NewFlagSet("inspect", flag.ContinueOnError)
	var pf passportFlags
	pf.bind(fs)
	asJSON := fs.Bool("json", false, "ответ в JSON")
	if err := fs.Parse(args); err != nil {
		return err
	}
	p, err := pf.load()
	if err != nil {
		return err
	}
	c := capsOf(p)
	if *asJSON {
		return printJSON(map[string]any{"caps": c, "passport": p})
	}
	fmt.Print(capsText(c))
	return nil
}

// capsText: паспорт словами: что за ТВ и что на нём будет работать
func capsText(c Caps) string {
	var b strings.Builder
	yes := func(v bool, y, n string) string {
		if v {
			return y
		}
		return n
	}
	fmt.Fprintf(&b, "ТВ: %s\nAndroid %s (API %d), архитектура %s%s\n", nonEmpty(c.Model, "?"), nonEmpty(c.Android, "?"), c.SDK, strings.Join(c.ABI, ", "), yes(c.ABI32, " (32 бита)", ""))
	fmt.Fprintf(&b, "Профиль: %s, уровень TCL: %s\n", c.Profile, c.Level)
	if c.Why != "" {
		fmt.Fprintf(&b, "Не подходит: %s\n", c.Why)
	}
	fmt.Fprintf(&b, "Подсветка TCL: %s\n", yes(c.DisplayLib, "есть", "нет"))
	fmt.Fprintf(&b, "Dolby: %s\n", yes(c.Dolby, "есть", "нет"))
	fmt.Fprintf(&b, "Быстрая панель TCL: %s\n", yes(c.QuickPanel, "есть", "нет"))
	fmt.Fprintf(&b, "Таблица кнопок TCL: %s\n", yes(c.TKL, "есть", "нет"))
	fmt.Fprintf(&b, "Главный экран сейчас: %s\n", nonEmpty(c.HomeHolder, "не определён"))
	fmt.Fprintf(&b, "Key Mapper: %s\n", yes(c.KeyMapper, "стоит", "нет"))
	if c.DataFreeMB > 0 {
		fmt.Fprintf(&b, "Свободно на ТВ: %d МБ\n", c.DataFreeMB)
	}
	return b.String()
}

func releaseDir(v string) string {
	if v != "" {
		return v
	}
	if exe, err := os.Executable(); err == nil {
		d := filepath.Dir(exe)
		if _, err := os.Stat(filepath.Join(d, "manifest.json")); err == nil {
			return d
		}
	}
	return "."
}

func cmdPlan(args []string) error {
	fs := flag.NewFlagSet("plan", flag.ContinueOnError)
	var pf passportFlags
	pf.bind(fs)
	rel := fs.String("release", "", "папка выпуска (manifest.json и файлы)")
	with := fs.String("with", "", "добавить модули через запятую")
	without := fs.String("without", "", "убрать модули через запятую")
	home := fs.Bool("home", false, "сделать «Дом» главным экраном")
	asJSON := fs.Bool("json", false, "ответ в JSON")
	noCheck := fs.Bool("no-check", false, "не сверять файлы выпуска с описью")
	if err := fs.Parse(args); err != nil {
		return err
	}
	rec, err := loadRecipe()
	if err != nil {
		return err
	}
	m, err := loadManifest(releaseDir(*rel))
	if err != nil {
		return err
	}
	p, err := pf.load()
	if err != nil {
		return err
	}
	ch := Choices{Modules: map[string]bool{}}
	for _, x := range splitList(*with) {
		ch.Modules[x] = true
	}
	for _, x := range splitList(*without) {
		ch.Modules[x] = false
	}
	fs.Visit(func(f *flag.Flag) {
		if f.Name == "home" {
			ch.Home = home
		}
	})
	pl := buildPlan(rec, m, p, ch, !*noCheck)
	if *asJSON {
		return printJSON(pl)
	}
	fmt.Print(pl.Text())
	return nil
}

func splitList(s string) []string {
	var r []string
	for _, x := range strings.Split(s, ",") {
		if x = strings.TrimSpace(x); x != "" {
			r = append(r, x)
		}
	}
	return r
}

func printJSON(v any) error {
	e := json.NewEncoder(os.Stdout)
	e.SetIndent("", "  ")
	e.SetEscapeHTML(false)
	return e.Encode(v)
}

// backupRoot: где папки журналов. По умолчанию backup рядом с выпуском (папка установщика), иначе ./backup
func backupRoot(v, rel string) string {
	if v != "" {
		return v
	}
	if rel != "." {
		return filepath.Join(rel, "backup")
	}
	return "backup"
}

func connectTV(tvAddr string) (*ADB, TV, error) {
	a, err := findADB()
	if err != nil {
		return nil, nil, err
	}
	serial, err := pickSerial(a, tvAddr)
	if err != nil {
		return nil, nil, err
	}
	return a, &adbTV{a: a, serial: serial}, nil
}

func printEvent(e Event) {
	mark := map[string]string{"start": "…", "ok": "ок", "skip": "пропуск", "warn": "ВНИМАНИЕ", "fail": "ОШИБКА", "lost": "СВЯЗЬ ПРОПАЛА", "info": "·"}[e.Status]
	if e.Status == "start" {
		return
	}
	n := ""
	if e.N > 0 {
		n = fmt.Sprintf("[%d/%d] ", e.N, e.Total)
	}
	line := fmt.Sprintf("%s %s%s", mark, n, e.What)
	if e.Group != "" && e.N > 0 {
		line = fmt.Sprintf("%s %s%s: %s", mark, n, e.Group, e.What)
	}
	if e.Detail != "" {
		line += " (" + e.Detail + ")"
	}
	fmt.Println(line)
}

func confirm(q string) bool {
	fmt.Print(q + " [y/N] ")
	var ans string
	fmt.Scanln(&ans)
	switch strings.ToLower(strings.TrimSpace(ans)) {
	case "y", "yes", "д", "да":
		return true
	}
	return false
}

func cmdApply(args []string) error {
	fs := flag.NewFlagSet("apply", flag.ContinueOnError)
	tvAddr := fs.String("tv", "", "адрес ТВ (как в adb devices)")
	rel := fs.String("release", "", "папка выпуска")
	with := fs.String("with", "", "добавить модули через запятую")
	without := fs.String("without", "", "убрать модули через запятую")
	home := fs.Bool("home", false, "сделать «Дом» главным экраном")
	root := fs.String("backup", "", "папка журналов (по умолчанию backup рядом с выпуском)")
	allowDl := fs.Bool("allow-download", false, "разрешить скачать сторонние приложения из описи (Key Mapper с GitHub)")
	tvFree := fs.Bool("tv-free", false, "ТВ свободен: можно открыть «Дом» и нажать «Домой»")
	yes := fs.Bool("yes", false, "не спрашивать подтверждение")
	if err := fs.Parse(args); err != nil {
		return err
	}
	rec, err := loadRecipe()
	if err != nil {
		return err
	}
	rd := releaseDir(*rel)
	m, err := loadManifest(rd)
	if err != nil {
		return err
	}
	a, tv, err := connectTV(*tvAddr)
	if err != nil {
		return err
	}
	ch := Choices{Modules: map[string]bool{}}
	for _, x := range splitList(*with) {
		ch.Modules[x] = true
	}
	for _, x := range splitList(*without) {
		ch.Modules[x] = false
	}
	fs.Visit(func(f *flag.Flag) {
		if f.Name == "home" {
			ch.Home = home
		}
	})
	p, _, err := readPassport(a, tv.Serial())
	if err != nil {
		return err
	}
	pl := buildPlan(rec, m, p, ch, true)
	fmt.Print(pl.Text())
	if len(pl.Problems) > 0 {
		return errors.New("установка невозможна: " + strings.Join(pl.Problems, " "))
	}
	if !*yes && !confirm("\nСтавить по этому плану?") {
		fmt.Println("Отменено, на ТВ ничего не менял.")
		return nil
	}
	res := runApply(tv, a, rec, m, ch, ApplyOpts{AllowDownload: *allowDl, TVFree: *tvFree, BackupRoot: backupRoot(*root, rd), Emit: printEvent})
	if res.Backup != "" {
		fmt.Println("Журнал и снимок «до»: " + res.Backup)
	}
	if res.Error != "" { // само сообщение уже напечатано событием
		if res.Lost {
			os.Exit(3)
		}
		os.Exit(1)
	}
	return nil
}

// sessionDir: папка журнала из --backup (это может быть сама папка журнала или папка журналов) или самая свежая этого ТВ
func sessionDir(tv TV, v, rel string) (string, error) {
	if v != "" {
		if _, err := os.Stat(filepath.Join(v, "ledger.tsv")); err == nil {
			return v, nil
		}
	}
	root := backupRoot(v, releaseDir(rel))
	id := strings.TrimSpace(firstLine(mustShell(tv, "settings get secure android_id")))
	if d := latestSession(root, id); d != "" {
		return d, nil
	}
	return "", fmt.Errorf("в %s нет журнала установки для этого ТВ", root)
}

func cmdVerify(args []string) error {
	fs := flag.NewFlagSet("verify", flag.ContinueOnError)
	tvAddr := fs.String("tv", "", "адрес ТВ")
	dir := fs.String("backup", "", "папка журнала или папка журналов")
	rel := fs.String("release", "", "папка выпуска")
	if err := fs.Parse(args); err != nil {
		return err
	}
	_, tv, err := connectTV(*tvAddr)
	if err != nil {
		return err
	}
	d, err := sessionDir(tv, *dir, *rel)
	if err != nil {
		return err
	}
	cs, err := runVerify(tv, d)
	if err != nil {
		return err
	}
	bad := 0
	for _, c := range cs {
		mark := "ок"
		if !c.OK {
			mark, bad = "НЕТ", bad+1
		}
		line := mark + "  " + c.What
		if c.Detail != "" {
			line += " (" + c.Detail + ")"
		}
		fmt.Println(line)
	}
	fmt.Printf("Проверено: %d, не держится: %d (журнал %s)\n", len(cs), bad, d)
	if bad > 0 {
		os.Exit(1)
	}
	return nil
}

func cmdRestore(args []string) error {
	fs := flag.NewFlagSet("restore", flag.ContinueOnError)
	tvAddr := fs.String("tv", "", "адрес ТВ")
	dir := fs.String("backup", "", "папка журнала или папка журналов")
	rel := fs.String("release", "", "папка выпуска")
	yes := fs.Bool("yes", false, "не спрашивать подтверждение")
	if err := fs.Parse(args); err != nil {
		return err
	}
	_, tv, err := connectTV(*tvAddr)
	if err != nil {
		return err
	}
	d, err := sessionDir(tv, *dir, *rel)
	if err != nil {
		return err
	}
	entries, err := readLedger(filepath.Join(d, "ledger.tsv"))
	if err != nil {
		return err
	}
	undo, manual := restoreOrder(entries)
	fmt.Printf("Откат по журналу %s: команд %d, руками %d, потом сверка настроек со снимком «до».\n", d, len(undo), len(manual))
	if !*yes && !confirm("Выполнить откат на ТВ?") {
		fmt.Println("Отменено.")
		return nil
	}
	r := runRestore(tv, d, printEvent)
	fmt.Print(r.Text())
	switch {
	case r.Lost || r.Error != "":
		os.Exit(3)
	case r.Fail > 0:
		os.Exit(1)
	}
	return nil
}
