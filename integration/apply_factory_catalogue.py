"""Idempotent hooks: report missing factory articles independently of geometry/spelling."""
import ast,hashlib,json,shutil
from pathlib import Path

def patch(text):
    old="""        from mbs_factory_catalogue import article_finding
        catalogue_finding = article_finding(s['regel'], catalogus)
        if catalogue_finding:
            bevindingen.append(Bevinding(catalogue_finding['code'], catalogue_finding['ernst'], s['kort'],
                catalogue_finding['tekst'], rect=s['regel'].get('rect'), bron='A'))
"""
    text=text.replace(old,'')
    if '@_factory_catalogue_control' not in text:
        anchor='def controle_maatketens(page, regels, tabelgebied, woorden, catalogus=None):'
        assert text.count(anchor)==1,'Unsupported geometric entry point'
        text=text.replace(anchor,'from mbs_factory_catalogue import catalogue_control as _factory_catalogue_control\n\n@_factory_catalogue_control\n'+anchor)
    if 'from mbs_factory_catalogue import UNAVAILABLE_TEXT' not in text:
        anchor='        gevonden, overzicht = controle_maatketens(page, regels, tabelgebied, woorden, catalogus=catalogus)'
        assert text.count(anchor)==1,'Unsupported factory catalogue call'
        text=text.replace(anchor,'''        if catalogus is None:
            from mbs_factory_catalogue import UNAVAILABLE_TEXT
            bevindingen.append(Bevinding('FABRIEKSCATALOGUS_NIET_GECONTROLEERD', 'CONTROLEREN', '', UNAVAILABLE_TEXT, bron='A'))
'''+anchor)
    text = text.replace(
        'gevonden, overzicht = controle_maatketens(page, regels, tabelgebied, woorden, catalogus=catalogus)',
        'gevonden, overzicht = controle_maatketens(page, regels, tabelgebied, woorden, catalogus=catalogus, pdf_path=pdf_pad)')
    ast.parse(text);return text

def patch_lookup(text):
    if 'from mbs_catalogue_notation import recognize' not in text:
        anchor="        rows = self.by_name.get(normaliseer(recognition['basisnaam']), [])"
        assert text.count(anchor)==1,'Unsupported factory catalogue lookup'
        extra="""
        if not rows:
            from mbs_catalogue_notation import recognize
            notation=recognize(description,self.by_name)
            if notation:
                rows=[row for name in notation['keys'] for row in self.by_name[name]]
                recognition.update(naam_herkend=True,basisnaam=rows[0]['description'],
                    tekeninggegevens=notation['details'],notatie_regel=notation['rule'])
"""
        text=text.replace(anchor,anchor+extra)
    ast.parse(text);return text

def apply(package):
    package=Path(package);path=package/'mbs_controles_extra.py'
    if not path.is_file():return
    before=path.read_text(encoding='utf-8');after=patch(before)
    if before!=after:path.write_text(after,encoding='utf-8')
    shutil.copy2(Path(__file__).with_name('factory_catalogue_findings.py'),package/'mbs_factory_catalogue.py')
    lookup=package/'mbs_artikelcatalogus.py'
    if lookup.is_file():lookup.write_text(patch_lookup(lookup.read_text(encoding='utf8')),encoding='utf8')
    shutil.copy2(Path(__file__).with_name('catalogue_notation.py'),package/'mbs_catalogue_notation.py')
