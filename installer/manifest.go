package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Опись выпуска (manifest.json от build/release-manifest.py): приложения, файлы, суммы, подпись, зависимости
type Manifest struct {
	Product        string `json:"product"`
	Release        string `json:"release"`
	VersionCode    int64  `json:"versionCode"`
	Protocol       int    `json:"protocol"`
	SettingsSchema int    `json:"settings_schema"`
	SignerCert     string `json:"signer_cert_sha256"`
	BuiltFrom      struct {
		Commit      string `json:"commit"`
		Uncommitted bool   `json:"uncommitted_changes"`
	} `json:"built_from"`
	Apps       []ManifestApp `json:"apps"`
	ThirdParty []ThirdParty  `json:"third_party"`
	Tools      []Tool        `json:"tools"`
	Dir        string        `json:"-"`
}

// Tool: инструмент для компьютера (adb), который установщик качает с официального адреса по согласию человека.
// Файлы по системам: windows, darwin, linux; размер и SHA-1 из официального списка SDK Google
type Tool struct {
	ID             string              `json:"id"`
	Name           string              `json:"name"`
	Version        string              `json:"version"`
	Page           string              `json:"page"`
	Terms          string              `json:"terms"`
	License        string              `json:"license"`
	ChecksumSource string              `json:"checksum_source"`
	Files          map[string]ToolFile `json:"files"`
}

type ToolFile struct {
	URL    string `json:"url"`
	Size   int64  `json:"size"`
	SHA1   string `json:"sha1"`
	SHA256 string `json:"sha256,omitempty"`
}

func (m *Manifest) Tool(id string) *Tool {
	for i := range m.Tools {
		if m.Tools[i].ID == id {
			return &m.Tools[i]
		}
	}
	return nil
}

type ManifestApp struct {
	Module      string          `json:"module"`
	Title       string          `json:"title"`
	File        string          `json:"file"`
	Package     string          `json:"package"`
	VersionCode int64           `json:"versionCode"`
	VersionName string          `json:"versionName"`
	MinSdk      int             `json:"minSdk"`
	ABIRaw      json.RawMessage `json:"abi"`
	Sha256      string          `json:"sha256"`
	Size        int64           `json:"size"`
	CertSha256  string          `json:"cert_sha256"`
	Depends     []string        `json:"depends"`
}

type ThirdParty struct {
	Name          string   `json:"name"`
	Package       string   `json:"package"`
	PinnedVersion string   `json:"pinned_version"`
	Download      string   `json:"download"`
	Files         []TPFile `json:"files"`
	Releases      string   `json:"releases"`
	License       string   `json:"license"`
	CertSha256    string   `json:"cert_sha256"`
}

// TPFile: точный файл закреплённой версии под процессор ТВ (abi «any» подходит любому) с размером и суммой со страницы
// релиза GitHub (data/third-party-sources.json, поле files)
type TPFile struct {
	ABI    string `json:"abi"`
	URL    string `json:"url"`
	Size   int64  `json:"size"`
	Sha256 string `json:"sha256"`
}

// FileFor: файл под процессоры ТВ по порядку (первый в списке ТВ основной), иначе общий для всех. У записи без files
// (Key Mapper) одна ссылка download без суммы файла: сверяется только сертификат
func (t *ThirdParty) FileFor(abis []string) *TPFile {
	for _, a := range abis {
		for i := range t.Files {
			if t.Files[i].ABI == a {
				return &t.Files[i]
			}
		}
	}
	for i := range t.Files {
		if t.Files[i].ABI == "any" {
			return &t.Files[i]
		}
	}
	if len(t.Files) == 0 && t.Download != "" {
		return &TPFile{ABI: "any", URL: t.Download}
	}
	return nil
}

// ABI: список архитектур приложения; пусто значит «любая» (в описи строка "any")
func (a ManifestApp) ABI() []string {
	var l []string
	if json.Unmarshal(a.ABIRaw, &l) == nil {
		return l
	}
	return nil
}

func loadManifest(dir string) (*Manifest, error) {
	b, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
	if err != nil {
		return nil, fmt.Errorf("нет описи выпуска manifest.json в %s", dir)
	}
	var m Manifest
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, fmt.Errorf("опись выпуска не читается: %v", err)
	}
	if m.Product != "HYDE TV" || len(m.Apps) == 0 {
		return nil, fmt.Errorf("это не опись выпуска HYDE TV: %s", dir)
	}
	m.Dir = dir
	return &m, nil
}

func (m *Manifest) App(module string) *ManifestApp {
	for i := range m.Apps {
		if m.Apps[i].Module == module {
			return &m.Apps[i]
		}
	}
	return nil
}

func (m *Manifest) Third(pkg string) *ThirdParty {
	for i := range m.ThirdParty {
		if m.ThirdParty[i].Package == pkg {
			return &m.ThirdParty[i]
		}
	}
	return nil
}

// sha256File: SHA-256 файла шестнадцатеричной строкой
func sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// CheckFile: файл приложения на месте и совпадает с описью (размер и SHA-256). Пусто: всё верно
func (m *Manifest) CheckFile(a *ManifestApp) string {
	path := filepath.Join(m.Dir, a.File)
	f, err := os.Open(path)
	if err != nil {
		return "нет файла " + a.File
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return "не читается " + a.File
	}
	if n != a.Size {
		return fmt.Sprintf("%s: размер %d вместо %d (файл скачан не до конца?)", a.File, n, a.Size)
	}
	if !strings.EqualFold(hex.EncodeToString(h.Sum(nil)), a.Sha256) {
		return a.File + ": сумма не совпадает с описью (файл повреждён или подменён)"
	}
	return ""
}
