package main

import (
	"strings"
	"testing"
	"time"
)

// scriptTV: ТВ с ответами по сценарию: команда → ответы по очереди (последний повторяется)
type scriptTV struct {
	replies map[string][]string
	ran     []string
}

func (s *scriptTV) Serial() string                           { return "script" }
func (s *scriptTV) State() string                            { return "device" }
func (s *scriptTV) Install(apk, inst string) (string, error) { return "", nil }
func (s *scriptTV) Pull(remote, local string) error          { return nil }
func (s *scriptTV) Shell(cmd string, _ time.Duration) (string, error) {
	s.ran = append(s.ran, cmd)
	q := s.replies[cmd]
	if len(q) == 0 {
		return "", nil
	}
	r := q[0]
	if len(q) > 1 {
		s.replies[cmd] = q[1:]
	}
	return r, nil
}

func bcast(data string) string { return `Broadcast completed: result=0, data="` + data + `"` }

// Путь Б: «Разрешить» для «Сторожа» только на Android 9, 10 и 11, только с обновлениями; итог словами в ходе установки
func TestGuardAdbSelf(t *testing.T) {
	adbSelfPoll, adbSelfWait, adbSelfSettle = time.Millisecond, 50*time.Millisecond, time.Millisecond
	defer func() { adbSelfPoll, adbSelfWait, adbSelfSettle = 3*time.Second, 150*time.Second, 2*time.Second }()
	pair := adbSelfCmd + " --es a pair"
	guard := map[string][]string{"pm path app.glasstv.guard": {"package:/data/app/app.glasstv.guard/base.apk"}}
	run := func(sdk int, c Consent, status []string, pairReply string) (*scriptTV, []Event) {
		r := map[string][]string{adbSelfCmd: status, pair: {bcast(pairReply)}}
		for k, v := range guard {
			r[k] = v
		}
		tv := &scriptTV{replies: r}
		var evs []Event
		guardAdbSelf(tv, sdk, c, func(e Event) { evs = append(evs, e) })
		return tv, evs
	}
	ran := func(tv *scriptTV, cmd string) int {
		n := 0
		for _, c := range tv.ran {
			if c == cmd {
				n++
			}
		}
		return n
	}
	on := defaultConsent()
	wait := bcast("жду «Разрешить» на экране ТВ")

	// первый раз: просьба человеку, «Разрешить», итог ok
	tv, evs := run(28, on, []string{bcast("отладку ещё не пробовал"), wait, wait, bcast("через отладку ТВ; последний вход 10 нояб., 18:00")},
		"жду «Разрешить» на экране ТВ (до 2 минут), итог: --es do adbself")
	if ran(tv, pair) != 1 || len(evs) != 2 || evs[0].Status != "ask" || evs[0].What != adbSelfAsk || evs[1].Status != "ok" {
		t.Fatalf("первый раз: %+v, команды %q", evs, tv.ran)
	}
	// уже разрешено: не спрашивает
	tv, evs = run(29, on, []string{bcast("через отладку ТВ; последний вход 10 нояб., 18:00")}, "")
	if ran(tv, pair) != 0 || len(evs) != 1 || evs[0].Status != "info" {
		t.Fatalf("уже разрешено: %+v", evs)
	}
	// отказ: итог словами, установщик обновит сам
	tv, evs = run(30, on, []string{bcast("отладку ещё не пробовал"), wait, bcast("отладка «Сторожу» не разрешена")}, "жду «Разрешить» на экране ТВ")
	if ran(tv, pair) != 1 || len(evs) != 2 || evs[1].Status != "info" || !strings.Contains(evs[1].What, "установщик") || evs[1].Detail != "отладка «Сторожу» не разрешена" {
		t.Fatalf("отказ: %+v", evs)
	}
	// никто не нажал: после ожидания итог без зависания
	tv, evs = run(30, on, []string{bcast("отладку ещё не пробовал"), wait}, "жду «Разрешить» на экране ТВ")
	if len(evs) != 2 || evs[0].Status != "ask" || !strings.Contains(evs[1].What, "не нажали") {
		t.Fatalf("молчание: %+v", evs)
	}
	// «жду» на pair, а через пару секунд порт закрыт (так на виртуальном Android 9 со «Сторожем» из выпуска): без просьбы
	tv, evs = run(28, on, []string{bcast("отладку ещё не пробовал"), bcast("отладка ТВ выключена; порт 5555 закрыт")},
		"жду «Разрешить» на экране ТВ (до 2 минут), итог: --es do adbself")
	if ran(tv, pair) != 1 || len(evs) != 1 || evs[0].Status != "info" || !strings.Contains(evs[0].Detail, "порт 5555 закрыт") {
		t.Fatalf("порт закрыт после pair: %+v", evs)
	}
	// порт отладки закрыт (приставка по кабелю): итог сразу, без просьбы
	tv, evs = run(28, on, []string{bcast("отладка ТВ выключена")}, "отладка ТВ выключена; порт 5555 закрыт")
	if ran(tv, pair) != 1 || len(evs) != 1 || evs[0].Status != "info" {
		t.Fatalf("порт закрыт: %+v", evs)
	}
	// «Сторож» до 1.2.0 команды не знает, Android 12 и новее, обновления выключены, «Сторожа» нет: ничего не делается
	for _, c := range []struct {
		name   string
		sdk    int
		c      Consent
		status string
	}{
		{"старый «Сторож»", 28, on, "Broadcast completed: result=0"},
		{"Android 12+", 31, on, bcast("не нужно: Android 12 и новее обновляет без отладки")},
		{"обновления выключены", 28, Consent{Reports: true}, bcast("отладку ещё не пробовал")},
	} {
		tv, evs = run(c.sdk, c.c, []string{c.status}, "жду")
		if ran(tv, pair) != 0 || len(evs) != 0 {
			t.Fatalf("%s: события %+v, команды %q", c.name, evs, tv.ran)
		}
	}
	tv = &scriptTV{replies: map[string][]string{}}
	guardAdbSelf(tv, 28, on, func(Event) { t.Fatal("без «Сторожа» событий быть не должно") })
	if ran(tv, adbSelfCmd) != 0 {
		t.Fatalf("без «Сторожа» команды ему: %q", tv.ran)
	}
}
