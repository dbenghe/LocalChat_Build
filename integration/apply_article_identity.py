"""Exact compound identities, qualified position codes and honest size conflicts."""
import ast
from pathlib import Path

HELPERS=r'''
def _article_identity_variants(description, row, rows):
    if rows is None:
        return []
    aliases = []
    # A placement suffix is metadata; finishes and product specifications stay.
    base = re.sub(r"\s+(?:malzijde|stortzijde|lijstzijde|rondom)\s*$", "", description or "", flags=re.I).strip()
    if base != (description or "").strip() and len(base) >= 5:
        if sum(re.sub(r"\s+(?:malzijde|stortzijde|lijstzijde|rondom)\s*$", "", r.get("omschrijving", ""), flags=re.I).strip().casefold()==base.casefold() for r in rows)==1:
            aliases.append(base)
    # Declared lifting-anchor type: decimal load notation is hyphenated in CAD.
    match = re.search(r"\bhijsanker\s+HS\s*(\d+)[,.](\d+)-(\d+)\b", description or "", re.I)
    if match:
        alias = "HS" + "-".join(match.groups())
        owners = [r for r in rows if (m := re.search(r"\bhijsanker\s+HS\s*(\d+)[,.](\d+)-(\d+)\b", r.get("omschrijving", ""), re.I)) and m.groups()==match.groups()]
        if len(owners)==1:
            aliases.append(alias)
    return aliases

def _window_dimension_conflicts(page, row, table, rows=None):
    mark = _kozijnmerk(row.get("omschrijving", ""))
    expected = _artikel_maten(row.get("omschrijving", ""))
    if not mark or not expected:
        return []
    if rows is not None and sum((_kozijnmerk(r.get("omschrijving", "")) or "").casefold()==mark.casefold() for r in rows)!=1:
        return []
    lines = tekstregels(page); result = []
    for word in cache.woorden(page):
        if str(word[4]).casefold()!=mark.casefold():continue
        rect=fitz.Rect(word[:4])
        if (table is not None and rect.intersects(table)) or _in_revisieregel(page,rect):continue
        for line in lines:
            if not line['rect'].intersects(rect):continue
            caption=_samengestelde_caption(line,lines)
            actual=_artikel_maten(caption['tekst'])
            if actual and actual!=expected and actual not in [x[0] for x in result]:
                result.append((actual,rect))
    return result

def _shape_description(shape):
    return "; ".join("x".join(f"{n:g}" for n in values) if kind=='box' else f"diameter {values:g}" for kind,values in shape)
'''

def change(text,name,fn,prefix=''):
    n=next(n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name==name);lines=text.splitlines(keepends=True);old=''.join(lines[n.lineno-1:n.end_lineno]);nl='\r\n' if '\r\n' in old else '\n'
    new=prefix+fn(old.replace('\r\n','\n')).rstrip('\n')+ ('\n' if old.endswith('\n') else '');lines[n.lineno-1:n.end_lineno]=[new.replace('\n',nl)];out=''.join(lines);ast.parse(out);return out

def patch(text):
    if '# Qualified and compound article identities.' in text:return text
    def pos(old):
        anchor='    return bool(POS_CODE.match(v))';assert old.count(anchor)==1
        return old.replace(anchor,'''    # Qualified and compound article identities.
    qualified = re.fullmatch(r"(.+?)\\s*\\(\\s*([A-Za-z]{1,4})\\s*\\)", v)
    if qualified:
        base = qualified.group(1).strip()
        return bool(POS_CODE.fullmatch(base)) and base.casefold() not in _GEEN_POS_CODE and qualified.group(2).casefold() not in {"mm","cm","kg","stk","ev","tv","gv","rvs"}
'''+anchor)
    text=change(text,'is_pos_code',pos)
    def mark(old):
        start=old.index('    match = ')
        return old[:start]+'''    match = re.search(r"\\bkozijnmerk\\s*[=:]\\s*([A-Za-z][A-Za-z0-9_.-]{0,29}(?:/[A-Za-z][A-Za-z0-9_.-]{0,29}){0,3})(?![A-Za-z0-9_./-])", omschrijving or "", re.I)
    return match.group(1) if match else None
'''
    extra_helpers = ''
    if any(isinstance(n,ast.FunctionDef) and n.name=='_kozijnmerk' for n in ast.parse(text).body):
        text=change(text,'_kozijnmerk',mark)
    else:
        extra_helpers = mark('def _kozijnmerk(omschrijving):\n    match = None\n') + '\n\n'
    def labels(old):
        old=old.replace('([A-Z][A-Z0-9_.-]{0,29})(?![A-Z0-9_.-])','([A-Z][A-Z0-9_.-]{0,29}(?:/[A-Z][A-Z0-9_.-]{0,29}){0,3})(?![A-Z0-9_./-])')
        anchor='    positie_varianten = set(varianten)';assert old.count(anchor)==1
        old=old.replace(anchor,'    identity_aliases = _article_identity_variants(oms, regel, regels)\n    varianten.extend(identity_aliases)\n'+anchor)
        anchor='\n            if kozijn_alias and v == kozijn_alias:';assert old.count(anchor)==1
        old=old.replace(anchor,'''
            if re.fullmatch(r".+?\\s*\\(\\s*[A-Za-z]{1,4}\\s*\\)", pos or "") and re.sub(r"\\s+", "", v).casefold()==re.sub(r"\\s+", "", pos).casefold():
                identity = re.sub(r"\\s+", "", pos).casefold()
                gevonden = [w["rect"] for w in tekstwoorden(page)
                            if re.sub(r"\\s+", "", w["tekst"]).casefold()==identity]
            elif v in identity_aliases:
                pattern = r"(?<![A-Za-z0-9_])" + re.escape(v).replace(r"\\ ",r"\\s+") + r"(?![A-Za-z0-9_])"
                valid = [w["rect"] for w in tekstwoorden(page) if re.search(pattern,w["tekst"],re.I)]
                gevonden = [r for r in cache.zoek(page,v) if any(rect.contains(r) for rect in valid)]
            elif kozijn_alias and v == kozijn_alias:''')
        return old
    text=change(text,'zoek_labels',labels)
    def control(old):
        anchor='    bevindingen = []\n    for s in status:\n';assert old.count(anchor)==1
        return old.replace(anchor,anchor+'''        conflicts = _window_dimension_conflicts(page, s["regel"], tabelgebied, regels)
        if conflicts:
            expected = _shape_description(_artikel_maten(s["regel"].get("omschrijving", "")))
            actual = "; ".join(_shape_description(shape) for shape,rect in conflicts)
            s["verdict"] = "NIET OK"
            s["note"] = f"Zelfde kozijnmerk, andere maten: renvooi {expected}; tekening {actual}."
            bevindingen.append(Bevinding("KOZIJNMAAT_VERSCHIL", "FOUT", s["kort"], s["note"], rect=conflicts[0][1], bron="A"))
            continue
''')
    text=change(text,'_directe_sparingmaten',lambda old:old.replace("r'\\bsparing\\b|kozijnsparing'","r'\\bsparing\\b|kozijnsparing|kozijnmerk'"))
    return change(text,'controle_maatketens',control,prefix=extra_helpers+HELPERS+'\n')

def patch_checker(text):
    old='    "ARTIKEL_NIET_OP_TEKENING": "NIET OP TEKENING",'
    new='    "ARTIKEL_NIET_OP_TEKENING": "NIET AUTOMATISCH HERKEND",'
    if old in text:
        if text.count(old)!=1:raise ValueError('Ambiguous finding heading')
        text=text.replace(old,new,1)
    elif new not in text:raise ValueError('Unsupported finding heading')
    if '"KOZIJNMAAT_VERSCHIL":' not in text:
        nl='\r\n' if '\r\n' in text else '\n'
        text=text.replace(new,new+nl+'    "KOZIJNMAAT_VERSCHIL": "KOZIJNMAAT VERSCHILT",',1)
    ast.parse(text);return text

def apply(package):
    package=Path(package)
    for name,transform in [('mbs_controles_extra.py',patch),('mbs_pdf_checker_pdfcontrol.py',patch_checker)]:
        path=package/name
        if not path.exists():continue
        raw=path.read_bytes();new=transform(raw.decode('utf8')).encode('utf8')
        if new!=raw:path.write_bytes(new)
