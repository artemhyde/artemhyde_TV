/**
 * @OnlyCurrentDoc
 */
// Строка выше ограничивает скрипт этой одной таблицей: к другим файлам Google доступа у него нет.
// HYDE TV: технические отчёты в Google-таблицу автора (версия 4, 12.11.2026: в сигнале «Сторожа» погода и курсы
// «Дома», пришли ли и почему нет; версия 3, 10.11.2026: состояние кнопок пульта, главного экрана и отключённых пакетов,
// в «Сводке» блок «Сбои на ТВ за 7 дней»).
// Веб-приложение Google Apps Script, привязанное к таблице. Принимает два вида JSON (POST), оба только от тех,
// у кого при установке включена галочка «Отправлять автору технические отчёты»:
//   отчёт установщика (kind нет или "install"): одна строка в лист «Отчёты»;
//   ежедневный сигнал «Сторожа» (kind "ping"): строка этого ТВ в листе «Устройства» обновляется, новых строк не плодит.
// Скрипт проверяет каждое поле и отбрасывает лишнее. IP-адрес отправителя Google скрипту не передаёт,
// хранятся только присланные поля и время приёма. Личных данных в полях нет: модель ТВ, версии, итоги, ошибки.
//
// Листы: «Сводка» (итоги, модели, ТВ на связи по версиям, скачивания), «Устройства» (по строке на ТВ),
// «Отчёты» (каждая установка), «Неудачи» (последние 50 сбоев), «Скачивания» (раз в сутки число скачиваний с GitHub).
// Публично по адресу веб-приложения (GET) отдаётся только сводка: модели и сколько ТВ на какой версии, без номеров и ошибок.
// После вставки новой версии один раз запустить setup (данные не трогает), затем «Управление развёртываниями»,
// карандаш, «Новая версия», «Развернуть»: адрес остаётся прежним.

var REPORTS = 'Отчёты';
var DEVICES = 'Устройства';
var SUMMARY = 'Сводка';
var FAILURES = 'Неудачи';
var DOWNLOADS = 'Скачивания';
var REPO = 'artemhyde/artemhyde_TV';
var MAX_PER_MINUTE = 120;   // больше в минуту не принимаем: защита от засорения таблицы
var PING_EVERY_H = 12;      // повторный сигнал того же ТВ раньше этого срока не пишется

// Поля отчёта установщика по порядку колонок (A: время приёма, дальше эти). [ключ, заголовок, тип, ограничение]
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
  ['lang', 'Язык', 'str', 10],
  ['device_id', 'Устройство', 'id_opt']
];
// Колонки листа «Отчёты»: A время, B номер, C выпуск, D установщик, E компьютер, F путь, G марка, H модель,
// I Android, J SDK, K профиль, L процессор, M модули, N «Дом» главным, O итог, P где сломалось, Q ошибка,
// R проверка держится, S проверка всего, T язык, U устройство.

// Поля ежедневного сигнала «Сторожа».
var PING_FIELDS = [
  ['device_id', 'Устройство', 'id'],
  ['release', 'Версия HYDE', 'str', 20],
  ['tv_brand', 'Марка ТВ', 'str', 60],
  ['tv_model', 'Модель ТВ', 'str', 80],
  ['android', 'Android', 'str', 10],
  ['sdk', 'SDK', 'int', [1, 100]],
  ['profile', 'Профиль', 'str', 30],
  ['abi', 'Процессор', 'str', 30],
  ['auto_update', 'Автообновление', 'bool'],
  ['last_update', 'Последнее обновление', 'enum', ['none', 'ok', 'failed', 'needs_installer', 'waiting']],
  ['last_update_release', 'Обновлялся до', 'str', 20],
  ['error', 'Последняя ошибка', 'str', 500],
  ['lang', 'Язык', 'str', 10],
  // С «Сторожа» 1.2.0. keys: служба Key Mapper привязана (ok), упала и поднята «Сторожем» с прошлого сигнала
  // (repaired), не поднимается (broken), Key Mapper не стоит (absent). home: главный экран по умолчанию HYDE или нет.
  // reenabled: сколько пакетов, отключённых установщиком, снова включено (бывает после обновления прошивки).
  ['keys', 'Кнопки пульта', 'enum', ['ok', 'repaired', 'broken', 'absent']],
  ['home', 'Главный экран', 'enum', ['ok', 'lost']],
  ['reenabled', 'Включено обратно', 'int', [0, 200]],
  // С «Сторожа» 1.2.1: погода и курсы на главном экране одной строкой: «ок», «выключено», «ещё не загружалось» или
  // «сбой, данные 3 ч назад: нет связи (почему)». Без города и страны.
  ['weather', 'Погода', 'str', 200],
  ['rates', 'Курсы', 'str', 200]
];
// Колонки листа «Устройства»: A устройство, B впервые, C последний сигнал, D сигналов, E версия HYDE, F марка,
// G модель, H Android, I SDK, J профиль, K процессор, L автообновление, M последнее обновление, N обновлялся до,
// O последняя ошибка, P язык, Q кнопки пульта, R главный экран, S включено обратно, T погода, U курсы.

// Запускать из редактора после каждой новой версии скрипта: создаёт недостающие листы и колонки,
// пересобирает «Сводку» и «Неудачи» (там только формулы) и ежедневный снимок скачиваний. Данные не трогает.
function setup() {
  var ss = book_();
  ensureHeaders_(sheet_(REPORTS, headers_()), headers_());
  ensureHeaders_(sheet_(DEVICES, devHeaders_()), devHeaders_());
  sheet_(DOWNLOADS, ['Когда', 'Выпуск', 'Файл', 'Скачиваний']);

  var sm = sheet_(SUMMARY, []);
  sm.clear();
  sm.getRange('A1').setValue('Итоги установок').setFontWeight('bold');
  sm.getRange('A2').setFormula("=QUERY('Отчёты'!A:U,\"select O, count(B) where B is not null and O <> 'test' group by O order by count(B) desc label O 'Итог', count(B) 'Отчётов'\",1)");
  sm.getRange('D1').setValue('ТВ на связи за 7 дней по версиям').setFontWeight('bold');
  sm.getRange('D2').setFormula("=IFERROR(QUERY(FILTER('Устройства'!E2:E,'Устройства'!C2:C>=TODAY()-7),\"select Col1, count(Col1) group by Col1 order by Col1 desc label Col1 'Версия HYDE', count(Col1) 'ТВ'\",0),\"пока никого\")");
  sm.getRange('G1').setValue('Всего ТВ в таблице').setFontWeight('bold');
  sm.getRange('G2').setFormula("=COUNTA('Устройства'!A2:A)");
  // Сбои среди ТВ, приславших сигнал за 7 дней: колонки Q, R, S листа «Устройства»
  var week = ",'Устройства'!C2:C,\">=\"&(TODAY()-7))";
  sm.getRange('G4').setValue('Сбои на ТВ за 7 дней').setFontWeight('bold');
  sm.getRange('G5').setValue('Кнопки пульта не работают');
  sm.getRange('H5').setFormula("=COUNTIFS('Устройства'!Q2:Q,\"broken\"" + week);
  sm.getRange('G6').setValue('Кнопки чинились сами');
  sm.getRange('H6').setFormula("=COUNTIFS('Устройства'!Q2:Q,\"repaired\"" + week);
  sm.getRange('G7').setValue('Главный экран не HYDE');
  sm.getRange('H7').setFormula("=COUNTIFS('Устройства'!R2:R,\"lost\"" + week);
  sm.getRange('G8').setValue('Отключённое включилось обратно');
  sm.getRange('H8').setFormula("=COUNTIFS('Устройства'!S2:S,\">0\"" + week);
  // Версия 4: погода и курсы «Дома», колонки T и U
  sm.getRange('G9').setValue('Погода не приходит');
  sm.getRange('H9').setFormula("=COUNTIFS('Устройства'!T2:T,\"сбой*\"" + week);
  sm.getRange('G10').setValue('Курсы не приходят');
  sm.getRange('H10').setFormula("=COUNTIFS('Устройства'!U2:U,\"сбой*\"" + week);
  sm.getRange('A12').setValue('По моделям: сколько отчётов с каждым итогом').setFontWeight('bold');
  sm.getRange('A13').setFormula("=QUERY('Отчёты'!A:U,\"select G, H, I, count(B) where B is not null and O <> 'test' group by G, H, I pivot O\",1)");
  sm.getRange('M1').setValue('Скачивания архивов с GitHub').setFontWeight('bold');
  sm.getRange('M2').setFormula("=QUERY('Скачивания'!A:D,\"select B, C, max(D) where B is not null group by B, C order by B desc label B 'Выпуск', C 'Файл', max(D) 'Скачиваний'\",1)");

  var fl = sheet_(FAILURES, []);
  fl.clear();
  fl.getRange('A1').setFormula("=QUERY('Отчёты'!A:U,\"select A, G, H, I, C, F, P, Q where O = 'failed' or O = 'update_failed' or O = 'partial' order by A desc limit 50 label A 'Когда', G 'Марка', H 'Модель', I 'Android', C 'Выпуск', F 'Путь', P 'Где сломалось', Q 'Ошибка'\",1)");

  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'updateDownloads') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('updateDownloads').timeBased().everyHours(6).create();
  updateDownloads();

  var first = ss.getSheetByName('Лист1') || ss.getSheetByName('Sheet1');
  if (first && ss.getSheets().length > 1) ss.deleteSheet(first);
  ss.setActiveSheet(ss.getSheetByName(SUMMARY));
}

// Приём отчёта установщика или сигнала «Сторожа».
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

    var kind = data.kind === undefined || data.kind === null || data.kind === '' ? 'install' : String(data.kind);
    if (kind === 'ping') return ping_(data, cache);
    if (kind !== 'install') return out_({ ok: false, error: 'неизвестный вид отчёта' });

    var row = [new Date()];
    var vals = {};
    for (var i = 0; i < FIELDS.length; i++) {
      var v = clean_(data[FIELDS[i][0]], FIELDS[i]);
      if (v === null) return out_({ ok: false, error: 'неверное поле ' + FIELDS[i][0] });
      row.push(v);
      vals[FIELDS[i][0]] = v;
    }
    if (!vals.report_id || !vals.release || !vals.result) return out_({ ok: false, error: 'нет номера отчёта, выпуска или итога' });
    if (cache.get('r' + vals.report_id)) return out_({ ok: true, report: vals.report_id, duplicate: true });

    var lock = LockService.getScriptLock();
    lock.waitLock(10000);
    try {
      var sh = sheet_(REPORTS, headers_());
      ensureHeaders_(sh, headers_());
      sh.appendRow(row);
      // ТВ из отчёта сразу появляется в «Устройствах», ещё до первого сигнала «Сторожа»
      if (vals.device_id && ['ok', 'partial', 'update_ok'].indexOf(vals.result) >= 0) upsertDevice_(vals, false);
    } finally {
      lock.releaseLock();
    }
    cache.put('r' + vals.report_id, '1', 21600);
    return out_({ ok: true, report: vals.report_id });
  } catch (err) {
    return out_({ ok: false, error: 'отчёт не принят' });
  }
}

// Ежедневный сигнал «Сторожа»: обновить строку этого ТВ в «Устройствах».
function ping_(data, cache) {
  var vals = {};
  for (var i = 0; i < PING_FIELDS.length; i++) {
    var f = PING_FIELDS[i];
    var v = clean_(data[f[0]], f);
    if (v === null) return out_({ ok: false, error: 'неверное поле ' + f[0] });
    vals[f[0]] = v;
  }
  if (!vals.device_id || !vals.release) return out_({ ok: false, error: 'нет устройства или версии' });
  var key = 'p' + vals.device_id;
  if (cache.get(key)) return out_({ ok: true, duplicate: true });
  var lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    upsertDevice_(vals, true);
  } finally {
    lock.releaseLock();
  }
  cache.put(key, '1', PING_EVERY_H * 3600);
  return out_({ ok: true });
}

// Строка ТВ в «Устройствах»: новая или обновлённая. Пустые новые значения не затирают прежние.
function upsertDevice_(vals, isPing) {
  var sh = sheet_(DEVICES, devHeaders_());
  ensureHeaders_(sh, devHeaders_());
  var now = new Date();
  var rest = PING_FIELDS.slice(1).map(function (f) { return vals[f[0]] === undefined ? '' : vals[f[0]]; });
  var found = null;
  if (sh.getLastRow() > 1) {
    found = sh.getRange(2, 1, sh.getLastRow() - 1, 1).createTextFinder(vals.device_id).matchEntireCell(true).findNext();
  }
  if (found) {
    var r = found.getRow();
    var count = Number(sh.getRange(r, 4).getValue() || 0) + (isPing ? 1 : 0);
    var old = sh.getRange(r, 5, 1, rest.length).getValues()[0];
    for (var i = 0; i < rest.length; i++) if (rest[i] === '') rest[i] = old[i];
    sh.getRange(r, 3, 1, 2).setValues([[now, count]]);
    sh.getRange(r, 5, 1, rest.length).setValues([rest]);
  } else {
    sh.appendRow([vals.device_id, now, now, isPing ? 1 : 0].concat(rest));
  }
}

// Публичная сводка: модели (сколько установок и удачных) и сколько ТВ на какой версии. Без номеров и ошибок.
function doGet() {
  var ss = book_();
  var stats = {};
  var sh = ss.getSheetByName(REPORTS);
  if (sh && sh.getLastRow() > 1) {
    sh.getRange(2, 1, sh.getLastRow() - 1, 15).getValues().forEach(function (r) {
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

  var devices = { total: 0, active7: 0, versions: {} };
  var dv = ss.getSheetByName(DEVICES);
  if (dv && dv.getLastRow() > 1) {
    var weekAgo = Date.now() - 7 * 86400000;
    dv.getRange(2, 1, dv.getLastRow() - 1, 5).getValues().forEach(function (r) {
      if (!r[0]) return;
      devices.total++;
      if (new Date(r[2]).getTime() >= weekAgo) {
        devices.active7++;
        var ver = String(r[4] || '?');
        devices.versions[ver] = (devices.versions[ver] || 0) + 1;
      }
    });
  }
  return out_({ ok: true, models: list, devices: devices });
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
      if (/\.(zip|tar\.gz)$/.test(a.name) && a.name.indexOf('source') < 0) rows.push([now, rel.tag_name, a.name, a.download_count]);
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
    case 'id_opt':
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

// Убирает то, чего в отчёте быть не должно, даже если отправитель пропустил: IP и MAC-адреса, почту.
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

// Дописывает в первую строку заголовки новых колонок, если лист создан прежней версией скрипта
function ensureHeaders_(sh, headers) {
  var have = sh.getLastColumn();
  if (have >= headers.length) return;
  sh.getRange(1, have + 1, 1, headers.length - have).setValues([headers.slice(have)]).setFontWeight('bold');
}

function headers_() {
  return ['Принят'].concat(FIELDS.map(function (f) { return f[1]; }));
}

function devHeaders_() {
  return ['Устройство', 'Впервые', 'Последний сигнал', 'Сигналов'].concat(PING_FIELDS.slice(1).map(function (f) { return f[1]; }));
}

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
