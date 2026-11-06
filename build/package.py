"""package.py: архивы выпуска HYDE TV для людей, по одному на систему.

В каждом архиве папка HYDE-TV-<версия>/: установщик hyde-install под эту систему, release/ (файлы выпуска ровно по
SHA256SUMS выпуска плюс manifest.json и сами суммы), README.txt (как начать на этой системе), CLAUDE.md (путь с Claude,
installer/AGENT.md) и licenses/. adb в архив не кладётся: условия Android SDK запрещают его раздавать, установщик
качает его сам с сайта Google по согласию человека.
Архивы повторяемые: постоянные даты, порядок и права файлов; тот же выпуск и тот же установщик дают те же байты.
VERSION.txt: номер выпуска, коммит приложений (из описи) и коммит установщика.
Запуск: python package.py <папка выпуска> <папка установщиков> <куда> <версия> <корень репозитория> <коммит установщика>
"""
import gzip
import hashlib
import io
import json
import os
import sys
import tarfile
import zipfile

rel, inst, out, ver, root, icommit = sys.argv[1:7]
EPOCH = (1980, 1, 1, 0, 0, 0)
EPOCH_TS = 315532800

man = json.load(open(os.path.join(rel, 'manifest.json'), encoding='utf-8'))
if man.get('release') != ver:
    sys.exit('в описи выпуск %s, а просили %s' % (man.get('release'), ver))
if man.get('built_from', {}).get('uncommitted_changes'):
    sys.exit('выпуск собран пробой из рабочей папки (незакоммиченные правки): для людей не упаковываю')
if not any(t.get('id') == 'platform-tools' for t in man.get('tools', [])):
    sys.exit('в описи нет записи об adb (tools): установщик не сможет предложить его скачать. Пересоберите выпуск')
cert = man['signer_cert_sha256']


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


# Файлы выпуска: ровно те, что в его SHA256SUMS, и каждый сверен; лишнее в папке (журналы, пробы) не попадает
release_files = []
for line in open(os.path.join(rel, 'SHA256SUMS'), encoding='utf-8'):
    line = line.strip()
    if not line:
        continue
    s, name = line.split(None, 1)
    name = name.lstrip('*')
    if '/' in name or '\\' in name or name.startswith('.'):
        sys.exit('странное имя в SHA256SUMS: %s' % name)
    if sha256(os.path.join(rel, name)) != s:
        sys.exit('файл выпуска не совпал со своей суммой: %s' % name)
    release_files.append(name)
release_files += ['SHA256SUMS', 'manifest.json']

readme_tpl = open(os.path.join(root, 'installer', 'dist', 'README.txt'), encoding='utf-8').read()


def readme(kind, platform, archive):
    """Шаблон: общие части и блоки @@windows, @@macos, @@linux до @@end; берётся блок этой системы"""
    outl, cur = [], None
    for ln in readme_tpl.split('\n'):
        if ln.startswith('@@'):
            cur = None if ln == '@@end' else ln[2:]
            continue
        if cur is None or cur == kind:
            outl.append(ln)
    t = '\n'.join(outl)
    for k, v in (('{VERSION}', ver), ('{PLATFORM}', platform), ('{ARCHIVE}', archive), ('{CERT}', cert)):
        t = t.replace(k, v)
    return ('﻿' + t).encode('utf-8')  # с BOM: так кириллицу верно открывают Блокнот и TextEdit


licenses = [('LICENSE', 'LICENSE.txt'), ('THIRD-PARTY.md', 'THIRD-PARTY.md'),
            ('aura/LICENSE-Aura.txt', 'LICENSE-Aura.txt'), ('tvbro/LICENSE-TVBro.md', 'LICENSE-TVBro.md')]

PLATFORMS = [  # (установщик, имя в архиве, система для README, подпись, вид архива)
    ('hyde-install-windows-amd64.exe', 'Windows', 'windows', 'Windows', 'zip'),
    ('hyde-install-darwin-arm64', 'macOS-Apple', 'macos', 'macOS на Apple M1 и новее', 'zip'),
    ('hyde-install-darwin-amd64', 'macOS-Intel', 'macos', 'macOS на Intel', 'zip'),
    ('hyde-install-linux-amd64', 'Linux-x64', 'linux', 'Linux x86-64', 'tar.gz'),
    ('hyde-install-linux-arm64', 'Linux-ARM64', 'linux', 'Linux ARM64', 'tar.gz'),
]

os.makedirs(out, exist_ok=True)
top = 'HYDE-TV-%s/' % ver
sums = []
for binname, label, kind, platform, fmt in PLATFORMS:
    archive = 'HYDE-TV-%s-%s.%s' % (ver, label, fmt)
    entries = []  # (имя в архиве, байты или путь, исполняемый)
    exe = 'hyde-install.exe' if kind == 'windows' else 'hyde-install'
    entries.append((exe, os.path.join(inst, binname), True))
    entries.append(('README.txt', readme(kind, platform, archive), False))
    entries.append(('CLAUDE.md', os.path.join(root, 'installer', 'AGENT.md'), False))
    entries.append(('VERSION.txt', ('HYDE TV %s (%s)\nприложения: коммит %s, опись release/manifest.json\nустановщик: коммит %s\n'
                                    % (ver, platform, man['built_from']['commit'], icommit)).encode('utf-8'), False))
    for src, dst in licenses:
        entries.append(('licenses/' + dst, os.path.join(root, src), False))
    for name in release_files:
        entries.append(('release/' + name, os.path.join(rel, name), False))
    entries.sort(key=lambda e: e[0])
    path = os.path.join(out, archive)

    def data(e):
        return e[1] if isinstance(e[1], bytes) else open(e[1], 'rb').read()

    if fmt == 'zip':
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for name, src, x in entries:
                zi = zipfile.ZipInfo(top + name, date_time=EPOCH)
                zi.create_system = 3  # права Unix: macOS и Linux сохранят исполняемость установщика
                zi.external_attr = (0o100755 if x else 0o100644) << 16
                zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, data((name, src, x)), compresslevel=9)
    else:
        raw = io.BytesIO()
        with tarfile.open(fileobj=raw, mode='w', format=tarfile.USTAR_FORMAT) as t:
            dirs = sorted({top.rstrip('/')} | {top + n.rsplit('/', 1)[0] for n, _, _ in entries if '/' in n})
            for d in dirs:
                ti = tarfile.TarInfo(d)
                ti.type, ti.mode, ti.mtime = tarfile.DIRTYPE, 0o755, EPOCH_TS
                t.addfile(ti)
            for name, src, x in entries:
                b = data((name, src, x))
                ti = tarfile.TarInfo(top + name)
                ti.size, ti.mode, ti.mtime = len(b), (0o755 if x else 0o644), EPOCH_TS
                t.addfile(ti, io.BytesIO(b))
        with open(path, 'wb') as f:
            with gzip.GzipFile(filename='', mode='wb', fileobj=f, mtime=0, compresslevel=9) as g:
                g.write(raw.getvalue())
    s = sha256(path)
    sums.append('%s  %s' % (s, archive))
    print('%s: %.1f МБ' % (archive, os.path.getsize(path) / 1048576))

open(os.path.join(out, 'SHA256SUMS'), 'w', encoding='utf-8', newline='\n').write('\n'.join(sums) + '\n')
print('архивы и SHA256SUMS: %s' % out)
