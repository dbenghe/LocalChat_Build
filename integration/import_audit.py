"""Offline factory/import/drawing audit. Never changes a production source or calls an API."""
import argparse, collections, csv, hashlib, html, io, json, re, sys, time
from decimal import Decimal, InvalidOperation
from pathlib import Path

VERSION = 1
FACTORIES = {'soest', 'veenoord', 'alphen', 'lelystad'}
MAX_BYTES = 100 * 1024 * 1024


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as source:
        while chunk := source.read(1024 * 1024): h.update(chunk)
    return h.hexdigest()


def norm(value):
    return re.sub(r'\s+', ' ', str(value or '').strip().casefold()).replace('\u00d7', 'x')


def unit(value):
    return {'stk': 'st', 'stuks': 'st', 'pcs': 'st', 'meter': 'm', 'meters': 'm'}.get(norm(value), norm(value))


def number(value):
    text = str(value or '').strip()
    if not re.fullmatch(r'[+-]?\d+(?:[.,]\d+)?', text): return None
    try: return Decimal(text.replace(',', '.'))
    except InvalidOperation: return None


def file_input(value, extensions):
    path = Path(value).resolve(strict=True)
    if not path.is_file() or path.suffix.lower() not in extensions:
        raise ValueError('Unsupported input type: ' + str(path))
    if path.stat().st_size > MAX_BYTES: raise ValueError('Input exceeds 100 MiB: ' + str(path))
    return path


def tables(path, reader):
    if path.suffix.lower() == '.xlsx':
        yield from reader.xlsx_bladen(path)
    else:
        raw = path.read_bytes()
        try: text = raw.decode('utf-8-sig')
        except UnicodeDecodeError: text = raw.decode('cp1252')
        try: dialect = csv.Sniffer().sniff(text[:16384], delimiters=';,\t')
        except csv.Error: raise ValueError('Cannot identify CSV delimiter: ' + str(path))
        yield 'CSV', [{'row': i, 'cells': row, 'formulas': []}
                      for i, row in enumerate(csv.reader(io.StringIO(text), dialect), 1)]


def imports(path, reader):
    records, issues = [], []
    for sheet, rows in tables(path, reader):
        header = None
        merk = ''
        for row in rows:
            cells = row['cells']; keys = [norm(c) for c in cells]
            if 'merk' in keys and 'instortvoorziening' in keys:
                if len(set(keys) - {''}) != len([k for k in keys if k]):
                    raise ValueError('Duplicate import columns: ' + str(path))
                header = {key: i for i, key in enumerate(keys) if key}
                merk = ''; continue
            if header is None: continue
            fields = {key: str(cells[i] if i < len(cells) else '').strip() for key, i in header.items()}
            if not any(fields.values()): continue
            source = {'path': str(path), 'sheet': sheet, 'row': row['row']}
            if fields.get('merk'): merk = fields['merk'].upper()
            if row['formulas']:
                issues.append(dict(source, code='formula_requires_verification', cells=row['formulas']))
            description = fields.get('instortvoorziening', '')
            if not description or description == '0': continue
            if not merk:
                issues.append(dict(source, code='article_without_merk', description=description)); continue
            records.append(dict(source, merk=merk, description=description, fields=fields,
                                formula_unverified=bool(row['formulas'])))
        if header is None:
            issues.append({'path': str(path), 'sheet': sheet, 'code': 'import_header_not_found'})
    if not records: issues.append({'path': str(path), 'code': 'no_import_articles'})
    return records, issues


def catalog(path, factory, reader):
    if path.suffix.lower() == '.json': data = json.loads(path.read_text(encoding='utf-8-sig'))
    else: data = reader.lees_catalogus(path, factory)
    if norm(data.get('factory')) != factory: raise ValueError('Catalogue factory does not match selected factory')
    result = reader.Catalogus(data)
    if not result.by_code: raise ValueError('Catalogue has no import articles; billing MB articles are not import articles')
    return result


def canonical(description, catalogue):
    # Reuse the owned engine's conservative longest complete base-name recognition.
    result = catalogue.zoek(description, catalogue.data['factory'])
    matches = result.get('matches', [])
    codes = sorted({r['code'] for r in matches})
    return result, codes


def audit_rows(records, catalogue):
    issues = []
    for record in records:
        result, codes = canonical(record['description'], catalogue)
        record['catalogue'] = {'status': result['status'], 'codes': codes,
                               'base_name': result.get('basisnaam', ''),
                               'technical_details': result.get('tekeninggegevens', ''),
                               'physical_equivalence_verified': False}
        location = {k: record[k] for k in ('path', 'sheet', 'row', 'merk', 'description')}
        if len(codes) != 1 or result['status'] == 'catalogus_conflict':
            issues.append(dict(location, code='article_unresolved', catalogue_status=result['status'],
                               candidates=[{'code': r['code'], 'description': r['description']}
                                           for r in result.get('candidates', [])][:5]))
            continue
        matches = result['matches']
        # Contradictory aliases/units under the selected code stay unresolved.
        units = {unit(r['fields'].get('eenheid') or r['fields'].get('verkoopeenheid')) for r in matches}
        units.discard('')
        actual = unit(record['fields'].get('eenheid'))
        if len(units) != 1:
            issues.append(dict(location, code='catalogue_unit_unconfirmed', units=sorted(units)))
        elif not actual or actual not in units:
            issues.append(dict(location, code='unit_mismatch', actual=actual, expected=sorted(units)))
        if actual == 'm' or units == {'m'}:
            length = number(record['fields'].get('lengte'))
            if length is None or length <= 0:
                issues.append(dict(location, code='length_required', value=record['fields'].get('lengte', ''),
                                   note='Use a drawing-linked length; do not substitute pieces for metres.'))
        for field, expected in [('tekenbureau', '14337'), ('status tekening', '20')]:
            # Status applies to actual Merk rows, not accessory continuation rows.
            if field in record['fields'] and (field != 'status tekening' or record['fields'].get('merk')):
                if record['fields'][field] != expected:
                    issues.append(dict(location, code='field_mismatch', field=field,
                                       actual=record['fields'][field], expected=expected))
    return issues


def bind_merk(text, path, marks, project):
    # No guessing from unrelated numbers on the drawing. Require a unique filename mark or labelled title.
    candidates = {mark for mark in marks if re.search(r'(?<![A-Z0-9])' + re.escape(mark) + r'(?![A-Z0-9])', path.stem.upper())}
    labelled = re.findall(r'(?im)^\s*(?:merk|elementmerk|element)\s*[:=]\s*([A-Z0-9_-]+)\s*$', text)
    labelled = {m.upper() for m in labelled if m.upper() in marks}
    if labelled: candidates = labelled if not candidates else candidates & labelled
    project_numbers = set(re.findall(r'(?<!\d)\d{6}(?!\d)', path.stem))
    if project_numbers and project not in project_numbers: return None
    return next(iter(candidates)) if len(candidates) == 1 else None


def drawing_rows(paths, marks, project):
    import contextlib
    import pymupdf
    import mbs_controles_extra as extractor
    evidence, issues = [], []
    total_pages = 0
    for path in paths:
        with pymupdf.open(path) as document:
            if total_pages + len(document) > 500: raise ValueError('Maximum 500 selected drawing pages per audit')
            total_pages += len(document)
            for index, page in enumerate(document):
                text = page.get_text()
                merk = bind_merk(text, path, marks, project)
                location = {'path': str(path), 'page': index + 1, 'merk': merk}
                if not merk:
                    issues.append(dict(location, code='drawing_merk_unconfirmed')); continue
                # Existing local table extractor. Scans are explicitly incomplete; never a false pass.
                with contextlib.redirect_stdout(sys.stderr): rows, _ = extractor.lees_artikel_tabel(page)
                if not rows:
                    issues.append(dict(location, code='drawing_table_unreadable',
                                       note='Run local PDFCONTROL OCR for this page; no absence conclusion.')); continue
                for row in rows:
                    rect = row.get('rect')
                    evidence.append(dict(location, pos=row.get('pos', ''), description=row.get('omschrijving', ''),
                                         quantity=str(row.get('aantal', '')), bbox=list(rect) if rect is not None else None,
                                         method='pdfcontrol_text_table',
                                         ocr_uncertain=bool(row.get('ocr_diameter'))))
    return evidence, issues


def compare_drawings(records, evidence, catalogue):
    issues, comparisons = [], []
    by_mark = collections.defaultdict(list)
    for record in records: by_mark[record['merk']].append(record)
    pages = collections.defaultdict(list)
    for item in evidence: pages[(item['path'], item['page'], item['merk'])].append(item)
    covered = set()
    for (path, page, mark), rows in pages.items():
        covered.add(mark)
        expected = by_mark.get(mark, [])
        for record in expected:
            code = record['catalogue']['codes']
            matched = []
            if len(code) == 1:
                for row in rows:
                    result, codes = canonical(row['description'], catalogue)
                    if codes == code and result['status'] != 'catalogus_conflict': matched.append(row)
            location = {k: record[k] for k in ('path', 'sheet', 'row', 'merk', 'description')}
            comparison = dict(location, drawing=path, page=page, table_matches=matched,
                              status='name_evidence_only', physical_equivalence_verified=False)
            if not matched:
                comparison['status'] = 'not_found_in_extracted_table'
                issues.append(dict(location, code='drawing_article_not_found_in_table', drawing=path, page=page,
                                   note='Candidate discrepancy, not proof of missing physical article.'))
            elif len(matched) != 1 or sum(r['catalogue']['codes'] == code and r['path'] == record['path'] for r in expected) != 1:
                comparison['status'] = 'ambiguous_rows'
                issues.append(dict(location, code='drawing_rows_ambiguous', drawing=path, page=page))
            else:
                row = matched[0]
                quantity = re.fullmatch(r'\s*(\d+(?:[.,]\d+)?)\s*(st|stk|stuks|pcs|m|mm|cm)?\s*', row['quantity'], re.I)
                actual_unit = unit(record['fields'].get('eenheid'))
                if quantity and unit(quantity.group(2)) == actual_unit == 'st':
                    a, b = number(record['fields'].get('aantal')), number(quantity.group(1))
                    if a is not None and b is not None and a != b:
                        issues.append(dict(location, code='quantity_mismatch', expected=str(b), actual=str(a), drawing=path, page=page))
                # A length explicitly printed with a unit in the table can be compared; isolated page dimensions cannot.
                lengths = re.findall(r'(?i)\b(?:L|lengte)\s*[:=]\s*(\d+(?:[.,]\d+)?)\s*(mm|cm|m)\b', row['description'])
                if actual_unit == 'm':
                    comparison['length_evidence'] = [{'value': v, 'unit': u} for v, u in lengths]
                    comparison['status'] = 'length_needs_verified_drawing_dimension'
                    issues.append(dict(location, code='length_dimension_review', drawing=path, page=page,
                                       candidates=comparison['length_evidence'],
                                       note='Lengte column unit is not inferred; isolated dimensions are never applied automatically.'))
            comparisons.append(comparison)
        for row in rows:
            result, codes = canonical(row['description'], catalogue)
            if len(codes) != 1 or result['status'] == 'catalogus_conflict':
                issues.append(dict(row, code='drawing_article_unresolved', catalogue_status=result['status']))
            elif not any(r['catalogue']['codes'] == codes for r in expected):
                issues.append(dict(row, code='drawing_article_not_in_import', catalogue_codes=codes))
    for mark in sorted(set(by_mark) - covered):
        issues.append({'merk': mark, 'code': 'no_readable_drawing_table_for_merk'})
    return comparisons, issues


def report_html(report):
    e = lambda value: html.escape(str(value), quote=True)
    summary = report['summary']
    rows = ''.join('<tr><td>' + '</td><td>'.join(e(item.get(k, '')) for k in ('code', 'merk', 'description', 'path', 'sheet', 'row', 'drawing', 'page')) + '</td></tr>' for item in report['issues'])
    return ('<!doctype html><meta charset="utf-8"><title>Local import audit</title><style>'
            'body{font:14px system-ui;margin:24px;color:#202124;background:#fff}table{border-collapse:collapse;width:100%}'
            'td,th{border:1px solid #bbb;padding:6px;text-align:left;overflow-wrap:anywhere}th{background:#eee}'
            '</style><h1>Local import audit</h1><p>Project ' + e(report['project']) + ' / ' + e(report['factory']) + '</p>'
            '<p>Read-only audit. No production, drawing or server file was changed. No API inference.</p>'
            '<pre>' + e(json.dumps(summary, ensure_ascii=False, indent=2)) + '</pre><p>Names are evidence, not physical equivalence or transfer approval. See JSON for page/row evidence and limits.</p>'
            '<table><tr>' + ''.join('<th>'+e(k)+'</th>' for k in ('Issue', 'Merk', 'Article', 'Import', 'Sheet', 'Row', 'Drawing', 'Page')) + '</tr>' + rows + '</table>')


def run(request, output, package=None):
    start = time.monotonic()
    package = Path(package or Path(__file__).parent).resolve()
    if str(package) not in sys.path: sys.path.insert(0, str(package))
    import mbs_artikelcatalogus as reader
    if set(request) - {'project', 'factory', 'imports', 'drawings', 'catalogue'}: raise ValueError('Unknown audit fields')
    project, factory = request.get('project'), request.get('factory')
    if not isinstance(project, str) or not re.fullmatch(r'\d{6}', project): raise ValueError('Project must be six digits')
    if factory not in FACTORIES: raise ValueError('An explicitly confirmed factory is required')
    def selection(key, maximum, extensions, required=False):
        values = request.get(key, [])
        if not isinstance(values, list) or (required and not values) or len(values) > maximum or not all(isinstance(x, str) for x in values):
            raise ValueError('Invalid selection: ' + key)
        paths = [file_input(value, extensions) for value in values]
        if len(set(paths)) != len(paths): raise ValueError('Duplicate selected input: ' + key)
        return paths
    sources = selection('imports', 30, {'.xlsx', '.csv'}, True)
    drawings = selection('drawings', 50, {'.pdf'})
    for source in sources:
        labelled_projects = set(re.findall(r'(?<!\d)\d{6}(?!\d)', source.stem))
        if labelled_projects and project not in labelled_projects:
            raise ValueError('Import filename refers to a different project: ' + str(source))
    catalogue_path = file_input(request.get('catalogue') or package/'data_lokaal'/('catalogus_'+factory+'.json'), {'.json', '.xlsx'})
    catalogue = catalog(catalogue_path, factory, reader)
    manifest = [{'path': str(p), 'sha256': digest(p), 'role': role}
                for role, paths in [('import', sources), ('drawing', drawings), ('catalogue', [catalogue_path])] for p in paths]
    engine = {p.name: digest(p) for p in sorted(set([Path(__file__)] + list(package.glob('mbs_*.py')) + list(package.glob('*.json')))) if p.is_file()}
    cache_key = hashlib.sha256(json.dumps([VERSION, project, factory, manifest, engine], sort_keys=True).encode()).hexdigest()
    output = Path(output).resolve(); folder = output/cache_key
    result_path, html_path = folder/'report.json', folder/'report.html'
    if result_path.is_file() and html_path.is_file():
        report = json.loads(result_path.read_text(encoding='utf-8'))
        if report.get('cache_key') == cache_key and report.get('sources') == manifest:
            return compact(report, result_path, html_path, True, time.monotonic()-start)
    records, issues, comparisons = [], [], []
    for path in sources:
        found, warnings = imports(path, reader)
        records.extend(found); issues.extend(warnings)
    issues.extend(audit_rows(records, catalogue))
    for issue in catalogue.data.get('excluded_rows', []): issues.append(dict(issue, code='catalogue_row_excluded', path=str(catalogue_path)))
    evidence = []
    if drawings:
        evidence, warnings = drawing_rows(drawings, {r['merk'] for r in records}, project)
        issues.extend(warnings)
        comparisons, warnings = compare_drawings(records, evidence, catalogue)
        issues.extend(warnings)
    else: issues.append({'code': 'drawings_not_selected', 'note': 'Catalogue-only audit; drawing quantities/dimensions not checked.'})
    # Files changing during read must never yield a report that claims the original hash.
    for item in manifest:
        if digest(item['path']) != item['sha256']: raise ValueError('Source changed during audit; rerun after it stabilizes')
    counts = dict(collections.Counter(issue['code'] for issue in issues))
    report = {'schema_version': VERSION, 'cache_key': cache_key, 'project': project, 'factory': factory,
              'sources': manifest, 'engine': engine, 'records': records, 'drawing_evidence': evidence,
              'comparisons': comparisons, 'issues': issues,
              'summary': {'import_files': len(sources), 'import_article_rows': len(records), 'drawings': len(drawings),
                          'drawing_table_rows': len(evidence), 'issues': len(issues), 'by_code': counts},
              'status': 'review_required' if issues else 'name_checks_completed',
              'physical_equivalence_verified': False, 'production_files_changed': False, 'api_requests': 0,
              'limitations': ['Only explicitly selected factory and files; no automatic source or factory discovery.',
                             'PDF tables use the owned local extractor. Scans/unreadable tables require PDFCONTROL OCR and review.',
                             'Names do not establish physical equivalence. Dimensions outside the article table are not inferred.',
                             'No XLSX correction, production approval, network write or ISAH import is performed.']}
    folder.mkdir(parents=True, exist_ok=True)
    # Unique content-addressed destination; replace incomplete cache only, never a production source.
    import os, uuid
    for path, content in [(result_path, json.dumps(report, ensure_ascii=False, indent=2)), (html_path, report_html(report))]:
        temporary = path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp')
        try: temporary.write_text(content, encoding='utf-8'); os.replace(temporary, path)
        finally: temporary.unlink(missing_ok=True)
    return compact(report, result_path, html_path, False, time.monotonic()-start)


def compact(report, json_path, html_path, cached, elapsed):
    return {'tool': 'pdfcontrol_compare_import', 'project': report['project'], 'factory': report['factory'],
            'status': report['status'], 'cached': cached, 'elapsed_seconds': round(elapsed, 3),
            'summary': report['summary'], 'report_json': str(json_path), 'report_html': str(html_path),
            'examples': [{k: (str(v)[:180] if isinstance(v, str) else v) for k, v in issue.items()
                          if k in {'code', 'merk', 'description', 'row', 'page', 'actual', 'expected'}} for issue in sorted(report['issues'], key=lambda i: (i['code'] in {'catalogue_unit_unconfirmed', 'drawings_not_selected'}, i['code']))[:8]],
            'production_files_changed': False, 'api_requests': 0,
            'note': 'Read only specific report fragments if needed. Do not reread entire catalogues/imports or rerun unchanged audits. Not a production approval.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        request = json.loads(args.request.read_text(encoding='utf-8-sig'))
        print(json.dumps(run(request, args.output), ensure_ascii=True))
    except Exception as error:
        print(json.dumps({'status': 'error', 'error': str(error), 'production_files_changed': False}, ensure_ascii=True)); sys.exit(1)


if __name__ == '__main__': main()
