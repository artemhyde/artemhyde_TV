package main

import (
	"os"
	"path/filepath"
	"testing"
)

// Подпись настоящих файлов выпуска: лежат рядом с рабочей копией (TCL-TV/releases/1.0.0), в публичной копии их нет,
// тогда проверка пропускается. Путь можно задать: HYDE_RELEASE=папка
func TestAPKCertRelease(t *testing.T) {
	dir := os.Getenv("HYDE_RELEASE")
	if dir == "" {
		dir = filepath.Join("..", "..", "releases", "1.0.0")
	}
	m, err := loadManifest(dir)
	if err != nil {
		t.Skip("нет выпуска рядом: " + err.Error())
	}
	n := 0
	for _, a := range m.Apps {
		got, err := apkCertSHA256(filepath.Join(dir, a.File))
		if err != nil {
			t.Fatalf("%s: %v", a.File, err)
		}
		if got != m.SignerCert || got != a.CertSha256 {
			t.Fatalf("%s: сертификат %s, в описи %s", a.File, got, m.SignerCert)
		}
		n++
	}
	if n < 10 {
		t.Fatalf("проверено файлов %d", n)
	}
}

func TestAPKCertNotAPK(t *testing.T) {
	p := filepath.Join(t.TempDir(), "x.apk")
	if err := os.WriteFile(p, []byte("not a zip at all, just text"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := apkCertSHA256(p); err == nil {
		t.Fatal("не ZIP прочитан как APK")
	}
}
