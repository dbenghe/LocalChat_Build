"""Narrow shared-engine hook for read-only companion-model recognition evidence."""
import ast
from pathlib import Path
HELPER='''
def _reference_component_evidence(page, rows, table, engine):
    try:
        from mbs_ifc_reference import evaluate
        return evaluate(page, rows, table, engine)
    except Exception as error:
        # Invalid/missing references never approve an article or remove its finding.
        stil.meld('IFC companion reference rejected', error)
        return {}

'''
def patch(text):
    if '# Read-only companion-model identity evidence.' in text:return text
    node=next(n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name=='controle_maatketens');lines=text.splitlines(keepends=True);old=''.join(lines[node.lineno-1:node.end_lineno]);newline='\r\n' if '\r\n' in old else '\n';old=old.replace('\r\n','\n')
    anchor="    profile_review = profile_result['review']";assert old.count(anchor)==1
    old=old.replace(anchor,anchor+'''
    # Read-only companion-model identity evidence.
    reference_engine = SimpleNamespace(geometry=_profile_geometry, rectangles=_profile_rectangles,
        wall_scale=_profile_wall_scale, words=tekstwoorden, joined=_profile_joined,
        ordinates=_profile_ordinates, one_axis=maar_een_maat_nodig, adapter=profile_engine)
    reference_result = _reference_component_evidence(page, regels, tabelgebied, reference_engine)
''')
    anchor="profile_pos in profile_proof";assert old.count(anchor)==1;old=old.replace(anchor,"profile_pos in reference_result or "+anchor,1)
    anchor="            profiles = profile_proof.get(s['regel'].get('pos'))";assert old.count(anchor)==1
    old=old.replace(anchor,'''            reference = reference_result.get(s['regel'].get('pos'))
            if reference:
                s['primaire_ketens'].update(tuple(part['rect']) for part in reference['parts'])
                s['note'] = reference['note']
                if reference['approved']:
                    s['verdict'] = 'OK'
                    s['assen'] = {'referentie|H': 'X in actuele tekening (ordinaten bij geverifieerde ankerprojecties)',
                                  'referentie|regel': 'N.V.T. tweede as: bestaande ankerregel'}
                else:
                    s['verdict'] = 'CONTROLEREN'
                    s['maat_onduidelijk'] = True
                    bevindingen.append(Bevinding('ARTIKEL_HERKEND_PLAATSMAAT_ONBEVESTIGD', 'CONTROLEREN',
                        s['kort'], reference['note'], rect=fitz.Rect(reference['parts'][0]['rect']), bron='A'))
                continue

'''+anchor)
    new=HELPER+old;lines[node.lineno-1:node.end_lineno]=[new.replace('\n',newline)];result=''.join(lines);ast.parse(result);return result

def patch_checker(text):
    newline='\r\n' if '\r\n' in text else '\n'
    if '"ARTIKEL_HERKEND_PLAATSMAAT_ONBEVESTIGD":' not in text:
        anchor='    "ARTIKEL_NIET_OP_TEKENING": "NIET AUTOMATISCH HERKEND",';assert text.count(anchor)==1
        text=text.replace(anchor,anchor+newline+'    "ARTIKEL_HERKEND_PLAATSMAAT_ONBEVESTIGD": "ARTIKEL HERKEND: PLAATSMAAT ONBEVESTIGD",',1)
    if '# Capture original content before checker annotations.' not in text:
        node=next(n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name=='check_pdf')
        lines=text.splitlines(keepends=True);old=''.join(lines[node.lineno-1:node.end_lineno]).replace('\r\n','\n');anchor='    page = doc[0]';assert old.count(anchor)==1
        block="""
    # Capture original content before checker annotations.
    try:
        from mbs_ifc_reference import capture_source
        capture_source(page)
    except Exception as reference_error:
        stil.meld('IFC source provenance capture', reference_error)
"""
        old=old.replace(anchor,anchor+block,1);lines[node.lineno-1:node.end_lineno]=[old.replace('\n',newline)];text=''.join(lines)
    ast.parse(text);return text


def apply(package):
    package=Path(package)
    for name,transform in [('mbs_controles_extra.py',patch),('mbs_pdf_checker_pdfcontrol.py',patch_checker)]:
        path=package/name
        if not path.is_file():continue
        raw=path.read_bytes();new=transform(raw.decode('utf8')).encode('utf8')
        if new!=raw:path.write_bytes(new)
