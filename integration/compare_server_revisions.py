"""LocalChat 1.28.13: read-only comparison of a server project folder with the downloaded project folder, per drawing.

Conventions (taken from the original PDFCONTROL engine, not guessed):
  * Drawing identity = file name without extension and without a trailing revision token, e.g. 507391_BD01_V
    (mbs_vergelijk.py strips "[ _-]*(rev|revisie)[ _.-]*<token>$" before matching sheets); V and W sheets of one mark are separate drawings.
  * Revision = the explicit Rev/Revisie/Revision/Wijz. field in the lower-right title block of the PDF
    (mbs_vergelijk_pdf.revision, never the revision history table); otherwise the "_rev<token>" suffix of the file name.
  * The engine only compares revision values for equality. The ORDER used here (numbers numerically, letters A<B<...<Z<AA)
    is a heuristic; mixed or unknown values are reported as unordered, with a modified-time hint only.

Both folders are only read. The JSON report goes to the output folder; no drawing is created, copied, changed or deleted.
"""
import argparse, hashlib, json, os, re, sys, time
from pathlib import Path

VERSION = '1.28.13'
EXCLUDED = re.compile(r'(?i)^(output|controle|old|oud|archive|archief|backup|octop-pdfcontrol|pdfcontrol|\.git|\$recycle\.bin|[_.].*|tmp.*|temp.*)$')
REV_SUFFIX = re.compile(r'[\s_-]*(?:rev|revisie)[\s_.-]*([A-Za-z0-9][A-Za-z0-9.-]*)$', re.I)
MAX_FILES = 40000
MAX_DEPTH = 8
LIST_LIMIT = 40
CATEGORIES = ['newer_local', 'older_local', 'same', 'same_revision_different_content', 'different_revision_unordered', 'only_local', 'only_server', 'ambiguous', 'unreadable']
LABELS = {'newer_local': 'NEWER LOCALLY (local revision is higher)', 'older_local': 'OLDER LOCALLY (server has the higher revision)',
          'same': 'SAME (identical content)', 'same_revision_different_content': 'SAME REVISION, DIFFERENT CONTENT',
          'different_revision_unordered': 'DIFFERENT, REVISION ORDER UNKNOWN', 'only_local': 'ONLY LOCAL', 'only_server': 'ONLY ON SERVER',
          'ambiguous': 'AMBIGUOUS (several files for one drawing)', 'unreadable': 'UNREADABLE'}


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def drawing_key(name):
    stem = Path(name).stem
    return REV_SUFFIX.sub('', stem).casefold()


def name_revision(name):
    match = REV_SUFFIX.search(Path(name).stem)
    return match.group(1) if match else ''


def collect(root):
    root = Path(root)
    base = len(root.parts)
    found, count = {}, 0
    for dirpath, dirs, files in os.walk(root, followlinks=False):
        depth = len(Path(dirpath).parts) - base
        dirs[:] = sorted(d for d in dirs if not EXCLUDED.match(d) and not os.path.islink(os.path.join(dirpath, d)))
        if depth >= MAX_DEPTH:
            dirs[:] = []
        for name in sorted(files):
            if not name.lower().endswith('.pdf'):
                continue
            count += 1
            if count > MAX_FILES:
                raise RuntimeError('Too many files under ' + str(root))
            path = Path(dirpath) / name
            found.setdefault(drawing_key(name), []).append(path)
    return found


def rev_key(value):
    value = (value or '').strip().upper()
    if re.fullmatch(r'\d+', value):
        return (0, int(value))
    if re.fullmatch(r'[A-Z]+', value):
        return (1, len(value), value)
    return None


def compare_revisions(local, server):
    a, b = rev_key(local), rev_key(server)
    if a is None or b is None or a[0] != b[0]:
        return None
    return (a > b) - (a < b)


def pdf_revision(path, reader):
    """(value, source): the title-block revision field, else the file name suffix, else ('', 'niet vastgesteld')."""
    if reader is not None:
        try:
            import pymupdf
            with pymupdf.open(str(path)) as doc:
                if doc.page_count:
                    found = reader(doc[0], path.name)
                    if found.get('waarde'):
                        return found['waarde'], found.get('bron', '')
        except Exception:
            pass
    value = name_revision(path.name)
    return (value, 'bestandsnaam') if value else ('', 'niet vastgesteld')


def info(path):
    stat = path.stat()
    return {'path': str(path), 'bytes': stat.st_size, 'modified': time.strftime('%Y-%m-%d %H:%M', time.localtime(stat.st_mtime))}


def run(request, output, package=None):
    package = Path(package or Path(__file__).parent).resolve()
    if str(package) not in sys.path:
        sys.path.insert(0, str(package))
    if set(request) - {'project', 'local_folder', 'server_folder'}:
        raise ValueError('Unknown fields: ' + ', '.join(sorted(set(request) - {'project', 'local_folder', 'server_folder'})))
    project = request.get('project')
    if not isinstance(project, str) or not re.fullmatch(r'\d{6}', project):
        raise ValueError('Project must be six digits')
    local, server = Path(request['local_folder']).resolve(), Path(request['server_folder']).resolve()
    for label, folder in (('local', local), ('server', server)):
        if not folder.is_dir():
            raise ValueError(label + ' folder not found: ' + str(folder))
    if local == server:
        raise ValueError('local and server folder are the same')
    try:
        from mbs_vergelijk_pdf import revision as reader
    except Exception:
        reader = None
    started = time.monotonic()
    left, right = collect(local), collect(server)
    report = {'version': VERSION, 'project': project, 'local_folder': str(local), 'server_folder': str(server),
              'revision_source': 'title block field (engine mbs_vergelijk_pdf.revision), else _rev suffix of the file name' if reader else 'file name suffix only (engine reader unavailable)',
              'read_only': True, 'items': {c: [] for c in CATEGORIES}}
    for key in sorted(set(left) | set(right)):
        a, b = left.get(key, []), right.get(key, [])
        entry = {'drawing': key}
        try:
            if len(a) > 1 or len(b) > 1:
                entry.update(local=[str(p) for p in a], server=[str(p) for p in b])
                report['items']['ambiguous'].append(entry)
            elif not b:
                entry['local'] = info(a[0]); report['items']['only_local'].append(entry)
            elif not a:
                entry['server'] = info(b[0]); report['items']['only_server'].append(entry)
            else:
                entry.update(local=info(a[0]), server=info(b[0]))
                if entry['local']['bytes'] == entry['server']['bytes'] and sha256(a[0]) == sha256(b[0]):
                    report['items']['same'].append(entry); continue
                (lv, ls), (sv, ss) = pdf_revision(a[0], reader), pdf_revision(b[0], reader)
                entry.update(local_revision=lv or None, server_revision=sv or None, local_revision_source=ls, server_revision_source=ss)
                order = compare_revisions(lv, sv) if lv and sv else None
                newer_by_time = 'local' if a[0].stat().st_mtime > b[0].stat().st_mtime else 'server'
                if order is None:
                    entry['newer_by_modified_time_hint'] = newer_by_time
                    report['items']['different_revision_unordered'].append(entry)
                elif order > 0:
                    report['items']['newer_local'].append(entry)
                elif order < 0:
                    report['items']['older_local'].append(entry)
                else:
                    entry['newer_by_modified_time_hint'] = newer_by_time
                    report['items']['same_revision_different_content'].append(entry)
        except OSError as error:
            entry['error'] = str(error); report['items']['unreadable'].append(entry)
    report['counts'] = {c: len(v) for c, v in report['items'].items()}
    report['seconds'] = round(time.monotonic() - started, 1)
    Path(output).mkdir(parents=True, exist_ok=True)
    stamp = time.strftime('%Y%m%d-%H%M%S') + '-' + str(time.time_ns() % 1000000)
    path = Path(output) / ('server-revisions-' + project + '-' + stamp + '.json')
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    return report, path


def line(category, entry):
    name = entry['drawing']
    local, server = entry.get('local'), entry.get('server')
    if category in ('newer_local', 'older_local', 'same_revision_different_content', 'different_revision_unordered'):
        extra = ' (newer by modified time: ' + entry['newer_by_modified_time_hint'] + ', hint only)' if 'newer_by_modified_time_hint' in entry else ''
        return '  %s: local rev %s (%s) vs server rev %s (%s)%s' % (name, entry.get('local_revision') or '?', local['modified'], entry.get('server_revision') or '?', server['modified'], extra)
    if category == 'only_local':
        return '  %s (%s)' % (name, local['path'])
    if category == 'only_server':
        return '  %s (%s)' % (name, server['path'])
    if category == 'ambiguous':
        return '  %s: %d local, %d on server' % (name, len(entry['local']), len(entry['server']))
    return '  %s %s' % (name, entry.get('error', ''))


def summary(report, path):
    counts = report['counts']
    lines = ['Server vs local project ' + report['project'] + ' (read-only, nothing written next to the drawings)',
             'Local: ' + report['local_folder'], 'Server: ' + report['server_folder'], 'Revision source: ' + report['revision_source'],
             ', '.join('%s %d' % (c, counts[c]) for c in CATEGORIES)]
    for category in CATEGORIES:
        if category == 'same' or not report['items'][category]:
            continue
        items = report['items'][category]
        lines.append(LABELS[category] + ': ' + str(len(items)))
        lines.extend(line(category, e) for e in items[:LIST_LIMIT])
        if len(items) > LIST_LIMIT:
            lines.append('  ... %d more in the report' % (len(items) - LIST_LIMIT))
    lines.append('Report: ' + str(path))
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
