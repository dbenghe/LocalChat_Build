"""Shared drawing-report layout: full vector sheets, compact captions outside source content."""
from pathlib import Path
import hashlib,itertools,json,math,os,uuid
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

def collect_finding(page,finding):
    """Only collect labels during analysis. Recognition never reads its own generated captions."""
    marks=getattr(page,'_mbs_report_marks',None)
    if marks is None:marks=[];page._mbs_report_marks=marks
    severity=str(getattr(finding,'ernst','CONTROLEREN'))
    color=getattr(finding,'markeerkleur',None) or ((.85,0,0) if severity=='FOUT' else (.88,.45,0))
    entry={'code':str(getattr(finding,'code','')),'severity':severity,'article':str(getattr(finding,'artikel','')),
           'message':str(getattr(finding,'tekst','') or getattr(finding,'korte_tekst','') or ''),'color':color}
    pair=getattr(finding,'codevergelijking',None)
    regions=[(getattr(finding,'rect',None),color)]
    if pair:regions=[(pair['tabel_rect'],(0,.3,.85))]+[(r,(.7,0,.55)) for r in pair['tekening_rects']]
    for region,ink in regions:
        item=dict(entry,color=ink,bbox=list(pdf.Rect(region)) if region is not None else None)
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
    for i,mark in enumerate(marks,1):
        if mark.get('bbox') is None:continue
        native=pdf.Rect(mark['bbox'])
        if not all(math.isfinite(v) for v in native):raise ValueError('Invalid finding rectangle')
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
