"""Conservative ink bounds for overlap candidates and explicit window parents."""
import ast
from pathlib import Path

HELPER = r'''
def _dimension_ink_box(page, word):
    """Use outlined numeric glyphs, falling back to the original metric box.

    Control-point hulls enclose curves: unsupported paths/transforms or ambiguous
    text never suppress a warning. No raster/OCR or installed font is required.
    """
    import xml.etree.ElementTree as ET
    original = fitz.Rect(word["rect"])
    try:
        glyphs = getattr(page, "_dimension_ink_glyphs", None)
        if glyphs is None:
            root = ET.fromstring(page.get_svg_image(text_as_path=True))
            paths = {x.get("id"): x.get("d") for x in root.iter()
                     if x.tag.endswith("}path") and x.get("id")}
            glyphs = []
            def visit(element, inherited=False):
                transformed = inherited or bool(element.get("transform"))
                if element.tag.endswith("}use") and not inherited:
                    text = element.get("data-text", "")
                    href = element.get("{http://www.w3.org/1999/xlink}href", "")
                    matrix = re.fullmatch(r"matrix\(([^)]+)\)", element.get("transform", ""))
                    path = paths.get(href.lstrip("#"), "")
                    # MuPDF emits absolute M/L/H/V/C/Q/Z outlines. Other SVG
                    # constructs are deliberately left to the existing check.
                    if text in "0123456789" and len(text) == 1 and matrix and path:
                        tokens = re.findall(r"[A-Za-z]|[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?", path)
                        points = []; current = [0., 0.]; command = None; i = 0
                        while i < len(tokens):
                            if tokens[i].isalpha():
                                command = tokens[i]; i += 1
                                if command == "Z": continue
                            count = {"M":2,"L":2,"H":1,"V":1,"C":6,"Q":4}.get(command)
                            if count is None or i + count > len(tokens): raise ValueError("Unsupported outline")
                            numbers = [float(t) for t in tokens[i:i+count]]; i += count
                            if command == "H": current[0] = numbers[0]; points.append(tuple(current))
                            elif command == "V": current[1] = numbers[0]; points.append(tuple(current))
                            else:
                                points.extend(zip(numbers[::2], numbers[1::2])); current = numbers[-2:]
                        values = [float(t) for t in re.split(r"[ ,]+", matrix.group(1).strip())]
                        if len(values) != 6 or not points: raise ValueError("Invalid glyph")
                        a,b,c,d,e,f = values
                        points = [(a*x+c*y+e,b*x+d*y+f) for x,y in points]
                        box = fitz.Rect(min(x for x,y in points),min(y for x,y in points),max(x for x,y in points),max(y for x,y in points))
                        glyphs.append((text, box))
                for child in element: visit(child, transformed)
            visit(root)
            page._dimension_ink_glyphs = glyphs
        tolerance = original + (-.05,-.05,.05,.05)
        matches = [(t,r) for t,r in glyphs if tolerance.contains(r)]
        if "".join(t for t,r in matches) != word["tekst"]: return original
        result = fitz.Rect(matches[0][1])
        for _,box in matches[1:]: result |= box
        return result
    except Exception:
        return original

'''

def patch(text):
    if '# Conservative numeric ink overlap.' in text: return text
    tree = ast.parse(text); lines = text.splitlines(keepends=True)
    nodes = {n.name:n for n in tree.body if isinstance(n,ast.FunctionDef)}
    nl = '\r\n' if '\r\n' in text else '\n'
    def replace_function(name, before, after, prefix=''):
        node=nodes[name];old=''.join(lines[node.lineno-1:node.end_lineno])
        before=before.replace('\n',nl);after=after.replace('\n',nl)
        if old.count(before)!=1: raise ValueError('Unsupported function '+name)
        return node,prefix.replace('\n',nl)+old.replace(before,after,1)
    changes=[]
    changes.append(replace_function('controle_overlap',
        '            if kleinste <= 0 or opp / kleinste < 0.25:\n                continue',
        '            if kleinste <= 0 or opp / kleinste < 0.25:\n                continue\n            # Conservative numeric ink overlap.\n            if a.get("hoek", 0) % 90 == 0 and b.get("hoek", 0) % 90 == 0:\n                ia, ib = _dimension_ink_box(page, a), _dimension_ink_box(page, b)\n                ink = ia & ib\n                minimum = min(ia.width * ia.height, ib.width * ib.height)\n                if ink.is_empty or minimum <= 0 or ink.width * ink.height / minimum < 0.25:\n                    continue',HELPER))
    changes.append(replace_function('kozijn_ouder',
        'KOZIJN_KOPPELING["ouder"]):',
        'KOZIJN_KOPPELING["ouder"]) or re.match(\n                      r"^\\s*kozijnmerk\\s*=\\s*[A-Z][A-Z0-9_.-]*(?![A-Z0-9_.-])",\n                      ander.get("omschrijving", ""), re.I):'))
    for node,new in sorted(changes,key=lambda item:item[0].lineno,reverse=True):lines[node.lineno-1:node.end_lineno]=[new]
    result=''.join(lines);ast.parse(result);return result

def apply(path):
    path=Path(path);before=path.read_bytes();after=patch(before.decode('utf-8')).encode('utf-8')
    if before!=after:path.write_bytes(after)
    return before!=after
