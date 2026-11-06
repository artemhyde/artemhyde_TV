package main

import (
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io/fs"
	"net"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"sync"
	"time"
)

//go:embed web
var webFS embed.FS

// Окно мастера: страница в браузере этого компьютера. Сервер слушает только 127.0.0.1 на случайном порту; каждый запрос
// к API несёт случайный ключ из адреса страницы (после #, в запросы браузер его сам не кладёт), а заголовок Host обязан
// совпадать с адресом сервера: чужие страницы в браузере не могут управлять установщиком. Без запросов 30 минут
// установщик закрывается сам
type uiServer struct {
	adb       *ADB
	adbErr    string
	token     string
	addr      string
	rec       Recipe
	relDir    string
	mu        sync.Mutex
	last      time.Time
	passports map[string]Passport
}

const idleLimit = 30 * time.Minute

func cmdUI(args []string) error {
	fl := flag.NewFlagSet("ui", flag.ContinueOnError)
	rel := fl.String("release", "", "папка выпуска")
	noBrowser := fl.Bool("no-browser", false, "не открывать браузер (адрес будет в окне команд)")
	if err := fl.Parse(args); err != nil {
		return err
	}
	rec, err := loadRecipe()
	if err != nil {
		return err
	}
	s := &uiServer{rec: rec, relDir: releaseDir(*rel), passports: map[string]Passport{}, last: time.Now(), token: randHex(16)}
	if a, err := findADB(); err != nil {
		s.adbErr = err.Error()
	} else {
		s.adb = a
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	s.addr = ln.Addr().String()
	url := "http://" + s.addr + "/#" + s.token
	fmt.Println("Окно мастера HYDE TV: " + url)
	fmt.Println("Это окно не закрывайте, пока идёт работа. Без действий 30 минут установщик закроется сам.")
	if !*noBrowser {
		openBrowser(url)
	}
	mux := http.NewServeMux()
	page, _ := fs.Sub(webFS, "web")
	mux.Handle("GET /", http.FileServerFS(page))
	mux.HandleFunc("GET /api/hello", s.api(s.hello))
	mux.HandleFunc("GET /api/devices", s.api(s.devices))
	mux.HandleFunc("POST /api/connect", s.api(s.connect))
	mux.HandleFunc("POST /api/pair", s.api(s.pair))
	mux.HandleFunc("POST /api/inspect", s.api(s.inspect))
	mux.HandleFunc("POST /api/plan", s.api(s.plan))
	go func() {
		for range time.Tick(time.Minute) {
			s.mu.Lock()
			idle := time.Since(s.last)
			s.mu.Unlock()
			if idle > idleLimit {
				fmt.Println("30 минут без действий: установщик закрывается.")
				os.Exit(0)
			}
		}
	}()
	srv := &http.Server{Handler: s.guard(mux), ReadHeaderTimeout: 10 * time.Second}
	return srv.Serve(ln)
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}

// guard: только свой адрес в Host (защита от подмены имени), для API ещё и ключ
func (s *uiServer) guard(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != s.addr {
			http.Error(w, "чужой адрес", http.StatusForbidden)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		h.ServeHTTP(w, r)
	})
}

type apiFunc func(r *http.Request) (any, error)

func (s *uiServer) api(f apiFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Hyde-Key") != s.token {
			http.Error(w, "нет ключа", http.StatusForbidden)
			return
		}
		s.mu.Lock()
		s.last = time.Now()
		s.mu.Unlock()
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		v, err := f(r)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			v = map[string]string{"error": err.Error()}
		}
		e := json.NewEncoder(w)
		e.SetEscapeHTML(false)
		_ = e.Encode(v)
	}
}

func readBody(r *http.Request, v any) error {
	return json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<16)).Decode(v)
}

func (s *uiServer) needADB() error {
	if s.adb == nil {
		return fmt.Errorf("%s", s.adbErr)
	}
	return nil
}

func (s *uiServer) hello(r *http.Request) (any, error) {
	out := map[string]any{"version": toolVersion}
	if s.adb != nil {
		out["adb"] = s.adb
	} else {
		out["adb_error"] = s.adbErr
	}
	m, err := loadManifest(s.relDir)
	if err != nil {
		out["release_error"] = err.Error()
		return out, nil
	}
	var bad []string
	for i := range m.Apps {
		if x := m.CheckFile(&m.Apps[i]); x != "" {
			bad = append(bad, x)
		}
	}
	out["release"] = map[string]any{"version": m.Release, "commit": short(m.BuiltFrom.Commit), "apps": len(m.Apps), "problems": bad}
	return out, nil
}

func (s *uiServer) devices(r *http.Request) (any, error) {
	if err := s.needADB(); err != nil {
		return nil, err
	}
	ms, _ := s.adb.Mdns()
	ds, _ := s.adb.Devices()
	return map[string]any{"mdns": ms, "devices": ds}, nil
}

func (s *uiServer) connect(r *http.Request) (any, error) {
	if err := s.needADB(); err != nil {
		return nil, err
	}
	var q struct{ Addr string }
	if err := readBody(r, &q); err != nil || q.Addr == "" {
		return nil, fmt.Errorf("нужен адрес ТВ")
	}
	return s.adb.Connect(q.Addr), nil
}

func (s *uiServer) pair(r *http.Request) (any, error) {
	if err := s.needADB(); err != nil {
		return nil, err
	}
	var q struct{ Addr, Code string }
	if err := readBody(r, &q); err != nil || q.Addr == "" || q.Code == "" {
		return nil, fmt.Errorf("нужны адрес окна сопряжения и код")
	}
	ok, raw := s.adb.Pair(q.Addr, q.Code)
	return map[string]any{"ok": ok, "raw": raw}, nil
}

func (s *uiServer) inspect(r *http.Request) (any, error) {
	if err := s.needADB(); err != nil {
		return nil, err
	}
	var q struct{ Serial string }
	if err := readBody(r, &q); err != nil || q.Serial == "" {
		return nil, fmt.Errorf("не выбран ТВ")
	}
	p, _, err := readPassport(s.adb, q.Serial)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	s.passports[q.Serial] = p
	s.mu.Unlock()
	return map[string]any{"caps": capsOf(p), "text": capsText(capsOf(p))}, nil
}

func (s *uiServer) plan(r *http.Request) (any, error) {
	var q struct {
		Serial  string          `json:"serial"`
		Modules map[string]bool `json:"modules"`
		Home    *bool           `json:"home"`
	}
	if err := readBody(r, &q); err != nil {
		return nil, err
	}
	s.mu.Lock()
	p, ok := s.passports[q.Serial]
	s.mu.Unlock()
	if !ok {
		return nil, fmt.Errorf("сначала прочитайте паспорт ТВ")
	}
	m, err := loadManifest(s.relDir)
	if err != nil {
		return nil, err
	}
	if q.Modules == nil {
		q.Modules = map[string]bool{}
	}
	return buildPlan(s.rec, m, p, Choices{Modules: q.Modules, Home: q.Home}, false), nil
}
