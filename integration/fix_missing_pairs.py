"""LocalChat 1.28.13: find production drawings without their W pair and copy the confirmed W under the missing pair name.

Convention (taken from the original PDFCONTROL engine, mbs_webapp.plan_wapening / w_naam_van, not guessed):
  <project>_<mark>_V.pdf is the formwork drawing; its pair is <project>_<mark>_W.pdf (same name, last _V -> _W).
  A V sheet can name the reinforcement of ANOTHER mark ("WA133_W", "GB1wap"); then the source is <project>_<ref>_W.pdf
  and it is copied under the missing pair name. The source must be confirmed by the engine itself (_bevestigd_w_bestand):
  W stamp, right mark, right project, no list or V sheet.

Safety: sources are never changed, an existing file is never overwritten (exclusive create, SHA-256 verified), nothing is
deleted, and a source is copied only when exactly one distinct confirmed file exists. Everything else is reported.
"""
import argparse, contextlib, hashlib, json, os, re, shutil, sys, time
from pathlib import Path

VERSION = '1.28.13'
# Folders that never hold final drawings: generated output, controls, archives, helper/scratch folders.
EXCLUDED = re.compile(r'(?i)^(output|controle|old|oud|archive|archief|backup|octop-pdfcontrol|pdfcontrol|\.git|\$recycle\.bin|[_.].*|tmp.*|temp.*)$')
# Folder names that look like "final / separate drawings" (Dumitru's wording); preferred when several distinct candidates exist.
PREFERRED = re.compile(r'(?i)(final|finale|definitiv|definitief|separat|separate|gescheiden|individual|losse|los)')
V_FILE = re.compile(r'^(\d{6})_(.+)_V\.pdf$', re.I)
W_NAME = re.compile(r'^(\d{6})_(.+)_W\.pdf$', re.I)
MAX_FILES = 40000
MAX_DEPTH = 8


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def allowed_dir(name):
    return not EXCLUDED.match(name)


def walk(root, max_depth=MAX_DEPTH):
    """(dirpath, filenames) below root, skipping generated/helper/archive folders; symlinks are not followed."""
    root = Path(root)
    base = len(root.parts)
    count = 0
    for dirpath, dirs, files in os.walk(root, followlinks=False):
        depth = len(Path(dirpath).parts) - base
        dirs[:] = sorted(d for d in dirs if allowed_dir(d) and not os.path.islink(os.path.join(dirpath, d)))
        if depth >= max_depth:
            dirs[:] = []
        count += len(files)
        if count > MAX_FILES:
            raise RuntimeError('Too many files under ' + str(root))
        yield dirpath, files


def drawing_folders(project_root, project):
    """Folders that hold <project>_*_V.pdf drawings (discovery of the project layout; any depth)."""
    found = []
    for dirpath, files in walk(project_root):
        if any(V_FILE.match(f) and f.startswith(project + '_') for f in files):
            found.append(Path(dirpath))
    return found


def build_index(roots):
    """Lower-case file name -> [paths] of every PDF below the search roots."""
    index = {}
    for root in roots:
        for dirpath, files in walk(root):
            for f in files:
                if f.lower().endswith('.pdf'):
                    index.setdefault(f.lower(), []).append(Path(dirpath) / f)
    return index


def preferred(path, project_root):
    try:
        parts = Path(path).parent.relative_to(project_root).parts
    except ValueError:
        parts = Path(path).parent.parts
    return any(PREFERRED.search(p) for p in parts)


def mark_of(name):
    m = W_NAME.match(name)
    return m.group(2) if m else ''


def copy_new(source, target):
    """Create target from source. Never overwrites (exclusive create) and verifies the copy; returns the SHA-256."""
    source, target = Path(source), Path(target)
    expected = sha256(source)
    try:
        with open(source, 'rb') as src, open(target, 'xb') as dst:
            shutil.copyfileobj(src, dst, 1 << 20)
    except FileExistsError:
        return None
    try:
        shutil.copystat(source, target)
    except OSError:
        pass
    if sha256(target) != expected:
        try:
            target.unlink()  # our own just-created file, never an existing one
        finally:
            raise RuntimeError('Copy verification failed: ' + str(target))
    return expected


def classify(web, folder, project):
    """Engine plan for one folder (reads the V drawings, copies nothing)."""
    with contextlib.redirect_stdout(sys.stderr):
        plan = web.plan_wapening(str(folder), fase='los', prefix='')
    return [step for step in plan if str(step.get('vorm', '')).startswith(project + '_')]


def fix_folder(web, folder, project, index, project_root, apply, report):
    folder = Path(folder)
    names = {f.lower(): f for f in os.listdir(folder) if f.lower().endswith('.pdf')}
    for step in classify(web, folder, project):
        kind, drawing, target = step.get('soort'), step.get('vorm', ''), step.get('doel', '')
        item = {'folder': str(folder), 'drawing': drawing, 'pair': target}
        m = V_FILE.match(drawing)
        item['mark'] = m.group(2) if m else ''
        if kind == 'aanwezig':
            item['status'] = 'present'
            report['present'].append(item)
            continue
        if kind == 'meer_verwijzingen':
            item.update(status='ambiguous', reason='The drawing or the folder points to several W sources.', sources=step.get('bronnen', []))
            report['ambiguous'].append(item)
            continue
        if kind == 'kopie':  # the source W is already in this folder
            item['mesh'] = mark_of(step.get('bron', ''))
            candidates = [folder / step['bron']]
        else:
            wanted = step.get('gezocht') or target
            reason = step.get('reden', '')
            if target.lower() in names:
                item.update(status='conflict', reason='A W file with the pair name exists but is not the referenced reinforcement; it was NOT overwritten.', existing=target)
                report['conflict'].append(item)
                continue
            if not reason.startswith('Verwezen W-bron ontbreekt'):
                item.update(status='unreadable', reason=reason or 'The drawing could not be read.')
                report['unreadable'].append(item)
                continue
            item['mesh'] = mark_of(wanted)
            item['searched'] = wanted
            candidates = [p for p in index.get(wanted.lower(), []) if p.parent.resolve() != folder.resolve()]
        confirmed, unconfirmed = [], []
        for path in candidates:
            code = mark_of(path.name)
            with contextlib.redirect_stdout(sys.stderr):
                ok = web._bevestigd_w_bestand(str(path.parent), path.name, code, project)
            (confirmed if ok else unconfirmed).append(path)
        distinct = {}
        for path in confirmed:
            distinct.setdefault(sha256(path), []).append(path)
        if not distinct:
            item.update(status='not_found', reason=('Found by name but not confirmed as that W drawing: ' + ', '.join(str(p) for p in unconfirmed)) if unconfirmed else 'No file with that name below the project folder (searched final/separate and all other drawing folders).')
            report['not_found'].append(item)
            continue
        chosen = None
        if len(distinct) == 1:
            chosen = sorted(next(iter(distinct.values())), key=lambda p: (not preferred(p, project_root), len(str(p))))[0]
        else:
            favoured = {digest: paths for digest, paths in distinct.items() if any(preferred(p, project_root) for p in paths)}
            if len(favoured) == 1:
                chosen = sorted(next(iter(favoured.values())), key=lambda p: (not preferred(p, project_root), len(str(p))))[0]
        if chosen is None:
            item.update(status='ambiguous', reason='Several different confirmed files with that name; none is in a final/separate folder.', sources=[str(p) for paths in distinct.values() for p in paths])
            report['ambiguous'].append(item)
            continue
        item['source'] = str(chosen)
        dest = folder / target
        if dest.exists() or target.lower() in {n.lower() for n in os.listdir(folder)}:
            item.update(status='exists', reason='The pair name exists already; nothing overwritten.')
            report['conflict'].append(item)
            continue
        if not apply:
            item['status'] = 'would_copy'
            report['would_copy'].append(item)
            continue
        digest = copy_new(chosen, dest)
        if digest is None:
            item.update(status='exists', reason='The pair name appeared meanwhile; nothing overwritten.')
            report['conflict'].append(item)
            continue
        item.update(status='copied', created=str(dest), sha256=digest)
        report['copied'].append(item)


def run(request, output, package=None):
    package = Path(package or Path(__file__).parent).resolve()
    if str(package) not in sys.path:
        sys.path.insert(0, str(package))
    allowed = {'project', 'project_root', 'folders', 'source_folders', 'apply'}
    if set(request) - allowed:
        raise ValueError('Unknown fields: ' + ', '.join(sorted(set(request) - allowed)))
    project = request.get('project')
    if not isinstance(project, str) or not re.fullmatch(r'\d{6}', project):
        raise ValueError('Project must be six digits')
    project_root = Path(request['project_root']).resolve()
    if not project_root.is_dir():
        raise ValueError('Project folder not found: ' + str(project_root))

    def inside(value):
        path = Path(value).resolve()
        if not path.is_dir() or (path != project_root and project_root not in path.parents):
            raise ValueError('Folder is not inside the project folder: ' + str(value))
        return path

    folders = [inside(f) for f in request.get('folders') or []] or drawing_folders(project_root, project)
    sources = [inside(f) for f in request.get('source_folders') or []] or [project_root]
    apply = request.get('apply', True) is not False
    import mbs_webapp as web
    started = time.monotonic()
    report = {'version': VERSION, 'project': project, 'project_root': str(project_root), 'apply': apply, 'folders': [str(f) for f in folders],
              'search_roots': [str(s) for s in sources], 'copied': [], 'would_copy': [], 'present': [], 'not_found': [], 'ambiguous': [], 'conflict': [], 'unreadable': []}
    index = build_index(sources)
    for folder in folders:
        fix_folder(web, folder, project, index, project_root, apply, report)
    report['seconds'] = round(time.monotonic() - started, 1)
    Path(output).mkdir(parents=True, exist_ok=True)
    stamp = time.strftime('%Y%m%d-%H%M%S') + '-' + str(time.time_ns() % 1000000)
    path = Path(output) / ('missing-pairs-' + project + '-' + stamp + '.json')
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    return report, path


def summary(report, path):
    n = lambda key: len(report[key])
    lines = ['pdfcontrol_fix_missing_pairs ' + report['project'] + (' (dry run, nothing copied)' if not report['apply'] else '') + ': '
             + str(len(report['folders'])) + ' drawing folder(s), ' + str(sum(n(k) for k in ('present', 'copied', 'would_copy', 'not_found', 'ambiguous', 'conflict', 'unreadable'))) + ' V drawing(s) checked.',
             'Pair present: ' + str(n('present')) + ' | copied: ' + str(n('copied')) + (' | would copy: ' + str(n('would_copy')) if not report['apply'] else '')
             + ' | not found: ' + str(n('not_found')) + ' | ambiguous: ' + str(n('ambiguous')) + ' | conflict (existing, not overwritten): ' + str(n('conflict')) + ' | unreadable: ' + str(n('unreadable'))]
    shown = 0
    for key, label in (('copied', 'COPIED'), ('would_copy', 'WOULD COPY'), ('not_found', 'NOT FOUND'), ('ambiguous', 'AMBIGUOUS'), ('conflict', 'NOT OVERWRITTEN'), ('unreadable', 'UNREADABLE')):
        for item in report[key]:
            if shown >= 60:
                break
            shown += 1
            detail = (item.get('source', '') + ' -> ' + item.get('pair', '')) if key in ('copied', 'would_copy') else (item.get('reason', '') + (' Searched: ' + item['searched'] if item.get('searched') else ''))
            lines.append(label + ': ' + item['drawing'] + (' [mesh ' + item['mesh'] + ']' if item.get('mesh') else '') + ' | ' + detail)
    lines.append('Sources unchanged, nothing deleted, no file overwritten. Report: ' + str(path))
    lines.append('Not copied items need the user (several sources, not found, existing different W). V sheets inside multi-page bundles are not handled here: use pdfcontrol_start_extract first.')
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--request', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--package')
    args = parser.parse_args()
    try:
        request = json.loads(Path(args.request).read_text(encoding='utf-8'))
        report, path = run(request, args.output, args.package)
    except Exception as error:
        print('Error: ' + str(error))
        return 2
    print(summary(report, path))
    return 0


if __name__ == '__main__':
    sys.exit(main())