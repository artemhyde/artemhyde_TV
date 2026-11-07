package main

import (
	_ "embed"
	"encoding/json"
)

// Рецепт установки (recipe.json рядом): вшит в программу, чтобы установщик был одним файлом
//
//go:embed recipe.json
var recipeJSON []byte

type Recipe struct {
	Schema     int                 `json:"schema"`
	Modules    []RecipeModule      `json:"modules"`
	TileAlts   map[string][]string `json:"tile_alts"`
	HomeSwitch struct {
		Title      string `json:"title"`
		What       string `json:"what"`
		Default    bool   `json:"default"`
		TCLDisable []struct {
			Package string `json:"package"`
			Why     string `json:"why"`
		} `json:"tcl_disable"`
		Settings []RecipeSetting `json:"settings"`
	} `json:"home_switch"`
	NightOff RecipeStep `json:"night_off_not_tcl"`
}

type RecipeModule struct {
	Module     string       `json:"module"`
	Title      string       `json:"title"`
	What       string       `json:"what"`
	Default    bool         `json:"default"`
	Required   bool         `json:"required"`
	Admin      bool         `json:"admin"`
	Needs      string       `json:"needs"`
	NeedsWhy   string       `json:"needs_why"`
	ThirdParty string       `json:"third_party"`
	PartOf     string       `json:"part_of"`
	Steps      []RecipeStep `json:"steps"`
	// Для сторонних: Keep непустой значит «откат не удаляет» и почему; Updates «guard»: ставить от имени «Сторожа»,
	// тогда он сам обновляет приложение без нажатий (apps/guard/assets/updates.txt); Installed: что сказать, если уже стоит
	Keep      string `json:"keep"`
	Updates   string `json:"updates"`
	Installed string `json:"installed"`
	// Group «apps»: раздел «Приложения по желанию» в окне мастера; Note: оговорка под названием (серая зона, эксперимент)
	Group string `json:"group"`
	Note  string `json:"note"`
}

type RecipeStep struct {
	Kind    string `json:"kind"` // пусто: готовая команда; a11y и ime считаются по паспорту
	What    string `json:"what"`
	Do      string `json:"do"`
	Undo    string `json:"undo"`
	Run     bool   `json:"run"`
	When    string `json:"when"`
	Service string `json:"service"`
	IME     string `json:"ime"`
}

type RecipeSetting struct {
	What  string `json:"what"`
	NS    string `json:"ns"`
	Key   string `json:"key"`
	Value string `json:"value"`
}

func loadRecipe() (Recipe, error) {
	var r Recipe
	err := json.Unmarshal(recipeJSON, &r)
	return r, err
}

func (r Recipe) Module(name string) *RecipeModule {
	for i := range r.Modules {
		if r.Modules[i].Module == name {
			return &r.Modules[i]
		}
	}
	return nil
}
