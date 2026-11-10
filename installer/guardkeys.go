package main

import (
	"strings"
	"time"
)

// Кнопки пульта в конце установки (1.2.0; договор с сессией «Сторожа» 10.11). На TCL служба Key Mapper бывает в списке
// спецвозможностей, но не привязана: прошивка не дала её поднять (AUTO_START ignore), а шаг «служба кнопок» видит её
// «уже включённой» и списка не трогает. «Сторож» 1.2.0 по команде do keys проверяет службу и при надобности перезапускает
// её (ответ до 10 с): «работает», «перезапущен», «не поднялся», «не установлен», «нет прав перезапустить (…)», «список
// спецвозможностей поменяли во время перезапуска, попробую позже». «Сторож» старше 1.2.0 команды не знает: пропуск
const guardKeysCmd = "am broadcast -n app.glasstv.guard/.Cmd --es do keys"

// guardKeys: только если на ТВ есть и «Сторож», и Key Mapper. Молчит, когда всё работает; перезапуск и сбой говорит словами
func guardKeys(tv TV, emit func(Event)) {
	if !hasPackage(tv, "app.glasstv.guard") || !hasPackage(tv, "io.github.sds100.keymapper") {
		return
	}
	out, _ := tv.Shell(guardKeysCmd, 30*time.Second)
	data := ""
	if m := reData.FindStringSubmatch(out); m != nil {
		data = strings.TrimSpace(m[1])
	}
	const group = "Key Mapper"
	switch {
	case strings.HasPrefix(data, "перезапущен"):
		emit(Event{Status: "info", Group: group, What: "служба кнопок стояла, «Сторож» её перезапустил: кнопки пульта работают"})
	case strings.HasPrefix(data, "не поднялся"), strings.HasPrefix(data, "нет прав"), strings.HasPrefix(data, "список спецвозможностей поменяли"):
		emit(Event{Status: "warn", Group: group, What: "служба кнопок пульта не поднялась: откройте Key Mapper, нажмите Fix, затем Restart; не помогло, перезагрузите телевизор", Detail: data})
	}
	// «работает», «не установлен», пусто («Сторож» старше 1.2.0) и непонятный ответ: молча дальше
}
