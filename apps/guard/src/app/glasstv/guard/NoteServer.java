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

    NoteServer(GuardService g, int port) { this.g = g; this.port = port; }

    void start() {
        Thread t = new Thread(() -> {
            try {
                ss = new ServerSocket(port);
                while (!ss.isClosed()) {
                    Socket s = ss.accept();
                    new Thread(() -> handle(s), "note").start();
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
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try { len = Math.min(4096, Integer.parseInt(line.substring(15).trim())); } catch (Exception ignored) { }
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
                g.showNote(text);
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
            + "</style></head><body><main><h1>Записка на ТВ</h1><p>Появится поверх фильма на 12 секунд.</p>"
            + "<textarea id=\"t\" maxlength=\"200\" placeholder=\"Например: ужин готов\"></textarea>"
            + "<div class=\"chips\"><button>Ужин готов</button><button>Подойди, пожалуйста</button>"
            + "<button>Сделай потише</button><button>Пора спать</button></div>"
            + "<button class=\"send\" id=\"s\">Показать на телевизоре</button><div id=\"st\"></div></main><script>"
            + "const t=document.getElementById('t'),st=document.getElementById('st');"
            + "async function send(x){x=(x||t.value).trim();if(!x)return;st.textContent='Отправляю…';"
            + "try{const r=await fetch('/note',{method:'POST',body:x});st.textContent=r.ok?'Показано на телевизоре':'Не получилось';"
            + "if(r.ok)t.value=''}catch(e){st.textContent='Телевизор не отвечает'}}"
            + "document.getElementById('s').onclick=()=>send();"
            + "document.querySelectorAll('.chips button').forEach(b=>b.onclick=()=>send(b.textContent));"
            + "</script></body></html>";
}
