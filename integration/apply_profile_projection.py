"""Embed guarded, project-independent orthographic profile matching in either engine."""
import ast
from pathlib import Path


def helpers():
    tree=ast.parse(Path(__file__).with_name('profile_projection.py').read_text(encoding='utf8'))
    names={n.name for n in tree.body if isinstance(n,ast.FunctionDef)}
    class Prefix(ast.NodeTransformer):
        def visit_FunctionDef(self,node):
            node.name='_profile_'+node.name if node.name in names else node.name
            return self.generic_visit(node)
        def visit_Name(self,node):
            if node.id in names:node.id='_profile_'+node.id
            return node
    tree.body=[Prefix().visit(n) for n in tree.body if isinstance(n,ast.FunctionDef)]
    ast.fix_missing_locations(tree)
    return ast.unparse(tree)+'\n\n'


def upgrade(text):
    newline='\r\n' if '\r\n' in text else '\n'
    old='    profile_proof = _profile_confirmed(profile_engine, page, regels)'
    new="    profile_result = _profile_evaluate(profile_engine, page, regels)\n    profile_proof = profile_result['approved']\n    profile_review = profile_result['review']"
    if old in text:text=text.replace(old,new.replace('\n',newline),1)
    if "review = profile_review.get" not in text:
        anchor='            onduidelijk = beschrijvende_labels('
        assert text.count(anchor)==1
        block="""            review = profile_review.get(s['regel'].get('pos'))
            if review:
                s['verdict'] = 'CONTROLEREN'
                s['maat_onduidelijk'] = True
                s['note'] = review['note']
                s['primaire_ketens'].update(tuple(box) for box in review['boxes'])
                bevindingen.append(Bevinding(review['code'], 'CONTROLEREN', s['kort'],
                    review['note'], rect=fitz.Rect(review['rect']), bron='A'))
                continue

"""
        text=text.replace(anchor,block.replace('\n',newline)+anchor,1)
    if "profile_status['geometrisch_herkend']" not in text:
        anchor="    profile_review = profile_result['review']"
        block="""
    for profile_status in status:
        profile_pos = profile_status['regel'].get('pos')
        profile_status['geometrisch_herkend'] = (profile_pos in profile_proof
            or profile_review.get(profile_pos, {}).get('code') == 'PROFIEL_PLAATSMAAT_ONBEVESTIGD')"""
        assert text.count(anchor)==1;text=text.replace(anchor,anchor+block.replace('\n',newline),1)
    anchor='            s["note"] = _niet_gevonden_uitleg(status, s, kort=True)'
    assert text.count(anchor)==1
    if anchor+newline+'            s["maat_onduidelijk"] = True' not in text:
        text=text.replace(anchor,anchor+newline+'            s["maat_onduidelijk"] = True',1)
    # Report unknown identity as unknown axes; count independently recognized geometry.
    node=next(n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name=='_niet_gevonden_uitleg')
    lines=text.splitlines(keepends=True);old=''.join(lines[node.lineno-1:node.end_lineno]);new=old
    new=new.replace('if not s["labels"]]', 'if not (s["labels"] or s.get("geometrisch_herkend"))]')
    new=new.replace('if s["labels"]]', 'if s["labels"] or s.get("geometrisch_herkend")]')
    lines[node.lineno-1:node.end_lineno]=[new];text=''.join(lines)
    ast.parse(text);return text


def patch_checker(text):
    newline='\r\n' if '\r\n' in text else '\n'
    anchor='    "ARTIKEL_NIET_OP_TEKENING": "NIET AUTOMATISCH HERKEND",'
    for code,title in [('PROFIEL_PLAATSMAAT_ONBEVESTIGD','PROFIEL: PLAATSMAAT ONBEVESTIGD'),('PROFIEL_POSITIECODE_ONBEVESTIGD','PROFIEL: POSITIECODE ONBEVESTIGD')]:
        if '"'+code+'":' in text:continue
        assert text.count(anchor)==1
        text=text.replace(anchor,anchor+newline+'    "'+code+'": "'+title+'",',1)
    ast.parse(text);return text


def patch(text):
    if '# Guarded orthographic profile projections.' in text:
        nodes=[n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name.startswith('_profile_')]
        lines=text.splitlines(keepends=True);newline='\r\n' if '\r\n' in text else '\n'
        lines[nodes[0].lineno-1:nodes[-1].end_lineno]=[helpers().rstrip('\n').replace('\n',newline)+newline]
        result=''.join(lines);ast.parse(result);return upgrade(result)
    def change(text,name,fn,prefix=''):
        node=next(n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name==name)
        lines=text.splitlines(keepends=True);old=''.join(lines[node.lineno-1:node.end_lineno])
        newline='\r\n' if '\r\n' in old else '\n'
        new=prefix+fn(old.replace('\r\n','\n')).rstrip('\n')+('\n' if old.endswith('\n') else '')
        lines[node.lineno-1:node.end_lineno]=[new.replace('\n',newline)]
        result=''.join(lines);ast.parse(result);return result
    def control(old):
        anchor='    bevindingen = []\n    for s in status:\n'
        assert old.count(anchor)==1
        old=old.replace(anchor,'''    # Guarded orthographic profile projections.
    from types import SimpleNamespace
    profile_engine = SimpleNamespace(_artikel_maten=_artikel_maten, lees_artikel_tabel=lees_artikel_tabel,
        tekstwoorden=tekstwoorden, _getal_op_lijn=_getal_op_lijn, _sparing_ordinaten=_sparing_ordinaten)
    profile_proof = _profile_confirmed(profile_engine, page, regels)
'''+anchor)
        anchor='            onduidelijk = beschrijvende_labels('
        assert old.count(anchor)==1
        old=old.replace(anchor,'''            profiles = profile_proof.get(s['regel'].get('pos'))
            if profiles:
                s['verdict'] = 'OK'
                s['assen'] = {'profiel|H': 'X in vooraanzicht (contour met eigen maatketen)',
                              'profiel|V': 'Z in vooraanzicht (bovenzijde aan gemaatvoerde wandcontour)'}
                s['primaire_ketens'].update(tuple(p['box']) for p in profiles)
                s['note'] = ('Rechthoekprofiel geometrisch gekoppeld in voor- en bovenaanzicht; '
                    'afmetingen afgeleid van maatlijn met twee eindstrepen en onafhankelijk '
                    'gekalibreerde wandcontour; X-positie gecontroleerd. '
                    f"Alle {len(profiles)} exemplaren gekoppeld, unieke artikelregel. "
                    + '; '.join(f"{p['height']:g} x {p['depth']:g} mm, contourlengte circa {p['length']:.1f} mm" for p in profiles))
                continue

'''+anchor)
        return old
    return upgrade(change(text,'controle_maatketens',control,prefix=helpers()))


def apply(package):
    package=Path(package)
    for name,transform in [('mbs_controles_extra.py',patch),('mbs_pdf_checker_pdfcontrol.py',patch_checker)]:
        path=package/name
        if not path.exists():continue
        raw=path.read_bytes();new=transform(raw.decode('utf8')).encode('utf8')
        if new!=raw:path.write_bytes(new)
