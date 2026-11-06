package main

import (
	"errors"
	"fmt"
	"regexp"
	"time"
)

// Выключатели «Сторожа» после установки (план Codex, этап 2, шаг 9 мастера: режим обслуживания по выбору человека
// с проверкой фактического состояния). Отладка по сети: always держит её всегда (нужна для обновлений и отката с этого
// компьютера), service держит ещё 2 часа и выключает. Записки с телефона: on или off. Пустой set: только прочитать.
// В ответе «Сторожа» нет кода записок: код человек видит на экране ТВ. Это не изменение из журнала: откат его не трогает
var (
	reData      = regexp.MustCompile(`data="([^"]*)"`)
	reNotesCode = regexp.MustCompile(`,? код \d+`)
)

func guardSwitch(tv TV, what, set string) (string, error) {
	var cmd string
	switch what {
	case "adb":
		cmd = "am broadcast -n app.glasstv.guard/.Cmd --es do adb"
		switch set {
		case "":
		case "always", "service":
			cmd += " --es m " + set
		default:
			return "", errors.New("режим отладки: always (держать всегда) или service (ещё 2 часа, потом выключить)")
		}
	case "notes":
		cmd = "am broadcast -n app.glasstv.guard/.Cmd --es do notes"
		switch set {
		case "":
		case "on":
			cmd += " --ez on true"
		case "off":
			cmd += " --ez on false"
		default:
			return "", errors.New("записки: on или off")
		}
	default:
		return "", errors.New("выключатели «Сторожа»: adb или notes")
	}
	out, err := tv.Shell(cmd, 30*time.Second)
	m := reData.FindStringSubmatch(out)
	if m == nil {
		if err == nil {
			err = errors.New(nonEmpty(lastLine(out), "без ответа"))
		}
		return "", fmt.Errorf("«Сторож» не ответил (стоит ли он и запущена ли его служба?): %v", err)
	}
	return reNotesCode.ReplaceAllString(m[1], ""), nil
}
