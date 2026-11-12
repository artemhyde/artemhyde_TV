// Установщик HYDE TV (план Codex, этап 2): одна программа без Java, Python и Git у человека, та же для Claude.
// Заход 1 (07.11.2026): найти ТВ, прочитать паспорт, показать план. Заход 2: установка по плану с журналом,
// продолжение после обрыва, проверка и откат. Заход 3: adb с сайта Google по согласию, архив выпуска для людей,
// путь Claude через установщик (installer/AGENT.md), откат нескольких установок по очереди. Команды: usage ниже.
//
// --from: паспорт из сохранённого ответа ТВ (--save), для проверок без ТВ. Выпуск по умолчанию: папка установщика
// или папка release рядом с ним (так лежит архив выпуска). Журналы: ~/.glasstv/backup (home.go)
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

const toolVersion = "0.6"

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
	case "report":
		err = cmdReport(args)
	case "restore":
		err = cmdRestore(args)
	case "get-adb":
		err = cmdGetADB(args)
	case "guard":
		err = cmdGuard(args)
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
  hyde-install get-adb [--yes]         скачать adb с сайта Google (с вашего согласия с условиями Google)
  hyde-install devices                 ТВ в сети и уже подключённые
  hyde-install connect адрес[:порт]    подключиться к ТВ
  hyde-install pair адрес:порт код     сопряжение по коду с экрана ТВ
  hyde-install inspect [--tv адрес] [--json] [--save файл] [--from файл]
  hyde-install plan [--tv адрес] [--release папка] [--with м1,м2] [--without м3] [--home] [--json] [--from файл]
  hyde-install apply [как plan] [--backup папка] [--allow-download] [--tv-free] [--yes] [--no-report] [--no-auto-update]   установка по плану
  hyde-install verify [--tv адрес] [--backup папка]   проверка после установки
  hyde-install report [--tv адрес] [--send] [--test [--backup папка_журнала]]   отчёт автору о последней установке: без --send только показать; --test: проверка приёма (с папкой журнала без ТВ)
  hyde-install restore [--tv адрес] [--backup папка] [--yes] [--no-report]   откат всех установок этого ТВ (или одной: --backup папка_журнала)
  hyde-install guard adb [always|service] [--tv адрес]   отладка по сети: держать всегда или ещё 2 часа, потом выключить
  hyde-install guard notes [on|off] [--tv адрес]         записки с телефона; без значения: только показать
Модули: guard keymapper home nightmode sound picture clip keyboard volna browser
Приложения по желанию (--with, качаются со страниц авторов, нужен --allow-download): smarttube lampa torrserve localsend files byedpi
Обновления и отчёты (с 1.2.0) включены заранее: «Сторож» сам ставит новые выпуски, а технический отчёт автору (модель ТВ, Android,
что поставилось, ошибки, номер устройства; без личных данных) уходит в конце apply и restore сам. Выключить: --no-auto-update и --no-report.
Повторный apply после обрыва продолжает незаконченную установку этого ТВ с того же места.
Журналы и снимки «до»: ~/.glasstv/backup (папка HYDE TV: GLASSTV_HOME)`

func cmdDevices() error {
	a, err := findADB()
	if err != nil {
		return err
	}
	ss, _ := a.Mdns()
	fmt.Println("ТВ, которые объявили беспроводную отладку в этой сети:")
	if len(ss) == 0 {
		fmt.Println("  никого (включите на ТВ «Отладка по Wi-Fi», на некоторых ТВ «Беспроводная отладка»; компьютер и ТВ должны быть в одной сети)")
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
	fmt.Println("Сопряжение прошло. Теперь подключитесь с адресом и портом главного экрана «Отладка по Wi-Fi» (строка «IP-адрес и порт»).")
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

// releaseDir: папка выпуска: --release, иначе папка установщика или release рядом с ним (архив выпуска), иначе
// текущая папка или release в ней
func releaseDir(v string) string {
	if v != "" {
		return v
	}
	var cands []string
	if exe, err := os.Executable(); err == nil {
		d := filepath.Dir(exe)
		cands = append(cands, d, filepath.Join(d, "release"))
	}
	cands = append(cands, ".", "release")
	for _, d := range cands {
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
	var live *adbTV
	if pf.from == "" { // живой ТВ: журналы этого ТВ (снова включённое установщик отключит снова)
		if a, err := findADB(); err == nil {
			if serial, err := pickSerial(a, pf.tv); err == nil {
				live = &adbTV{a: a, serial: serial}
				if id := tvIDOf(live); id != "" {
					ch.Redisable = disabledByInstaller(sessionsOf(backupRoot(""), id, true))
				}
			}
		}
	}
	pl := buildPlan(rec, m, p, ch, !*noCheck)
	if live != nil { // пометить то, что на нём уже так
		markHeld(&pl, heldSteps(live, &pl, nil))
		// с чем пойдёт apply без ключей: прошлый выбор этого ТВ, иначе оба включены
		c := defaultConsent()
		if last := lastConsentOf(backupRoot(""), tvIDOf(live)); last != nil {
			c, pl.ConsentLast = *last, true
		}
		pl.Consent = &c
	}
	if *asJSON {
		return printJSON(pl)
	}
	fmt.Print(pl.Text())
	if pl.Consent != nil {
		from := "по умолчанию"
		if pl.ConsentLast {
			from = "как в прошлый раз для этого ТВ"
		}
		fmt.Println("Выбор (" + from + "): " + consentText(*pl.Consent) + " (выключить: --no-auto-update, --no-report).")
	}
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

// backupRoot: где папки журналов: --backup или ~/.glasstv/backup
func backupRoot(v string) string {
	if v != "" {
		return v
	}
	return defaultBackupRoot()
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
	mark := map[string]string{"start": "…", "ok": "ок", "skip": "пропуск", "warn": "ВНИМАНИЕ", "fail": "ОШИБКА", "lost": "СВЯЗЬ ПРОПАЛА", "info": "·",
		"ask": "НА ТЕЛЕВИЗОРЕ:"}[e.Status]
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
	root := fs.String("backup", "", "папка журналов (по умолчанию ~/.glasstv/backup)")
	allowDl := fs.Bool("allow-download", false, "разрешить скачать сторонние приложения из описи со страниц авторов (Key Mapper и выбранные приложения по желанию с GitHub)")
	tvFree := fs.Bool("tv-free", false, "ТВ свободен: можно открыть «Дом» и нажать «Домой»")
	yes := fs.Bool("yes", false, "не спрашивать подтверждение")
	_ = fs.Bool("report", true, "оставлен для старых команд: отчёт и так уходит сам")
	_ = fs.Bool("no-report", false, "не отправлять автору технический отчёт (модель ТВ, Android, что поставилось и что не вышло) и не включать «Сторожу» суточный отчёт")
	_ = fs.Bool("no-auto-update", false, "не обновлять HYDE автоматически («Сторож» не ставит новые выпуски сам)")
	via := fs.String("via", "cli", "кто запускает: cli или claude (для отчёта)")
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
	// Ключами ничего не сказано: прошлый выбор этого ТВ, иначе обновления и отчёты включены (решение автора 10.11)
	consent := consentFromFlags(fs, lastConsentOf(backupRoot(*root), tvIDOf(tv)))
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
	fmt.Println("Выбор: " + consentText(consent) + " (выключить: --no-auto-update, --no-report).")
	if len(pl.Problems) > 0 {
		return errors.New("установка невозможна: " + strings.Join(pl.Problems, " "))
	}
	if !*yes && !confirm("\nСтавить по этому плану?") {
		fmt.Println("Отменено, на ТВ ничего не менял.")
		return nil
	}
	res := runApply(tv, a, rec, m, ch, ApplyOpts{AllowDownload: *allowDl, TVFree: *tvFree, BackupRoot: backupRoot(*root), Emit: printEvent, Consent: &consent})
	if res.Backup != "" {
		fmt.Println("Журнал и снимок «до»: " + res.Backup)
	}
	if consent.Reports && res.Backup != "" {
		var cs []Check
		if res.Error == "" {
			cs, _ = runVerify(tv, res.Backup)
		}
		reportAfter(true, res.Backup, "apply", *via, cs, false)
	}
	if res.Error != "" { // само сообщение уже напечатано событием
		if res.Lost {
			os.Exit(3)
		}
		os.Exit(1)
	}
	return nil
}

var errAllRestored = errors.New("все установки HYDE TV на этом ТВ уже откачены")

// sessionDirs: журналы, по которым работать: --backup с папкой журнала: только он; иначе все не откаченные установки
// этого ТВ в папке журналов, от новой к старой
func sessionDirs(tv TV, v string) ([]string, error) {
	if v != "" {
		if _, err := os.Stat(filepath.Join(v, "ledger.tsv")); err == nil {
			return []string{v}, nil
		}
	}
	root := backupRoot(v)
	id := strings.TrimSpace(firstLine(mustShell(tv, "settings get secure android_id")))
	if ds := sessionsOf(root, id, true); len(ds) > 0 {
		return ds, nil
	}
	if len(sessionsOf(root, id, false)) > 0 {
		return nil, fmt.Errorf("%w (журналы в %s)", errAllRestored, root)
	}
	return nil, fmt.Errorf("в %s нет журнала установки для этого ТВ", root)
}

func cmdVerify(args []string) error {
	fs := flag.NewFlagSet("verify", flag.ContinueOnError)
	tvAddr := fs.String("tv", "", "адрес ТВ")
	dir := fs.String("backup", "", "папка журнала или папка журналов")
	if err := fs.Parse(args); err != nil {
		return err
	}
	_, tv, err := connectTV(*tvAddr)
	if err != nil {
		return err
	}
	ds, err := sessionDirs(tv, *dir)
	if errors.Is(err, errAllRestored) { // HYDE на этом ТВ откачен целиком: не ошибка, проверять нечего
		fmt.Println("Проверять нечего: " + err.Error())
		return nil
	}
	if err != nil {
		return err
	}
	cs, err := runVerify(tv, ds...)
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
	fmt.Printf("Проверено: %d, не держится: %d (журналы: %s)\n", len(cs), bad, strings.Join(ds, ", "))
	if bad > 0 {
		os.Exit(1)
	}
	return nil
}

func cmdRestore(args []string) error {
	fs := flag.NewFlagSet("restore", flag.ContinueOnError)
	tvAddr := fs.String("tv", "", "адрес ТВ")
	dir := fs.String("backup", "", "папка журнала (откатить только её) или папка журналов")
	yes := fs.Bool("yes", false, "не спрашивать подтверждение")
	_ = fs.Bool("report", true, "оставлен для старых команд: отчёт и так уходит сам")
	_ = fs.Bool("no-report", false, "не отправлять автору технический отчёт о возврате (модель ТВ, Android, итог)")
	via := fs.String("via", "cli", "кто запускает: cli или claude (для отчёта)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	_, tv, err := connectTV(*tvAddr)
	if err != nil {
		return err
	}
	ds, err := sessionDirs(tv, *dir)
	if errors.Is(err, errAllRestored) { // повторный откат: не ошибка, откатывать нечего
		fmt.Println("Откатывать нечего: " + err.Error())
		return nil
	}
	if err != nil {
		return err
	}
	fmt.Println("Откат по журналам, от новой установки к старой:")
	removed := removedByAll(ds)
	for _, d := range ds {
		entries, err := readLedger(filepath.Join(d, "ledger.tsv"))
		if err != nil {
			return err
		}
		undo, manual := restoreOrder(entries)
		manual = keepManual(manual, removed)
		rel := ""
		if s, err := loadSession(d); err == nil {
			rel = ", выпуск " + s.Release + ", начата " + s.Started
		}
		fmt.Printf("  %s%s: команд %d, руками %d\n", d, rel, len(undo), len(manual))
	}
	fmt.Println("В конце каждого: сверка настроек со снимком «до».")
	if !*yes && !confirm("Выполнить откат на ТВ?") {
		fmt.Println("Отменено.")
		return nil
	}
	ra := runRestoreAll(tv, ds, printEvent)
	fmt.Print(ra.Text())
	// Отчёт о возврате по выбору человека в установке, которую откатили (ключ важнее)
	if len(ds) > 0 && consentFromFlags(fs, lastConsent(ds)).Reports {
		reportAfter(true, ds[0], "restore", *via, nil, ra.OK())
	}
	if c := ra.Code(); c != 0 {
		os.Exit(c)
	}
	return nil
}

// cmdGuard: выключатели «Сторожа»: hyde-install guard adb|notes [значение] [--tv адрес]
func cmdGuard(args []string) error {
	var pos []string
	tvAddr := ""
	for i := 0; i < len(args); i++ {
		switch {
		case args[i] == "--tv" && i+1 < len(args):
			tvAddr = args[i+1]
			i++
		case strings.HasPrefix(args[i], "--tv="):
			tvAddr = strings.TrimPrefix(args[i], "--tv=")
		default:
			pos = append(pos, args[i])
		}
	}
	if len(pos) < 1 || len(pos) > 2 {
		return errors.New("нужно: hyde-install guard adb [always|service] или hyde-install guard notes [on|off]")
	}
	set := ""
	if len(pos) == 2 {
		set = pos[1]
	}
	_, tv, err := connectTV(tvAddr)
	if err != nil {
		return err
	}
	out, err := guardSwitch(tv, pos[0], set)
	if err != nil {
		return err
	}
	fmt.Println(out)
	return nil
}

// cmdGetADB: скачать adb с сайта Google. Условия Google принимает человек: здесь ответом «y», у Claude словом в чате,
// после которого Claude запускает с --yes
func cmdGetADB(args []string) error {
	fs := flag.NewFlagSet("get-adb", flag.ContinueOnError)
	rel := fs.String("release", "", "папка выпуска (в описи версия и суммы adb)")
	yes := fs.Bool("yes", false, "условия Google приняты, не спрашивать")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if a, err := findADB(); err == nil {
		fmt.Printf("adb уже есть: %s (версия %s), скачивать не нужно\n", a.Path, a.Version)
		return nil
	}
	m, err := loadManifest(releaseDir(*rel))
	if err != nil {
		return err
	}
	off, err := adbOffer(m)
	if err != nil {
		return err
	}
	fmt.Printf("Нужен adb: через него установщик говорит с телевизором.\n%s %s, %s, с %s\nКуда: %s\nУсловия Google: %s\n",
		off.Name, off.Version, off.Size, off.Host, off.Dest, off.Terms)
	if !*yes && !confirm("Принимаете условия Google и разрешаете скачать?") {
		fmt.Println("Отменено, ничего не скачано.")
		return nil
	}
	a, err := downloadADB(m, printEvent)
	if err != nil {
		return err
	}
	fmt.Println("Готово: " + a.Path)
	return nil
}

// cmdReport: отчёт по последней установке этого ТВ задним числом: без --send только показывает, что уйдёт
func cmdReport(args []string) error {
	fs := flag.NewFlagSet("report", flag.ContinueOnError)
	tvAddr := fs.String("tv", "", "адрес ТВ")
	dir := fs.String("backup", "", "папка журнала или папка журналов")
	send := fs.Bool("send", false, "отправить автору (без ключа только показать)")
	via := fs.String("via", "cli", "кто запускает: cli или claude")
	test := fs.Bool("test", false, "проверка приёма для автора: итог test, в сводку таблицы не попадает")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if !reportEnabled() {
		fmt.Println("В этой версии адрес для отчётов не настроен: отправлять некуда, ничего не уходит.")
		return nil
	}
	var ds []string
	var cs []Check
	kind := "apply"
	if _, e := os.Stat(filepath.Join(*dir, "session.json")); *test && *dir != "" && e == nil {
		ds = []string{*dir} // проверка приёма по готовому журналу: телевизор не нужен, проверки ТВ в отчёте нет
	} else {
		_, tv, err := connectTV(*tvAddr)
		if err != nil {
			return err
		}
		ds, err = sessionDirs(tv, *dir)
		if errors.Is(err, errAllRestored) { // всё возвращено: отчёт о возврате по последнему журналу
			id := strings.TrimSpace(firstLine(mustShell(tv, "settings get secure android_id")))
			ds, err, kind = sessionsOf(backupRoot(*dir), id, false), nil, "restore"
		}
		if err != nil {
			return err
		}
		if len(ds) == 0 {
			return errors.New("нет журнала установки для этого ТВ")
		}
		if kind == "apply" {
			cs, _ = runVerify(tv, ds[0])
		}
	}
	r, err := reportFromSession(ds[0], kind, *via, os.Getenv("LANG"), cs, kind == "restore")
	if err != nil {
		return err
	}
	if *test {
		r.Result = "test"
	}
	printReport(r)
	if !*send {
		if *test {
			fmt.Println("Ничего не отправлено. Отправить проверочный: добавьте к этой же команде --send")
		} else {
			fmt.Println("Ничего не отправлено. Отправить: hyde-install report --send")
		}
		return nil
	}
	if err := sendReport(r); err != nil {
		fmt.Println("Отчёт не отправлен: " + err.Error())
		return nil
	}
	fmt.Println("Отчёт отправлен, номер " + r.ReportID + ". Чтобы автор удалил его, напишите ему этот номер.")
	return nil
}
