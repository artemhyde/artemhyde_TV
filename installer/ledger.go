package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
	"time"
)

// Журнал изменений ledger.tsv в том же виде, что у мастера (tools/lib.sh, ledger): время, что сделано, команда, обратная
// команда через табуляцию. Его понимают и tools/make-restore.sh, и откат установщика. Строка пишется до команды
// (намерение): если связь пропадёт посреди, обратная команда уже в журнале, а повтор отмены того, чего не было, безвреден
const ledgerHeader = "# GlassTV: журнал изменений на ТВ. Колонки через табуляцию: время, что сделано, команда, обратная команда.\n" +
	"# Обе команды идут в оболочку ТВ (adb shell). Пустая обратная команда значит «откатом не покрывается».\n"

type LedgerEntry struct {
	Time, What, Do, Undo string
}

func clean(s string) string {
	return strings.NewReplacer("\t", " ", "\r", " ", "\n", " ").Replace(s)
}

func appendLedger(path string, e LedgerEntry) error {
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if e.Time == "" {
		e.Time = time.Now().Format("2006-01-02 15:04:05")
	}
	_, err = fmt.Fprintf(f, "%s\t%s\t%s\t%s\n", clean(e.Time), clean(e.What), clean(e.Do), clean(e.Undo))
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	return err
}

func readLedger(path string) ([]LedgerEntry, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var r []LedgerEntry
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<16), 1<<20)
	for sc.Scan() {
		ln := strings.TrimRight(sc.Text(), "\r")
		if strings.TrimSpace(ln) == "" || strings.HasPrefix(ln, "#") {
			continue
		}
		f := strings.Split(ln, "\t")
		for len(f) < 4 {
			f = append(f, "")
		}
		r = append(r, LedgerEntry{Time: f[0], What: f[1], Do: f[2], Undo: f[3]})
	}
	return r, sc.Err()
}
