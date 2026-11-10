package app.glasstv.guard;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

// Крошечный веб-сервер для записок: телефон в домашней сети открывает http://<адрес ТВ>:8765,
// пишет текст, и он всплывает на экране. Наружу из дома порт не виден (роутер его не пробрасывает)
final class NoteServer {
    private final GuardService g;
    private final int port;
    private volatile ServerSocket ss;
    // Защита (06.11): не больше трёх запросов одновременно (лишние сразу закрываются), записка не чаще раза в 3 секунды
    private final java.util.concurrent.atomic.AtomicInteger busy = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastNoteAt;
    // Код (06.11, план Codex: записки только по коду): шесть цифр с экрана «Сторожа», телефон присылает его заголовком X-Code.
    // Пять неверных кодов за минуту: минуту записки не принимаются совсем, чтобы код нельзя было подобрать перебором
    private int wrong;
    private long wrongSince, lockedUntil;

    private synchronized boolean codeOk(String code) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return false;
        boolean ok = code != null && code.trim().equals(GuardService.notesCode(g));
        if (ok) { wrong = 0; return true; }
        if (now - wrongSince > 60_000) { wrongSince = now; wrong = 0; }
        if (++wrong >= 5) { lockedUntil = now + 60_000; wrong = 0; }
        return false;
    }

    private synchronized boolean locked() { return System.currentTimeMillis() < lockedUntil; }

    NoteServer(GuardService g, int port) { this.g = g; this.port = port; }

    void start() {
        Thread t = new Thread(() -> {
            try {
                ss = new ServerSocket(port);
                while (!ss.isClosed()) {
                    Socket s = ss.accept();
                    if (busy.incrementAndGet() > 3) {
                        busy.decrementAndGet();
                        try { s.close(); } catch (Exception ignored) { }
                        continue;
                    }
                    new Thread(() -> { try { handle(s); } finally { busy.decrementAndGet(); } }, "note").start();
                }
            } catch (Exception e) {
                if (ss == null || !ss.isClosed()) Log.w("guard", "сервер записок остановился", e);
            }
        }, "note-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() { try { if (ss != null) ss.close(); } catch (Exception ignored) { } }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(8000);
            InputStream in = sock.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] first = head.split("\r\n")[0].split(" ");
            String method = first.length > 0 ? first[0] : "", path = first.length > 1 ? first[1] : "/";
            int len = 0;
            String code = null;
            for (String line : head.split("\r\n")) {
                String low = line.toLowerCase(Locale.ROOT);
                if (low.startsWith("content-length:")) {
                    try { len = Math.min(4096, Integer.parseInt(line.substring(15).trim())); } catch (Exception ignored) { }
                } else if (low.startsWith("x-code:")) {
                    code = line.substring(7).trim();
                }
            }
            OutputStream out = sock.getOutputStream();
            if (method.equals("POST") && path.startsWith("/note")) {
                byte[] body = new byte[len];
                int got = 0;
                while (got < len) { int n = in.read(body, got, len - got); if (n < 0) break; got += n; }
                String text = new String(body, 0, got, StandardCharsets.UTF_8).trim();
                if (text.length() > 200) text = text.substring(0, 200);
                if (text.isEmpty()) { reply(out, 400, "text/plain", "пусто"); return; }
                if (locked()) { reply(out, 429, "text/plain", "много неверных кодов, подождите минуту"); return; }
                if (!codeOk(code)) { reply(out, 403, "text/plain", "код не подошёл"); return; }
                long now = System.currentTimeMillis();
                if (now - lastNoteAt < 3000) { reply(out, 429, "text/plain", "подождите пару секунд"); return; }
                lastNoteAt = now;
                g.showNote(text, "phone");
                reply(out, 200, "text/plain", "ok");
            } else if (method.equals("GET")) {
                reply(out, 200, "text/html", PAGE);
            } else {
                reply(out, 404, "text/plain", "нет");
            }
        } catch (Exception e) {
            Log.w("guard", "запрос записки", e);
        }
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (b.size() > 16384) return null;
            state = (c == '\r' || c == '\n') ? state + 1 : 0;
            if (state == 4) return b.toString("UTF-8");
        }
        return null;
    }

    private static void reply(OutputStream out, int code, String type, String body) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type
                + "; charset=utf-8\r\nContent-Length: " + data.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    // Страница для телефона: светлая в духе Apple, тёмная ночью
    private static final String PAGE = "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<meta name=\"apple-mobile-web-app-capable\" content=\"yes\"><title>Записка на ТВ</title><style>"
            + ":root{--bg:#f5f5f7;--card:#fff;--text:#1d1d1f;--muted:#6e6e73;--accent:#0071e3}"
            + "@media (prefers-color-scheme:dark){:root{--bg:#000;--card:#1c1c1e;--text:#f5f5f7;--muted:#98989d;--accent:#2997ff}}"
            + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);"
            + "font:17px/1.4 -apple-system,BlinkMacSystemFont,'SF Pro Text',Inter,system-ui,sans-serif;-webkit-font-smoothing:antialiased}"
            + "main{max-width:520px;margin:0 auto;padding:32px 16px}h1{font-size:32px;line-height:1.1;font-weight:700;letter-spacing:-.02em;margin:0 0 6px}"
            + "p{color:var(--muted);margin:0 0 22px}textarea{width:100%;min-height:120px;border:0;border-radius:16px;padding:16px;"
            + "font:inherit;background:var(--card);color:var(--text);resize:none;outline:none}"
            + ".chips{display:flex;flex-wrap:wrap;gap:8px;margin:14px 0 22px}.chips button{border:0;border-radius:999px;padding:10px 15px;"
            + "background:var(--card);color:var(--text);font:inherit;font-size:15px}"
            + ".send{width:100%;border:0;border-radius:14px;padding:16px;background:var(--accent);color:#fff;font:inherit;font-weight:600}"
            + ".send:active,.chips button:active{opacity:.6}#st{text-align:center;color:var(--muted);margin-top:14px;min-height:24px}"
            + "#cb{display:none;margin-bottom:14px}#c{width:100%;border:0;border-radius:16px;padding:16px;font:inherit;font-size:22px;letter-spacing:.2em;"
            + "background:var(--card);color:var(--text);outline:none}#cb p{margin:0 0 8px;font-size:15px}"
            + "</style></head><body><main><h1>Записка на ТВ</h1><p>Появится поверх фильма на 12 секунд.</p>"
            + "<div id=\"cb\"><p>Код с экрана ТВ: «Сторож», строка «Записки с телефона». Спросим один раз.</p>"
            + "<input id=\"c\" inputmode=\"numeric\" maxlength=\"6\" autocomplete=\"off\" placeholder=\"000000\"></div>"
            + "<textarea id=\"t\" maxlength=\"200\" placeholder=\"Например: ужин готов\"></textarea>"
            + "<div class=\"chips\"><button>Ужин готов</button><button>Подойди, пожалуйста</button>"
            + "<button>Сделай потише</button><button>Пора спать</button></div>"
            + "<button class=\"send\" id=\"s\">Показать на телевизоре</button><div id=\"st\"></div></main><script>"
            + "const t=document.getElementById('t'),st=document.getElementById('st'),cb=document.getElementById('cb'),cf=document.getElementById('c');"
            + "let code='';try{code=localStorage.getItem('hydeCode')||''}catch(e){}if(!code)cb.style.display='block';"
            + "async function send(x){x=(x||t.value).trim();if(!x)return;if(!code){code=cf.value.trim();"
            + "if(code.length<6){cb.style.display='block';st.textContent='Введите код с экрана ТВ';code='';return}}"
            + "st.textContent='Отправляю…';"
            + "try{const r=await fetch('/note',{method:'POST',headers:{'X-Code':code},body:x});"
            + "if(r.status==403){try{localStorage.removeItem('hydeCode')}catch(e){}code='';cf.value='';cb.style.display='block';"
            + "st.textContent='Код не подошёл: посмотрите его на экране «Сторожа»';return}"
            + "if(r.status==429){st.textContent=await r.text();return}"
            + "st.textContent=r.ok?'Показано на телевизоре':'Не получилось';"
            + "if(r.ok){t.value='';try{localStorage.setItem('hydeCode',code)}catch(e){}cb.style.display='none'}}"
            + "catch(e){st.textContent='Телевизор не отвечает'}}"
            + "document.getElementById('s').onclick=()=>send();"
            + "document.querySelectorAll('.chips button').forEach(b=>b.onclick=()=>send(b.textContent));"
            + "</script></body></html>";
}
