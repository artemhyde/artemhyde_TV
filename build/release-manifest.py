"""release-manifest.py: опись выпуска HYDE TV (manifest.json) и SHA256SUMS по готовым apk в папке выпуска.

Запуск (его делает build/release.sh): python release-manifest.py <папка выпуска> <версия> <versionCode> <aapt2> <java> <apksigner.jar> <корень репозитория>
По каждому файлу: модуль, пакет, versionCode и versionName, minSdk и targetSdk, ABI, SHA-256 и размер файла, SHA-256 сертификата,
источник (папка в репозитории и коммит), лицензия, зависимости и способ возврата. Сверяет, что у всех один ключ и одна версия.
"""
import hashlib
import json
import os
import re
import subprocess
import sys

stage, ver, code, aapt2, java, signer, root = sys.argv[1:8]
code = int(code)

# Протокол между приложениями: команды AppCmd и право по подписи, схема файла переноса настроек (v1).
# Меняется, когда старые и новые модули перестают понимать друг друга: тогда ставятся только целым выпуском
PROTOCOL = 1
SETTINGS_SCHEMA = 1

FIRST = 'первая установка: pm uninstall --user 0 {pkg}'
BACK = ('прежний выпуск поверх не ставится (Android не даёт понизить версию): settings_save, удалить, '
        'поставить прежний выпуск, settings_load (docs/rollback.md)')
MODULES = {
    'home': ('Главный экран «Дом»', [], 'роль «Домой» возвращается прежнему владельцу (home_switch, restore.sh)'),
    'guard': ('«Сторож»: фоновая служба', [], ''),
    'nightmode': ('«Ночной режим»', [], 'перед обновлением снять права администратора (CLAUDE.md, шаг 7)'),
    'sound': ('«Звук»', ['guard'], ''),
    'picture': ('«Картинка»', ['guard'], ''),
    'volna': ('«Волна»: музыка', ['app.glasstv.aura (плеер «Волны», hyde-aura в выпуске)'], ''),
    'aura': ('Плеер «Волны» (Aura Music с нашими патчами)', [], 'подпись совпадает с «Волной»: команды плеер принимает только от неё'),
    'clip': ('«Буфер»: вставка текста с компьютера', [], ''),
    'keyboard': ('Клавиатура', [], 'перед удалением вернуть прежнюю клавиатуру: ime set'),
    'browser': ('HYDE Browser: браузер для пульта (TV Bro с нашими правками)', [], ''),
}
MIT = 'MIT (LICENSE); шрифты OFL 1.1 (shared/fonts/OFL.txt)'
# HYDE Browser: изменённая сборка TV Bro по второму пути его лицензии (своё имя, значок, application ID, окно авторства)
TVBRO_COMMIT = '52b078f'
AURA_COMMIT = '2e4f1263f710d88b6841c9ee097bb94a0e456f16'
BROWSER_LICENSE = ('лицензия TV Bro (tvbro/LICENSE-TVBro.md): изменённая сборка со своим именем, значком, application ID '
                   'и окном «О программе» со ссылкой на github.com/truefedex/tv-bro; наши патчи MIT')


def run(*cmd):
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace').stdout


def badging(apk):
    out = run(aapt2, 'dump', 'badging', apk)
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    sdk = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", out)
    tgt = re.search(r"targetSdkVersion:'(\d+)'", out)
    abi = re.search(r"native-code: (.+)", out)
    if not m:
        sys.exit('не прочитался %s: %s' % (apk, out[:300]))
    return {'package': m.group(1), 'versionCode': int(m.group(2)), 'versionName': m.group(3),
            'minSdk': int(sdk.group(1)) if sdk else None, 'targetSdk': int(tgt.group(1)) if tgt else None,
            'abi': abi.group(1).replace("'", '').split() if abi else 'any'}


def cert(apk):
    out = run(java, '-jar', signer, 'verify', '--print-certs', apk)
    m = re.search(r'Signer #1 certificate SHA-256 digest: *([0-9a-f]+)', out)
    if not m:
        sys.exit('подпись %s не прошла проверку: %s' % (apk, out[:300]))
    return m.group(1)


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


# Коммит и пометку о незакоммиченных правках передаёт build/release.sh: исходники выпуска это выгрузка без .git
commit = os.environ.get('HYDE_COMMIT') or None
dirty = os.environ.get('HYDE_DIRTY') == '1'

tiles = {}
tt = os.path.join(root, 'apps', 'tiles', 'tiles.txt')
if os.path.exists(tt):
    for line in open(tt, encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#'):
            p = line.split('|')
            if len(p) >= 3:
                tiles[p[0]] = (p[1], p[2], p[3] if len(p) > 3 else p[0])

apps, sums, certs = [], [], set()
for name in sorted(os.listdir(stage)):
    if not name.endswith('.apk'):
        continue
    path = os.path.join(stage, name)
    b = badging(path)
    c = cert(path)
    certs.add(c)
    if b['versionCode'] != code or b['versionName'] != ver:
        sys.exit('%s: версия %s (%s), а выпуск %s (%s)' % (name, b['versionName'], b['versionCode'], ver, code))
    m = re.match(r'hyde-(tile-)?(.+)-' + re.escape(ver) + r'\.apk$', name)
    if not m:
        sys.exit('непонятное имя файла в выпуске: %s' % name)
    module = ('tile-' if m.group(1) else '') + m.group(2)
    if m.group(1):
        t = tiles.get(m.group(2))
        title, deps, note, src = ('Плитка «%s»' % (t[2] if t else m.group(2)), [t[1]] if t else [], 'ставится, только если стоит цель', 'apps/tiles')
    else:
        title, deps, note = MODULES.get(module, (module, [], ''))
        src = {'browser': 'tvbro/patches', 'aura': 'aura/patches'}.get(module, 'apps/' + module)
    s = sha256(path)
    sums.append('%s  %s' % (s, name))
    apps.append({
        'module': module, 'title': title, 'file': name, 'package': b['package'],
        'versionCode': b['versionCode'], 'versionName': b['versionName'],
        'minSdk': b['minSdk'], 'targetSdk': b['targetSdk'], 'abi': b['abi'],
        'sha256': s, 'size': os.path.getsize(path), 'cert_sha256': c,
        'source': ({'path': src, 'commit': commit, 'upstream': 'https://github.com/truefedex/tv-bro', 'upstream_commit': TVBRO_COMMIT}
                   if module == 'browser' else
                   {'path': src, 'commit': commit, 'upstream': 'https://github.com/TeamAuraMusic/AuraMusic', 'upstream_commit': AURA_COMMIT,
                    'source_archive': 'hyde-aura-source-%s.tar.gz' % ver}
                   if module == 'aura' else {'path': src, 'commit': commit}),
        'license': (BROWSER_LICENSE if module == 'browser' else
                    'GPL-3.0 (aura/LICENSE-Aura.txt): исходники в этом выпуске, hyde-aura-source-%s.tar.gz' % ver if module == 'aura' else MIT),
        'depends': deps,
        'rollback': {'first_install': FIRST.format(pkg=b['package']), 'update': BACK, 'note': note},
    })

# Архивы исходников (плеер «Волны» по GPL) в суммах и в описи
archives = []
for name in sorted(os.listdir(stage)):
    if name.endswith('.tar.gz'):
        path = os.path.join(stage, name)
        s = sha256(path)
        sums.append('%s  %s' % (s, name))
        archives.append({'file': name, 'sha256': s, 'size': os.path.getsize(path)})

if len(certs) != 1:
    sys.exit('у файлов выпуска разные ключи: %s' % ', '.join(sorted(certs)))

third, tools = [], []
tp = os.path.join(root, 'data', 'third-party-sources.json')
if os.path.exists(tp):
    src = json.load(open(tp, encoding='utf-8'))
    for a in src.get('apps', []):
        third.append({k: a[k] for k in ('name', 'package', 'pinned_version', 'version_seen', 'releases', 'download',
                                         'license', 'cert_sha256', 'cert_subject') if k in a})
    # Инструменты для компьютера (adb): установщик качает их сам по согласию человека и сверяет с этой записью
    for t in src.get('tools', []):
        tools.append({k: t[k] for k in ('id', 'name', 'version', 'page', 'terms', 'license', 'checksum_source', 'files') if k in t})

manifest = {
    'product': 'HYDE TV', 'release': ver, 'versionCode': code,
    'protocol': PROTOCOL, 'settings_schema': SETTINGS_SCHEMA,
    'compatibility': 'модули ставятся только целым выпуском: одна версия и один ключ у всех (protocol %d)' % PROTOCOL,
    'signer_cert_sha256': certs.pop(),
    'built_from': {'commit': commit, 'uncommitted_changes': dirty},
    'reproducible': 'bash build/release.sh %s --check собирает второй раз и сравнивает байт в байт' % ver,
    'apps': apps,
    'source_archives': archives,
    'third_party': third,
    'tools': tools,
}
json.dump(manifest, open(os.path.join(stage, 'manifest.json'), 'w', encoding='utf-8', newline='\n'), ensure_ascii=False, indent=1)
open(os.path.join(stage, 'SHA256SUMS'), 'w', encoding='utf-8', newline='\n').write('\n'.join(sums) + '\n')
print('опись: %d файлов, ключ %s, коммит %s%s' % (len(apps), manifest['signer_cert_sha256'][:16] + '…', (commit or '?')[:7],
                                                  ' (есть незакоммиченные правки)' if dirty else ''))
