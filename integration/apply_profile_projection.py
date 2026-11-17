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


def patch(text):
    if '# Guarded orthographic profile projections.' in text:
        nodes=[n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name.startswith('_profile_')]
        lines=text.splitlines(keepends=True);newline='\r\n' if '\r\n' in text else '\n'
        lines[nodes[0].lineno-1:nodes[-1].end_lineno]=[helpers().rstrip('\n').replace('\n',newline)+newline]
        result=''.join(lines);ast.parse(result);return result
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
    return change(text,'controle_maatketens',control,prefix=helpers())


def apply(package):
    path=Path(package)/'mbs_controles_extra.py'
    if not path.exists():return
    raw=path.read_bytes();new=patch(raw.decode('utf8')).encode('utf8')
    if raw!=new:path.write_bytes(new)
