"""Narrow, idempotent window-mark aliases; no project-specific dimensions."""
import ast
from pathlib import Path

MARKER = '# Explicit window mark alias; never a substring or an unscoped suffix.'

def patch(text):
    node=next(n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name=='zoek_labels')
    lines=text.splitlines(keepends=True);old=''.join(lines[node.lineno-1:node.end_lineno])
    if MARKER in old:return text
    nl='\r\n' if '\r\n' in old else '\n'
    def replace(value,before,after):
        before=before.replace('\n',nl);after=after.replace('\n',nl)
        if value.count(before)!=1:raise ValueError('Unsupported zoek_labels anchor: '+before[:65])
        return value.replace(before,after,1)
    new=replace(old,'    treffers = []',r'''    # Explicit window mark alias; never a substring or an unscoped suffix.
    alias_pattern = r"^\s*kozijnmerk\s*=\s*([A-Z][A-Z0-9_.-]{0,29})(?![A-Z0-9_.-])"
    alias_match = re.match(alias_pattern, oms, re.I)
    kozijn_alias = alias_match.group(1) if alias_match else None
    if kozijn_alias and regels is not None:
        owners = [r for r in regels if (m := re.match(alias_pattern, r.get("omschrijving", ""), re.I))
                  and m.group(1).casefold() == kozijn_alias.casefold()]
        if len(owners) != 1:
            kozijn_alias = None
    treffers = []''')
    new=replace(new,'    positie_varianten = set(varianten)','    if kozijn_alias:\n        varianten.append(kozijn_alias)\n    positie_varianten = set(varianten)')
    new=replace(new,'            if kort is not None and v == kort:','''            if kozijn_alias and v == kozijn_alias:
                gevonden = [w["rect"] for w in tekstwoorden(page)
                            if w["tekst"].strip().casefold() == kozijn_alias.casefold()]
            elif kort is not None and v == kort:''')
    new=replace(new,'                observed = [_artikel_maten(line["tekst"]) for line in captions]','''                if kozijn_alias and v == kozijn_alias:
                    captions = [_samengestelde_caption(line, tekstregels(page)) for line in captions]
                observed = [_artikel_maten(line["tekst"]) for line in captions]''')
    lines[node.lineno-1:node.end_lineno]=[new]
    result=''.join(lines);ast.parse(result);return result

def apply(path):
    path=Path(path);before=path.read_bytes();after=patch(before.decode('utf-8')).encode('utf-8')
    if after!=before:path.write_bytes(after)
    return after!=before
