package main

// adb с сайта Google: распаковка без выхода за папку, сверка сумм, скачивание с подставного сервера, где искать adb.
// В сеть проверки не ходят

import (
	"archive/zip"
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func zipOf(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, body := range files {
		h := &zip.FileHeader{Name: name, Method: zip.Deflate}
		h.SetMode(0o755)
		w, err := zw.CreateHeader(h)
		if err != nil {
			t.Fatal(err)
		}
		w.Write([]byte(body))
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestUnzipPlatformTools(t *testing.T) {
	dir := t.TempDir()
	good := filepath.Join(dir, "good.zip")
	os.WriteFile(good, zipOf(t, map[string]string{"platform-tools/" + exeName("adb"): "adb", "platform-tools/lib64/x.so": "lib",
		"platform-tools/NOTICE.txt": "n", "other/readme.txt": "чужое"}), 0o644)
	dest := filepath.Join(dir, "pt")
	if err := unzipPlatformTools(good, dest); err != nil {
		t.Fatal(err)
	}
	for _, f := range []string{exeName("adb"), filepath.Join("lib64", "x.so"), "NOTICE.txt"} {
		if _, err := os.Stat(filepath.Join(dest, f)); err != nil {
			t.Fatalf("не распаковано %s", f)
		}
	}
	if _, err := os.Stat(filepath.Join(dest, "readme.txt")); err == nil {
		t.Fatal("распаковано не из platform-tools/")
	}
	for name, body := range map[string]map[string]string{
		"наружу":     {"platform-tools/" + exeName("adb"): "adb", "platform-tools/../../evil.txt": "x"},
		"бэкслэш":    {"platform-tools/" + exeName("adb"): "adb", `platform-tools/..\evil.txt`: "x"},
		"без adb":    {"platform-tools/fastboot": "fb"},
		"абсолютный": {"platform-tools/" + exeName("adb"): "adb", "platform-tools//etc/x": "x"},
	} {
		z := filepath.Join(dir, "bad.zip")
		os.WriteFile(z, zipOf(t, body), 0o644)
		d := filepath.Join(dir, "bad-"+strings.ReplaceAll(name, " ", "_"))
		if err := unzipPlatformTools(z, d); err == nil {
			t.Fatalf("%s: архив должен отклоняться", name)
		}
	}
	if _, err := os.Stat(filepath.Join(dir, "evil.txt")); err == nil {
		t.Fatal("файл вышел за папку назначения")
	}
}

func TestCheckSums(t *testing.T) {
	f := filepath.Join(t.TempDir(), "a.zip")
	os.WriteFile(f, []byte("hello"), 0o644)
	s := sha1.Sum([]byte("hello"))
	ok := &ToolFile{Size: 5, SHA1: hex.EncodeToString(s[:])}
	if err := checkSums(f, ok); err != nil {
		t.Fatal(err)
	}
	if err := checkSums(f, &ToolFile{Size: 6, SHA1: ok.SHA1}); err == nil || !strings.Contains(err.Error(), "размер") {
		t.Fatalf("размер: %v", err)
	}
	if err := checkSums(f, &ToolFile{Size: 5, SHA1: strings.Repeat("0", 40)}); err == nil || !strings.Contains(err.Error(), "SHA-1") {
		t.Fatalf("SHA-1: %v", err)
	}
	if err := checkSums(f, &ToolFile{Size: 5, SHA1: ok.SHA1, SHA256: strings.Repeat("0", 64)}); err == nil {
		t.Fatal("SHA-256 должна проверяться, если есть")
	}
}

// adbEnv: своя папка HYDE TV и выпуск с записью об adb на подставном сервере
func adbEnv(t *testing.T, body []byte) (*Manifest, *httptest.Server) {
	t.Helper()
	t.Setenv("GLASSTV_HOME", t.TempDir())
	t.Setenv("GLASSTV_TOOLS", "")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(body) }))
	t.Cleanup(srv.Close)
	s := sha1.Sum(body)
	m := testManifest(t)
	m.Tools = []Tool{{ID: "platform-tools", Name: "Android SDK Platform-Tools (adb)", Version: "37.0.1", Terms: "https://developer.android.com/studio/terms",
		Files: map[string]ToolFile{runtime.GOOS: {URL: srv.URL + "/platform-tools_r37.0.1.zip", Size: int64(len(body)), SHA1: hex.EncodeToString(s[:])}}}}
	old := adbVersionOf
	adbVersionOf = func(string) string { return "Android Debug Bridge version 1.0.41\nVersion 37.0.1-15733141\n" }
	t.Cleanup(func() { adbVersionOf = old })
	return m, srv
}

func TestDownloadADB(t *testing.T) {
	body := zipOf(t, map[string]string{"platform-tools/" + exeName("adb"): "adb", "platform-tools/NOTICE.txt": "n"})
	m, _ := adbEnv(t, body)
	off, err := adbOffer(m)
	if err != nil || off.Version != "37.0.1" || off.Host != "127.0.0.1" {
		t.Fatalf("предложение: %+v %v", off, err)
	}
	var evs []Event
	a, err := downloadADB(m, func(e Event) { evs = append(evs, e) })
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(glasstvHome(), "tools", "platform-tools", exeName("adb"))
	if a.Path != want || a.Version != "37.0.1" {
		t.Fatalf("adb: %+v, ждали %s", a, want)
	}
	if ents, _ := os.ReadDir(filepath.Join(glasstvHome(), "downloads")); len(ents) != 0 {
		t.Fatal("скачанный архив не убран после распаковки")
	}
	found := false
	for _, c := range adbCandidates() {
		if c == want {
			found = true
		}
	}
	if !found {
		t.Fatal("findADB не смотрит туда, куда распакован adb")
	}
	// Папка platform-tools уже есть (свой прежний adb): новый ложится рядом, в platform-tools-<версия>
	b, err := downloadADB(m, nil)
	if err != nil || filepath.Base(filepath.Dir(b.Path)) != "platform-tools-37.0.1" {
		t.Fatalf("второй раз: %+v %v", b, err)
	}
}

func TestDownloadADBRejects(t *testing.T) {
	body := zipOf(t, map[string]string{"platform-tools/" + exeName("adb"): "adb"})
	m, _ := adbEnv(t, body)
	f := m.Tools[0].Files[runtime.GOOS]
	f.SHA1 = strings.Repeat("1", 40)
	m.Tools[0].Files[runtime.GOOS] = f
	if _, err := downloadADB(m, nil); err == nil || !strings.Contains(err.Error(), "не совпал") {
		t.Fatalf("чужая сумма: %v", err)
	}
	if _, err := os.Stat(filepath.Join(glasstvHome(), "tools", "platform-tools")); err == nil {
		t.Fatal("после несовпадения суммы что-то распаковано")
	}
	m2, _ := adbEnv(t, body)
	adbVersionOf = func(string) string { return "Version 29.0.6-1\n" }
	if _, err := downloadADB(m2, nil); err == nil || !strings.Contains(err.Error(), "версию") {
		t.Fatalf("старая версия после распаковки: %v", err)
	}
	m3 := testManifest(t)
	if _, err := adbOffer(m3); err == nil {
		t.Fatal("без записи об adb в описи предлагать нечего")
	}
}
