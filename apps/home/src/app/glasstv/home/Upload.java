package app.glasstv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Своё фото с телефона (10.11, «Оформление», «Обои»): пока открыта эта панель, «Дом» сам отвечает в домашней сети
// маленькой страницей. На ТВ код QR с её адресом: телефон наводит камеру, выбирает фото, оно сразу встаёт обоями.
// Без новых прав приложения (только сеть, она уже есть), без выбора файлов на ТВ (неявный GET_CONTENT на этом ТВ
// зацикливается), без чужих приложений. Безопасность: страница живёт, только пока панель открыта (и не дольше
// 15 минут), адрес с случайной частью (её видно только на экране ТВ), одно фото до 25 МБ за раз.
// Телефон сам уменьшает фото до 2560 точек и шлёт JPEG (страница рисует его на холсте); не вышло: шлёт как есть,
// «Дом» поворачивает его по EXIF и обрезает до 16:9 (Walls.saveOwn)
final class Upload {
    static final int PORT = 8766;
    static final long LIFE_MS = 15 * 60_000;
    static final int MAX_BYTES = 25 * 1024 * 1024;

    private final HomeActivity a;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final String token;
    private volatile ServerSocket server;
    private volatile boolean stopped;
    private Sheet sheet;
    private TextView status;

    private Upload(HomeActivity a) {
        this.a = a;
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        token = b.toString();
    }

    static void open(HomeActivity a) { new Upload(a).start(); }

    private void start() {
        String ip = lanAddress();
        int port = listen();
        String url = ip == null || port < 0 ? null : "http://" + ip + (port == 80 ? "" : ":" + port) + "/" + token;
        android.util.Log.i("home", "своё фото: страница " + url);
        sheet = new Sheet(a, "Своё фото с телефона", null, new ArrayList<Sheet.Item>(), 1, a.vision, 300, 0);
        sheet.content = panel(url);
        sheet.onBack = () -> sheet.dismiss(false);
        final Runnable gone = sheet.onGone;
        sheet.onGone = () -> { stop(); if (gone != null) gone.run(); };
        a.showSheetBlurred(sheet);
        h.postDelayed(this::stop, LIFE_MS);
    }

    private void stop() {
        stopped = true;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
    }

    // Вид панели: код слева, шаги и адрес справа, строка состояния
    private LinearLayout panel(String url) {
        float dp = a.dp;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (url != null) {
            ImageView qr = new ImageView(a);
            qr.setImageBitmap(qrBitmap(url, Math.round(180 * dp)));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFFFFFFFF);
            bg.setCornerRadius(18 * dp);
            qr.setBackground(bg);
            // Белое поле вокруг кода не уже трёх модулей: камера телефона находит код уверенно
            int p = Math.round(20 * dp);
            qr.setPadding(p, p, p, p);
            row.addView(qr, new LinearLayout.LayoutParams(Math.round(220 * dp), Math.round(220 * dp)));
        }
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Math.round(330 * dp), -2);
        cl.leftMargin = Math.round(28 * dp);
        row.addView(col, cl);
        if (url == null) {
            col.addView(line("ТВ не в домашней сети: фото с телефона не принять. Проверьте Wi-Fi или кабель", 17, 0xEBFFFFFF), new LinearLayout.LayoutParams(-1, -2));
        } else {
            String[] steps = {"1. Наведите камеру телефона на код", "2. Выберите фото", "3. Оно сразу встанет на экран"};
            for (String s : steps) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = Math.round(8 * dp);
                col.addView(line(s, 18, 0xF2FFFFFF), lp);
            }
            LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-1, -2);
            ul.topMargin = Math.round(10 * dp);
            col.addView(line("Телефон и ТВ в одной сети Wi-Fi. Или откройте в браузере телефона:", 13, 0x99FFFFFF), ul);
            TextView u = line(url, 15, 0xE6FFFFFF);
            u.setTypeface(Fonts.med18);
            LinearLayout.LayoutParams ul2 = new LinearLayout.LayoutParams(-1, -2);
            ul2.topMargin = Math.round(4 * dp);
            col.addView(u, ul2);
        }
        status = line(url == null ? "" : "Жду фото…  «Назад»: закрыть", 14, Fonts.ACCENT);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = Math.round(14 * dp);
        col.addView(status, sl);
        return row;
    }

    private TextView line(String s, float sp, int color) {
        TextView t = Fonts.text(a, sp, Fonts.regular(sp), color);
        t.setText(s);
        t.setFontFeatureSettings("");
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private void say(String s) { h.post(() -> { if (status != null) status.setText(s); }); }

    // Код QR чёрным на белом: камера телефона читает его уверенно
    static Bitmap qrBitmap(String text, int px) {
        boolean[][] m = Qr.encode(text);
        int n = m.length;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFFFFFFF);
        Paint p = new Paint();
        p.setColor(0xFF000000);
        float k = px / (float) n;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++)
            if (m[y][x]) c.drawRect((float) Math.floor(x * k), (float) Math.floor(y * k), (float) Math.ceil((x + 1) * k), (float) Math.ceil((y + 1) * k), p);
        return Tile.fixed(b);
    }

    // Адрес ТВ в домашней сети: первый частный IPv4 (Wi-Fi или кабель), без туннелей VPN
    static String lanAddress() {
        try {
            List<String> cand = new ArrayList<>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = ni.getName();
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("dummy")) continue;
                for (InetAddress ad : Collections.list(ni.getInetAddresses())) {
                    if (ad instanceof Inet4Address && ad.isSiteLocalAddress()) {
                        String s = ad.getHostAddress();
                        if (name.startsWith("wlan") || name.startsWith("eth")) cand.add(0, s); else cand.add(s);
                    }
                }
            }
            return cand.isEmpty() ? null : cand.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private int listen() {
        for (int p = PORT; p < PORT + 5; p++) {
            try {
                ServerSocket s = new ServerSocket(p);
                server = s;
                Thread t = new Thread(() -> serve(s), "home-upload");
                t.setDaemon(true);
                t.start();
                return p;
            } catch (Exception ignored) { }
        }
        return -1;
    }

    private void serve(ServerSocket s) {
        while (!stopped && !s.isClosed()) {
            try (Socket c = s.accept()) {
                c.setSoTimeout(30_000);
                handle(c);
            } catch (Exception ignored) { }
        }
    }

    private void handle(Socket c) throws Exception {
        InputStream in = c.getInputStream();
        String head = readHead(in);
        if (head == null) return;
        String[] lines = head.split("\r\n");
        String[] req = lines[0].split(" ");
        if (req.length < 2) return;
        String method = req[0], path = req[1];
        long len = 0;
        for (String l : lines) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase("Content-Length")) {
                try { len = Long.parseLong(l.substring(i + 1).trim()); } catch (Exception ignored) { }
            }
        }
        OutputStream out = c.getOutputStream();
        String base = "/" + token;
        if ("GET".equals(method) && (path.equals(base) || path.equals(base + "/"))) {
            reply(out, 200, "text/html; charset=utf-8", PAGE.getBytes(StandardCharsets.UTF_8));
            say("Телефон открыл страницу, жду фото…");
            return;
        }
        if ("POST".equals(method) && path.equals(base + "/photo")) {
            if (len <= 0 || len > MAX_BYTES) { reply(out, 413, "application/json", json(false, "фото больше 25 МБ")); return; }
            say("Получаю фото…");
            byte[] body = new byte[(int) len];
            int got = 0;
            while (got < len) {
                int n = in.read(body, got, (int) len - got);
                if (n < 0) break;
                got += n;
            }
            if (got < len) { reply(out, 400, "application/json", json(false, "фото пришло не целиком")); say("Фото пришло не целиком, попробуйте ещё раз"); return; }
            String err = Walls.saveOwn(a.getApplicationContext(), body);
            reply(out, 200, "application/json", json(err.isEmpty(), err));
            if (err.isEmpty()) h.post(this::done);
            else say("Не вышло: " + err);
            return;
        }
        reply(out, 404, "text/plain; charset=utf-8", "нет такой страницы".getBytes(StandardCharsets.UTF_8));
    }

    // Фото принято: оно сразу обои (это и был выбор человека), панель закрывается
    private void done() {
        if (a.isDestroyed()) return;
        Look.saveWall(a, Look.OWN);
        Look.tryWall = null;
        a.amb.showWall(450);
        a.ownPhotoArrived();
        if (sheet != null) sheet.dismiss(false);
    }

    static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int st = 0;
        while (b.size() < 16_384) {
            int x = in.read();
            if (x < 0) return null;
            b.write(x);
            st = (x == '\r' && (st == 0 || st == 2)) ? st + 1 : (x == '\n' && (st == 1 || st == 3)) ? st + 1 : 0;
            if (st == 4) return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    static byte[] json(boolean ok, String err) {
        String e = err == null ? "" : err.replace("\\", "\\\\").replace("\"", "\\\"");
        return ("{\"ok\":" + ok + (ok ? "" : ",\"error\":\"" + e + "\"") + "}").getBytes(StandardCharsets.UTF_8);
    }

    static void reply(OutputStream out, int code, String type, byte[] body) throws Exception {
        String st = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 413 ? "Payload Too Large" : "Bad Request";
        String head = "HTTP/1.1 " + code + " " + st + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    // Страница для телефона: одна кнопка, телефон сам уменьшает фото и отправляет, ход отправки словами
    static final String PAGE = "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Фото на ТВ</title><style>"
            + ":root{color-scheme:dark}body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
            + "background:radial-gradient(120% 80% at 50% 0%,#2a2f45,#0b0b10);color:#fff;font:17px/1.45 -apple-system,system-ui,Roboto,sans-serif}"
            + "main{width:min(420px,92vw);text-align:center}h1{font-size:26px;margin:0 0 6px;letter-spacing:-.02em}"
            + "p{margin:0 0 22px;color:rgba(255,255,255,.7)}.btn{display:block;padding:17px;border-radius:16px;background:#fff;color:#111;"
            + "font-weight:600;font-size:18px;cursor:pointer}#s{margin-top:18px;min-height:1.4em;color:#5ed3b4}"
            + "img{display:none;margin-top:18px;max-width:100%;border-radius:14px;box-shadow:0 10px 30px rgba(0,0,0,.4)}</style></head><body><main>"
            + "<h1>Фото на телевизор</h1><p>Выберите фото: оно встанет обоями главного экрана</p>"
            + "<label class=\"btn\">Выбрать фото<input id=\"f\" type=\"file\" accept=\"image/*\" hidden></label>"
            + "<div id=\"s\"></div><img id=\"p\" alt=\"\"></main><script>"
            + "var f=document.getElementById('f'),s=document.getElementById('s'),p=document.getElementById('p');"
            + "function load(file){return new Promise(function(ok,no){var i=new Image();i.onload=function(){ok(i)};i.onerror=no;i.src=URL.createObjectURL(file);});}"
            + "function send(blob){var x=new XMLHttpRequest();x.open('POST',location.pathname.replace(/\\/$/,'')+'/photo');"
            + "x.upload.onprogress=function(e){if(e.lengthComputable)s.textContent='Отправляю… '+Math.round(e.loaded*100/e.total)+' %';};"
            + "x.onload=function(){try{var j=JSON.parse(x.responseText);s.textContent=j.ok?'Готово: фото на экране ТВ':'Не вышло: '+j.error;}catch(e){s.textContent='Не вышло, попробуйте ещё раз';}};"
            + "x.onerror=function(){s.textContent='Нет связи с ТВ: телефон и ТВ в одной сети Wi-Fi? Страница работает, пока на ТВ открыт код';};"
            + "x.send(blob);}"
            + "f.onchange=function(){var file=f.files[0];if(!file)return;s.textContent='Готовлю фото…';"
            + "load(file).then(function(img){var m=2560,w=img.naturalWidth,h=img.naturalHeight,k=Math.min(1,m/Math.max(w,h));"
            + "var c=document.createElement('canvas');c.width=Math.round(w*k);c.height=Math.round(h*k);c.getContext('2d').drawImage(img,0,0,c.width,c.height);"
            + "p.src=c.toDataURL('image/jpeg',0.5);p.style.display='block';c.toBlob(function(b){send(b||file)},'image/jpeg',0.9);})"
            + ".catch(function(){send(file);});};"
            + "</script></body></html>";
}
