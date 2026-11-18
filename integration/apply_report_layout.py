"""Install the common report renderer with narrow, idempotent hooks; preserve unrelated engine code."""
import ast,shutil
from pathlib import Path

def _entry(text,name,body,marker):
    if marker in text:return text
    node=next((n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name==name),None)
    if node is None:raise ValueError('Unsupported report function: '+name)
    first=node.body[1] if isinstance(node.body[0],ast.Expr) and isinstance(node.body[0].value,ast.Constant) and isinstance(node.body[0].value.value,str) else node.body[0]
    lines=text.splitlines(keepends=True);newline='\r\n' if '\r\n' in text else '\n'
    lines.insert(first.lineno-1,body.replace('\n',newline));value=''.join(lines);ast.parse(value);return value

def patch_checker(text):
    text=_entry(text,'markeer_bevinding','    from mbs_report_layout import collect_finding\n    collect_finding(page, bevinding)\n    return\n','from mbs_report_layout import collect_finding')
    text=_entry(text,'add_xy_legend','    # The common report footer carries the legend outside the source drawing.\n    return None\n','# The common report footer carries the legend')
    if 'from mbs_report_layout import frame_checked_document' not in text:
        anchor='    maak_rapport(doc, base + ".pdf", results, bevindingen, is_wapening)';assert text.count(anchor)==1,'Unsupported report assembly'
        newline='\r\n' if '\r\n' in text else '\n'
        body='    from mbs_report_layout import frame_checked_document\n    doc = frame_checked_document(doc, page, base + ".pdf", bepaal_status(bevindingen, results), bevindingen, paginanr + 1)\n'
        text=text.replace(anchor,body.replace('\n',newline)+anchor)
    ast.parse(text);return text

def patch_comparison(text):
    text=_entry(text,'_drawing',"    from mbs_report_layout import place_source\n    if info is not None:\n        with fitz.open(stream=Path(info['pad']).read_bytes(), filetype='pdf') as full_source:\n            return place_source(page, full_source, info['pagina'], area)\n",'from mbs_report_layout import place_source')
    node=next(n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name=='comparison_page');lines=text.splitlines(keepends=True);old=''.join(lines[node.lineno-1:node.end_lineno]);new=old
    for a,b in [('A3[0]-48, 15)','A3[0]-48, 8.5)'),("_version(info), 11, color)","_version(info), 8, color)"),('area.width, 9)','area.width, 7.5)'),("verschillenrapport', 9)","verschillenrapport', 7.5)"),("number, 8)","number, 6.8)")]:new=new.replace(a,b)
    lines[node.lineno-1:node.end_lineno]=[new];result=''.join(lines);ast.parse(result);return result

def apply(package):
    package=Path(package);changes=[]
    for name,patch in [('mbs_pdf_checker_pdfcontrol.py',patch_checker),('mbs_vergelijk_pdf.py',patch_comparison)]:
        p=package/name
        if not p.exists():continue
        raw=p.read_bytes();text=raw.decode('utf8')
        # Minimal manifest fixtures intentionally contain no real engine implementation.
        if name=='mbs_pdf_checker_pdfcontrol.py' and 'def check_pdf(' not in text:continue
        changes.append((p,raw,patch(text).encode('utf8')))
    for p,raw,new in changes:
        if raw!=new:p.write_bytes(new)
    shutil.copy2(Path(__file__).with_name('report_layout.py'),package/'mbs_report_layout.py')
