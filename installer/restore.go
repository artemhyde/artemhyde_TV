package main

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Откат по журналу (операция restore), та же логика, что у tools/make-restore.sh и собранного им restore.sh:
// обратные команды от последней к первой; возврат владельца главного экрана после последнего pm enable (на TCL иначе не
// пройдёт); команда не прошла при живой связи: ещё попытка; приложения уже нет: «отменять нечего», не ошибка; связь
// пропала: дальше ничего не выполняется. В конце сверка со снимком «до» настроек, которые меняются мимо журнала.
// Отладку по сети не трогаем: через неё идёт сам откат

type RestoreResult struct {
	Done    int      `json:"done"`
	Gone    int      `json:"gone"`
	Fail    int      `json:"fail"`
	Left    int      `json:"left"`
	Lost    bool     `json:"lost"`
	Manual  []string `json:"manual"`
	Warns   []string `json:"warns"`
	Changed []string `json:"changed"` // настройки, возвращённые по снимку
	Error   string   `json:"error,omitempty"`
}

// restoreOrder: обратные команды в порядке отката и записи «делать руками»
func restoreOrder(entries []LedgerEntry) (undo, manual []LedgerEntry) {
	for i := len(entries) - 1; i >= 0; i-- {
		e := entries[i]
		if e.Undo == "" || e.Undo == "-" {
			continue
		}
		undo = append(undo, e)
	}
	for _, e := range entries {
		if e.Undo == "" || e.Undo == "-" {
			manual = append(manual, e)
		}
	}
	last := -1
	for i, e := range undo {
		if strings.HasPrefix(e.Undo, "pm enable ") {
			last = i
		}
	}
	if last < 0 {
		return undo, manual
	}
	var out, roles []LedgerEntry
	for i, e := range undo {
		if strings.Contains(e.Undo, "cmd role add-role-holder") && i < last {
			roles = append(roles, e)
			continue
		}
		out = append(out, e)
		if i == last {
			out = append(out, roles...)
		}
	}
	return out, manual
}

// restoreWarnings: удаление приложения с правами администратора без их снятия вешает систему; Key Mapper откатом не удаляют
func restoreWarnings(entries, undo []LedgerEntry) []string {
	admins := map[string]bool{}
	for _, e := range entries {
		f := strings.Fields(e.Do)
		for i, w := range f {
			if w == "set-active-admin" && i+1 < len(f) {
				p := f[len(f)-1]
				pkg, _, _ := strings.Cut(p, "/")
				admins[pkg] = true
			}
		}
	}
	unadmin := map[string]bool{}
	var w []string
	for _, e := range undo {
		if strings.Contains(e.Undo, "unadmin") {
			for p := range admins {
				if strings.Contains(e.Undo, p) {
					unadmin[p] = true
				}
			}
		}
		if pkg := pkgOf(e.Undo); strings.HasPrefix(e.Undo, "pm uninstall") && pkg != "" {
			if admins[pkg] && !unadmin[pkg] {
				w = append(w, "перед удалением "+pkg+" в откате нет снятия прав администратора: система зависнет примерно на 70 секунд")
			}
			if pkg == "io.github.sds100.keymapper" {
				w = append(w, "Key Mapper откатом не удаляется: его права администратора снимаются только руками (docs/rollback.md)")
			}
		}
	}
	return w
}

// pkgOf: приложение, к которому относится обратная команда (как pkg_of в restore.sh)
func pkgOf(cmd string) string {
	f := strings.Fields(cmd)
	switch {
	case len(f) >= 3 && f[0] == "appops" && f[1] == "set", len(f) >= 3 && f[0] == "pm" && (f[1] == "revoke" || f[1] == "grant"):
		return f[2]
	case len(f) >= 5 && f[0] == "pm" && f[1] == "uninstall" && f[2] == "--user":
		return f[4]
	case len(f) >= 3 && f[0] == "pm" && f[1] == "uninstall":
		return f[2]
	case len(f) >= 4 && f[0] == "cmd" && f[1] == "notification":
		p, _, _ := strings.Cut(f[3], "/")
		return p
	case len(f) >= 4 && f[0] == "dumpsys" && f[1] == "deviceidle":
		return strings.TrimLeft(f[3], "+-")
	case len(f) >= 4 && f[0] == "am" && f[1] == "start" && f[2] == "-n":
		p, _, _ := strings.Cut(f[3], "/")
		return p
	}
	return ""
}

// Настройки, которые приложения меняют сами, мимо журнала (как RUNTIME_KEYS в make-restore.sh)
var runtimeKeys = [][3]string{
	{"secure", "selected_input_method_subtype", "язык штатной клавиатуры"},
	{"secure", "input_methods_subtype_history", "история переключения клавиатур"},
	{"global", "private_dns_mode", "частный DNS"},
	{"global", "private_dns_specifier", "адрес частного DNS"},
	{"secure", "enabled_accessibility_services", "службы спецвозможностей"},
	{"secure", "accessibility_enabled", "спецвозможности"},
	{"secure", "screensaver_enabled", "заставка Android"},
	{"system", "screen_off_timeout", "через сколько гаснет экран"},
}

// readSettingsList: «key=value» из settings-<ns>.txt снимка; null значит «не было»
func readSettingsList(path string) (map[string]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	m := map[string]string{}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<16), 1<<20)
	for sc.Scan() {
		if k, v, ok := strings.Cut(strings.TrimRight(sc.Text(), "\r"), "="); ok && v != "null" {
			if _, dup := m[k]; !dup {
				m[k] = v
			}
		}
	}
	return m, sc.Err()
}

func runRestore(tv TV, dir string, emit func(Event)) (r RestoreResult) {
	if emit == nil {
		emit = func(Event) {}
	}
	entries, err := readLedger(filepath.Join(dir, "ledger.tsv"))
	if err != nil {
		r.Error = "журнал не читается: " + err.Error()
		return r
	}
	undo, manual := restoreOrder(entries)
	for _, e := range manual {
		r.Manual = append(r.Manual, e.What)
	}
	r.Warns = restoreWarnings(entries, undo)
	if tv.State() != "device" {
		r.Lost, r.Left = true, len(undo)
		r.Error = "ТВ не на связи: откат не начат, на ТВ ничего не менялось"
		return r
	}
	run := func(ev Event, cmd string) bool {
		ev.Status = "start"
		emit(ev)
		out, err := tv.Shell(cmd, 2*time.Minute)
		if !cmdOK(out, err) && tv.State() == "device" {
			time.Sleep(retryPause)
			out, err = tv.Shell(cmd, 2*time.Minute)
		}
		switch {
		case cmdOK(out, err):
			r.Done++
			ev.Status = "ok"
		case tv.State() != "device":
			r.Lost = true
			ev.Status, ev.Detail = "lost", "связь с ТВ пропала: дальше ничего не выполняю"
		default:
			if p := pkgOf(cmd); p != "" && !hasPackage(tv, p) {
				r.Gone++
				ev.Status, ev.Detail = "skip", p+" на ТВ уже нет: отменять нечего"
			} else {
				r.Fail++
				ev.Status, ev.Detail = "fail", nonEmpty(lastLine(out), "не прошло")
			}
		}
		emit(ev)
		return !r.Lost
	}
	for i, e := range undo {
		if !run(Event{N: i + 1, Total: len(undo), Group: "вернуть", What: e.What}, e.Undo) {
			r.Left = len(undo) - i
			break
		}
	}
	if !r.Lost {
		for _, k := range runtimeKeys {
			snap, err := readSettingsList(filepath.Join(dir, "settings-"+k[0]+".txt"))
			if err != nil {
				r.Warns = append(r.Warns, "в снимке нет settings-"+k[0]+".txt: "+k[2]+" откат не сверил")
				continue
			}
			was, had := snap[k[1]]
			cur := strings.TrimSpace(mustShell(tv, "settings get "+k[0]+" "+k[1]))
			switch {
			case had && cur == was, !had && cur == "null":
				continue
			case had:
				r.Changed = append(r.Changed, fmt.Sprintf("%s: было «%s», вернул «%s»", k[2], cur, was))
				run(Event{Group: "сверка со снимком", What: k[2]}, fmt.Sprintf("settings put %s %s %s", k[0], k[1], shq(was)))
			default:
				r.Changed = append(r.Changed, fmt.Sprintf("%s: «%s», до установки не было, убрал", k[2], cur))
				run(Event{Group: "сверка со снимком", What: k[2]}, fmt.Sprintf("settings delete %s %s", k[0], k[1]))
			}
		}
	}
	if !r.Lost {
		// Возвращённые права сразу на диск: Android пишет их с задержкой до 30 минут (как и при установке, plan.go)
		_, _ = runTVCmd(tv, "appops write-settings")
	}
	if !r.Lost && r.Fail == 0 {
		if s, err := loadSession(dir); err == nil {
			s.Restored = time.Now().Format("2006-01-02 15:04:05")
			_ = saveSession(dir, s)
		}
	}
	return r
}

// RestoreAll: откат нескольких установок этого ТВ от новой к старой (обновление, потом установка). Связь пропала или
// откат не начат: дальше не идём; отдельные непрошедшие команды не останавливают (как restore.sh)
type RestoreAll struct {
	Sessions []string        `json:"sessions"`
	Results  []RestoreResult `json:"results"`
}

func runRestoreAll(tv TV, dirs []string, emit func(Event)) RestoreAll {
	if emit == nil {
		emit = func(Event) {}
	}
	var ra RestoreAll
	for _, d := range dirs {
		if len(dirs) > 1 {
			emit(Event{Status: "info", What: "откат установки " + filepath.Base(d)})
		}
		r := runRestore(tv, d, emit)
		ra.Sessions, ra.Results = append(ra.Sessions, d), append(ra.Results, r)
		if r.Lost || r.Error != "" {
			break
		}
	}
	return ra
}

// Code: код выхода как у restore.sh: 0 всё вернулось, 1 часть команд не прошла, 3 связь пропала или откат не начат
func (ra RestoreAll) Code() int {
	code := 0
	for _, r := range ra.Results {
		switch {
		case r.Lost || r.Error != "":
			return 3
		case r.Fail > 0:
			code = 1
		}
	}
	return code
}

// OK: всё вернулось
func (ra RestoreAll) OK() bool { return len(ra.Results) > 0 && ra.Code() == 0 }

func (ra RestoreAll) Text() string {
	if len(ra.Results) == 1 {
		return ra.Results[0].Text()
	}
	var b strings.Builder
	for i, r := range ra.Results {
		fmt.Fprintf(&b, "Установка %s:\n%s", filepath.Base(ra.Sessions[i]), r.Text())
	}
	return b.String()
}

func (r RestoreResult) Text() string {
	var b strings.Builder
	for _, w := range r.Warns {
		b.WriteString("ВНИМАНИЕ: " + w + "\n")
	}
	switch {
	case r.Error != "":
		b.WriteString("ОТКАТ НЕ НАЧАТ: " + r.Error + "\n")
	case r.Lost:
		fmt.Fprintf(&b, "ОТКАТ НЕ ЗАКОНЧЕН: связь с ТВ пропала, не выполнено команд: %d. Подключитесь заново и запустите откат ещё раз: повтор безвреден.\n", r.Left)
	case r.Fail > 0:
		fmt.Fprintf(&b, "ОТКАТ ВЫПОЛНЕН НЕ ПОЛНОСТЬЮ: не прошло команд: %d. Проверьте пульт, главный экран, HDMI и звук.\n", r.Fail)
	default:
		fmt.Fprintf(&b, "Готово: откат прошёл (команд %d, уже не нужно %d).\n", r.Done, r.Gone)
	}
	for _, c := range r.Changed {
		b.WriteString("  " + c + "\n")
	}
	if len(r.Manual) > 0 {
		b.WriteString("Откатом не покрывается, делайте руками:\n")
		for _, m := range r.Manual {
			b.WriteString("  " + m + "\n")
		}
	}
	return b.String()
}
