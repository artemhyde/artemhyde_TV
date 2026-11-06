package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"time"
)

const maxDownload = 300 << 20

// fetchFile: скачать файл в dst через dst.part, не больше limit байт (0: 300 МБ). Только https; исключение для
// 127.0.0.1, на нём проверки подставляют свой сервер. Оборвалось или больше лимита: недокачанное удаляется
func fetchFile(raw, dst string, limit int64, progress func(done, total int64)) error {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return fmt.Errorf("плохой адрес %s", raw)
	}
	if u.Scheme != "https" && u.Hostname() != "127.0.0.1" {
		return fmt.Errorf("скачиваю только по https: %s", raw)
	}
	if limit <= 0 || limit > maxDownload {
		limit = maxDownload
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Minute)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, "GET", raw, nil)
	req.Header.Set("User-Agent", "hyde-install")
	client := &http.Client{Transport: &http.Transport{ResponseHeaderTimeout: time.Minute, Proxy: http.ProxyFromEnvironment}}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("не скачалось: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("сервер ответил %s", resp.Status)
	}
	part := dst + ".part"
	f, err := os.Create(part)
	if err != nil {
		return err
	}
	var done int64
	buf := make([]byte, 64<<10)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if done += int64(n); done > limit {
				f.Close()
				os.Remove(part)
				return fmt.Errorf("файл больше ожидаемого (%s): прерываю", mb(limit))
			}
			if _, werr := f.Write(buf[:n]); werr != nil {
				f.Close()
				os.Remove(part)
				return werr
			}
			if progress != nil {
				progress(done, resp.ContentLength)
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			f.Close()
			os.Remove(part)
			return fmt.Errorf("связь оборвалась на %d КБ: %v", done>>10, rerr)
		}
	}
	if err := f.Close(); err != nil {
		os.Remove(part)
		return err
	}
	return os.Rename(part, dst)
}

// downloadThirdParty: чужое приложение из описи выпуска (third_party) в свою папку, затем сверка сертификата подписи
// с записью в описи. Не совпал: файл удаляется, установки не будет (CLAUDE.md, «Скачивание»)
func downloadThirdParty(tp *ThirdParty, dir string, progress func(done, total int64)) (string, error) {
	u, err := url.Parse(tp.Download)
	if err != nil {
		return "", fmt.Errorf("плохой адрес %s", tp.Download)
	}
	if tp.CertSha256 == "" {
		return "", fmt.Errorf("в описи нет сертификата %s: проверить файл нечем", tp.Name)
	}
	dst := filepath.Join(dir, path.Base(u.Path))
	if err := fetchFile(tp.Download, dst, 0, progress); err != nil {
		return "", err
	}
	cert, err := certOf(dst)
	if err != nil || cert != tp.CertSha256 {
		os.Remove(dst)
		if err == nil {
			err = fmt.Errorf("сертификат %s…, а в описи %s…", prefix(cert, 12), prefix(tp.CertSha256, 12))
		}
		return "", fmt.Errorf("подпись %s не совпала, файл удалён: %v", tp.Name, err)
	}
	return dst, nil
}

var errNoTool = errors.New("в описи выпуска нет записи об этом инструменте")
