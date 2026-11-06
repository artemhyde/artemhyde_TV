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
	Install(apk string) (string, error) // adb install -r
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

func (t *adbTV) Install(apk string) (string, error) {
	return t.a.run(10*time.Minute, "-s", t.serial, "install", "-r", apk)
}

func (t *adbTV) Pull(remote, local string) error {
	_, err := t.a.run(5*time.Minute, "-s", t.serial, "pull", remote, local)
	return err
}

func (t *adbTV) State() string {
	out, _ := t.a.run(10*time.Second, "-s", t.serial, "get-state")
	return strings.TrimSpace(out)
}

// homeHolder: владелец роли «Домой»
func homeHolder(tv TV) string {
	out, _ := tv.Shell("cmd role get-role-holders --user 0 android.app.role.HOME", 20*time.Second)
	return strings.TrimSpace(firstLine(out))
}

var reTop = regexp.MustCompile(`topResumedActivity=ActivityRecord\{\S+ u\d+ ([^/ ]+)/`)

// topPackage: приложение, которое сейчас на экране
func topPackage(tv TV) string {
	out, _ := tv.Shell("dumpsys activity activities | grep topResumedActivity", 20*time.Second)
	if m := reTop.FindStringSubmatch(out); m != nil {
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
