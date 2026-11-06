package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// ADB: путь к adb и его версия. Ищем по порядку: переменная ADB, рядом с установщиком (platform-tools),
// ~/.glasstv/tools/platform-tools, PATH. Нужен adb не старше 30: в старом нет pair и mdns (как в tools/lib.sh)
type ADB struct {
	Path    string `json:"path"`
	Version string `json:"version"`
}

func exeName(n string) string {
	if runtime.GOOS == "windows" {
		return n + ".exe"
	}
	return n
}

var reADBVersion = regexp.MustCompile(`(?m)^Version (\d+)\.(\d+)\.(\d+)`)

// adbMajor: старший номер platform-tools из «adb version» (строка «Version 37.0.1-…»), 0 если не понять
func adbMajor(out string) (int, string) {
	m := reADBVersion.FindStringSubmatch(out)
	if m == nil {
		return 0, ""
	}
	n, _ := strconv.Atoi(m[1])
	return n, m[1] + "." + m[2] + "." + m[3]
}

func findADB() (*ADB, error) {
	var cands []string
	if p := os.Getenv("ADB"); p != "" {
		cands = append(cands, p)
	}
	if exe, err := os.Executable(); err == nil {
		d := filepath.Dir(exe)
		cands = append(cands, filepath.Join(d, "platform-tools", exeName("adb")), filepath.Join(d, exeName("adb")))
	}
	if h, err := os.UserHomeDir(); err == nil {
		cands = append(cands, filepath.Join(h, ".glasstv", "tools", "platform-tools", exeName("adb")))
	}
	if p, err := exec.LookPath(exeName("adb")); err == nil {
		cands = append(cands, p)
	}
	var old []string
	for _, c := range cands {
		if st, err := os.Stat(c); err != nil || st.IsDir() {
			continue
		}
		out, _ := runCmd(c, 10*time.Second, "version")
		major, ver := adbMajor(out)
		if major >= 30 {
			return &ADB{Path: c, Version: ver}, nil
		}
		old = append(old, c)
	}
	if len(old) > 0 {
		return nil, fmt.Errorf("найден только старый adb (%s): в нём нет сопряжения по коду, нужны свежие platform-tools", strings.Join(old, ", "))
	}
	return nil, errors.New("adb не найден: положите папку platform-tools рядом с установщиком или задайте переменную ADB")
}

// runCmd: запуск программы с ограничением времени, вывод вместе с ошибками, без символов возврата каретки
func runCmd(path string, timeout time.Duration, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, path, args...)
	hideWindow(cmd)
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	err := cmd.Run()
	s := strings.ReplaceAll(out.String(), "\r", "")
	if ctx.Err() == context.DeadlineExceeded {
		return s, fmt.Errorf("нет ответа за %s", timeout)
	}
	return s, err
}

func (a *ADB) run(timeout time.Duration, args ...string) (string, error) {
	return runCmd(a.Path, timeout, args...)
}

// Shell: команда в оболочке ТВ целиком одной строкой (конвейеры и циклы выполняются на ТВ, а не на компьютере)
func (a *ADB) Shell(serial, script string, timeout time.Duration) (string, error) {
	return a.run(timeout, "-s", serial, "shell", script)
}

// MdnsService: строка «adb mdns services»: имя, тип и адрес:порт. Тип _adb-tls-connect._tcp это порт для подключения
// (главный экран «Беспроводная отладка»), _adb-tls-pairing._tcp открытое окно сопряжения, _adb._tcp старая отладка 5555
type MdnsService struct {
	Name string `json:"name"`
	Type string `json:"type"`
	Addr string `json:"addr"`
}

func parseMdns(out string) []MdnsService {
	var r []MdnsService
	for _, ln := range strings.Split(out, "\n") {
		f := strings.Fields(ln)
		if len(f) != 3 || !strings.HasPrefix(f[1], "_adb") || !strings.Contains(f[2], ":") {
			continue
		}
		r = append(r, MdnsService{Name: f[0], Type: f[1], Addr: f[2]})
	}
	return r
}

func (a *ADB) Mdns() ([]MdnsService, error) {
	out, err := a.run(15*time.Second, "mdns", "services")
	return parseMdns(out), err
}

// Device: строка «adb devices -l»
type Device struct {
	Serial string `json:"serial"`
	State  string `json:"state"`
	Model  string `json:"model"`
}

func parseDevices(out string) []Device {
	var r []Device
	for _, ln := range strings.Split(out, "\n") {
		f := strings.Fields(ln)
		if len(f) < 2 || strings.HasPrefix(ln, "List of devices") || strings.HasPrefix(ln, "*") {
			continue
		}
		d := Device{Serial: f[0], State: f[1]}
		for _, kv := range f[2:] {
			if strings.HasPrefix(kv, "model:") {
				d.Model = strings.ReplaceAll(strings.TrimPrefix(kv, "model:"), "_", " ")
			}
		}
		r = append(r, d)
	}
	return r
}

func (a *ADB) Devices() ([]Device, error) {
	out, err := a.run(15*time.Second, "devices", "-l")
	return parseDevices(out), err
}

// Итог подключения, как коды выхода tools/find-tv.sh: ok (0), allow (2: нажать «Разрешить» на ТВ), noreply (3), pair (4)
type ConnectResult struct {
	Result string `json:"result"`
	Serial string `json:"serial"`
	Say    string `json:"say"`
	Raw    string `json:"raw"`
}

// classifyConnect: что значит ответ adb connect и get-state (без ТВ, проверяется тестом)
func classifyConnect(connectOut, state string) (string, string) {
	low := strings.ToLower(connectOut)
	if strings.Contains(low, "failed to authenticate") {
		return "pair", "ТВ не знает этот компьютер: нужно сопряжение. На ТВ: Настройки, Система, Для разработчиков, Беспроводная отладка, «Подключить устройство с помощью кода». В этом окне свои адрес:порт и шестизначный код."
	}
	switch strings.TrimSpace(state) {
	case "device":
		return "ok", "Связь есть."
	case "unauthorized":
		return "allow", "На экране ТВ появилось окно «Разрешить отладку?»: нажмите «Разрешить» (можно с галочкой «всегда») и подключитесь ещё раз."
	}
	say := "ТВ не отвечает. Проверьте адрес и порт с главного экрана «Беспроводная отладка» (порт случайный и меняется после перезагрузки или смены Wi-Fi), включена ли отладка, не спит ли ТВ."
	if regexp.MustCompile(`refused|cannot connect|unable to connect|timed out|no route`).MatchString(low) {
		say += " Если этот компьютер с ТВ ещё не сопрягали, сначала сопряжение по коду."
	}
	return "noreply", say
}

// Connect: подключиться к адресу. Без порта: порт ищется через mDNS, 5555 только запасной вариант (как find-tv.sh)
func (a *ADB) Connect(addr string) ConnectResult {
	addr = strings.TrimSpace(addr)
	if !strings.Contains(addr, ":") {
		port := ""
		if ss, err := a.Mdns(); err == nil {
			for _, s := range ss {
				if s.Type == "_adb-tls-connect._tcp" && strings.HasPrefix(s.Addr, addr+":") {
					port = s.Addr
					break
				}
			}
		}
		if port == "" {
			port = addr + ":5555"
		}
		addr = port
	}
	out, _ := a.run(20*time.Second, "connect", addr)
	state, _ := a.run(10*time.Second, "-s", addr, "get-state")
	res, say := classifyConnect(out, state)
	return ConnectResult{Result: res, Serial: addr, Say: say, Raw: strings.TrimSpace(out)}
}

// Pair: сопряжение по коду из окна «Подключить устройство с помощью кода» (его адрес:порт не тот, что для подключения)
func (a *ADB) Pair(addr, code string) (bool, string) {
	out, _ := a.run(30*time.Second, "pair", strings.TrimSpace(addr), strings.TrimSpace(code))
	return strings.Contains(strings.ToLower(out), "successfully paired"), strings.TrimSpace(out)
}
