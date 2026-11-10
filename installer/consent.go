package main

import (
	"crypto/rand"
	"encoding/hex"
	"flag"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Согласия человека (выпуск 1.2.0; решение автора 10.11: «просто галочку при установке… получать автообновление софта…
// и это соглашение с отчетом, чтобы мы видели ошибки… Никакой персональной информации мы не собираем, кроме модели,
// технического состояния устройства»). Обе галочки включены заранее: «Обновлять HYDE автоматически» и «Отправлять автору
// технические отчёты». Снятая галочка отчётов не выключает обновления. После установки выбор уходит «Сторожу»; отчёт
// об установке, обновлении или откате установщик шлёт сам, если отчёты включены
type Consent struct {
	Updates bool `json:"updates"`
	Reports bool `json:"reports"`
}

func defaultConsent() Consent { return Consent{Updates: true, Reports: true} }

// consentText: выбор человека словами (план в командной строке, ход установки)
func consentText(c Consent) string {
	yes := map[bool]string{true: "да", false: "нет"}
	return "обновлять HYDE автоматически: " + yes[c.Updates] + "; технические отчёты автору: " + yes[c.Reports]
}

// tvIDOf: android_id ТВ, по нему находятся журналы этого ТВ на этом компьютере (наружу не уходит)
func tvIDOf(tv TV) string {
	return strings.TrimSpace(firstLine(mustShell(tv, "settings get secure android_id")))
}

// lastConsent: выбор человека в самой свежей установке из списка (журналы от новой к старой; 1.2.0 и новее), иначе nil.
// Повторный запуск (обновление, откат) без явного выбора берёт его: не включает молча то, от чего человек отказался
func lastConsent(dirs []string) *Consent {
	for _, d := range dirs {
		if s, err := loadSession(d); err == nil && s.Consent != nil {
			c := *s.Consent
			return &c
		}
	}
	return nil
}

// rememberConsent: запуск, где на ТВ всё уже так, журнала не оставляет. Выбор человека (и номер устройства, если его не
// было) тогда пишется в самую свежую установку этого ТВ: иначе следующий запуск взял бы прежний выбор и вернул бы «Сторожу»
// то, что человек только что выключил
func rememberConsent(root, tvID string, c *Consent, devID string) {
	if c == nil || tvID == "" {
		return
	}
	d := latestSession(root, tvID)
	if d == "" {
		return
	}
	s, err := loadSession(d)
	if err != nil {
		return
	}
	cc := *c
	s.Consent = &cc
	if !validDeviceID(s.DeviceID) && validDeviceID(devID) {
		s.DeviceID = devID
	}
	_ = saveSession(d, s)
}

// lastConsentOf: прошлый выбор для этого ТВ в папке журналов, иначе nil
func lastConsentOf(root, tvID string) *Consent {
	if tvID == "" {
		return nil
	}
	return lastConsent(sessionsOf(root, tvID, false))
}

// consentFromFlags: выбор для apply и restore в командной строке. Явный ключ важнее всего (--no-report, --no-auto-update;
// старый --report включает отчёт), без ключа прошлый выбор этого ТВ, без него оба включены
func consentFromFlags(fs *flag.FlagSet, last *Consent) Consent {
	c := defaultConsent()
	if last != nil {
		c = *last
	}
	fs.Visit(func(f *flag.Flag) {
		on := f.Value.String() == "true"
		switch f.Name {
		case "no-auto-update":
			c.Updates = !on
		case "no-report":
			c.Reports = !on
		case "report":
			c.Reports = on
		}
	})
	return c
}

// Номер устройства: один на ТВ ([a-z0-9]{16,40}, как ждёт таблица автора), им же «Сторож» подписывает суточный сигнал.
// «Сторож» 1.2.0 из выпуска называет номер в ответе на do ping («…; номер <id>; …», заводит его, если не было): берётся он,
// и обновление не заводит ТВ новый номер. «Сторож» до 1.2.0 и сборка не из выпуска номера не называют: тогда номер из
// прошлых установок этого ТВ с этого компьютера (deviceIDFromSessions). Нет и их: новый
var (
	reGuardID = regexp.MustCompile(`номер ([a-z0-9]{16,40})\b`)
	reIDOK    = regexp.MustCompile(`^[a-z0-9]{16,40}$`)
)

func validDeviceID(s string) bool { return reIDOK.MatchString(s) }

func newDeviceID() string {
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func deviceIDFromGuard(tv TV) string {
	if !hasPackage(tv, "app.glasstv.guard") {
		return ""
	}
	out, _ := tv.Shell("am broadcast -n app.glasstv.guard/.Cmd --es do ping", 20*time.Second)
	return guardIDFrom(out)
}

// guardIDFrom: номер устройства из ответа «Сторожа» на do ping; нет части «номер …»: пусто
func guardIDFrom(out string) string {
	if m := reGuardID.FindStringSubmatch(out); m != nil {
		return m[1]
	}
	return ""
}

// guardConsentCmds: команды «Сторожу» с выбором человека: автообновление HYDE (do hyde) и отчёты с номером (do ping)
func guardConsentCmds(c Consent, id string) []string {
	ping := "am broadcast -n app.glasstv.guard/.Cmd --es do ping --ez on " + strconv.FormatBool(c.Reports)
	if validDeviceID(id) {
		ping += " --es id " + id
	}
	return []string{"am broadcast -n app.glasstv.guard/.Cmd --es do hyde --ez on " + strconv.FormatBool(c.Updates), ping}
}

// consentToGuard: передать выбор «Сторожу» после установки. Его нет на ТВ: передавать некому. Ответ строго не проверяется:
// «Сторож» до 1.2.0 команды ping не знает, тогда с ТВ отчёты не идут, а обновления он ставит и так
func consentToGuard(tv TV, c Consent, id string, emit func(Event)) {
	if !hasPackage(tv, "app.glasstv.guard") {
		return
	}
	for _, cmd := range guardConsentCmds(c, id) {
		_, _ = tv.Shell(cmd, 20*time.Second)
	}
	emit(Event{Status: "info", What: consentText(c)})
}
