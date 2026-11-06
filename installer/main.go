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
Модули: guard keymapper home nightmode sound picture clip keyboard volna browser`

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
