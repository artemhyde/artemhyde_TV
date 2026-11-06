package main

import (
	"archive/zip"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// adb для человека без Android SDK (план Codex, этап 2: «на чистой Windows без SDK»). В архив выпуска adb не кладётся:
// условия Android SDK не разрешают его раздавать. Установщик по согласию человека качает с dl.google.com ту версию
// platform-tools, что записана в описи выпуска (data/third-party-sources.json, раздел tools), сверяет размер и SHA-1 из
// официального списка SDK Google, распаковывает в папку инструментов (~/.glasstv/tools/platform-tools, ту же, что у
// мастера в CLAUDE.md) и проверяет, что adb version показывает ту же версию

// ADBOffer: что предложить человеку, если adb не найден
type ADBOffer struct {
	Name    string `json:"name"`
	Version string `json:"version"`
	Size    string `json:"size"`
	Host    string `json:"host"`
	URL     string `json:"url"`
	Terms   string `json:"terms"`
	Page    string `json:"page"`
	Dest    string `json:"dest"`
}

// adbFile: запись и файл platform-tools для этой системы. Linux не на x86-64: у Google adb нет
func adbFile(m *Manifest) (*Tool, *ToolFile, error) {
	t := m.Tool("platform-tools")
	if t == nil {
		return nil, nil, errors.New("в описи выпуска нет записи об adb: скачайте Android SDK Platform-Tools с developer.android.com сами и положите папку platform-tools рядом с установщиком")
	}
	if runtime.GOOS == "linux" && runtime.GOARCH != "amd64" {
		return t, nil, errors.New("для Linux на ARM у Google adb нет: поставьте его пакетом системы (например, sudo apt install adb)")
	}
	f, ok := t.Files[runtime.GOOS]
	if !ok || f.URL == "" || f.Size <= 0 || (f.SHA1 == "" && f.SHA256 == "") {
		return t, nil, fmt.Errorf("в описи нет adb для %s", runtime.GOOS)
	}
	return t, &f, nil
}

func adbOffer(m *Manifest) (*ADBOffer, error) {
	t, f, err := adbFile(m)
	if err != nil {
		return nil, err
	}
	u, _ := url.Parse(f.URL)
	return &ADBOffer{Name: t.Name, Version: t.Version, Size: mb(f.Size), Host: u.Hostname(), URL: f.URL, Terms: t.Terms, Page: t.Page,
		Dest: adbDest(t.Version)}, nil
}

// adbDest: куда распаковать. Своя папка platform-tools, а если она уже есть (там старый или чужой adb, его не трогаем),
// то platform-tools-<версия>; findADB смотрит обе
func adbDest(ver string) string {
	d := filepath.Join(toolsDir(), "platform-tools")
	if _, err := os.Stat(d); err == nil {
		return filepath.Join(toolsDir(), "platform-tools-"+ver)
	}
	return d
}

// checkSums: размер и суммы файла как в описи
func checkSums(file string, f *ToolFile) error {
	fh, err := os.Open(file)
	if err != nil {
		return err
	}
	defer fh.Close()
	h1, h256 := sha1.New(), sha256.New()
	n, err := io.Copy(io.MultiWriter(h1, h256), fh)
	if err != nil {
		return err
	}
	if n != f.Size {
		return fmt.Errorf("размер %d байт, а в описи %d", n, f.Size)
	}
	if f.SHA1 != "" && !strings.EqualFold(hex.EncodeToString(h1.Sum(nil)), f.SHA1) {
		return errors.New("сумма SHA-1 не совпала с официальной")
	}
	if f.SHA256 != "" && !strings.EqualFold(hex.EncodeToString(h256.Sum(nil)), f.SHA256) {
		return errors.New("сумма SHA-256 не совпала с описью")
	}
	return nil
}

// unzipPlatformTools: из архива только папка platform-tools/, без выхода за папку назначения; ссылки только внутрь неё
func unzipPlatformTools(zipPath, dest string) error {
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return fmt.Errorf("архив не открывается: %v", err)
	}
	defer zr.Close()
	var total int64
	found := false
	for _, zf := range zr.File {
		rel, ok := strings.CutPrefix(zf.Name, "platform-tools/")
		if !ok || rel == "" {
			continue
		}
		clean := filepath.Clean(filepath.FromSlash(rel))
		if strings.HasPrefix(rel, "/") || filepath.IsAbs(clean) || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) || strings.Contains(rel, `\`) {
			return fmt.Errorf("в архиве странный путь %q: не распаковываю", zf.Name)
		}
		target := filepath.Join(dest, clean)
		mode := zf.Mode()
		switch {
		case mode.IsDir():
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		case mode&os.ModeSymlink != 0:
			rc, err := zf.Open()
			if err != nil {
				return err
			}
			b, _ := io.ReadAll(io.LimitReader(rc, 4096))
			rc.Close()
			link := filepath.FromSlash(string(b))
			if filepath.IsAbs(link) || !strings.HasPrefix(filepath.Clean(filepath.Join(filepath.Dir(target), link)), filepath.Clean(dest)+string(filepath.Separator)) {
				return fmt.Errorf("в архиве ссылка наружу %q: не распаковываю", zf.Name)
			}
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			if err := os.Symlink(link, target); err != nil {
				return err
			}
			continue
		case !mode.IsRegular():
			continue
		}
		if total += int64(zf.UncompressedSize64); total > 300<<20 {
			return errors.New("в архиве больше 300 МБ: так не бывает, не распаковываю")
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		perm := os.FileMode(0o644)
		if mode&0o111 != 0 || clean == exeName("adb") {
			perm = 0o755
		}
		rc, err := zf.Open()
		if err != nil {
			return err
		}
		out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, perm)
		if err != nil {
			rc.Close()
			return err
		}
		_, err = io.Copy(out, io.LimitReader(rc, int64(zf.UncompressedSize64)+1))
		rc.Close()
		if cerr := out.Close(); err == nil {
			err = cerr
		}
		if err != nil {
			return err
		}
		if clean == exeName("adb") {
			found = true
		}
	}
	if !found {
		return errors.New("в архиве нет platform-tools/" + exeName("adb"))
	}
	return nil
}

// adbVersionOf: ответ «adb version» (в проверках подставной)
var adbVersionOf = func(path string) string {
	out, _ := runCmd(path, 30*time.Second, "version")
	return out
}

// downloadADB: скачать, сверить, распаковать и проверить adb. Вызывать только после согласия человека с условиями Google
func downloadADB(m *Manifest, emit func(Event)) (*ADB, error) {
	if emit == nil {
		emit = func(Event) {}
	}
	t, f, err := adbFile(m)
	if err != nil {
		return nil, err
	}
	need := f.Size*4>>20 + 50
	if free := freeMB(glasstvHome()); free >= 0 && free < need {
		return nil, fmt.Errorf("на диске мало места: свободно %d МБ, нужно около %d МБ", free, need)
	}
	u, _ := url.Parse(f.URL)
	dl := filepath.Join(glasstvHome(), "downloads", "platform-tools-"+t.Version+"-"+time.Now().Format("20060102-150405"))
	file := filepath.Join(dl, path.Base(u.Path))
	what := fmt.Sprintf("скачиваю %s %s с %s (%s)", t.Name, t.Version, u.Hostname(), mb(f.Size))
	emit(Event{Status: "start", Group: "adb", What: what})
	last := time.Now()
	err = fetchFile(f.URL, file, f.Size, func(done, total int64) {
		if time.Since(last) > time.Second {
			last = time.Now()
			emit(Event{Status: "start", Group: "adb", What: what, Detail: fmt.Sprintf("%s из %s", mb(done), mb(f.Size))})
		}
	})
	if err != nil {
		os.RemoveAll(dl)
		return nil, fmt.Errorf("adb не скачался: %v", err)
	}
	emit(Event{Status: "ok", Group: "adb", What: what})
	sumWhat := "сверяю размер и сумму с официальным списком Google"
	if err := checkSums(file, f); err != nil {
		os.RemoveAll(dl)
		emit(Event{Status: "fail", Group: "adb", What: sumWhat, Detail: err.Error()})
		return nil, fmt.Errorf("скачанный adb не совпал с описью, файл удалён: %v", err)
	}
	emit(Event{Status: "ok", Group: "adb", What: sumWhat})
	dest := adbDest(t.Version)
	unzWhat := "распаковываю в " + dest
	tmp := dest + ".part"
	os.RemoveAll(tmp)
	if err := unzipPlatformTools(file, tmp); err != nil {
		os.RemoveAll(tmp)
		os.RemoveAll(dl)
		return nil, err
	}
	if _, err := os.Stat(dest); err == nil {
		if err := os.RemoveAll(dest); err != nil { // своя папка той же версии от прошлого раза: adb из неё не запустился
			os.RemoveAll(tmp)
			return nil, fmt.Errorf("не убрать прежнюю %s (adb из неё сейчас работает?): %v", dest, err)
		}
	}
	if err := os.Rename(tmp, dest); err != nil {
		os.RemoveAll(tmp)
		return nil, err
	}
	os.RemoveAll(dl)
	emit(Event{Status: "ok", Group: "adb", What: unzWhat})
	adb := filepath.Join(dest, exeName("adb"))
	out := adbVersionOf(adb)
	if major, ver := adbMajor(out); major < 30 || ver != t.Version {
		return nil, fmt.Errorf("распакованный adb показывает версию %q, а ждали %s: %s", ver, t.Version, lastLine(out))
	}
	emit(Event{Status: "ok", Group: "adb", What: "adb " + t.Version + " работает"})
	return &ADB{Path: adb, Version: t.Version}, nil
}
