"""Page-local bundle selection: unknown layouts cannot silently disappear."""
import ast
from pathlib import Path

SELECT = r'''def is_projecttekening(pdf_pad, tekst, doc=None):
    """Skip only positively identified information pages, never unknown layouts."""
    lines = [re.sub(r"\s+", " ", line).strip().casefold()
             for line in (tekst or "").splitlines() if line.strip()]
    text = "\n".join(lines)
    drawing_evidence = (bool(re.search(r"instortvoorzieningen|buigstaat|wapeningsstaat", text))
        or bool(re.search(r"bovenaanzicht|vooraanzicht|zijaanzicht|doorsnede", text)))
    if drawing_evidence:
        return True, "tekeninhoud op deze pagina"
    info_heading = r"(?:\d+[.) ]+)?(?:voorblad|projectgegevens|tekeningenlijst|tekeninglijst|drawing (?:list|index)|cover (?:page|sheet)|algemene (?:voorschriften|opmerkingen)|general (?:notes|specifications)|berekeningsrapport(?:\s+pb)?)(?:\s*[:\-].*)?"
    if any(re.fullmatch(info_heading, line, re.I) for line in lines[:12]):
        return False, "expliciet informatieblad zonder herkende tekeninhoud"
    name = achtervoegsel_op_blad(tekst)
    if name:
        return True, "bladnaam '%s'" % name
    if achtervoegsel_in_bestandsnaam(pdf_pad):
        return True, "achtervoegsel in de bestandsnaam"
    return True, "onbekende notatie behouden; inhoud en layout afzonderlijk controleren"
'''

PROJECT = r'''
    # Generic title labels are a bounded fallback AFTER explicit MBS labels.
    candidates = set()
    for kop in ("Projectnr.", "Projectnummer", "Werknr.", "Werknummer"):
        for label in cache.zoek(page, kop):
            kx, ky = midden(label)
            near = sorted((((midden(w)[0]-kx)**2+(midden(w)[1]-ky)**2)**.5, str(w[4]).strip())
                          for w in getallen)
            if near and near[0][0] < 90:
                # Competing values in the same title cell are not a choice.
                if len(near)>1 and near[1][0]-near[0][0] < 10 and near[1][1]!=near[0][1]:
                    return "", "dubbelzinnige projectwaarden bij titelkop"
                candidates.add(near[0][1])
    if len(candidates)==1:
        return next(iter(candidates)), "projectwaarde bij generieke titelkop (geometrisch)"
    if len(candidates)>1:
        return "", "tegenstrijdige projectwaarden bij titelkoppen"
'''

def _function_patch(text, name, transform):
    node=next(n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name==name)
    lines=text.splitlines(keepends=True);old=''.join(lines[node.lineno-1:node.end_lineno]);nl='\r\n' if '\r\n' in old else '\n'
    new=(transform(old.replace('\r\n','\n')).rstrip('\n') + ('\n' if old.endswith('\n') else '')).replace('\n',nl);lines[node.lineno-1:node.end_lineno]=[new]
    out=''.join(lines);ast.parse(out);return out

def patch_extra(text):
    text=_function_patch(text,'is_projecttekening',lambda old:SELECT.rstrip('\n'))
    def project(old):
        if '# Generic title labels are a bounded fallback' in old:return old
        anchor='    uniek = sorted({str(w[4]).strip() for w in getallen})'
        if old.count(anchor)!=1:raise ValueError('Unsupported project reader')
        return old.replace(anchor,PROJECT+'\n'+anchor)
    return _function_patch(text,'projectnummer_van_blad',project)

def patch_checker(text):
    def coverage(old):
        if '# Complete page coverage, unique identities, no first-page fallback.' in old:return old
        anchor='    overgeslagen = []'
        assert old.count(anchor)==1
        old=old.replace(anchor,'    # Complete page coverage, unique identities, no first-page fallback.\n'+anchor)
        anchor='        doc.close()'
        assert old.count(anchor)==1
        old=old.replace(anchor,'''        # Duplicate sheet names must not overwrite a previous page's report.
        from collections import Counter
        counts = Counter(namen[i] for i in bladen)
        for i in bladen:
            if namen[i] is not None and counts[namen[i]] > 1:
                namen[i] = f"{namen[i]}_p{i+1:03d}"
        doc.close()''')
        anchor='        aantal, namen, bladen = 1, [None], [0]'
        assert old.count(anchor)==1
        old=old.replace(anchor,'        if "doc" in locals():\n            doc.close()\n        raise RuntimeError("Bundelinventarisatie mislukt; geen gedeeltelijk succes gemeld") from fout')
        anchor='    uit = []'
        assert old.count(anchor)==1
        old=old.replace(anchor,'''    print(f"  Paginadekking: {aantal} gelezen; {len(bladen)} te controleren; "
          f"{len(overgeslagen)} expliciet overgeslagen.")
    for number, reason in overgeslagen:
        print(f"  Overgeslagen pagina {number}: {reason}")
    uit = []''')
        old=old.replace('    return uit','''    if len(uit) != len(bladen) or len({os.path.normcase(os.path.abspath(row[0])) for row in uit}) != len(uit):
        raise RuntimeError("Onvolledige of overschreven pagina-uitkomsten; controle niet voltooid")
    print(f"  Paginadekking voltooid: {len(uit)}/{len(bladen)} afzonderlijke uitkomsten.")
    return uit''')
        return old
    return _function_patch(text,'check_pdf_alle',coverage)

def apply(package):
    package=Path(package)
    for name,patch in [('mbs_controles_extra.py',patch_extra),('mbs_pdf_checker_pdfcontrol.py',patch_checker)]:
        p=package/name
        if not p.exists():continue
        before=p.read_bytes();after=patch(before.decode('utf8')).encode('utf8')
        if before!=after:p.write_bytes(after)
