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

// homeHolderCmd: владелец роли «Домой». Команда cmd role get-role-holders есть только с Android 13; на Android 12 она
// отвечает «Unknown command», и владелец берётся из dumpsys role (блок name=android.app.role.HOME, строка holders=).
// Проверено 07.11 на виртуальном Google TV 12 и на TCL с Android 14
const homeHolderCmd = `h=$(cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null) && echo "$h" || dumpsys role | grep -A1 'name=android.app.role.HOME$' | sed -n 's/^ *holders=//p'`

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
