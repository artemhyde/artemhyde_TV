package main

import (
	"errors"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Паспорт ТВ (CLAUDE.md, шаг 3): только чтение. Одной командой оболочки ТВ собираются ровно те свойства, что нужны
// правилам: без серийного номера, MAC и идентификаторов, поэтому сохранённый ответ годится в образцы для проверок.
// Обрыв связи посреди ответа виден по отсутствию строки ##end
const passportScript = `echo '##props'
for k in ro.product.manufacturer ro.product.brand ro.product.model ro.boot.product ro.tcl.product_type ro.hardware ro.product.cpu.abilist ro.build.version.sdk ro.build.version.release ro.build.fingerprint; do echo "$k=$(getprop $k)"; done
echo '##features'; pm list features
echo '##libraries'; pm list libraries
echo '##packages'; pm list packages --show-versioncode
echo '##disabled'; pm list packages -d
echo '##home'; ` + homeHolderCmd + `
echo '##home_activity'; cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1
echo '##roles'; service check role
echo '##display_service'; service check tcl_tv_display
echo '##quickpanel'; cmd package query-services --brief -a com.tcl.settings.SHOW_WINDOW
echo '##tkl'; ls /tclconfig/tvcust/aio_resources/resource/common_overseas/input_key/ 2>/dev/null
echo '##settings'
for k in secure:screensaver_components secure:screensaver_enabled secure:screensaver_activate_on_sleep secure:screensaver_activate_on_dock system:screen_off_timeout secure:enabled_accessibility_services secure:accessibility_enabled secure:default_input_method global:private_dns_mode global:private_dns_specifier global:adb_wifi_enabled; do echo "$k=$(settings get ${k%%:*} ${k#*:})"; done
echo '##admins'; dumpsys device_policy | grep -A8 'Enabled Device Admins'
echo '##df'; df /data
echo '##end'`

type Passport struct {
	Props          map[string]string `json:"props"`
	Features       []string          `json:"features"`
	Libraries      map[string]bool   `json:"-"`
	Packages       map[string]int64  `json:"-"` // пакет → versionCode
	Disabled       map[string]bool   `json:"-"`
	Home           string            `json:"home"`
	HomeActivity   string            `json:"home_activity"` // экран «Домой», к которому ведёт кнопка (Android 9: так и возвращают)
	Roles          bool              `json:"roles"`         // служба ролей есть (Android 10 и новее)
	DisplayService bool              `json:"display_service"`
	QuickPanel     bool              `json:"quick_panel"`
	TKL            bool              `json:"tkl"`
	Settings       map[string]string `json:"settings"` // «secure:screensaver_enabled» → «1»
	Admins         []string          `json:"admins"`
	DataFreeMB     int64             `json:"data_free_mb"`
	Complete       bool              `json:"complete"`
}

func (p Passport) Prop(k string) string { return strings.TrimSpace(p.Props[k]) }

func (p Passport) Has(pkg string) bool { _, ok := p.Packages[pkg]; return ok }

// Setting: значение настройки или пусто, если её нет (Android отвечает «null»)
func (p Passport) Setting(k string) string {
	v := strings.TrimSpace(p.Settings[k])
	if v == "null" {
		return ""
	}
	return v
}

func parsePassport(raw string) Passport {
	p := Passport{Props: map[string]string{}, Libraries: map[string]bool{}, Packages: map[string]int64{},
		Disabled: map[string]bool{}, Settings: map[string]string{}}
	sec := ""
	for _, ln := range strings.Split(strings.ReplaceAll(raw, "\r", ""), "\n") {
		t := strings.TrimSpace(ln)
		if strings.HasPrefix(t, "##") {
			sec = strings.TrimPrefix(t, "##")
			if sec == "end" {
				p.Complete = true
			}
			continue
		}
		if t == "" {
			continue
		}
		switch sec {
		case "props":
			if k, v, ok := strings.Cut(t, "="); ok {
				p.Props[k] = v
			}
		case "features":
			f := strings.TrimPrefix(t, "feature:")
			if strings.Contains(f, "leanback") || strings.Contains(f, "television") {
				p.Features = append(p.Features, f)
			}
		case "libraries":
			p.Libraries[strings.TrimPrefix(t, "library:")] = true
		case "packages":
			f := strings.Fields(t)
			if len(f) == 0 || !strings.HasPrefix(f[0], "package:") {
				continue
			}
			var vc int64
			for _, x := range f[1:] {
				if v, ok := strings.CutPrefix(x, "versionCode:"); ok {
					vc, _ = strconv.ParseInt(v, 10, 64)
				}
			}
			p.Packages[strings.TrimPrefix(f[0], "package:")] = vc
		case "disabled":
			p.Disabled[strings.TrimPrefix(t, "package:")] = true
		case "home":
			if p.Home == "" {
				p.Home = t
			}
		case "home_activity":
			if strings.Contains(t, "/") && !strings.Contains(t, " ") && !strings.HasPrefix(t, "android/") {
				p.HomeActivity = t
			}
		case "roles":
			if strings.HasSuffix(t, ": found") {
				p.Roles = true
			}
		case "display_service":
			if strings.HasSuffix(t, ": found") {
				p.DisplayService = true
			}
		case "quickpanel":
			if strings.Contains(t, "/") && !strings.Contains(t, " ") {
				p.QuickPanel = true
			}
		case "tkl":
			if t == "shortcutkey.tkl" {
				p.TKL = true
			}
		case "settings":
			if k, v, ok := strings.Cut(t, "="); ok {
				p.Settings[k] = v
			}
		case "admins":
			// «    io.github.sds100.keymapper/.system.DeviceAdmin:»: компонент администратора
			if strings.HasSuffix(t, ":") && strings.Contains(t, "/") && !strings.Contains(t, " ") {
				p.Admins = append(p.Admins, strings.TrimSuffix(t, ":"))
			}
		case "df":
			f := strings.Fields(t)
			if len(f) >= 4 && f[0] != "Filesystem" {
				if kb, err := strconv.ParseInt(f[3], 10, 64); err == nil {
					p.DataFreeMB = kb / 1024
				}
			}
		}
	}
	sort.Strings(p.Features)
	return p
}

// AdminActive: есть ли у приложения права администратора устройства
func (p Passport) AdminActive(pkg string) bool {
	for _, a := range p.Admins {
		if strings.HasPrefix(a, pkg+"/") {
			return true
		}
	}
	return false
}

func readPassport(a *ADB, serial string) (Passport, string, error) {
	raw, err := a.Shell(serial, passportScript, 90*time.Second)
	p := parsePassport(raw)
	if !p.Complete {
		if err == nil {
			err = errors.New("ответ ТВ оборвался посередине")
		}
		return p, raw, errors.New("паспорт ТВ не прочитан до конца: " + err.Error())
	}
	return p, raw, nil
}
