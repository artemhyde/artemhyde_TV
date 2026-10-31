# Вкус для второго профиля «Волны» из плейлистов: YouTube Music, YouTube или Spotify.
# Плейлист YouTube Music или YouTube (открытый или «доступ по ссылке») берётся как есть, с id треков.
# У Spotify берётся открытая страница плейлиста, и каждый трек ищется в YouTube Music
# тем же открытым поиском, что сайт music.youtube.com. Итог пишется в res/raw/seed_second.tsv.
# Запуск: python seed-taste.py <ссылка на плейлист> [ещё ссылки...] [--out файл]
# Кроме ссылок можно дать текстовый файл: в нём ссылки или строки «Исполнитель - Название».
# После него собрать «Волну» (bash build/build-app.sh volna из корня репозитория) и поставить на ТВ.
import difflib
import json
import os
import re
import sys
import time
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"
VER = "1.20261020.10.00"
SONGS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"   # фильтр поиска «Песни»
HERE = os.path.dirname(os.path.abspath(__file__))
# Страна запросов к YouTube Music (две буквы): от неё зависят чарты и порядок результатов поиска. Меняется переменной среды
COUNTRY = os.environ.get("VOLNA_COUNTRY", "US")


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ru,en"})
    return urllib.request.urlopen(req, timeout=20).read().decode("utf-8", "replace")


def spotify_playlist(url):
    m = re.search(r"playlist[/:]([A-Za-z0-9]{22})", url)
    if not m:
        raise ValueError("не похоже на ссылку плейлиста Spotify: " + url)
    html = get("https://open.spotify.com/embed/playlist/" + m.group(1))
    data = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', html, re.S)
    ent = json.loads(data.group(1))["props"]["pageProps"]["state"]["data"]["entity"]
    tracks = [(t.get("subtitle", ""), t.get("title", ""), (t.get("duration") or 0) // 1000) for t in ent.get("trackList", [])]
    return ent.get("name", "Плейлист"), tracks


def ytm_call(endpoint, body, gl=COUNTRY):
    body = dict(body)
    body["context"] = {"client": {"clientName": "WEB_REMIX", "clientVersion": VER, "hl": "en", "gl": gl}}
    req = urllib.request.Request("https://music.youtube.com/youtubei/v1/" + endpoint + ("&" if "?" in endpoint else "?") + "prettyPrint=false",
                                 data=json.dumps(body).encode(), headers={
                                     "Content-Type": "application/json", "User-Agent": UA, "Origin": "https://music.youtube.com"})
    return json.loads(urllib.request.urlopen(req, timeout=20).read().decode("utf-8"))


def find_all(o, key, out):
    if isinstance(o, dict):
        if key in o:
            out.append(o[key])
        for v in o.values():
            find_all(v, key, out)
    elif isinstance(o, list):
        for v in o:
            find_all(v, key, out)
    return out


def ytm_playlist(url):
    """Плейлист YouTube Music или YouTube: (название, [(id, название, исполнители, длительность)])"""
    m = re.search(r"list=([A-Za-z0-9_-]+)", url)
    if not m:
        raise ValueError("нет list= в ссылке: " + url)
    pid = m.group(1)
    j = ytm_call("browse", {"browseId": "VL" + pid})
    name = "Плейлист"
    for h in find_all(j, "musicResponsiveHeaderRenderer", []) + find_all(j, "musicDetailHeaderRenderer", []):
        runs = (h.get("title") or {}).get("runs") or []
        if runs:
            name = runs[0]["text"]
            break
    items, pages = [], 0
    while True:
        for r in find_all(j, "musicResponsiveListItemRenderer", []):
            vid = (r.get("playlistItemData") or {}).get("videoId")
            cols = r.get("flexColumns") or []
            if not vid or len(cols) < 2:
                continue
            runs = lambda c: (c.get("musicResponsiveListItemFlexColumnRenderer", {}).get("text", {}).get("runs") or [])
            title = "".join(x.get("text", "") for x in runs(cols[0]))
            arts = [x["text"] for x in runs(cols[1]) if "ARTIST" in json.dumps(x.get("navigationEndpoint", {}))]
            if not arts:
                arts = ["".join(x.get("text", "") for x in runs(cols[1])).split(" • ")[0]]
            fixed = find_all(r.get("fixedColumns") or [], "text", [])
            dur = next((x["runs"][0]["text"] for x in fixed if isinstance(x, dict) and x.get("runs")), "")
            items.append((vid, title, ", ".join(a for a in arts if a), dur))
        # Длинный плейлист приходит частями: новое продолжение в continuationEndpoint, старое в nextContinuationData
        tokens = [c["continuationCommand"]["token"] for c in find_all(j, "continuationEndpoint", []) if "continuationCommand" in c]
        old = [c["nextContinuationData"]["continuation"] for cs in find_all(j, "continuations", []) if isinstance(cs, list)
               for c in cs if isinstance(c, dict) and "nextContinuationData" in c]
        pages += 1
        if (not tokens and not old) or pages > 30:
            break
        if tokens:
            j = ytm_call("browse", {"continuation": tokens[0]})
        else:
            j = ytm_call("browse?ctoken=" + old[0] + "&continuation=" + old[0] + "&type=next", {})
        time.sleep(0.3)
    seen, uniq = set(), []
    for it in items:
        if it[0] not in seen:
            seen.add(it[0])
            uniq.append(it)
    return name, uniq


def ytm_search(query, gl=COUNTRY):
    body = {"query": query, "params": SONGS,
            "context": {"client": {"clientName": "WEB_REMIX", "clientVersion": VER, "hl": "en", "gl": gl}}}
    req = urllib.request.Request("https://music.youtube.com/youtubei/v1/search?prettyPrint=false",
                                 data=json.dumps(body).encode(), headers={
                                     "Content-Type": "application/json", "User-Agent": UA, "Origin": "https://music.youtube.com"})
    j = json.loads(urllib.request.urlopen(req, timeout=20).read().decode("utf-8"))
    out = []

    def walk(o):
        if isinstance(o, dict):
            r = o.get("musicResponsiveListItemRenderer")
            if r:
                out.append(r)
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(j)
    res = []
    for r in out:
        vid = (r.get("playlistItemData") or {}).get("videoId")
        cols = r.get("flexColumns") or []
        if not vid or len(cols) < 2:
            continue
        runs = lambda c: (c.get("musicResponsiveListItemFlexColumnRenderer", {}).get("text", {}).get("runs") or [])
        title = "".join(x.get("text", "") for x in runs(cols[0]))
        sub = runs(cols[1])
        artists = [x["text"] for x in sub if "ARTIST" in json.dumps(x.get("navigationEndpoint", {}))]
        if not artists:   # у части официальных треков исполнитель без ссылки: берём первый кусок до « • »
            first = "".join(x.get("text", "") for x in sub).split(" • ")[0]
            artists = [a.strip() for a in re.split(r",|&", first) if a.strip()]
        dur = next((x["text"] for x in reversed(sub) if re.fullmatch(r"\d+:\d\d(:\d\d)?", x.get("text", ""))), "")
        res.append((vid, title, artists, dur))
    return res


def norm(s):
    s = s.lower()
    s = re.sub(r"\(.*?\)|\[.*?\]|feat\..*|ft\..*| - .*remaster.*", " ", s)
    return re.sub(r"[^\w]+", " ", s).strip()


def secs(d):
    p = [int(x) for x in d.split(":")] if d else []
    return p[0] * 60 + p[1] if len(p) == 2 else (p[0] * 3600 + p[1] * 60 + p[2] if len(p) == 3 else 0)


VERSIONS = ("cover", "karaoke", "instrumental", "piano", "acoustic", "extended", "sped up", "slowed", "remix", "live", "8d")


def best_match(artist, title, dur):
    first = re.split(r",|&", artist)[0].strip()
    want_a = set(norm(a) for a in re.split(r",|&", artist) if a.strip())
    # Сначала точный запрос, потом попроще (первый исполнитель и название без скобок), потом поиск не по своей стране
    for query, gl in ((artist + " " + title, COUNTRY), (first + " " + norm(title), COUNTRY), (first + " " + norm(title), "US")):
        try:
            found = ytm_search(query, gl)
        except Exception as e:
            print("  поиск не удался:", e)
            continue
        best, best_score = None, 0
        for vid, t, arts, d in found[:8]:
            # Кавер, караоке, ремикс берём, только если в оригинале так же
            if any(v in t.lower() and v not in title.lower() for v in VERSIONS):
                continue
            ts = difflib.SequenceMatcher(None, norm(title), norm(t)).ratio()
            a_ok = bool(want_a & set(norm(a) for a in arts))
            d_ok = dur and secs(d) and abs(secs(d) - dur) <= 6
            score = ts + (0.6 if a_ok else 0) + (0.3 if d_ok else 0)
            # Исполнитель обязан совпасть: одно название и длительность часто дают чужую песню
            if ts >= 0.6 and a_ok and score > best_score:
                best, best_score = (vid, t, ", ".join(arts) or artist, d), score
        if best:
            return best
        time.sleep(0.3)
    return None


def main():
    args, out = [], os.path.join(HERE, "res", "raw", "seed_second.tsv")
    it = iter(sys.argv[1:])
    for a in it:
        if a == "--out":
            out = next(it)
        elif os.path.isfile(a):
            args += [l.strip() for l in open(a, encoding="utf-8") if l.strip() and not l.startswith("#")]
        else:
            args.append(a)
    if not args:
        print(__doc__ or "Нужны ссылки на плейлисты Spotify")
        sys.exit(1)

    wanted = []   # (исполнитель, название, длительность в секундах, источник)
    rows, missed, seen = [], [], set()
    for a in args:
        if "list=" in a and ("youtube.com" in a or "youtu.be" in a):
            name, items = ytm_playlist(a)
            print(f"Плейлист YouTube «{name}»: {len(items)} треков")
            for vid, ti, ar, du in items:
                if vid not in seen:
                    seen.add(vid)
                    rows.append("	".join([vid, ti, ar, du or "0:00", "pl:" + name]))
        elif "spotify" in a:
            name, tracks = spotify_playlist(a)
            print(f"Плейлист «{name}»: {len(tracks)} треков")
            wanted += [(ar, ti, du, "pl:" + name) for ar, ti, du in tracks]
        elif " - " in a:
            ar, ti = a.split(" - ", 1)
            wanted.append((ar.strip(), ti.strip(), 0, "pl:Список"))

    for i, (ar, ti, du, src) in enumerate(wanted, 1):
        m = best_match(ar, ti, du)
        if not m:
            missed.append(f"{ar} - {ti}")
            continue
        if m[0] in seen:
            continue
        seen.add(m[0])
        rows.append("\t".join([m[0], m[1], m[2], m[3] or "0:00", src]))
        if i % 10 == 0:
            print(f"  найдено {len(rows)} из {i}")
        time.sleep(0.4)

    with open(out, "w", encoding="utf-8") as f:
        f.write("# Вкус второго профиля: плейлисты, найденные в YouTube Music (собирает seed-taste.py), "
                + time.strftime("%d.%m.%Y") + "\n")
        f.write("# videoId\tназвание\tисполнители\tдлительность\tисточники (pl:<плейлист>)\n")
        f.write("\n".join(rows) + "\n")
    print(f"Готово: {len(rows)} треков в {out}")
    if missed:
        miss = os.path.splitext(out)[0] + "_не_найдено.txt"
        open(miss, "w", encoding="utf-8").write("\n".join(missed) + "\n")
        print(f"Не нашлись в YouTube Music: {len(missed)}, список в {miss}")


if __name__ == "__main__":
    main()
