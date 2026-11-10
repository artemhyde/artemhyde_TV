package main

import (
	"strings"
	"testing"
)

// «Дом» уже главный экран, а пакет, который установщик отключал раньше, снова включён (обновление прошивки): план отключает
// его снова и на Google TV не TCL (10.11, виртуальный Google TV 14: повторный запуск писал «всё уже так»). Уже отключённый
// и не из журнала не трогается, повтора шага нет
func TestPlanRedisable(t *testing.T) {
	rec, err := loadRecipe()
	if err != nil {
		t.Fatal(err)
	}
	m := testManifest(t)
	raw := fixtureRaw(t, "google-tv.txt")
	const lx = "com.google.android.apps.tv.launcherx"
	homeIsHyde := strings.Replace(raw, "##home\n"+lx+"\n", "##home\napp.glasstv.home\n", 1)
	if homeIsHyde == raw {
		t.Fatal("в образце паспорта не нашёлся главный экран launcherx")
	}
	yes := true
	count := func(pl Plan) int {
		n := 0
		for _, s := range pl.Steps {
			if s.Do == "pm disable-user --user 0 "+lx {
				n++
				if !strings.HasPrefix(s.What, "отключить снова") || s.Undo != "pm enable --user 0 "+lx {
					t.Fatalf("шаг: %+v", s)
				}
			}
		}
		return n
	}
	ch := Choices{Modules: map[string]bool{}, Home: &yes, Redisable: []string{lx, "com.example.gone"}}
	if n := count(buildPlan(rec, m, parsePassport(homeIsHyde), ch, false)); n != 1 {
		t.Fatalf("снова включённый launcherx: шагов %d, нужен 1", n)
	}
	disabled := strings.Replace(homeIsHyde, "##disabled\n", "##disabled\npackage:"+lx+"\n", 1)
	if n := count(buildPlan(rec, m, parsePassport(disabled), ch, false)); n != 0 {
		t.Fatalf("уже отключённый launcherx: шагов %d", n)
	}
	if n := count(buildPlan(rec, m, parsePassport(homeIsHyde), Choices{Modules: map[string]bool{}, Home: &yes}, false)); n != 0 {
		t.Fatalf("без журнала шаг появился: %d", n)
	}
}
