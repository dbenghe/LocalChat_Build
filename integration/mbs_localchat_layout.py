"""Conservative MBS Specials title-block support for the owned LocalChat engine."""
import re


def project(text):
    ids=set(re.findall(r'MBS\s+Specials\s+nr[.: ]*\s*(\d{6})(?!\d)',text or '',re.I))
    return next(iter(ids)) if len(ids)==1 else ''


def classify(text):
    if not project(text):return None
    lines=[line.strip() for line in (text or '').splitlines() if line.strip()]
    if not all(label in lines for label in ('WERKNO','BLADNO','merk')):return None
    marks=set(line.upper() for line in lines if re.fullmatch(r'TRK[0-9]+',line,re.I))
    if len(marks)!=1:return None
    mark=next(iter(marks))
    reinforcement=len(re.findall(r'[Øø⌀]\s*\d{1,2}\b',text))>=8 and bool(re.search(r'hrsp|bgls',text,re.I))
    form=any(re.fullmatch(r'VORM\s+DEFINITIEF',line,re.I) for line in lines)
    if reinforcement and not form:role='W'
    elif form and not reinforcement:role='V'
    else:return None
    return {'rol':role,'eigen':mark,'wap':mark if role=='W' else '', 'naam':mark+'_'+role+'.pdf',
            'naam_v':mark+'_V.pdf' if role=='V' else '', 'naam_w':mark+'_W.pdf' if role=='W' else ''}


def status(page,angle=0):
    """Read the current bottom status cell, never the revision table or a legend."""
    import pymupdf
    import mbs_paginacache as cache
    if not project(cache.tekst(page)):return None
    rotation=page.rotation
    try:
        page.set_rotation(angle);matrix=page.rotation_matrix;size=page.rect
        words=[(pymupdf.Rect(w[:4])*matrix,w[4]) for w in cache.woorden(page)]
    finally:page.set_rotation(rotation)
    labels={}
    for rect,text in words:
        if text in {'BLADNO','WERKNO','OPDRACHTGEVER','merk'} and rect.x0>size.width*.55 and rect.y0>size.height*.75:
            labels.setdefault(text,[]).append(rect)
    if any(len(labels.get(label,[]))!=1 for label in ('BLADNO','WERKNO','OPDRACHTGEVER','merk')):return None
    blad=labels['BLADNO'][0];merk=labels['merk'][0];h=max(blad.height,5)
    # MBS Specials bottom status cell ends before the revision codes column.
    cell=pymupdf.Rect(merk.x0-5,blad.y0-1,blad.x0-size.width*.08,min(size.height,blad.y1+h*2))
    if cell.width<=0:return None
    selected=sorted([(rect,text) for rect,text in words if cell.contains(rect.tl) and cell.contains(rect.br)],key=lambda value:(round(value[0].y0/3),value[0].x0))
    value=' '.join(text for _,text in selected).strip()
    if not value or len(value)>80:return None
    if re.search(r'\bstatus\b',value,re.I):return None
    # Retain the actual cell value, including any non-final or misspelled value.
    return {'waarde':value,'bron':'MBS Specials huidige statuscel','vak':[cell.x0,cell.y0,cell.x1,cell.y1]}
