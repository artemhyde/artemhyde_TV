package main

import (
	"path/filepath"
	"strings"
	"time"
)

// Проверка после установки (операция verify): по каждой строке журнала смотрит, держится ли изменение на ТВ, и жив ли
// «Сторож». Только чтение
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

func runVerify(tv TV, dir string) ([]Check, error) {
	entries, err := readLedger(filepath.Join(dir, "ledger.tsv"))
	if err != nil {
		return nil, err
	}
	var cs []Check
	sh := func(cmd string) string { out, _ := tv.Shell(cmd, time.Minute); return out }
	var whitelist, policy, disabled, listeners string
	guard := false
	for _, e := range entries {
		f := strings.Fields(e.Do)
		c := Check{What: e.What}
		switch {
		case reInstalled.MatchString(e.What):
			pkg := reInstalled.FindStringSubmatch(e.What)[2]
			c.OK = hasPackage(tv, pkg)
			if pkg == "app.glasstv.guard" {
				guard = true
			}
		case len(f) >= 4 && f[0] == "pm" && f[1] == "grant":
			c.OK = strings.Contains(sh("dumpsys package "+f[2]+" | grep '"+f[3]+": granted=true'"), "granted=true")
		case len(f) >= 5 && f[0] == "appops" && f[1] == "set":
			c.OK = strings.Contains(sh("appops get "+f[2]+" "+f[3]), "allow")
		case len(f) >= 4 && f[0] == "dumpsys" && f[1] == "deviceidle" && strings.HasPrefix(f[3], "+"):
			if whitelist == "" {
				whitelist = sh("dumpsys deviceidle whitelist")
			}
			c.OK = strings.Contains(whitelist, ","+strings.TrimPrefix(f[3], "+")+",")
		case len(f) >= 5 && f[0] == "dpm" && f[1] == "set-active-admin":
			if policy == "" {
				policy = sh("dumpsys device_policy")
			}
			c.OK = strings.Contains(policy, f[len(f)-1])
		case len(f) >= 4 && f[0] == "cmd" && f[1] == "notification" && f[2] == "allow_listener":
			if listeners == "" {
				listeners = sh("settings get secure enabled_notification_listeners")
			}
			c.OK = strings.Contains(listeners, f[3])
		case len(f) >= 5 && f[0] == "settings" && f[1] == "put":
			want := unquote(strings.Join(f[4:], " "))
			got := strings.TrimSpace(sh("settings get " + f[2] + " " + f[3]))
			c.OK, c.Detail = got == want, "сейчас "+got
		case len(f) >= 4 && f[0] == "cmd" && f[1] == "package" && f[2] == "set-home-activity":
			pkg, _, _ := strings.Cut(f[3], "/")
			got := homeHolder(tv)
			c.OK, c.Detail = got == pkg, "главный экран сейчас "+got
		case len(f) >= 3 && f[0] == "ime" && f[1] == "enable":
			want := strings.TrimSuffix(f[2], ";")
			got := strings.TrimSpace(sh("settings get secure default_input_method"))
			c.OK, c.Detail = got == want, "клавиатура сейчас "+got
		case len(f) >= 5 && f[0] == "pm" && f[1] == "disable-user":
			if disabled == "" {
				disabled = sh("pm list packages -d")
			}
			c.OK = strings.Contains(disabled, "package:"+f[len(f)-1]+"\n") || strings.HasSuffix(strings.TrimSpace(disabled), "package:"+f[len(f)-1])
		default:
			continue // запуск, ускорение, ночная подсветка: проверять нечего
		}
		if c.OK {
			c.Detail = ""
		}
		cs = append(cs, c)
	}
	if guard {
		out := sh("dumpsys activity services app.glasstv.guard")
		cs = append(cs, Check{What: "«Сторож» работает", OK: strings.Contains(out, "ServiceRecord")})
	}
	return cs, nil
}
