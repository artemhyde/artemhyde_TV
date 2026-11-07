"""update-json.py: файл обновлений для «Сторожа» hyde-update.json по описи выпуска (manifest.json).
Запуск: python -I -X utf8 build/update-json.py <папка выпуска> <корень исходников> <репозиторий> [--prev <метка прошлого выпуска>]
Лежит ассетом выпуска на GitHub рядом с APK: «Сторож» раз в сутки читает
https://github.com/artemhyde/artemhyde_TV/releases/latest/download/hyde-update.json и ставит новые версии приложений HYDE сам,
если его собственная версия не ниже silent_from и он числится их установщиком; иначе пишет «Нужен установщик на компьютере».
Выпуск требует установщика, если по сравнению с предыдущим в рецепте появились новые шаги прав или настроек
(не скачивание и не разовые команды): их без компьютера не выдать. Или сменился протокол между приложениями (protocol
в описи, PROTOCOL в build/release-manifest.py): приложения разных протоколов вместе не работают. И выпуск сразу после 1.0.0: там приложения HYDE стоят
от имени adb, а не «Сторожа», и тихо обновить их он не может, пока установщик не переставит их.
silent_from: versionCode последнего такого выпуска среди всех меток v* и этого выпуска (0, если таких нет). Так ТВ, который
пропустил выпуск с новыми правами, не получит следующие тихо. needs_installer и why: то же только про переход с --prev.
"""
import json
import os
import re
import subprocess
import sys

REPO = 'artemhyde/artemhyde_TV'


def steps(recipe):
    """Команды шагов прав и настроек по модулям: то, что выдаётся только с компьютера"""
    out = set()
    for m in recipe.get('modules', []):
        for s in m.get('steps', []):
            if s.get('run') or not s.get('do') or s.get('kind') in ('download',):
                continue
            out.add((m.get('module'), s['do']))
    for s in recipe.get('home_switch', {}).get('settings', []):
        out.add(('home_switch', '%s %s %s' % (s.get('ns'), s.get('key'), s.get('value'))))
    return out


def code_of(ver):
    """versionCode выпуска X.Y.Z: X*10000 + Y*100 + Z (как в build/release.sh)"""
    x, y, z = (int(p) for p in ver.lstrip('v').split('.'))
    return x * 10000 + y * 100 + z


def recipe_at(repo, tag):
    return json.loads(subprocess.run(['git', '-C', repo, 'show', tag + ':installer/recipe.json'],
                                     capture_output=True, check=True).stdout.decode('utf-8'))


def protocol_at(repo, tag):
    """Протокол между приложениями в выпуске tag (PROTOCOL в build/release-manifest.py); None: не прочитался"""
    try:
        src = subprocess.run(['git', '-C', repo, 'show', tag + ':build/release-manifest.py'],
                             capture_output=True, check=True).stdout.decode('utf-8')
    except (subprocess.CalledProcessError, OSError):
        return None
    m = re.search(r'^PROTOCOL\s*=\s*(\d+)', src, re.M)
    return int(m.group(1)) if m else None


def needs(old_tag, old, new, old_proto, new_proto):
    """Почему переход с выпуска old_tag (рецепт old, протокол old_proto) на рецепт new с протоколом new_proto
    требует установщика; пустой список: не требует"""
    why = []
    if old is None:
        why.append('не удалось прочитать рецепт %s: на всякий случай нужен установщик' % old_tag)
    else:
        added = sorted(steps(new) - steps(old))
        if added:
            why.append('новые права или настройки: ' + '; '.join('%s: %s' % (m, d.split('\n')[0][:80]) for m, d in added[:6]))
    if old_proto is None or new_proto is None:
        why.append('не удалось прочитать протокол %s: на всякий случай нужен установщик' % old_tag)
    elif old_proto != new_proto:
        why.append('сменился протокол между приложениями (%d на %d)' % (old_proto, new_proto))
    if old_tag.lstrip('v') == '1.0.0':
        why.append('переход с 1.0.0: приложения HYDE там стоят от имени adb, а не «Сторожа»')
    return why


def releases(repo, below):
    """Метки выпусков v* с versionCode ниже below, по возрастанию: [(code, метка)]"""
    out = subprocess.run(['git', '-C', repo, 'tag', '--list', 'v*'], capture_output=True, check=True).stdout.decode('utf-8')
    res = []
    for t in out.split():
        try:
            c = code_of(t)
        except ValueError:
            continue  # не номер выпуска X.Y.Z
        if c < below:
            res.append((c, t))
    return sorted(res)


def load(repo, tag):
    try:
        return recipe_at(repo, tag)
    except (subprocess.CalledProcessError, OSError, ValueError):
        return None


def main():
    args = sys.argv[1:]
    stage, src, repo = args[0], args[1], args[2]
    prev = ''
    if '--prev' in args:
        prev = args[args.index('--prev') + 1]
    man = json.load(open(os.path.join(stage, 'manifest.json'), encoding='utf-8'))
    ver = man['release']
    code = man.get('versionCode') or code_of(ver)
    cur = json.load(open(os.path.join(src, 'installer', 'recipe.json'), encoding='utf-8'))

    why = needs(prev, load(repo, prev), cur, protocol_at(repo, prev), man.get('protocol')) if prev else []

    # silent_from: последний выпуск, после которого нужен установщик, по всей истории меток и этому выпуску
    silent = code if why else 0
    if not why:
        chain = releases(repo, code)
        for (_, a), (cb, b) in zip(chain, chain[1:]):
            nb = load(repo, b)
            # рецепт не прочитался: на всякий случай нужен установщик
            if nb is None or needs(a, load(repo, a), nb, protocol_at(repo, a), protocol_at(repo, b)):
                silent = cb

    apps = [{'package': a['package'], 'version_code': a['versionCode'], 'version_name': a.get('versionName', ver),
             'file': a['file'], 'size': a['size'], 'sha256': a['sha256']} for a in man['apps']]
    out = {
        'schema': 1,
        'version': ver,
        'version_code': code,
        'silent_from': silent,
        'needs_installer': bool(why),
        'why': '; '.join(why),
        'signer_cert_sha256': man.get('signer_cert_sha256'),
        'url_base': 'https://github.com/%s/releases/download/v%s/' % (REPO, ver),
        'apps': sorted(apps, key=lambda a: a['package']),
    }
    path = os.path.join(stage, 'hyde-update.json')
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print('hyde-update.json: %d приложений, silent_from=%d, needs_installer=%s%s' % (
        len(apps), silent, str(out['needs_installer']).lower(), (' (' + out['why'] + ')') if why else ''))


if __name__ == '__main__':
    main()
