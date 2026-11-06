package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Снимок «до» в папку журнала (те же файлы, что у tools/snapshot.sh): списки настроек и пакетов, состояние, паспорт и
// пустой журнал. По нему откат в конце сверяет настройки, которые меняются мимо журнала. Внутри настроек есть
// android_id: папка остаётся только на этом компьютере
func takeSnapshot(tv TV, dir, passportRaw string, a *ADB) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	grab := func(file, cmd string) error {
		out, err := tv.Shell(cmd, 2*time.Minute)
		if err != nil && strings.TrimSpace(out) == "" {
			return fmt.Errorf("%s: %v", file, err)
		}
		return os.WriteFile(filepath.Join(dir, file), []byte(out), 0o644)
	}
	for _, g := range [][2]string{
		{"settings-global.txt", "settings list global"},
		{"settings-secure.txt", "settings list secure"},
		{"settings-system.txt", "settings list system"},
		{"packages-all.txt", "pm list packages -f"},
		{"packages-disabled.txt", "pm list packages -d"},
		{"packages-enabled.txt", "pm list packages -e"},
		{"packages-system.txt", "pm list packages -s"},
		{"packages-third.txt", "pm list packages -3"},
		{"deviceidle-whitelist.txt", "dumpsys deviceidle whitelist"},
	} {
		if err := grab(g[0], g[1]); err != nil {
			return err
		}
	}
	state := `echo "home_role_holder: $(cmd role get-role-holders --user 0 android.app.role.HOME)"
for k in screensaver_components screensaver_enabled screensaver_activate_on_sleep screensaver_activate_on_dock enabled_accessibility_services accessibility_enabled default_input_method; do echo "secure.$k: $(settings get secure $k)"; done
for k in private_dns_mode private_dns_specifier window_animation_scale transition_animation_scale animator_duration_scale adb_enabled adb_wifi_enabled stay_on_while_plugged_in; do echo "global.$k: $(settings get global $k)"; done
for k in screen_brightness screen_off_timeout; do echo "system.$k: $(settings get system $k)"; done
echo "device_admins:"; dumpsys device_policy | grep -A8 'Enabled Device Admins' | sed 's/^/  /'`
	if err := grab("state.txt", state); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(dir, "passport.txt"), []byte(passportRaw), 0o644); err != nil {
		return err
	}
	meta := fmt.Sprintf("label: %s\ntime: %s\ntv: %s\nadb: %s\ntool: hyde-install %s\n", filepath.Base(dir),
		time.Now().Format("2006-01-02 15:04:05"), tv.Serial(), adbVer(a), toolVersion)
	if err := os.WriteFile(filepath.Join(dir, "meta.txt"), []byte(meta), 0o644); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, "ledger.tsv"), []byte(ledgerHeader), 0o644)
}

func adbVer(a *ADB) string {
	if a == nil {
		return "?"
	}
	return a.Version
}

// Session: состояние установки в папке журнала (session.json): для продолжения после обрыва и проверки
type Session struct {
	Label     string          `json:"label"`
	TVID      string          `json:"tv_id"` // android_id: тот же ли это ТВ (остаётся только на этом компьютере)
	Model     string          `json:"model"`
	Release   string          `json:"release"`
	Modules   map[string]bool `json:"modules"`
	Home      *bool           `json:"home"`
	Started   string          `json:"started"`
	Finished  string          `json:"finished,omitempty"`
	Restored  string          `json:"restored,omitempty"`
	LastError string          `json:"last_error,omitempty"`
}

func loadSession(dir string) (*Session, error) {
	b, err := os.ReadFile(filepath.Join(dir, "session.json"))
	if err != nil {
		return nil, err
	}
	var s Session
	return &s, json.Unmarshal(b, &s)
}

func saveSession(dir string, s *Session) error {
	b, _ := json.MarshalIndent(s, "", "  ")
	return os.WriteFile(filepath.Join(dir, "session.json"), b, 0o644)
}

// findUnfinished: самая свежая незаконченная установка этого ТВ в папке журналов (продолжение после обрыва)
func findUnfinished(root, tvID string) string {
	ents, err := os.ReadDir(root)
	if err != nil {
		return ""
	}
	var names []string
	for _, e := range ents {
		if e.IsDir() {
			names = append(names, e.Name())
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(names)))
	for _, n := range names {
		s, err := loadSession(filepath.Join(root, n))
		if err != nil {
			continue
		}
		if s.TVID == tvID && s.Finished == "" && s.Restored == "" {
			return filepath.Join(root, n)
		}
	}
	return ""
}

// latestSession: самая свежая папка журнала этого ТВ (для проверки и отката по умолчанию)
func latestSession(root, tvID string) string {
	ents, err := os.ReadDir(root)
	if err != nil {
		return ""
	}
	var names []string
	for _, e := range ents {
		if e.IsDir() {
			names = append(names, e.Name())
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(names)))
	for _, n := range names {
		if s, err := loadSession(filepath.Join(root, n)); err == nil && (tvID == "" || s.TVID == tvID) {
			return filepath.Join(root, n)
		}
	}
	return ""
}
