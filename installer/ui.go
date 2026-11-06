package main

import (
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
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
	adbErrV   error
	token     string
	addr      string
	rec       Recipe
	relDir    string
	mu        sync.Mutex
	last      time.Time
	passports map[string]Passport
	job       *uiJob
}

// uiJob: установка или откат идут в фоне, страница раз в секунду забирает ход (/api/job). Одновременно одна работа
type uiJob struct {
	Kind    string  `json:"kind"`
	Running bool    `json:"running"`
	Events  []Event `json:"events"`
	Result  any     `json:"result,omitempty"`
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
		s.adbErr, s.adbErrV = err.Error(), err
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
	mux.HandleFunc("POST /api/apply", s.api(s.apply))
	mux.HandleFunc("POST /api/verify", s.api(s.verify))
	mux.HandleFunc("POST /api/restore", s.api(s.restore))
	mux.HandleFunc("POST /api/getadb", s.api(s.getADB))
	mux.HandleFunc("POST /api/guard", s.api(s.guardAPI))
	mux.HandleFunc("GET /api/job", s.api(s.jobState))
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
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.adb == nil {
		return fmt.Errorf("%s", s.adbErr)
	}
	return nil
}

// getADB: скачать adb с сайта Google. Только с галочкой согласия на странице (условия Google принимает человек)
func (s *uiServer) getADB(r *http.Request) (any, error) {
	var q struct {
		Accept bool `json:"accept"`
	}
	if err := readBody(r, &q); err != nil || !q.Accept {
		return nil, fmt.Errorf("нужно согласие с условиями Google")
	}
	s.mu.Lock()
	have := s.adb != nil
	s.mu.Unlock()
	if have {
		return map[string]bool{"started": false}, nil
	}
	m, err := loadManifest(s.relDir)
	if err != nil {
		return nil, err
	}
	err = s.startJob("adb", func(emit func(Event)) any {
		a, err := downloadADB(m, emit)
		if err != nil {
			emit(Event{Status: "fail", What: err.Error()})
			return map[string]string{"error": err.Error()}
		}
		s.mu.Lock()
		s.adb, s.adbErr, s.adbErrV = a, "", nil
		s.mu.Unlock()
		return map[string]any{"adb": a}
	})
	return map[string]bool{"started": err == nil}, err
}

func (s *uiServer) hello(r *http.Request) (any, error) {
	out := map[string]any{"version": toolVersion, "pc": pcInfo()}
	s.mu.Lock()
	adb, adbErr := s.adb, s.adbErr
	s.mu.Unlock()
	m, err := loadManifest(s.relDir)
	if adb != nil {
		out["adb"] = adb
	} else {
		out["adb_error"] = adbErr
		if err == nil && errors.Is(s.adbErrV, errNoADB) {
			if off, oerr := adbOffer(m); oerr == nil {
				out["adb_offer"] = off
			} else {
				out["adb_error"] = oerr.Error()
			}
		}
	}
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
	ms, _ := s.getA().Mdns()
	ds, _ := s.getA().Devices()
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
	return s.getA().Connect(q.Addr), nil
}

func (s *uiServer) pair(r *http.Request) (any, error) {
	if err := s.needADB(); err != nil {
		return nil, err
	}
	var q struct{ Addr, Code string }
	if err := readBody(r, &q); err != nil || q.Addr == "" || q.Code == "" {
		return nil, fmt.Errorf("нужны адрес окна сопряжения и код")
	}
	ok, raw := s.getA().Pair(q.Addr, q.Code)
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
	p, _, err := readPassport(s.getA(), q.Serial)
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

// startJob: одна работа за раз; события копятся, итог в Result
func (s *uiServer) startJob(kind string, work func(emit func(Event)) any) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.job != nil && s.job.Running {
		return fmt.Errorf("уже идёт: %s", s.job.Kind)
	}
	j := &uiJob{Kind: kind, Running: true}
	s.job = j
	go func() {
		emit := func(e Event) {
			s.mu.Lock()
			j.Events = append(j.Events, e)
			s.last = time.Now()
			s.mu.Unlock()
		}
		res := work(emit)
		s.mu.Lock()
		j.Result, j.Running = res, false
		s.mu.Unlock()
	}()
	return nil
}

func (s *uiServer) jobState(r *http.Request) (any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.job == nil {
		return map[string]any{"running": false}, nil
	}
	b, _ := json.Marshal(s.job)
	var cp map[string]any
	_ = json.Unmarshal(b, &cp)
	return cp, nil
}

func (s *uiServer) apply(r *http.Request) (any, error) {
	if err := s.needADB(); err != nil {
		return nil, err
	}
	var q struct {
		Serial        string          `json:"serial"`
		Modules       map[string]bool `json:"modules"`
		Home          *bool           `json:"home"`
		AllowDownload bool            `json:"allow_download"`
		TVFree        bool            `json:"tv_free"`
	}
	if err := readBody(r, &q); err != nil || q.Serial == "" {
		return nil, fmt.Errorf("не выбран ТВ")
	}
	m, err := loadManifest(s.relDir)
	if err != nil {
		return nil, err
	}
	if q.Modules == nil {
		q.Modules = map[string]bool{}
	}
	tv := s.adbTVFor(q.Serial)
	o := ApplyOpts{AllowDownload: q.AllowDownload, TVFree: q.TVFree, BackupRoot: backupRoot("")}
	err = s.startJob("установка", func(emit func(Event)) any {
		o.Emit = emit
		return runApply(tv, tv.a, s.rec, m, Choices{Modules: q.Modules, Home: q.Home}, o)
	})
	return map[string]bool{"started": err == nil}, err
}

// guardAPI: выключатели «Сторожа» после установки: {serial, what: adb|notes, set: ""|always|service|on|off}
func (s *uiServer) guardAPI(r *http.Request) (any, error) {
	if err := s.needADB(); err != nil {
		return nil, err
	}
	var q struct{ Serial, What, Set string }
	if err := readBody(r, &q); err != nil || q.Serial == "" {
		return nil, fmt.Errorf("не выбран ТВ")
	}
	out, err := guardSwitch(s.adbTVFor(q.Serial), q.What, q.Set)
	return map[string]string{"text": out}, err
}

func (s *uiServer) getA() *ADB {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.adb
}

// adbTVFor: ТВ по адресу из запроса через найденный adb
func (s *uiServer) adbTVFor(serial string) *adbTV {
	s.mu.Lock()
	defer s.mu.Unlock()
	return &adbTV{a: s.adb, serial: serial}
}

func (s *uiServer) verify(r *http.Request) (any, error) {
	if err := s.needADB(); err != nil {
		return nil, err
	}
	var q struct{ Serial string }
	if err := readBody(r, &q); err != nil || q.Serial == "" {
		return nil, fmt.Errorf("не выбран ТВ")
	}
	tv := s.adbTVFor(q.Serial)
	ds, err := sessionDirs(tv, "")
	if err != nil {
		return nil, err
	}
	cs, err := runVerify(tv, ds...)
	return map[string]any{"checks": cs, "backup": ds}, err
}

func (s *uiServer) restore(r *http.Request) (any, error) {
	if err := s.needADB(); err != nil {
		return nil, err
	}
	var q struct{ Serial string }
	if err := readBody(r, &q); err != nil || q.Serial == "" {
		return nil, fmt.Errorf("не выбран ТВ")
	}
	tv := s.adbTVFor(q.Serial)
	ds, err := sessionDirs(tv, "")
	if err != nil {
		return nil, err
	}
	err = s.startJob("откат", func(emit func(Event)) any {
		ra := runRestoreAll(tv, ds, emit)
		return map[string]any{"ok": ra.OK(), "code": ra.Code(), "text": ra.Text(), "backup": ds}
	})
	return map[string]bool{"started": err == nil}, err
}
