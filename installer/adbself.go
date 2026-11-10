package main

import (
	"strings"
	"time"
)

// Путь Б для Android 9, 10 и 11 (1.2.0; решение автора, передано сессией «Сторожа» 10.11). Эти версии не дают ставить
// приложения без нажатий, поэтому «Сторож» ставит обновления HYDE через adb своего же ТВ (127.0.0.1:5555). Его ключу
// нужно одно «Разрешить» на экране ТВ: установщик просит его в конце установки, если обновления включены и «Сторож»
// стоит (do adbself a=pair, затем опрос do adbself до итога; «Сторож» ждёт человека до 2 минут). Не разрешили, порт
// отладки закрыт (приставка по кабелю) или «Сторож» старше 1.2.0: новые версии HYDE ставит установщик, как раньше, и в
// этот запуск он больше не спрашивает. В журнал это не пишется: разрешение живёт в ключе «Сторожа» и уходит вместе с ним

const adbSelfCmd = "am broadcast -n app.glasstv.guard/.Cmd --es do adbself"

// adbSelfAsk: что сделать человеку у ТВ. Порядок кнопок проверен на Android 9, 10 и 11: фокус на галочке «Всегда
// разрешать», вниз ведёт на «Отмена», вправо на кнопку справа (на 9 и 10 «OK», на 11 «Разрешить»): поэтому «на кнопку справа»
const adbSelfAsk = "На экране телевизора ещё раз появится «Разрешить отладку по USB?». Это «Сторож»: так новые версии HYDE " +
	"будут вставать сами. Фокус уже стоит на «Всегда разрешать»: нажмите OK, потом вниз, вправо на кнопку справа и ещё раз OK."

var (
	adbSelfWait   = 150 * time.Second // «Сторож» ждёт «Разрешить» 2 минуты, сверху запас на опрос
	adbSelfPoll   = 3 * time.Second
	adbSelfSettle = 2 * time.Second // после pair: закрытый порт «Сторож» видит сразу, тогда просить человека не о чем
)

// adbSelfState: ответ «Сторожа» на do adbself словом и сам текст. ok: ставит через отладку ТВ; wait: ждёт «Разрешить»;
// fresh: ещё не пробовал; no: не разрешили, отладка ТВ выключена или недоступна, на Android 12+ не нужно; пусто: команды
// не знает («Сторож» до 1.2.0) или ответ непонятен
func adbSelfState(out string) (string, string) {
	data := ""
	if m := reData.FindStringSubmatch(out); m != nil {
		data = strings.TrimSpace(m[1])
	}
	switch {
	case strings.HasPrefix(data, "через отладку ТВ"):
		return "ok", data
	case strings.HasPrefix(data, "жду"):
		return "wait", data
	case strings.HasPrefix(data, "отладку ещё не пробовал"):
		return "fresh", data
	case strings.HasPrefix(data, "отладка «Сторожу»"), strings.HasPrefix(data, "отладка ТВ"), strings.HasPrefix(data, "не нужно"):
		return "no", data
	}
	return "", data
}

// guardAdbSelf: шаг пути Б в конце установки. sdk из паспорта ТВ; только Android 9, 10 и 11, только при включённых
// обновлениях и стоящем «Стороже». Уже разрешено: не спрашивает. Прошлый отказ или закрытый порт спрашивает заново:
// человек мог передумать, а порт открыться
func guardAdbSelf(tv TV, sdk int, c Consent, emit func(Event)) {
	if sdk <= 0 || sdk >= 31 || !c.Updates || !hasPackage(tv, "app.glasstv.guard") {
		return
	}
	const group = "«Сторож»"
	st, data := adbSelfState(mustShell(tv, adbSelfCmd))
	switch st {
	case "ok":
		emit(Event{Status: "info", Group: group, What: "новые версии HYDE «Сторож» уже ставит сам, через отладку ТВ"})
		return
	case "":
		return // «Сторож» старше 1.2.0: новые версии ставит установщик, как раньше
	case "no":
		if strings.HasPrefix(data, "не нужно") {
			return
		}
	}
	if st != "wait" { // идёт прежний запрос: второй не нужен, ждём его итог
		if st, data = adbSelfState(mustShell(tv, adbSelfCmd+" --es a pair")); st != "wait" {
			adbSelfDone(st, data, group, emit)
			return
		}
		// «Сторож» отвечает «жду» сразу, а вход идёт отдельно: порт закрыт, и окна на ТВ не будет. Просьбу человеку
		// показывать, только если через пару секунд он всё ещё ждёт «Разрешить»
		time.Sleep(adbSelfSettle)
		if st, data = adbSelfState(mustShell(tv, adbSelfCmd)); st != "wait" {
			adbSelfDone(st, data, group, emit)
			return
		}
	}
	emit(Event{Status: "ask", Group: group, What: adbSelfAsk})
	for end := time.Now().Add(adbSelfWait); time.Now().Before(end); {
		time.Sleep(adbSelfPoll)
		if st, data = adbSelfState(mustShell(tv, adbSelfCmd)); st != "wait" {
			adbSelfDone(st, data, group, emit)
			return
		}
	}
	emit(Event{Status: "info", Group: group, What: "«Разрешить» на ТВ не нажали за 2 минуты: новые версии HYDE поставит установщик"})
}

func adbSelfDone(st, data, group string, emit func(Event)) {
	if st == "ok" {
		emit(Event{Status: "ok", Group: group, What: "«Разрешить» получено: новые версии HYDE «Сторож» будет ставить сам"})
		return
	}
	emit(Event{Status: "info", Group: group, What: "«Сторож» сам обновлять HYDE не сможет: новые версии поставит установщик", Detail: data})
}
