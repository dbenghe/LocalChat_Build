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
    if 'layout.frame_checked_document' not in text:
        anchor='    maak_rapport(doc, base + ".pdf", results, bevindingen, is_wapening)'
        newline='\r\n' if '\r\n' in text else '\n'
        old='    from mbs_report_layout import frame_checked_document\n    doc = frame_checked_document(doc, page, base + ".pdf", bepaal_status(bevindingen, results), bevindingen, paginanr + 1)\n'
        body="""    import mbs_report_layout as layout
    if not hasattr(layout, '_table_mark_rect'):
        from importlib import reload
        reload(layout)
    doc = layout.frame_checked_document(doc, page, base + ".pdf", bepaal_status(bevindingen, results), bevindingen, paginanr + 1)
"""
        old=old.replace('\n',newline)
        body=body.replace('\n',newline)
        if old in text:
            text=text.replace(old,body,1)
        else:
            assert text.count(anchor)==1,'Unsupported report assembly'
            text=text.replace(anchor,body+anchor)
    text=text.replace("if not hasattr(layout, 'OVERLAY_OPACITY'):", "if not hasattr(layout, '_table_mark_rect'):")
    ast.parse(text);return text

def patch_comparison(text):
    text=_entry(text,'_drawing',"    from mbs_report_layout import place_source\n    if info is not None:\n        with fitz.open(stream=Path(info['pad']).read_bytes(), filetype='pdf') as full_source:\n            return place_source(page, full_source, info['pagina'], area)\n",'from mbs_report_layout import place_source')
    marker=next(n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name=='_mark')
    lines=text.splitlines(keepends=True)
    if 'from mbs_report_layout import mark_comparison' not in text:
        newline='\r\n' if '\r\n' in text else '\n'
        body="""def _mark(page, area, box, number, color, placed=None, text_rects=(), markers=(), draw_box=True, background=None):
    from mbs_report_layout import mark_comparison
    return mark_comparison(page, area, _marker_rect(area, box), number, color, placed, text_rects, markers,
                           draw_box, VERGELIJK_LABELMAAT, ANNOTATIE_LIJNDIKTE, background=background)
"""
        lines[marker.lineno-1:marker.end_lineno]=[body.replace('\n',newline)]
        text=''.join(lines)
        text=text.replace('    page = _page(doc, A3)'+newline+'    title_end = _wrapped(page, (24, 25),',
                          ('    import mbs_report_layout as layout'+newline+
                           '    if not hasattr(layout, "label_background"):'+newline+
                           '        from importlib import reload'+newline+
                           '        reload(layout)'+newline+
                           '    page = _page(doc, A3)'+newline+'    title_end = _wrapped(page, (24, 25),'),1)
        text=text.replace('        placed_labels=[]','        placed_labels=[]'+newline+'        background=layout.label_background(page) if sheet["rows"] else None',1)
        text=text.replace("draw_box=sheet.get('viewer_markers') is None)","draw_box=sheet.get('viewer_markers') is None, background=background)",1)
        text=text.replace('width=ANNOTATIE_LIJNDIKTE)'+newline+'        placed_labels=[]',
                          'width=ANNOTATIE_LIJNDIKTE, stroke_opacity=layout.OVERLAY_OPACITY)'+newline+'        placed_labels=[]',1)
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
