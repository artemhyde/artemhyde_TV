package main

import (
	"fmt"
	"strconv"
	"strings"
)

// Правила паспорта ТВ. Уровень TCL те же, что у «Сторожа» (apps/guard/src/app/glasstv/guard/CapsRules.java: совпадение
// проверяет тест), профиль и отметки из CLAUDE.md, шаг 3. Пара «серия + прошивка», проверенная на живом ТВ, получает
// «проверено», остальные TCL «экспериментально», чужой бренд «не TCL»
const verifiedSeries = "C6K"

var verifiedFW = []string{"TCL/G08_4K_GB/G08:14/UTT2.250416.001/AU02:user/release-keys"}

const (
	levelVerified     = "проверено"
	levelExperimental = "экспериментально"
	levelOther        = "не TCL"

	profileTCL     = "tcl-gtv"
	profileGeneric = "generic-gtv"
	profileNone    = "unsupported"

	minSDK = 31 // наши приложения собраны под Android 12 и выше
)

type Caps struct {
	Model          string   `json:"model"`
	Android        string   `json:"android"`
	SDK            int      `json:"sdk"`
	ABI            []string `json:"abi"`
	ABI32          bool     `json:"abi32"`
	TCL            bool     `json:"tcl"`
	Level          string   `json:"level"`
	Profile        string   `json:"profile"`
	Why            string   `json:"why,omitempty"` // почему ТВ не подходит
	Leanback       bool     `json:"leanback"`
	DisplayLib     bool     `json:"display_lib"`
	DisplayService bool     `json:"display_service"`
	Dolby          bool     `json:"dolby"`
	QuickPanel     bool     `json:"quick_panel"`
	TKL            bool     `json:"tkl"`
	HomeHolder     string   `json:"home_holder"`
	LauncherX      bool     `json:"launcherx"`
	SetupWraith    bool     `json:"setupwraith"`
	KeyMapper      bool     `json:"keymapper"`
	DataFreeMB     int64    `json:"data_free_mb"`
}

func isTCL(manufacturer, brand string) bool {
	return strings.EqualFold(strings.TrimSpace(manufacturer), "TCL") || strings.EqualFold(strings.TrimSpace(brand), "TCL")
}

func tclLevel(manufacturer, brand, series, fingerprint string) string {
	if !isTCL(manufacturer, brand) {
		return levelOther
	}
	if strings.EqualFold(strings.TrimSpace(series), verifiedSeries) {
		for _, f := range verifiedFW {
			if f == strings.TrimSpace(fingerprint) {
				return levelVerified
			}
		}
	}
	return levelExperimental
}

func capsOf(p Passport) Caps {
	c := Caps{}
	man, brand := p.Prop("ro.product.manufacturer"), p.Prop("ro.product.brand")
	c.SDK, _ = strconv.Atoi(p.Prop("ro.build.version.sdk"))
	c.Android = p.Prop("ro.build.version.release")
	for _, a := range strings.Split(p.Prop("ro.product.cpu.abilist"), ",") {
		if a = strings.TrimSpace(a); a != "" {
			c.ABI = append(c.ABI, a)
		}
	}
	c.ABI32 = len(c.ABI) > 0 && !contains(c.ABI, "arm64-v8a") && !contains(c.ABI, "x86_64")
	c.TCL = isTCL(man, brand)
	c.Level = tclLevel(man, brand, p.Prop("ro.boot.product"), p.Prop("ro.build.fingerprint"))
	for _, f := range p.Features {
		if strings.Contains(f, "leanback") {
			c.Leanback = true
		}
	}
	c.DisplayLib = p.Libraries["com.tcl.tv.display"]
	c.DisplayService = p.DisplayService
	c.Dolby = p.Has("com.dolby.android.audio.service")
	c.QuickPanel = p.QuickPanel
	c.TKL = p.TKL
	c.HomeHolder = p.Home
	c.LauncherX = p.Has("com.google.android.apps.tv.launcherx")
	c.SetupWraith = p.Has("com.google.android.tungsten.setupwraith")
	c.KeyMapper = p.Has("io.github.sds100.keymapper")
	c.DataFreeMB = p.DataFreeMB
	model := strings.TrimSpace(man + " " + p.Prop("ro.boot.product"))
	if m := p.Prop("ro.product.model"); m != "" {
		model += " (" + m + ")"
	}
	c.Model = strings.TrimSpace(model)
	switch {
	case c.SDK > 0 && c.SDK < minSDK:
		c.Profile, c.Why = profileNone, fmt.Sprintf("Android %s: нужен Android 12 и выше, приложения собраны под него", c.Android)
	case c.TCL && strings.EqualFold(p.Prop("ro.tcl.product_type"), "GTV"):
		c.Profile = profileTCL
	case c.Leanback && c.SDK >= minSDK:
		c.Profile = profileGeneric
	case !c.Leanback:
		c.Profile, c.Why = profileNone, "это не Android TV и не Google TV (нет телевизионного интерфейса Android): Fire TV, Tizen и webOS не подходят"
	default:
		c.Profile, c.Why = profileNone, "не удалось прочитать версию Android"
	}
	return c
}

func contains(l []string, s string) bool {
	for _, x := range l {
		if x == s {
			return true
		}
	}
	return false
}

// Has: отметка по имени, как в рецепте (поле needs и when)
func (c Caps) Has(flag string) bool {
	switch flag {
	case "tcl":
		return c.TCL
	case "not_tcl":
		return !c.TCL
	case "dolby":
		return c.Dolby
	case "display_lib":
		return c.DisplayLib
	case "quick_panel":
		return c.QuickPanel
	case "":
		return true
	}
	return false
}
