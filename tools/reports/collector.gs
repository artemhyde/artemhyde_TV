/**
 * @OnlyCurrentDoc
 */
// Строка выше ограничивает скрипт этой одной таблицей: к другим файлам Google доступа у него нет.
// HYDE TV: приём отчётов об установке в Google-таблицу автора.
// Веб-приложение Google Apps Script, привязанное к таблице. Установщик HYDE TV присылает сюда
// JSON (POST) только после того, как человек ответил «Да» на вопрос «Поделиться результатом с автором?».
// Скрипт проверяет каждое поле, отбрасывает лишнее и пишет одну строку в лист «Отчёты».
// IP-адрес отправителя Google скрипту не передаёт, и скрипт ничего, кроме присланных полей и времени приёма, не хранит.
//
// Листы: «Отчёты» (все строки), «Сводка» (итоги, модели, скачивания), «Неудачи» (последние 50 сбоев),
// «Скачивания» (раз в сутки число скачиваний архивов с GitHub).
// Публично по адресу веб-приложения (GET) отдаётся только сводка по моделям: без номеров отчётов и текстов ошибок.

var REPORTS = 'Отчёты';
var SUMMARY = 'Сводка';
var FAILURES = 'Неудачи';
var DOWNLOADS = 'Скачивания';
var REPO = 'artemhyde/artemhyde_TV';
var MAX_PER_MINUTE = 30;   // больше отчётов в минуту не принимаем: защита от засорения таблицы

// Поля отчёта по порядку колонок (A: время приёма, дальше эти). [ключ, заголовок, тип, ограничение]
var FIELDS = [
  ['report_id', 'Номер отчёта', 'id'],
  ['release', 'Выпуск', 'str', 20],
  ['installer_version', 'Установщик', 'str', 20],
  ['pc_os', 'Компьютер', 'enum', ['windows', 'macos', 'linux', 'other']],
  ['path', 'Путь', 'enum', ['wizard', 'claude', 'cli']],
  ['tv_brand', 'Марка ТВ', 'str', 60],
  ['tv_model', 'Модель ТВ', 'str', 80],
  ['android', 'Android', 'str', 10],
  ['sdk', 'SDK', 'int', [1, 100]],
  ['profile', 'Профиль', 'str', 30],
  ['abi', 'Процессор', 'str', 30],
  ['modules', 'Модули', 'list', 40],
  ['home_switched', '«Дом» главным', 'bool'],
  ['result', 'Итог', 'enum', ['ok', 'failed', 'partial', 'restored', 'update_ok', 'update_failed', 'test']],
  ['failed_step', 'Где сломалось', 'str', 200],
  ['error', 'Ошибка', 'str', 500],
  ['verify_ok', 'Проверка: держится', 'int', [0, 1000]],
  ['verify_total', 'Проверка: всего', 'int', [0, 1000]],
  ['lang', 'Язык', 'str', 10]
];
// Колонки листа «Отчёты»: A время, B номер, C выпуск, D установщик, E компьютер, F путь, G марка, H модель,
// I Android, J SDK, K профиль, L процессор, M модули, N «Дом» главным, O итог, P где сломалось, Q ошибка,
// R проверка держится, S проверка всего, T язык.

// Один раз запустить из редактора: создаёт листы, сводку и ежедневный снимок скачиваний.
function setup() {
  var ss = book_();
  sheet_(REPORTS, headers_());
  sheet_(DOWNLOADS, ['Когда', 'Выпуск', 'Файл', 'Скачиваний']);

  var sm = sheet_(SUMMARY, []);
  sm.clear();
  sm.getRange('A1').setValue('Итоги').setFontWeight('bold');
  sm.getRange('A2').setFormula("=QUERY('Отчёты'!A:T,\"select O, count(B) where B is not null and O <> 'test' group by O order by count(B) desc label O 'Итог', count(B) 'Отчётов'\",1)");
  sm.getRange('D1').setValue('По моделям: сколько отчётов с каждым итогом').setFontWeight('bold');
  sm.getRange('D2').setFormula("=QUERY('Отчёты'!A:T,\"select G, H, I, count(B) where B is not null and O <> 'test' group by G, H, I pivot O\",1)");
  sm.getRange('M1').setValue('Скачивания архивов с GitHub').setFontWeight('bold');
  sm.getRange('M2').setFormula("=QUERY('Скачивания'!A:D,\"select B, C, max(D) where B is not null group by B, C order by B desc label B 'Выпуск', C 'Файл', max(D) 'Скачиваний'\",1)");

  var fl = sheet_(FAILURES, []);
  fl.clear();
  fl.getRange('A1').setFormula("=QUERY('Отчёты'!A:T,\"select A, G, H, I, C, F, P, Q where O = 'failed' or O = 'update_failed' or O = 'partial' order by A desc limit 50 label A 'Когда', G 'Марка', H 'Модель', I 'Android', C 'Выпуск', F 'Путь', P 'Где сломалось', Q 'Ошибка'\",1)");

  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'updateDownloads') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('updateDownloads').timeBased().everyHours(6).create();
  updateDownloads();

  var first = ss.getSheetByName('Лист1') || ss.getSheetByName('Sheet1');
  if (first && ss.getSheets().length > 1) ss.deleteSheet(first);
  ss.setActiveSheet(ss.getSheetByName(SUMMARY));
}

// Приём отчёта от установщика.
function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents || e.postData.contents.length > 8000) {
      return out_({ ok: false, error: 'пустой или слишком длинный отчёт' });
    }
    var data = JSON.parse(e.postData.contents);
    if (typeof data !== 'object' || data === null || Array.isArray(data)) {
      return out_({ ok: false, error: 'отчёт не объект' });
    }

    var cache = CacheService.getScriptCache();
    var minute = 'm' + Math.floor(Date.now() / 60000);
    var n = Number(cache.get(minute) || 0);
    if (n >= MAX_PER_MINUTE) return out_({ ok: false, error: 'слишком много отчётов в минуту' });
    cache.put(minute, String(n + 1), 120);

    var row = [new Date()];
    for (var i = 0; i < FIELDS.length; i++) {
      var v = clean_(data[FIELDS[i][0]], FIELDS[i]);
      if (v === null) return out_({ ok: false, error: 'неверное поле ' + FIELDS[i][0] });
      row.push(v);
    }
    if (!row[1] || !row[2] || !row[14]) return out_({ ok: false, error: 'нет номера отчёта, выпуска или итога' });
    if (cache.get('r' + row[1])) return out_({ ok: true, report: row[1], duplicate: true });

    var lock = LockService.getScriptLock();
    lock.waitLock(10000);
    try {
      sheet_(REPORTS, headers_()).appendRow(row);
    } finally {
      lock.releaseLock();
    }
    cache.put('r' + row[1], '1', 21600);
    return out_({ ok: true, report: row[1] });
  } catch (err) {
    return out_({ ok: false, error: 'отчёт не принят' });
  }
}

// Публичная сводка по моделям: марка, модель, Android, профиль, сколько установок и сколько удачных.
function doGet() {
  var stats = {};
  var sh = book_().getSheetByName(REPORTS);
  if (sh && sh.getLastRow() > 1) {
    sh.getRange(2, 1, sh.getLastRow() - 1, FIELDS.length + 1).getValues().forEach(function (r) {
      var result = String(r[14]);
      if (result === 'test' || !r[1]) return;
      var key = [r[6], r[7], r[8], r[10]].join('|');
      var s = stats[key] || (stats[key] = { brand: String(r[6]), model: String(r[7]), android: String(r[8]), profile: String(r[10]), installs: 0, ok: 0, failed: 0, last: '' });
      s.installs++;
      if (result === 'ok' || result === 'update_ok') s.ok++;
      if (result === 'failed' || result === 'update_failed') s.failed++;
      var day = Utilities.formatDate(new Date(r[0]), 'UTC', 'yyyy-MM-dd');
      if (day > s.last) s.last = day;
    });
  }
  var list = Object.keys(stats).map(function (k) { return stats[k]; });
  list.sort(function (a, b) { return b.installs - a.installs; });
  return out_({ ok: true, models: list });
}

// Раз в сутки: сколько раз скачали архивы каждого выпуска. GitHub иногда отказывает адресам Google
// по лимиту запросов, поэтому триггер стоит раз в 6 часов, а снимок пишется не чаще раза в сутки.
function updateDownloads() {
  var props = PropertiesService.getScriptProperties();
  var today = Utilities.formatDate(new Date(), 'UTC', 'yyyy-MM-dd');
  if (props.getProperty('DOWNLOADS_DAY') === today) return;
  var res = UrlFetchApp.fetch('https://api.github.com/repos/' + REPO + '/releases?per_page=20', {
    muteHttpExceptions: true,
    headers: { 'User-Agent': 'hyde-tv-reports', 'Accept': 'application/vnd.github+json' }
  });
  if (res.getResponseCode() !== 200) return;
  var now = new Date();
  var rows = [];
  JSON.parse(res.getContentText()).forEach(function (rel) {
    (rel.assets || []).forEach(function (a) {
      if (/\.(zip|tar\.gz)$/.test(a.name)) rows.push([now, rel.tag_name, a.name, a.download_count]);
    });
  });
  if (rows.length) {
    var sh = sheet_(DOWNLOADS, ['Когда', 'Выпуск', 'Файл', 'Скачиваний']);
    sh.getRange(sh.getLastRow() + 1, 1, rows.length, 4).setValues(rows);
  }
  props.setProperty('DOWNLOADS_DAY', today);
}

function clean_(v, f) {
  var type = f[2], lim = f[3];
  if (v === null || v === undefined || v === '') return '';
  switch (type) {
    case 'id':
      v = String(v);
      return /^[a-z0-9]{16,40}$/.test(v) ? v : null;
    case 'str':
      return scrub_(String(v)).slice(0, lim);
    case 'enum':
      v = String(v);
      return lim.indexOf(v) >= 0 ? v : null;
    case 'int':
      v = Number(v);
      return (isFinite(v) && Math.floor(v) === v && v >= lim[0] && v <= lim[1]) ? v : null;
    case 'bool':
      return v === true ? 'да' : (v === false ? 'нет' : null);
    case 'list':
      if (!Array.isArray(v)) return null;
      return scrub_(v.slice(0, lim).map(function (x) { return String(x).slice(0, 30); }).join(', '));
  }
  return null;
}

// Убирает то, чего в отчёте быть не должно, даже если установщик пропустил: IP и MAC-адреса, почту.
// Строку, начинающуюся с = + - @, таблица не должна принять за формулу.
function scrub_(s) {
  s = s.replace(/[\u0000-\u001f\u007f]/g, ' ')
    .replace(/\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b/g, '<адрес>')
    .replace(/\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b/gi, '<mac>')
    .replace(/[\w.+-]+@[\w-]+\.[\w.-]+/g, '<почта>');
  return /^[=+\-@]/.test(s) ? "'" + s : s;
}

function book_() {
  return SpreadsheetApp.getActive();
}

function sheet_(name, headers) {
  var ss = book_();
  var sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
    if (headers.length) {
      sh.appendRow(headers);
      sh.setFrozenRows(1);
      sh.getRange(1, 1, 1, headers.length).setFontWeight('bold');
    }
  }
  return sh;
}

function headers_() {
  return ['Принят'].concat(FIELDS.map(function (f) { return f[1]; }));
}

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
