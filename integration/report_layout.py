"""Shared drawing-report layout: full vector sheets, compact captions outside source content."""
from pathlib import Path
import hashlib,itertools,json,math,os,re,uuid
import pymupdf as pdf

POLICY='full-sheet-compact-v1'
OVERLAY_OPACITY = .45
ARCHIVE_NOTICE = "ARCHIEFRESULTAAT | Controle afkomstig uit een eerdere uitvoering."
_LABEL_RASTER_SCALE = .75


def label_background(page):
    return page.get_pixmap(matrix=pdf.Matrix(_LABEL_RASTER_SCALE, _LABEL_RASTER_SCALE),
                           colorspace=pdf.csGRAY, alpha=False)


def _blank(bitmap, rect):
    box = (rect * pdf.Matrix(_LABEL_RASTER_SCALE, _LABEL_RASTER_SCALE)).irect & bitmap.irect
    samples = bitmap.samples_mv
    return not box.is_empty and all(
        min(samples[(y - bitmap.y) * bitmap.stride + box.x0 - bitmap.x:
                    (y - bitmap.y) * bitmap.stride + box.x1 - bitmap.x]) >= 247
        for y in range(box.y0, box.y1)
    )


def mark_comparison(page, area, region, number, color, placed, text_rects, markers,
                    draw_box, size, line_width, background=None):
    """Place a comparison key in blank space, or use ink with no opaque backing."""
    placed = [] if placed is None else placed
    width = pdf.get_text_length(number, fontname="helv", fontsize=size) + 2
    height = size * 1.4
    if draw_box:
        page.draw_rect(region, color=color, width=line_width, stroke_opacity=OVERLAY_OPACITY)
    if background is None:
        background = label_background(page)

    def box(x, y):
        return pdf.Rect(x, y, x + width, y + height)

    def grid(area):
        for y in range(math.ceil(area.y0), math.floor(area.y1 - height) + 1, math.ceil(height) + 2):
            for x in range(math.ceil(area.x0), math.floor(area.x1 - width) + 1, math.ceil(width) + 2):
                yield box(x, y)

    obstacles = (*text_rects, *placed, *markers, region)
    candidates = [box(x, y)
                  for gap in (2, 8, 16, 24, 40, 64, 100, 160)
                  for x in (region.x0, region.x1 - width, region.x1 + gap, region.x0 - width - gap)
                  for y in (region.y0 - height - gap, region.y1 + gap)]
    nearby = [rect for rect in candidates if area.contains(rect)]
    margin = pdf.Rect(area.x0, max(80, area.y0 - 2 * height - 6), area.x1, area.y0 - 2)
    allowed = page.rect
    label = next((rect for rect in itertools.chain(grid(margin), nearby, grid(area))
                  if allowed.contains(rect) and _blank(background, rect)
                  and not any(rect.intersects(o) for o in obstacles)), None)
    opacity = 1
    if label is None:
        opacity = OVERLAY_OPACITY
        label = min(nearby, default=None, key=lambda rect: (
            sum((rect & other).get_area() for other in placed),
            sum((rect & other).get_area() for other in text_rects),
            abs(rect.x0 - region.x0) + abs(rect.y0 - region.y0),
        ))
        if label is None:
            # Very small panels can use the nearest page-space position.
            x = min(max(region.x0, allowed.x0), allowed.x1 - width)
            y = min(max(region.y0, allowed.y0), allowed.y1 - height)
            label = box(x, y)

    placed.append(label)
    page.insert_text((label.x0 + 1, label.y1 - 1.3), number, fontsize=size, color=color,
                     fill_opacity=opacity)
    start = (label.tl + label.br) * .5
    end = pdf.Point(min(max(start.x, region.x0), region.x1), min(max(start.y, region.y0), region.y1))
    if abs(start.x - end.x) + abs(start.y - end.y) > 8:
        page.draw_line(start, end, color=color, width=line_width, stroke_opacity=OVERLAY_OPACITY)
    return label


def _fit(area, visible):
    scale=min(area.width/visible.width,area.height/visible.height)
    width,height=visible.width*scale,visible.height*scale
    return pdf.Rect(area.x0+(area.width-width)/2,area.y0+(area.height-height)/2,
                    area.x0+(area.width+width)/2,area.y0+(area.height+height)/2)

def _line(page,box,value,size=8,color=(.15,.18,.21)):
    value=' '.join(str(value).split());size=min(size,max(5.5,box.height/1.6))
    while size>5.5 and pdf.get_text_length(value,fontname='helv',fontsize=size)>box.width:size-=.25
    original=value
    while value and pdf.get_text_length(value,fontname='helv',fontsize=size)>box.width:value=value[:-1]
    if value!=original:
        value=value[:-3]+'...' if len(value)>3 else '...'
    page.insert_text((box.x0,box.y1-3),value,fontsize=size,fontname='helv',color=color)

def place_source(page,source,number,area):
    """Copy the entire visible source sheet; never crop a title block, renvooi or drawing view."""
    original=source[number];visible=original.rect;target=_fit(pdf.Rect(area),visible)
    with pdf.open() as copy:
        copy.insert_pdf(source,from_page=number,to_page=number)
        if hasattr(copy,'bake'):copy.bake()
        if copy[0].rotation:
            if not hasattr(copy[0],'remove_rotation'):raise RuntimeError('PDF runtime cannot preserve rotated source content')
            copy[0].remove_rotation()
        if copy[0].get_contents():page.show_pdf_page(target,copy,0,keep_proportion=True)
    return target

def _article_tables(page):
    """Find printed table boundaries in the table's own reading direction."""
    textpage = page.get_textpage()

    def headers(labels):
        return [q for label in labels for q in page.search_for(label, quads=True, textpage=textpage)]

    positions = headers(("Pos.", "Posnr", "Positie", "Position", "Pos"))
    descriptions = headers(("Omschrijving", "Benaming", "Description"))
    amounts = headers(("Aantal", "Aant.", "Hoeveelheid", "Quantity"))
    titles = headers(("Instortvoorzieningen", "Instortartikelen", "Renvooi"))
    if not descriptions or not amounts or not (positions or titles):
        return []
    segments = []
    for drawing in page.get_drawings():
        for item in drawing["items"]:
            if item[0] == "l":
                segments.append(item[1:])
            elif item[0] == "re":
                r = item[1]
                segments.extend(((r.tl, r.tr), (r.tr, r.br), (r.br, r.bl), (r.bl, r.tl)))
    native_words = page.get_text("words", textpage=textpage)
    tables = []
    for heading in descriptions:
        vector = heading.ur - heading.ul
        rotate = pdf.Matrix(-math.degrees(math.atan2(vector.y, vector.x)))
        desc = heading.rect * rotate
        middle = (desc.y0 + desc.y1) / 2
        lefts = [q.rect * rotate for q in positions]
        rights = [q.rect * rotate for q in amounts]
        left = max((r for r in lefts if r.x1 <= desc.x0 + 2 and r.y0 <= middle <= r.y1),
                   key=lambda r: r.x1, default=None)
        right = min((r for r in rights if r.x0 > desc.x1 and r.y0 <= middle <= r.y1),
                    key=lambda r: r.x0, default=None)
        if left is None:
            title_rows = [q.rect * rotate for q in titles]
            if any(t.x0 <= desc.x0+2 and desc.y0-max(20, desc.height*4) <= t.y1 <= desc.y0+1
                   for t in title_rows):
                left = desc
        if left is None or right is None:
            continue

        edges = {}
        for a, b in segments:
            a, b = a * rotate, b * rotate
            x0, x1 = sorted((a.x, b.x))
            y = (a.y+b.y)/2
            if (abs(a.y-b.y) < .3 and left.x0-30 < x0 <= left.x0+1
                    and right.x1-1 <= x1 <= right.x1+max(25, right.width*3)
                    and y >= desc.y0-max(15, desc.height*4)):
                edges.setdefault((round(x0, 1), round(x1, 1)), set()).add(round(y, 2))
        grids = [(x0, x1, sorted(levels)) for (x0, x1), levels in edges.items()
                 if len(levels) >= 3 and min(levels) < middle < max(levels)]
        if not grids:
            continue
        x0, x1, levels = max(grids, key=lambda grid: (len(grid[2]), -grid[1]))
        # Only use consecutive ruled rows after the header. A detached title block
        # can contain lines of the same width further down the sheet.
        start = next(i for i in range(len(levels)-1) if levels[i] <= middle < levels[i+1])
        body = [levels[start+1]]
        for y in levels[start+2:]:
            if y - body[-1] > max(30, desc.height * 4):
                break
            body.append(y)
        if len(body) < 2:
            continue
        bounds = pdf.Rect(x0, body[0], x1, body[-1])
        words = [(w[4], pdf.Rect(w[:4])*rotate) for w in native_words]
        tables.append((rotate, bounds, body, words))
    return tables


def _table_mark_rect(native, mark, tables, padding):
    """Constrain implicated table marks; leave drawing-view marks alone."""
    if mark.get("table") is False:
        return native
    position = mark.get("article", "").partition(" - ")[0].strip().casefold()
    for rotate, table, levels, words in tables:
        r = native * rotate
        if not (table.x0-3 <= r.x0 < table.x1 and table.intersects(r)):
            continue
        matching = [w for value, w in words if value.casefold() == position
                    and table.x0 <= w.x0 <= table.x0+max(25, table.width*.18)
                    and table.intersects(w) and r.y0-2 <= (w.y0+w.y1)/2 <= r.y1+2]
        if len(matching) == 1:
            y = (matching[0].y0 + matching[0].y1)/2
        else:
            # Without a unique position, accept only an already local row marker.
            overlap = [(lo, hi) for lo, hi in zip(levels, levels[1:])
                       if max(lo, r.y0+1) < min(hi, r.y1-1)]
            if len(overlap) != 1 or matching:
                return None
            y = (r.y0+r.y1)/2
        for lo, hi in zip(levels, levels[1:]):
            if lo <= y < hi:
                inset = pdf.Rect(table.x0+padding, lo+padding, table.x1-padding, hi-padding)
                clipped = r & inset
                return (clipped * ~rotate) if not clipped.is_empty else None
        return None
    return None if mark.get("table") else native


_NUMBER = rb"(-?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+))"
_RECT_PATH = re.compile(rb"(?m)^" + rb" +".join([_NUMBER] * 4) + rb" re$")


def clip_preframed_sheets(document):
    """Update trusted report overlays in the in-memory copy, never the source drawing."""
    for page in document:
        marks = [a for a in page.annots() or () if a.type[1] == "Square"
                 and a.info.get("title", "").startswith("Bevinding ")
                 and not a.colors.get("fill")]
        if not marks or not any(x[1] == "fzFrm0" and x[2] == 0 for x in page.get_xobjects()):
            continue
        tables = _article_tables(page)
        if not tables:
            continue
        refs = page.get_contents()
        replacements = {}
        for mark in marks:
            outer = mark.rect
            previous = pdf.Rect(outer.x0+1, outer.y0+1, outer.x1-1, outer.y1-1)
            article = mark.info.get("content", "").split("\n", 1)[0]
            clipped = _table_mark_rect(previous, {"article": article}, tables, padding=1.35)
            if clipped == previous:
                continue
            matches = []
            for xref in dict.fromkeys(refs):
                data = document.xref_stream(xref)
                paths = list(_RECT_PATH.finditer(data))
                if len(paths) != 1 or not re.search(rb"RG S\s+Q\s*$", data):
                    continue
                path = paths[0]
                x, y, w, h = (float(value) for value in path.groups())
                box = pdf.Rect(x, y, x+w, y+h) * page.transformation_matrix
                if all(abs(a-b) < .15 for a, b in zip(box, previous)):
                    matches.append((xref, data, path))
            if not matches:
                # Never replace an unrecognised stream inside an imported PDF.
                continue
            for xref, data, path in matches:
                if xref in replacements:
                    continue
                if clipped is None:
                    replacements[xref] = None
                    continue
                native = clipped * ~page.transformation_matrix
                command = f"{native.x0:.5f} {native.y0:.5f} {native.width:.5f} {native.height:.5f} re".encode("ascii")
                # Deduplicated streams can be shared by multiple pages or marks.
                # Replace page references while leaving the original stream intact.
                new_xref = document.get_new_xref()
                document.update_object(new_xref, "<<>>")
                document.update_stream(new_xref, data[:path.start()]+command+data[path.end():])
                replacements[xref] = new_xref
            if clipped is None:
                page.delete_annot(mark)
            else:
                mark.set_rect(clipped)
                mark.update()
        if replacements:
            refs = [replacements.get(xref, xref) for xref in refs]
            document.xref_set_key(page.xref, "Contents", "["+" ".join(f"{xref} 0 R" for xref in refs if xref)+"]")


def collect_finding(page,finding):
    """Only collect labels during analysis. Recognition never reads its own generated captions."""
    marks=getattr(page,'_mbs_report_marks',None)
    if marks is None:marks=[];page._mbs_report_marks=marks
    severity=str(getattr(finding,'ernst','CONTROLEREN'))
    color=getattr(finding,'markeerkleur',None) or ((.85,0,0) if severity=='FOUT' else (.88,.45,0))
    entry={'code':str(getattr(finding,'code','')),'severity':severity,'article':str(getattr(finding,'artikel','')),
           'message':str(getattr(finding,'tekst','') or getattr(finding,'korte_tekst','') or ''),'color':color}
    pair=getattr(finding,'codevergelijking',None)
    table_finding = entry['code'] in {
        'ARTIKEL_PROJECTEQUIVALENT_BEVESTIGD', 'ARTIKEL_NIET_IN_FABRIEKSCATALOGUS',
        'ARTIKEL_FABRIEKSCATALOGUS_ONBEVESTIGD',
    }
    bbox = getattr(finding, "rect", None)
    table_area = getattr(page, "_mbs_tabelgebied", None)
    if bbox is not None and table_area is not None:
        table_finding = table_finding or pdf.Rect(table_area).contains(pdf.Rect(bbox).tl)
    regions=[(bbox,color,True if table_finding else None)]
    if pair:regions=[(pair['tabel_rect'],(0,.3,.85),True)]+[(r,(.7,0,.55),False) for r in pair['tekening_rects']]
    for region,ink,table in regions:
        item=dict(entry,color=ink,bbox=list(pdf.Rect(region)) if region is not None else None,table=table)
        if item not in marks:marks.append(item)

def append_sheet(doc,source,number,title='',status='',notes=(),marks=(),source_page=None):
    original=source[number];visible=original.rect
    source_page=number+1 if source_page is None else source_page
    page=doc.new_page(width=visible.width,height=visible.height)
    if visible.width<100 or visible.height<100:raise ValueError('Source sheet is too small for a readable report')
    area=pdf.Rect(4,31,visible.width-4,visible.height-15)
    target=place_source(page,source,number,area)
    _line(page,pdf.Rect(9,2,visible.width-130,15),title or 'Bronpagina '+str(number+1),8.5)
    ink=(.75,0,0) if status in ('FOUT','NIET_OK','NIET OK') else (.65,.34,0) if status in ('CONTROLEREN','ONBEVESTIGD') else (.15,.4,.25) if status=='OK' else (.35,.38,.4)
    _line(page,pdf.Rect(visible.width-125,2,visible.width-9,15),status,7.2,ink)
    note=notes[0] if len(notes)==1 else str(len(notes))+' bevindingen; volledige toelichting in het bevindingenrapport.' if notes else 'Volledige oorspronkelijke tekening; artikelrenvooi en titel-/merkgegevens behouden.'
    _line(page,pdf.Rect(9,16,visible.width-9,29),note,7.5,(.4,.3,.15))
    _line(page,pdf.Rect(9,visible.height-12,visible.width-9,visible.height-1),'Bronpagina '+str(source_page)+' | Volledig bronblad | X/Y volgens tekening | Opmaak wijzigt geen technisch oordeel.',6.8)
    scale=target.width/visible.width
    tables=_article_tables(original) if any(m.get('bbox') is not None and m.get('table') is not False for m in marks) else []
    for i,mark in enumerate(marks,1):
        if mark.get('bbox') is None:continue
        native=pdf.Rect(mark['bbox'])
        if not all(math.isfinite(v) for v in native):raise ValueError('Invalid finding rectangle')
        native=_table_mark_rect(native,mark,tables,padding=1.35/scale)
        if native is None:continue
        rect=native*original.rotation_matrix
        rect=pdf.Rect(target.x0+rect.x0*scale,target.y0+rect.y0*scale,target.x0+rect.x1*scale,target.y0+rect.y1*scale)&target
        if rect.is_empty:raise ValueError('Finding rectangle lies outside the source sheet')
        page.draw_rect(rect,color=mark.get('color',(.9,.45,0)),width=.55,stroke_opacity=OVERLAY_OPACITY)
        annotation=page.add_rect_annot(rect);annotation.set_border(width=.3);annotation.set_colors(stroke=mark.get('color',(.9,.45,0)))
        annotation.set_opacity(OVERLAY_OPACITY)
        annotation.set_info(title='Bevinding '+str(i),subject=mark.get('code',''),content=mark.get('article','')+'\n'+mark.get('message',''));annotation.update()
    page._mbs_report_layout={'policy':POLICY,'source_rect':list(visible),'drawing_rect':list(target),'caption_rects':[[9,2,visible.width-9,29],[9,visible.height-12,visible.width-9,visible.height-1]]}
    return page

def frame_checked_document(document,page,title,status,findings,source_page=1):
    marks=list(getattr(page,'_mbs_report_marks',[]));notes=[str(getattr(f,'tekst','') or getattr(f,'korte_tekst','') or getattr(f,'code','')) for f in findings]
    result=pdf.open()
    try:append_sheet(result,document,0,title,status,notes,marks,source_page)
    except Exception:result.close();raise
    document.close();return result

def _legend(doc,findings):
    page=None;y=800
    for index,item in enumerate(findings,1):
        lines=[str(index)+'. '+str(item.get('article',''))+' | '+str(item.get('severity','CONTROLEREN')),
               str(item.get('message','')),'Bron: '+item['file']+' | pagina '+str(item['page'])]
        for value in lines:
            # textbox pagination retains complete supplied findings, never truncates a warning to fit a drawing.
            words=[]
            for token in value.split():
                while pdf.get_text_length(token,fontname='helv',fontsize=9)>530:
                    end=1
                    while end<len(token) and pdf.get_text_length(token[:end+1],fontname='helv',fontsize=9)<=530:end+=1
                    words.append(token[:end]);token=token[end:]
                if token:words.append(token)
            line=''
            for word in words+['']:
                trial=(line+' '+word).strip()
                if word and pdf.get_text_length(trial,fontname='helv',fontsize=9)<530:line=trial;continue
                if page is None or y>805:page=doc.new_page(width=595,height=842);_line(page,pdf.Rect(28,15,567,39),'Bevindingen - aangeleverde beoordeling',12);y=60
                page.insert_text((28,y),line,fontsize=9,fontname='helv');y+=13;line=word
        y+=9

def review_report(root,files,findings):
    """Create a new local review report, not a technical recontrol or approval."""
    root=Path(root).resolve()
    if not isinstance(files,list) or not 1<=len(files)<=100 or any(not isinstance(x,str) for x in files) or len(set(files))!=len(files):raise ValueError('Select 1-100 distinct PDF files')
    if not isinstance(findings,list) or len(findings)>1000:raise ValueError('Maximum 1000 findings')
    sources={}
    for name in files:
        if not isinstance(name,str) or Path(name).is_absolute():raise ValueError('Sources must be project-relative PDFs')
        path=(root/name).resolve()
        if not path.is_relative_to(root) or path.suffix.lower()!='.pdf' or not path.is_file():raise ValueError('Source outside project or missing PDF')
        sources[name]=(path,hashlib.sha256(path.read_bytes()).hexdigest())
    checked=[]
    for item in findings:
        if not isinstance(item,dict) or set(item)-{'file','page','article','message','severity','bbox'}:raise ValueError('Unknown finding fields')
        if item.get('file') not in sources or type(item.get('page')) is not int or item['page']<1:raise ValueError('Finding has no selected source page')
        if item.get('severity','CONTROLEREN') not in ('FOUT','CONTROLEREN','WAARSCHUWING','OK'):raise ValueError('Invalid severity')
        for key in ['article','message']:
            if not isinstance(item.get(key,''),str) or len(item.get(key,''))>12000:raise ValueError('Invalid finding text')
        if item.get('bbox') is not None and (not isinstance(item['bbox'],list) or len(item['bbox'])!=4 or any(type(v) not in (int,float) or not math.isfinite(v) for v in item['bbox'])):raise ValueError('Invalid bbox')
        checked.append(item)
    doc=pdf.open();pages=0
    try:
        for name,(path,_) in sources.items():
            with pdf.open(path) as source:
                if source.needs_pass:raise ValueError('Encrypted PDF needs an unlocked source')
                if any(x['page']>len(source) for x in checked if x['file']==name):raise ValueError('Finding page does not exist')
                for n in range(len(source)):
                    notes=[x for x in checked if x['file']==name and x['page']==n+1]
                    marks=[dict(x,color=(.85,0,0) if x.get('severity')=='FOUT' else (.88,.45,0)) for x in notes]
                    status='FOUT' if any(x.get('severity')=='FOUT' for x in notes) else 'CONTROLEREN' if any(x.get('severity','CONTROLEREN')!='OK' for x in notes) else 'OK' if notes else 'GEEN OORDEEL'
                    append_sheet(doc,source,n,name+' | pagina '+str(n+1),status,[x.get('message','') for x in notes],marks);pages+=1
                    if pages>1000:raise ValueError('Maximum 1000 source pages per report')
        _legend(doc,checked)
        for path,digest in sources.values():
            if hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise ValueError('Source changed; report not published')
        target=root/'Controle/PDFCONTROL/Beoordeling'/uuid.uuid4().hex;target.mkdir(parents=True,exist_ok=False)
        output=target/'Beoordeling.pdf';doc.set_metadata({'title':'Beoordeling - volledige bronbladen','subject':'Aangeleverde bevindingen; geen nieuwe motorcontrole of technische goedkeuring'});doc.save(output,garbage=4,deflate=True)
        manifest={'layout':POLICY,'report':str(output),'source_pages':pages,'findings':len(checked),'technical_approval':False,'new_engine_check':False,'sources_sha256':{name:digest for name,(_,digest) in sources.items()},'report_sha256':hashlib.sha256(output.read_bytes()).hexdigest()}
        with pdf.open(output) as verified:
            if len(verified)!=len(doc):raise ValueError('Report page verification failed')
            for sheet in verified:sheet.get_pixmap(matrix=pdf.Matrix(.05,.05),alpha=False)
        manifest.update(status='done',delivery_verified=True,source_count=len(sources),source_files=list(sources),
                        artifacts=[dict(kind='report',path=str(output),sha256=manifest['report_sha256'],bytes=output.stat().st_size,pages=len(doc))])
        (target/'MANIFEST.json').write_text(json.dumps(manifest,indent=2),encoding='utf8');return manifest
    finally:doc.close()
