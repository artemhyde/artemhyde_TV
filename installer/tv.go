package main

import (
	"regexp"
	"strings"
	"time"
)

// TV: что установщику нужно от телевизора. Настоящий ходит через adb, в проверках подставной
type TV interface {
	Serial() string
	Shell(script string, timeout time.Duration) (string, error)
	Install(apk, installer string) (string, error) // adb install -r; installer непустой: от имени этого приложения (-i)
	Pull(remote, local string) error
	State() string // «device», если ТВ на связи
}

type adbTV struct {
	a      *ADB
	serial string
}

func (t *adbTV) Serial() string { return t.serial }

func (t *adbTV) Shell(script string, timeout time.Duration) (string, error) {
	return t.a.Shell(t.serial, script, timeout)
}

// Install: только основному пользователю (--user 0). У TCL есть служебный второй пользователь прошивки: приложение,
// поставленное всем, после отката «pm uninstall --user 0» осталось бы числиться за ним (07.11, настоящий TCL 65C6K)
func (t *adbTV) Install(apk, installer string) (string, error) {
	args := []string{"-s", t.serial, "install", "--user", "0", "-r"}
	if installer != "" {
		args = append(args, "-i", installer)
	}
	return t.a.run(10*time.Minute, append(args, apk)...)
}

func (t *adbTV) Pull(remote, local string) error {
	_, err := t.a.run(5*time.Minute, "-s", t.serial, "pull", remote, local)
	return err
}

func (t *adbTV) State() string {
	out, _ := t.a.run(10*time.Second, "-s", t.serial, "get-state")
	return strings.TrimSpace(out)
}

// homeHolderCmd: владелец главного экрана. Команда cmd role get-role-holders есть только с Android 13; на Android 12 она
// отвечает «Unknown command», и владелец берётся из dumpsys role (блок name=android.app.role.HOME, строка holders=).
// Проверено 07.11 на виртуальном Google TV 12 и на TCL с Android 14. На Android 9 службы ролей нет совсем (09.11,
// виртуальный Android TV 9): главный экран тот, к которому система ведёт кнопку «Домой» (resolve-activity). Если он
// не выбран, система отвечает окном выбора (пакет android): владельца нет
const homeHolderCmd = `h=$(cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null) || h=""; ` +
	`[ -n "$h" ] || h=$(dumpsys role 2>/dev/null | grep -A1 'name=android.app.role.HOME$' | sed -n 's/^ *holders=//p'); ` +
	`[ -n "$h" ] || h=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1 | grep / | cut -d/ -f1); ` +
	`[ "$h" = android ] && h=""; echo "$h"`

// homeActivity: экран «Домой» пакета pkg («пакет/активность») или пусто, если его нет
func homeActivity(tv TV, pkg string) string {
	out, _ := tv.Shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME "+pkg, 30*time.Second)
	act := ""
	for _, ln := range strings.Split(out, "\n") {
		if ln = strings.TrimSpace(ln); strings.HasPrefix(ln, pkg+"/") {
			act = ln
		}
	}
	return act
}

// hasRoles: есть ли служба ролей (Android 10 и новее). Без неё главный экран меняется только set-home-activity
func hasRoles(tv TV) bool {
	out, _ := tv.Shell("service check role", 20*time.Second)
	return strings.Contains(out, ": found")
}

// homeBackCmd: команда, которая вернёт главным экраном prev. Со службой ролей cmd role add-role-holder, как в 1.0.x;
// на Android 9 set-home-activity по его экрану «Домой». Пусто: вернуть нечем
func homeBackCmd(tv TV, prev string) string {
	if prev == "" {
		return ""
	}
	if hasRoles(tv) {
		return "cmd role add-role-holder --user 0 android.app.role.HOME " + prev
	}
	if act := homeActivity(tv, prev); act != "" {
		return "cmd package set-home-activity " + act
	}
	return ""
}

// homeHolder: владелец роли «Домой»
func homeHolder(tv TV) string {
	out, _ := tv.Shell(homeHolderCmd, 20*time.Second)
	return strings.TrimSpace(firstLine(out))
}

var reTop = regexp.MustCompile(`topResumedActivity=ActivityRecord\{\S+ u\d+ ([^/ ]+)/`)

// Android 12 строки topResumedActivity не пишет, только «ResumedActivity: ActivityRecord{…}» (проверено 07.11 на
// виртуальном Google TV 12: без этого установщик не видел «Дом» на экране и не менял главный экран)
var reResumed = regexp.MustCompile(`ResumedActivity: ActivityRecord\{\S+ u\d+ ([^/ ]+)/`)

// topPackage: приложение, которое сейчас на экране
func topPackage(tv TV) string {
	out, _ := tv.Shell("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity:'", 20*time.Second)
	if m := reTop.FindStringSubmatch(out); m != nil {
		return m[1]
	}
	if m := reResumed.FindStringSubmatch(out); m != nil {
		return m[1]
	}
	return ""
}

func firstLine(s string) string {
	s, _, _ = strings.Cut(strings.TrimLeft(s, "\n"), "\n")
	return s
}

// hasPackage: стоит ли пакет (pm path отвечает «package:…»)
func hasPackage(tv TV, pkg string) bool {
	out, _ := tv.Shell("pm path "+pkg, 20*time.Second)
	return strings.Contains(out, "package:")
}
