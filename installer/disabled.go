package main

import (
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// Пакеты, отключённые установщиком, для «Сторожа» (1.2.0; договор с сессией «Сторожа» 10.11). Обновление прошивки TCL умеет
// включать их обратно; «Сторож» это замечает и пишет в суточном отчёте (reenabled). Список берётся из журналов этого ТВ:
// все `pm disable-user --user 0 <пакет>` в неоткаченных установках, отключённое человеком до нас в журнал не попадает.
// Команда: do disabled --es list a,b (всегда полный список, до 200; пустой: списка нет). Ответ: «отключённых установщиком N,
// снова включено M[, имена]». «Сторож» до 1.2.0 команды не знает (ответа с data нет): пропустить молча.
// Шлётся в конце каждой установки (и когда всё уже так) и после отката, если «Сторож» остался на ТВ (откат одной установки)

const disabledMax = 200

var (
	rePkgName       = regexp.MustCompile(`^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+$`)
	reDisabledReply = regexp.MustCompile(`^отключённых установщиком (\d+), снова включено (\d+)(?:, (.+))?$`)
)

// disabledByInstaller: отключённые установщиком пакеты из журналов этих установок, по алфавиту, без повторов, до 200
func disabledByInstaller(dirs []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, d := range dirs {
		es, err := readLedger(filepath.Join(d, "ledger.tsv"))
		if err != nil {
			continue
		}
		for _, e := range es {
			f := strings.Fields(e.Do)
			if len(f) == 5 && f[0] == "pm" && f[1] == "disable-user" && f[2] == "--user" && f[3] == "0" && rePkgName.MatchString(f[4]) && !seen[f[4]] {
				seen[f[4]] = true
				out = append(out, f[4])
			}
		}
	}
	sort.Strings(out)
	if len(out) > disabledMax {
		out = out[:disabledMax]
	}
	return out
}

// guardDisabledCmd: команда «Сторожу» с полным списком; пустой список уходит как --es list ""
func guardDisabledCmd(list []string) string {
	v := strings.Join(list, ",")
	if v == "" {
		v = `""`
	}
	return "am broadcast -n app.glasstv.guard/.Cmd --es do disabled --es list " + v
}

// guardDisabled: передать «Сторожу» список по журналам dirs (неоткаченные установки этого ТВ). «Сторожа» нет: некому
func guardDisabled(tv TV, dirs []string, emit func(Event)) {
	if !hasPackage(tv, "app.glasstv.guard") {
		return
	}
	out, _ := tv.Shell(guardDisabledCmd(disabledByInstaller(dirs)), 20*time.Second)
	data := ""
	if m := reData.FindStringSubmatch(out); m != nil {
		data = strings.TrimSpace(m[1])
	}
	m := reDisabledReply.FindStringSubmatch(data)
	if m == nil {
		return // «Сторож» старше 1.2.0: следить за отключёнными пакетами не умеет
	}
	if m[2] != "0" {
		emit(Event{Status: "warn", Group: "«Сторож»", What: "снова включены пакеты, которые отключал установщик (так бывает после обновления прошивки): " + m[3],
			Detail: data})
		return
	}
	if m[1] != "0" {
		emit(Event{Status: "info", Group: "«Сторож»", What: "«Сторож» следит, чтобы отключённые установщиком пакеты не включились снова: " + m[1]})
	}
}

// guardDisabledAfterRestore: после отката список оставшихся неоткаченных установок этого ТВ (ТВ тот же, папка журналов та же)
func guardDisabledAfterRestore(tv TV, dirs []string, emit func(Event)) {
	if len(dirs) == 0 {
		return
	}
	s, err := loadSession(dirs[0])
	if err != nil || s.TVID == "" {
		return
	}
	guardDisabled(tv, sessionsOf(filepath.Dir(dirs[0]), s.TVID, true), emit)
}
