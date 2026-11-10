package main

// Отчёт об установке для автора. С 1.2.0 (решение автора 10.11) согласие даётся в начале: галочка «Отправлять автору
// технические отчёты» в окне мастера включена заранее, у команд apply и restore отчёт уходит сам, ключ --no-report его
// выключает. Отчёт уходит в конце установки, обновления и отката, и при удаче, и при сбое. До 1.2.0 отчёт уходил только
// после явного «Отправить». Без адреса приёма (пустая константа и нет HYDE_REPORT_URL) ничего не уходит.
// В отчёте нет имени, адресов, серийного номера, android_id и названий сетей: модель ТВ, Android, что поставилось,
// что не вышло и на каком шаге, номер устройства (случайный, его не вывести из ТВ). Номера отчёта и устройства человек
// видит и может попросить автора удалить записи по ним.

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

// Адрес приёма отчётов и заголовки (ключ доступа приёма): одно место. Пусто: вопрос не задаётся.
// Для проверок их подменяют HYDE_REPORT_URL и HYDE_REPORT_HEADERS («Имя: значение» через «;» или перевод строки).
// Приём: веб-приложение Google Apps Script при таблице автора, его код в tools/reports/collector.gs. Заголовки ему не нужны
var reportURL = "https://script.google.com/macros/s/AKfycbw9tzN979i7HZs_cTHT2FEWZndkg0uUuCpHVtonE0YYQROWYlSyjm4gaRfa1V8FK8BG6Q/exec"

var reportHeaders = map[string]string{}

// приёмник в Google-таблице просыпается несколько секунд и отвечает через переход на второй адрес
const reportTimeout = 20 * time.Second

func reportTarget() (string, map[string]string) {
	url := reportURL
	hdr := map[string]string{}
	for k, v := range reportHeaders {
		hdr[k] = v
	}
	if v := strings.TrimSpace(os.Getenv("HYDE_REPORT_URL")); v != "" {
		url = v
	}
	if v := os.Getenv("HYDE_REPORT_HEADERS"); v != "" {
		for _, part := range strings.FieldsFunc(v, func(r rune) bool { return r == ';' || r == '\n' }) {
			if k, val, ok := strings.Cut(part, ":"); ok && strings.TrimSpace(k) != "" {
				hdr[strings.TrimSpace(k)] = strings.TrimSpace(val)
			}
		}
	}
	return url, hdr
}

func reportEnabled() bool {
	u, _ := reportTarget()
	return u != ""
}

type Report struct {
	ReportID         string   `json:"report_id"`
	Release          string   `json:"release"`
	InstallerVersion string   `json:"installer_version"`
	PCOS             string   `json:"pc_os"`
	Path             string   `json:"path"` // wizard, claude или cli
	TVBrand          string   `json:"tv_brand"`
	TVModel          string   `json:"tv_model"`
	Android          string   `json:"android"`
	SDK              int      `json:"sdk,omitempty"` // 0 (паспорт не прочитан) таблица не примет, поэтому не пишется
	Profile          string   `json:"profile"`
	ABI              string   `json:"abi"`
	Modules          []string `json:"modules"`
	HomeSwitched     bool     `json:"home_switched"`
	Result           string   `json:"result"` // ok, failed, partial, restored, update_ok, update_failed; test: проверка приёма
	FailedStep       string   `json:"failed_step,omitempty"`
	Error            string   `json:"error,omitempty"`
	VerifyOK         int      `json:"verify_ok"`
	VerifyTotal      int      `json:"verify_total"`
	Lang             string   `json:"lang,omitempty"`
	// DeviceID (1.2.0): случайный номер ТВ ([a-z0-9]{16,40}), тот же у суточного сигнала «Сторожа»; по нему таблица
	// заводит строку устройства, и по нему же автор удаляет записи
	DeviceID string `json:"device_id,omitempty"`
}

// ReportField: строка «что уйдёт» для показа человеку до согласия
type ReportField struct {
	Label string `json:"label"`
	Value string `json:"value"`
}

// newReportID: 16 строчных шестнадцатеричных знаков: другой номер таблица автора не примет ([a-z0-9]{16,40})
func newReportID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// pcOS: система компьютера одним словом, как ждёт таблица автора: windows, macos, linux или other
func pcOS() string {
	switch runtime.GOOS {
	case "windows", "linux":
		return runtime.GOOS
	case "darwin":
		return "macos"
	}
	return "other"
}

var (
	reIPv4    = regexp.MustCompile(`\b\d{1,3}(\.\d{1,3}){3}(:\d{1,5})?\b`)
	reIPv6    = regexp.MustCompile(`[0-9A-Fa-f:]{6,}`)
	reMDNS    = regexp.MustCompile(`adb-[A-Za-z0-9]+-[A-Za-z0-9]+(\._adb-tls-[a-z]+\._tcp)?\.?`)
	reSerial  = regexp.MustCompile(`\b[A-Za-z0-9]*\d[A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*\b|\b[A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*\d[A-Za-z0-9]*\b`)
	reWinHome = regexp.MustCompile(`(?i)([A-Z]:[\\/]+(Users|Documents and Settings)[\\/]+)[^\\/\s"'«»]+`)
	reNixHome = regexp.MustCompile(`(/(home|Users)/)[^/\s"'«»]+`)
	reNet     = regexp.MustCompile(`(?i)((ssid|сеть|сети|сетью|wi-?fi)\s*[:=]?\s*)(«[^»]{1,64}»|"[^"]{1,64}"|'[^']{1,64}')`)
)

// cleanError: текст ошибки без адресов, серийных номеров, путей с именем пользователя и названий сетей, не длиннее 500 знаков
func cleanError(s string) string {
	s = reMDNS.ReplaceAllString(s, "<ТВ>")
	s = reIPv4.ReplaceAllString(s, "<адрес>")
	// IPv6: шестнадцатеричные куски через двоеточие; время вида 15:05:00 не трогаем (нет «::», букв и мало двоеточий)
	s = reIPv6.ReplaceAllStringFunc(s, func(w string) string {
		if n := strings.Count(w, ":"); n >= 2 && (strings.Contains(w, "::") || n >= 4 || strings.ContainsAny(strings.ToLower(w), "abcdef")) {
			return "<адрес>"
		}
		return w
	})
	s = reWinHome.ReplaceAllString(s, "${1}<имя>")
	s = reNixHome.ReplaceAllString(s, "${1}<имя>")
	s = reNet.ReplaceAllString(s, "${1}<сеть>")
	// длинные смешанные цифробуквенные слова (серийные номера, android_id, ключи): от 10 знаков
	s = reSerial.ReplaceAllStringFunc(s, func(w string) string {
		if len(w) >= 10 && !strings.Contains(w, ".") {
			return "<номер>"
		}
		return w
	})
	s = strings.Join(strings.Fields(s), " ")
	if utf8.RuneCountInString(s) > 500 {
		r := []rune(s)
		s = string(r[:497]) + "…"
	}
	return s
}

// reportFromSession: отчёт по папке журнала установки (session.json, passport.txt, packages-all.txt).
// kind: apply или restore; checks: проверка после установки (nil, если не делали); restoreOK: итог возврата
func reportFromSession(dir, kind, via, lang string, checks []Check, restoreOK bool) (Report, error) {
	ss, err := loadSession(dir)
	if err != nil {
		return Report{}, fmt.Errorf("нет журнала установки: %v", err)
	}
	raw, _ := os.ReadFile(filepath.Join(dir, "passport.txt"))
	p := parsePassport(string(raw))
	c := capsOf(p)
	before, _ := os.ReadFile(filepath.Join(dir, "packages-all.txt"))
	update := strings.Contains(string(before), "package:app.glasstv.guard\n") || strings.Contains(string(before), "package:app.glasstv.home\n")

	r := Report{
		ReportID:         newReportID(),
		Release:          ss.Release,
		InstallerVersion: toolVersion,
		PCOS:             pcOS(),
		Path:             via,
		TVBrand:          nonEmpty(p.Prop("ro.product.manufacturer"), p.Prop("ro.product.brand")),
		TVModel:          strings.TrimSpace(p.Prop("ro.product.model") + prefixed(" ", bracket(p.Prop("ro.boot.product")))),
		Android:          c.Android,
		SDK:              c.SDK,
		Profile:          c.Profile,
		ABI:              strings.Join(c.ABI, ","),
		Lang:             shortLang(lang),
	}
	if validDeviceID(ss.DeviceID) {
		r.DeviceID = ss.DeviceID
	}
	// что поставил план; в журналах до 1.0.1 его нет, тогда выбор человека
	r.Modules = append(r.Modules, ss.Installed...)
	if len(r.Modules) == 0 {
		for m, on := range ss.Modules {
			if on {
				r.Modules = append(r.Modules, m)
			}
		}
	}
	sort.Strings(r.Modules)
	if r.Modules == nil {
		r.Modules = []string{} // в отчёте пустой список, а не null
	}
	for _, ch := range checks {
		r.VerifyTotal++
		if ch.OK {
			r.VerifyOK++
		}
	}
	finished := ss.Finished != ""
	r.HomeSwitched = finished && (ss.HomePlanned || (ss.Installed == nil && ss.Home != nil && *ss.Home))
	switch {
	case kind == "restore" && restoreOK:
		r.Result = "restored"
		r.HomeSwitched = false
	case kind == "restore":
		r.Result, r.FailedStep = "failed", "вернуть как было"
	case finished && r.VerifyOK < r.VerifyTotal:
		r.Result = "partial"
	case finished && update:
		r.Result = "update_ok"
	case finished:
		r.Result = "ok"
	case update:
		r.Result = "update_failed"
	default:
		r.Result = "failed"
	}
	if !finished && kind != "restore" {
		r.FailedStep = cleanError(ss.FailedStep)
		r.Error = cleanError(ss.LastError)
	}
	return r.clip(), nil
}

// clip: поля в пределах таблицы автора (tools/reports/collector.gs): человек видит ровно то, что ляжет в строку,
// а поле вне правил таблица не обрежет, а отклонит весь отчёт
func (r Report) clip() Report {
	r.Release, r.InstallerVersion = cut(r.Release, 20), cut(r.InstallerVersion, 20)
	r.TVBrand, r.TVModel = cut(r.TVBrand, 60), cut(r.TVModel, 80)
	r.Android, r.Profile, r.ABI = cut(r.Android, 10), cut(r.Profile, 30), cut(r.ABI, 30)
	r.FailedStep, r.Error, r.Lang = cut(r.FailedStep, 200), cut(r.Error, 500), cut(r.Lang, 10)
	if len(r.Modules) > 40 {
		r.Modules = r.Modules[:40]
	}
	for i, m := range r.Modules {
		r.Modules[i] = cut(m, 30)
	}
	if r.SDK < 1 || r.SDK > 100 {
		r.SDK = 0 // не пишется вовсе
	}
	if r.Path != "wizard" && r.Path != "claude" {
		r.Path = "cli"
	}
	return r
}

func cut(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}

func bracket(s string) string {
	if s == "" {
		return ""
	}
	return "(" + s + ")"
}

// Fields: что уйдёт, человеческими словами, ровно те значения, что в отчёте
func (r Report) Fields() []ReportField {
	res := map[string]string{"ok": "установлено", "update_ok": "обновлено", "partial": "установлено, но не всё держится",
		"failed": "не вышло", "update_failed": "обновление не вышло", "restored": "возвращено как было", "test": "проверка приёма"}
	path := map[string]string{"wizard": "окно мастера", "claude": "Claude Code", "cli": "команды установщика"}
	home := "нет"
	if r.HomeSwitched {
		home = "да"
	}
	f := []ReportField{
		{"Номер отчёта", r.ReportID},
		{"Выпуск HYDE TV", r.Release + ", установщик " + r.InstallerVersion},
		{"Компьютер", r.PCOS},
		{"Как ставили", nonEmpty(path[r.Path], r.Path)},
		{"Телевизор", strings.TrimSpace(r.TVBrand + " " + r.TVModel)},
		{"Android", fmt.Sprintf("%s (API %d), процессор %s", r.Android, r.SDK, r.ABI)},
		{"Профиль", r.Profile},
		{"Что ставили", strings.Join(r.Modules, ", ")},
		{"HYDE главным экраном", home},
		{"Итог", nonEmpty(res[r.Result], r.Result)},
	}
	if r.DeviceID != "" {
		f = append(f, ReportField{"Номер устройства", r.DeviceID})
	}
	if r.VerifyTotal > 0 {
		f = append(f, ReportField{"Проверка", fmt.Sprintf("держится %d из %d", r.VerifyOK, r.VerifyTotal)})
	}
	if r.FailedStep != "" {
		f = append(f, ReportField{"На каком шаге", r.FailedStep})
	}
	if r.Error != "" {
		f = append(f, ReportField{"Текст ошибки", r.Error})
	}
	if r.Lang != "" {
		f = append(f, ReportField{"Язык", r.Lang})
	}
	return f
}

// sendReport: одна попытка с таймаутом; ошибка только текстом, на установку не влияет
func sendReport(r Report) error {
	url, hdr := reportTarget()
	if url == "" {
		return errors.New("адрес для отчётов не настроен")
	}
	body, err := json.Marshal(r)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "hyde-install/"+toolVersion)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	cl := &http.Client{Timeout: reportTimeout}
	resp, err := cl.Do(req)
	if err != nil {
		return fmt.Errorf("нет связи с сервером отчётов: %s", cleanError(err.Error()))
	}
	defer resp.Body.Close()
	ans, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return fmt.Errorf("сервер отчётов ответил %d", resp.StatusCode)
	}
	return reportAccepted(resp.Header.Get("Content-Type"), ans)
}

// reportAccepted: таблица автора отвечает 200 и на отказ ({"ok": false, "error": ...}), а закрытый приём
// отдаёт страницу входа Google; принятым считается только ответ без отказа и не страница
func reportAccepted(ctype string, body []byte) error {
	if strings.Contains(strings.ToLower(ctype), "text/html") {
		return errors.New("сервер отчётов ответил страницей, а не подтверждением")
	}
	var a struct {
		OK    *bool  `json:"ok"`
		Error string `json:"error"`
	}
	if json.Unmarshal(body, &a) == nil && a.OK != nil && !*a.OK {
		return fmt.Errorf("сервер отчётов не принял отчёт: %s", nonEmpty(cleanError(a.Error), "без причины"))
	}
	return nil
}

// shortLang: язык без кодировки и не длиннее 10 знаков (ru_RU.UTF-8 → ru_RU), как ждёт таблица автора
func shortLang(s string) string {
	s, _, _ = strings.Cut(strings.TrimSpace(s), ".")
	if len(s) > 10 {
		s = s[:10]
	}
	return s
}

// printReport: поля для командной строки
func printReport(r Report) {
	fmt.Println("Отчёт для автора (уйдёт только это):")
	for _, f := range r.Fields() {
		fmt.Printf("  %s: %s\n", f.Label, f.Value)
	}
}

// reportAfter: общий конец apply и restore в командной строке: отчёт уходит сам, если человек не выключил его (--no-report)
func reportAfter(send bool, dir, kind, via string, checks []Check, restoreOK bool) {
	if !send || dir == "" {
		return
	}
	if !reportEnabled() {
		fmt.Println("Отчёт: адрес для отчётов в этой версии не настроен, ничего не отправлено")
		return
	}
	r, err := reportFromSession(dir, kind, via, os.Getenv("LANG"), checks, restoreOK)
	if err != nil {
		fmt.Println("Отчёт не собран: " + err.Error())
		return
	}
	printReport(r)
	if err := sendReport(r); err != nil {
		fmt.Println("Отчёт не отправлен: " + err.Error())
		return
	}
	fmt.Println("Отчёт отправлен, номер " + r.ReportID + reportDeleteHint(r))
}

// reportDeleteHint: как попросить удалить записи: по номеру отчёта, а все записи этого ТВ по номеру устройства
func reportDeleteHint(r Report) string {
	if r.DeviceID != "" {
		return ". Чтобы автор удалил его, напишите ему этот номер, а чтобы удалил все записи этого телевизора, номер устройства " + r.DeviceID + "."
	}
	return ". Чтобы автор удалил его, напишите ему этот номер."
}
