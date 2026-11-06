package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// Установка по плану (план Codex, этап 2, операции apply и resume). Шаги, права и обратные команды из плана. Перед
// первым изменением: проверки (помехи плана, подписи файлов выпуска и уже стоящих приложений, согласие на скачивание и
// на нажатия кнопок), снимок «до» и пустой журнал. Строка журнала пишется до команды. После обрыва повторный запуск
// находит незаконченную установку этого ТВ, читает его заново и пропускает сделанное. Главный экран меняется как
// home_switch в tools/tv.sh: «Дом» открывается, отключается нужное, роль меняется, кнопка «Домой» проверяется,
// не вышло: всё возвращается

type Event struct {
	N      int    `json:"n"`
	Total  int    `json:"total"`
	Group  string `json:"group"`
	What   string `json:"what"`
	Status string `json:"status"` // start, ok, skip, warn, fail, lost, info
	Detail string `json:"detail,omitempty"`
}

type ApplyOpts struct {
	AllowDownload bool   // человек согласился скачать сторонние приложения из описи (Key Mapper)
	TVFree        bool   // ТВ свободен: можно открыть «Дом» и нажать «Домой»
	BackupRoot    string // где папки журналов (backup/<метка>)
	Emit          func(Event)
}

type ApplyResult struct {
	Backup   string `json:"backup"`
	Resumed  bool   `json:"resumed"`
	Done     int    `json:"done"`
	Skipped  int    `json:"skipped"`
	Warnings int    `json:"warnings"`
	Finished bool   `json:"finished"`
	Lost     bool   `json:"lost"`
	Error    string `json:"error,omitempty"`
}

var (
	homeWait   = 3 * time.Second
	retryPause = 2 * time.Second
	errLost    = errors.New("связь с ТВ пропала: состояние последнего шага неизвестно. Подключитесь заново и запустите установку ещё раз: она продолжит с того же места")
)

// cmdOK: команда прошла. «already» в ответе тоже успех (права уже выданы, клавиатура уже включена)
func cmdOK(out string, err error) bool {
	low := strings.ToLower(out)
	if strings.Contains(low, "already") {
		return true
	}
	if err != nil {
		return false
	}
	t := strings.TrimSpace(low)
	return !strings.Contains(low, "exception") && !strings.HasPrefix(t, "error") && !strings.HasPrefix(t, "failure")
}

func lastLine(s string) string {
	ls := strings.Split(strings.TrimSpace(s), "\n")
	return strings.TrimSpace(ls[len(ls)-1])
}

// runTVCmd: команда в оболочке ТВ; не прошла при живой связи: ещё одна попытка (мгновенный сбой связи, как в restore.sh)
func runTVCmd(tv TV, cmd string) (string, error) {
	out, err := tv.Shell(cmd, 2*time.Minute)
	if cmdOK(out, err) {
		return out, nil
	}
	if tv.State() != "device" {
		return out, errLost
	}
	time.Sleep(retryPause)
	out, err = tv.Shell(cmd, 2*time.Minute)
	if cmdOK(out, err) {
		return out, nil
	}
	if tv.State() != "device" {
		return out, errLost
	}
	return out, fmt.Errorf("не прошло: %s", nonEmpty(lastLine(out), "без ответа"))
}

func installFile(tv TV, file string) error {
	for try := 0; try < 2; try++ {
		out, _ := tv.Install(file)
		if strings.Contains(out, "Success") {
			return nil
		}
		if tv.State() != "device" {
			return errLost
		}
		switch {
		case strings.Contains(out, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"):
			return errors.New("на ТВ стоит сборка с другой подписью: поверх Android не ставит, а удалять её ради установки нельзя (пропадут данные). Переход: docs/release.md, «Переход с личной сборки»")
		case strings.Contains(out, "INSTALL_FAILED_VERSION_DOWNGRADE"):
			return errors.New("на ТВ версия новее этого файла: старую поверх Android не ставит")
		case strings.Contains(out, "INSTALL_FAILED_INSUFFICIENT_STORAGE"):
			return errors.New("на ТВ не хватает места")
		}
		if try == 0 {
			time.Sleep(retryPause)
			continue
		}
		return fmt.Errorf("установка не прошла: %s", nonEmpty(lastLine(out), "без ответа"))
	}
	return nil
}

var reInstalled = regexp.MustCompile(`^(установлен|обновлён) (\S+)`)

func runApply(tv TV, a *ADB, rec Recipe, m *Manifest, ch Choices, o ApplyOpts) (res ApplyResult) {
	emit := o.Emit
	if emit == nil {
		emit = func(Event) {}
	}
	fail := func(err error) ApplyResult {
		if errors.Is(err, errLost) {
			res.Lost = true
		}
		res.Error = err.Error()
		if res.Backup != "" {
			if s, e := loadSession(res.Backup); e == nil {
				s.LastError = res.Error
				_ = saveSession(res.Backup, s)
			}
		}
		emit(Event{Status: map[bool]string{true: "lost", false: "fail"}[res.Lost], What: res.Error})
		return res
	}
	if tv.State() != "device" {
		return fail(errors.New("ТВ не на связи: подключитесь (шаг «Телевизор»)"))
	}
	emit(Event{Status: "info", What: "читаю паспорт ТВ"})
	raw, err := tv.Shell(passportScript, 90*time.Second)
	p := parsePassport(raw)
	if !p.Complete {
		if err == nil {
			err = errors.New("ответ оборвался")
		}
		return fail(fmt.Errorf("паспорт ТВ не прочитан: %v", err))
	}
	pl := buildPlan(rec, m, p, ch, true)
	if len(pl.Problems) > 0 {
		return fail(errors.New(strings.Join(pl.Problems, " ")))
	}

	// Проверки до первого изменения
	emit(Event{Status: "info", What: "сверяю подписи файлов выпуска и уже стоящих приложений"})
	checked := map[string]bool{}
	for _, s := range pl.Steps {
		if s.Kind == "install" {
			cert, err := certOf(filepath.Join(m.Dir, s.File))
			if err != nil || cert != m.SignerCert {
				return fail(fmt.Errorf("файл %s подписан не ключом выпуска (%v): не ставлю", s.File, err))
			}
		}
		if s.Package != "" && strings.HasPrefix(s.Package, "app.glasstv.") && p.Has(s.Package) && !checked[s.Package] {
			checked[s.Package] = true
			if err := installedCertOK(tv, s.Package, m.SignerCert); err != nil {
				return fail(err)
			}
		}
		if s.Kind == "download" && !o.AllowDownload {
			return fail(fmt.Errorf("нужно согласие скачать %s с GitHub: без него уберите Key Mapper из выбора", s.URL))
		}
		if s.Kind == "home_role" && !o.TVFree {
			return fail(errors.New("смена главного экрана нажимает кнопки на ТВ: нужно подтверждение, что ТВ свободен"))
		}
	}

	// Папка журнала: продолжение незаконченной установки этого ТВ или новая со снимком «до»
	tvID := strings.TrimSpace(firstLine(mustShell(tv, "settings get secure android_id")))
	root := nonEmpty(o.BackupRoot, defaultBackupRoot())
	dir := findUnfinished(root, tvID)
	var held []string
	if dir != "" {
		res.Resumed = true
		emit(Event{Status: "info", What: "продолжаю незаконченную установку", Detail: dir})
		if s, err := loadSession(dir); err == nil {
			held = s.Held
		}
	} else {
		// Что на ТВ уже так до этой установки (обновление поверх стоящего выпуска): это не выполняется заново и не
		// пишется в журнал. Запоминается в session.json, чтобы после обрыва знать, что было до начала
		l := &tvLook{tv: tv}
		for _, s := range pl.Steps {
			if s.Kind != "" || s.Run || s.Do == "" {
				continue
			}
			if ok, known, _ := l.holds(s.Do); known && ok {
				held = append(held, s.Do)
			}
		}
		// Метка с секундами; занята (две установки в одну секунду, бывает в проверках): -2, -3. Новые метки при обратной
		// сортировке идут первыми, на этом стоят продолжение, проверка и откат от новой установки к старой
		base := filepath.Join(root, time.Now().Format("20060102-150405"))
		dir = base
		for i := 2; ; i++ {
			if _, err := os.Stat(dir); err != nil {
				break
			}
			dir = fmt.Sprintf("%s-%d", base, i)
		}
		emit(Event{Status: "info", What: "снимок ТВ «до» и пустой журнал", Detail: dir})
		if err := takeSnapshot(tv, dir, raw, a); err != nil {
			return fail(fmt.Errorf("снимок «до» не сделан, на ТВ ничего не менял: %v", err))
		}
		s := &Session{Label: filepath.Base(dir), TVID: tvID, Model: pl.Caps.Model, Release: m.Release, Modules: ch.Modules, Home: ch.Home,
			Started: time.Now().Format("2006-01-02 15:04:05"), Held: held}
		if err := saveSession(dir, s); err != nil {
			return fail(err)
		}
	}
	res.Backup = dir
	led := filepath.Join(dir, "ledger.tsv")
	prev, _ := readLedger(led)
	done := map[string]bool{}
	for _, e := range prev {
		done[e.Do] = true
	}
	journal := func(e LedgerEntry) error {
		if done[e.Do] {
			return nil
		}
		done[e.Do] = true
		return appendLedger(led, e)
	}
	wasHeld := map[string]bool{}
	for _, d := range held {
		wasHeld[d] = true
	}

	// Скачивание до первого изменения на ТВ: не скачалось или подпись чужая, ТВ не тронут
	files := map[string]string{}
	for _, s := range pl.Steps {
		if s.Kind != "download" {
			continue
		}
		tp := m.Third(s.Package)
		emit(Event{Status: "start", Group: s.Group, What: s.What})
		path, err := downloadThirdParty(tp, filepath.Join(dir, "downloads"), nil)
		if err != nil {
			return fail(err)
		}
		files[s.Package] = path
		emit(Event{Status: "ok", Group: s.Group, What: s.What})
	}

	homeDone, homeCand := false, ""
	var disables, ifNeeded []string
	for _, s := range pl.Steps {
		switch s.Kind {
		case "home_disable":
			disables = append(disables, s.Package)
		case "home_disable_if":
			ifNeeded = append(ifNeeded, s.Package)
		case "home_role":
			homeCand = s.Package // новый главный экран: из шага смены роли, а не из первого шага группы (07.11, тест на TCL)
		}
	}
	for i, s := range pl.Steps {
		ev := Event{N: i + 1, Total: len(pl.Steps), Group: s.Group, What: s.What}
		say := func(st, detail string) { e := ev; e.Status, e.Detail = st, detail; emit(e) }
		switch {
		case s.Kind == "download":
			continue
		case s.Kind == "home_disable" || s.Kind == "home_role" || s.Kind == "home_disable_if":
			if homeDone {
				continue
			}
			homeDone = true
			say("start", "")
			if homeCand == "" {
				return fail(errors.New("в плане нет шага смены главного экрана"))
			}
			if err := homeSwitch(tv, journal, homeCand, disables, ifNeeded); err != nil {
				return fail(err)
			}
			res.Done++
			say("ok", "«Домой» открывает «Дом»")
		case s.Kind == "install" || s.Kind == "install3p":
			file := filepath.Join(m.Dir, s.File)
			what := "установлен " + s.Package + " (" + s.Group + ", " + s.File + ")"
			if s.Undo == "-" {
				what = "обновлён " + s.Package + " (" + s.Group + ", " + s.File + "): откат версии не делается, данные на месте"
			}
			if s.Kind == "install3p" {
				file = files[s.Package]
			}
			say("start", "")
			undo := s.Undo
			if undo == "-" {
				undo = ""
			}
			if err := journal(LedgerEntry{What: what, Do: s.Do, Undo: undo}); err != nil {
				return fail(fmt.Errorf("журнал не пишется, дальше не иду: %v", err))
			}
			if err := installFile(tv, file); err != nil {
				return fail(fmt.Errorf("%s: %w", s.Group, err))
			}
			res.Done++
			say("ok", "")
		case s.Run:
			if s.Do == "" {
				res.Skipped++
				say("skip", "")
				continue
			}
			say("start", "")
			if _, err := runTVCmd(tv, s.Do); err != nil {
				if errors.Is(err, errLost) {
					return fail(err)
				}
				res.Warnings++
				say("warn", err.Error())
				continue
			}
			say("ok", "")
		default:
			if done[s.Do] {
				res.Skipped++
				say("skip", "уже сделано")
				continue
			}
			if wasHeld[s.Do] {
				if ok, known, _ := (&tvLook{tv: tv}).holds(s.Do); known && ok {
					res.Skipped++
					say("skip", "уже так, не меняю")
					continue
				}
				// Было так до установки, а сняли сами по ходу (права администратора перед обновлением): вернуть как было,
				// в журнал не писать: для отката этой установки это не изменение
				say("start", "")
				if _, err := runTVCmd(tv, s.Do); err != nil {
					return fail(fmt.Errorf("%s: %s: %w", s.Group, s.What, err))
				}
				res.Done++
				say("ok", "вернул как было до установки")
				continue
			}
			say("start", "")
			if err := journal(LedgerEntry{What: s.Group + ": " + s.What, Do: s.Do, Undo: s.Undo}); err != nil {
				return fail(fmt.Errorf("журнал не пишется, дальше не иду: %v", err))
			}
			if _, err := runTVCmd(tv, s.Do); err != nil {
				return fail(fmt.Errorf("%s: %s: %w", s.Group, s.What, err))
			}
			// Команда прошла, а держится ли изменение? Служба «Сторожа» после запуска поднимается не мгновенно, и ответ
			// «прошло» ещё не значит, что настройка встала: ещё одна попытка, потом честное предупреждение
			if ok, known, _ := (&tvLook{tv: tv}).holds(s.Do); known && !ok {
				time.Sleep(retryPause)
				if _, err := runTVCmd(tv, s.Do); errors.Is(err, errLost) {
					return fail(err)
				}
				if ok, _, detail := (&tvLook{tv: tv}).holds(s.Do); !ok {
					res.Done++
					res.Warnings++
					say("warn", "команда прошла, но изменение не держится: "+detail)
					continue
				}
			}
			res.Done++
			say("ok", "")
		}
	}
	res.Finished = true
	if s, err := loadSession(dir); err == nil {
		s.Finished, s.LastError = time.Now().Format("2006-01-02 15:04:05"), ""
		_ = saveSession(dir, s)
	}
	emit(Event{Status: "info", What: fmt.Sprintf("установка закончена: изменений %d, пропущено %d, предупреждений %d", res.Done, res.Skipped, res.Warnings)})
	return res
}

func mustShell(tv TV, cmd string) string {
	out, _ := tv.Shell(cmd, 20*time.Second)
	return out
}

// installedCertOK: стоящее на ТВ приложение подписано ключом выпуска (иначе поверх не встанет; переход по docs/release.md)
func installedCertOK(tv TV, pkg, want string) error {
	out, _ := tv.Shell("pm path "+pkg, 20*time.Second)
	remote := ""
	for _, ln := range strings.Split(out, "\n") {
		ln = strings.TrimSpace(ln)
		if strings.HasPrefix(ln, "package:") && strings.HasSuffix(ln, "base.apk") {
			remote = strings.TrimPrefix(ln, "package:")
		}
	}
	if remote == "" {
		return fmt.Errorf("не нашёл файл стоящего %s на ТВ", pkg)
	}
	tmp, err := os.MkdirTemp("", "hyde-cert-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	local := filepath.Join(tmp, "base.apk")
	if err := tv.Pull(remote, local); err != nil {
		return fmt.Errorf("не забрал %s с ТВ для сверки подписи: %v", pkg, err)
	}
	cert, err := certOf(local)
	if err != nil {
		return fmt.Errorf("подпись стоящего %s не читается: %v", pkg, err)
	}
	if cert != want {
		return fmt.Errorf("на ТВ стоит %s со своей подписью (%s…): выпуск поверх не встанет. Переход: docs/release.md, «Переход с личной сборки»", pkg, prefix(cert, 12))
	}
	return nil
}

// homeSwitch: «Дом» главным экраном, как home_switch в tools/tv.sh. disables отключаются до смены роли (TCL: иначе роль
// не меняется), ifNeeded после, только если роль сменилась, а «Домой» всё равно открывает главный экран Google TV
func homeSwitch(tv TV, journal func(LedgerEntry) error, cand string, disables, ifNeeded []string) error {
	out, _ := tv.Shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME "+cand, 30*time.Second)
	act := ""
	for _, ln := range strings.Split(out, "\n") {
		if ln = strings.TrimSpace(ln); strings.HasPrefix(ln, cand+"/") {
			act = ln
		}
	}
	if act == "" {
		return fmt.Errorf("у %s нет главного экрана: ничего не менял", cand)
	}
	prev := homeHolder(tv)
	if prev == cand {
		return nil
	}
	_, _ = tv.Shell("am start -n "+act, 30*time.Second)
	time.Sleep(homeWait)
	if top := topPackage(tv); top != cand {
		return fmt.Errorf("«Дом» не открылся (на экране %s): главный экран не менял", nonEmpty(top, "неизвестно"))
	}
	var off []string
	undo := func() {
		for i := len(off) - 1; i >= 0; i-- {
			_, _ = tv.Shell("pm enable --user 0 "+off[i], 30*time.Second)
		}
		if prev != "" {
			_, _ = tv.Shell("cmd role add-role-holder --user 0 android.app.role.HOME "+prev, 30*time.Second)
		}
	}
	disable := func(d string) error {
		if err := journal(LedgerEntry{What: "отключён " + d + " (вместе со сменой главного экрана на " + cand + ")", Do: "pm disable-user --user 0 " + d, Undo: "pm enable --user 0 " + d}); err != nil {
			undo()
			return err
		}
		if _, err := runTVCmd(tv, "pm disable-user --user 0 "+d); err != nil {
			if errors.Is(err, errLost) {
				return err
			}
			undo()
			return fmt.Errorf("не отключился %s: %v. Вернул как было", d, err)
		}
		off = append(off, d)
		return nil
	}
	for _, d := range disables {
		if err := disable(d); err != nil {
			return err
		}
	}
	roleUndo := ""
	if prev != "" {
		roleUndo = "cmd role add-role-holder --user 0 android.app.role.HOME " + prev
	}
	if err := journal(LedgerEntry{What: "главный экран: " + cand + " вместо " + nonEmpty(prev, "никого"), Do: "cmd package set-home-activity " + act, Undo: roleUndo}); err != nil {
		undo()
		return err
	}
	r, _ := tv.Shell("cmd package set-home-activity "+act, 30*time.Second)
	time.Sleep(homeWait)
	if tv.State() != "device" {
		return errLost
	}
	holder := homeHolder(tv)
	_, _ = tv.Shell("input keyevent KEYCODE_HOME", 20*time.Second)
	time.Sleep(homeWait)
	top := topPackage(tv)
	if holder == cand && top == cand {
		return nil
	}
	if tv.State() != "device" {
		return errLost
	}
	if holder == cand && len(ifNeeded) > 0 { // роль наша, но «Домой» перехватывает главный экран Google TV
		for _, d := range ifNeeded {
			if err := disable(d); err != nil {
				return err
			}
		}
		_, _ = tv.Shell("input keyevent KEYCODE_HOME", 20*time.Second)
		time.Sleep(homeWait)
		if top = topPackage(tv); top == cand {
			return nil
		}
		if tv.State() != "device" {
			return errLost
		}
	}
	undo()
	return fmt.Errorf("не вышло: ответ «%s», владелец %s, «Домой» открывает %s. Вернул как было", strings.TrimSpace(r), nonEmpty(holder, "никто"), nonEmpty(top, "неизвестно"))
}
