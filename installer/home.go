package main

import (
	"os"
	"path/filepath"
	"runtime"
)

// Папка HYDE TV у человека: ~/.glasstv (или GLASSTV_HOME). В ней tools (adb, та же папка, что у мастера в CLAUDE.md;
// GLASSTV_TOOLS её заменяет), downloads (скачанное, каждое в своей новой папке) и backup (снимки «до» и журналы).
// Журналы лежат здесь, а не рядом с выпуском: папку выпуска человек может удалить или скачать следующий выпуск в другую,
// а откат и обновление должны найти прежние установки этого ТВ
func glasstvHome() string {
	if h := os.Getenv("GLASSTV_HOME"); h != "" {
		return h
	}
	if h, err := os.UserHomeDir(); err == nil {
		return filepath.Join(h, ".glasstv")
	}
	return ".glasstv"
}

func toolsDir() string {
	if t := os.Getenv("GLASSTV_TOOLS"); t != "" {
		return t
	}
	return filepath.Join(glasstvHome(), "tools")
}

func defaultBackupRoot() string { return filepath.Join(glasstvHome(), "backup") }

// freeMB: свободное место на диске, где лежит путь (или ближайшая существующая папка над ним); -1, если не узнать
func freeMB(path string) int64 {
	for p := path; ; p = filepath.Dir(p) {
		if _, err := os.Stat(p); err == nil {
			return diskFreeMB(p)
		}
		if filepath.Dir(p) == p {
			return -1
		}
	}
}

// PC: что установщик знает о компьютере (шаг «Компьютер» окна мастера)
type PC struct {
	OS     string `json:"os"`
	Arch   string `json:"arch"`
	Home   string `json:"home"`
	FreeMB int64  `json:"free_mb"`
}

func pcInfo() PC {
	name := map[string]string{"windows": "Windows", "darwin": "macOS", "linux": "Linux"}[runtime.GOOS]
	return PC{OS: nonEmpty(name, runtime.GOOS), Arch: runtime.GOARCH, Home: glasstvHome(), FreeMB: freeMB(glasstvHome())}
}
