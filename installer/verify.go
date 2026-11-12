package main

import (
	"path/filepath"
	"strings"
	"time"
)

// Проверка после установки (операция verify): по каждой строке журнала смотрит, держится ли изменение на ТВ, и жив ли
// «Сторож». Только чтение. Та же проверка нужна установке: что на ТВ уже так, она не выполняет заново и не пишет
// в журнал (обновление поверх стоящего выпуска), иначе откат одного обновления отнял бы права, бывшие до него
type Check struct {
	What   string `json:"what"`
	OK     bool   `json:"ok"`
	Detail string `json:"detail,omitempty"`
}

func unquote(s string) string {
	s = strings.TrimSpace(s)
	if len(s) >= 2 && s[0] == '\'' && s[len(s)-1] == '\'' {
		s = strings.ReplaceAll(s[1:len(s)-1], `'\''`, "'")
	}
	return s
}

// tvLook: чтение состояния ТВ с запоминанием длинных ответов на одну проверку
type tvLook struct {
	tv                                     TV
	whitelist, policy, disabled, listeners string
}

func (l *tvLook) sh(cmd string) string {
	out, _ := l.tv.Shell(cmd, time.Minute)
	return out
}

// holds: держится ли изменение из команды. known=false: такую команду проверить нечем (запуск, ускорение, ночная подсветка)
func (l *tvLook) holds(do string) (ok, known bool, detail string) {
	f := strings.Fields(do)
	switch {
	case len(f) >= 4 && f[0] == "pm" && f[1] == "grant":
		return strings.Contains(l.sh("dumpsys package "+f[2]+" | grep '"+f[3]+": granted=true'"), "granted=true"), true, ""
	case len(f) >= 5 && f[0] == "appops" && f[1] == "set":
		got := strings.TrimSpace(l.sh("appops get " + f[2] + " " + f[3]))
		if unknownOp(got) {
			return false, false, "" // прошивка не знает такого разрешения: проверять нечего (журналы установщика 1.2.0)
		}
		return strings.Contains(got, ": "+f[4]), true, "сейчас " + firstLine(got)
	case len(f) >= 4 && f[0] == "dumpsys" && f[1] == "deviceidle" && strings.HasPrefix(f[3], "+"):
		if l.whitelist == "" {
			l.whitelist = l.sh("dumpsys deviceidle whitelist")
		}
		return strings.Contains(l.whitelist, ","+strings.TrimPrefix(f[3], "+")+","), true, ""
	case len(f) >= 4 && f[0] == "dpm" && f[1] == "set-active-admin":
		if l.policy == "" {
			l.policy = l.sh("dumpsys device_policy")
		}
		return strings.Contains(l.policy, f[len(f)-1]), true, ""
	case len(f) >= 4 && f[0] == "cmd" && f[1] == "notification" && f[2] == "allow_listener":
		if l.listeners == "" {
			l.listeners = l.sh("settings get secure enabled_notification_listeners")
		}
		return strings.Contains(l.listeners, f[3]), true, ""
	case len(f) >= 5 && f[0] == "settings" && f[1] == "put":
		want := unquote(strings.Join(f[4:], " "))
		got := strings.TrimSpace(l.sh("settings get " + f[2] + " " + f[3]))
		return got == want, true, "сейчас " + got
	case len(f) >= 4 && f[0] == "cmd" && f[1] == "package" && f[2] == "set-home-activity":
		pkg, _, _ := strings.Cut(f[3], "/")
		got := homeHolder(l.tv)
		return got == pkg, true, "главный экран сейчас " + got
	case len(f) >= 3 && f[0] == "ime" && f[1] == "enable":
		want := strings.TrimSuffix(f[2], ";")
		got := strings.TrimSpace(l.sh("settings get secure default_input_method"))
		return got == want, true, "клавиатура сейчас " + got
	case len(f) == 10 && f[0] == "am" && f[1] == "broadcast" && f[2] == "-n" && strings.HasSuffix(f[3], "/.Cmd") && f[6] == "nighthours" && f[8] == "enabled":
		// Ночная подсветка «Сторожа»: тот же приёмник без параметров только отвечает («ночная подсветка выключена»
		// или «ночная подсветка с 22:00 до 7:00»)
		got := l.sh("am broadcast -n " + f[3] + " --es do nighthours")
		off, on := strings.Contains(got, "ночная подсветка выключена"), strings.Contains(got, "ночная подсветка с ")
		if !off && !on {
			return false, true, "«Сторож» не ответил: " + lastLine(got)
		}
		return off == (f[9] == "false"), true, map[bool]string{true: "сейчас выключена", false: "сейчас включена"}[off]
	case len(f) >= 5 && f[0] == "pm" && f[1] == "disable-user":
		if l.disabled == "" {
			l.disabled = l.sh("pm list packages -d")
		}
		p := "package:" + f[len(f)-1]
		for _, ln := range strings.Split(l.disabled, "\n") {
			if strings.TrimSpace(ln) == p {
				return true, true, ""
			}
		}
		return false, true, ""
	}
	return false, false, ""
}

// runVerify: проверка по журналам установок этого ТВ (от новой к старой). Одна и та же команда в нескольких журналах
// (обновление после установки) проверяется один раз
func runVerify(tv TV, dirs ...string) ([]Check, error) {
	var cs []Check
	l := &tvLook{tv: tv}
	seen := map[string]bool{}
	guard := false
	for _, dir := range dirs {
		entries, err := readLedger(filepath.Join(dir, "ledger.tsv"))
		if err != nil {
			return nil, err
		}
		for _, e := range entries {
			c := Check{What: e.What}
			if m := reInstalled.FindStringSubmatch(e.What); m != nil {
				pkg := m[2]
				if seen["pkg "+pkg] {
					continue
				}
				seen["pkg "+pkg] = true
				c.OK = hasPackage(tv, pkg)
				if pkg == "app.glasstv.guard" {
					guard = true
				}
				cs = append(cs, c)
				continue
			}
			if seen[e.Do] {
				continue
			}
			ok, known, detail := l.holds(e.Do)
			if !known {
				continue
			}
			seen[e.Do] = true
			c.OK = ok
			if !ok {
				c.Detail = detail
			}
			cs = append(cs, c)
		}
	}
	if guard {
		out := l.sh("dumpsys activity services app.glasstv.guard")
		cs = append(cs, Check{What: "«Сторож» работает", OK: strings.Contains(out, "ServiceRecord")})
	}
	return cs, nil
}
