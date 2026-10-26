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


_ABWN_TITLE = re.compile(r'(VORM|WAPENING) (BALKON|GALERIJ|DAKPLAAT|TRAP|BORDES)', re.I)
_ABWN_PROJECT = re.compile(r'Projectnummer\s+ABWN\s*//\s*werknummer\s+MBS\s*:?\s*\n?\s*\d{5}\s*/\s*(\d{6})(?!\d)', re.I)


def abwn_project(text):
    """Only the labelled MBS number, never the five-digit bureau/model number."""
    ids=set(_ABWN_PROJECT.findall(text or ''))
    ids.update(re.findall(r'^LOCALCHAT_ABWN_COVER_PROJECT: (\d{6})$',text or '',re.M))
    return next(iter(ids)) if len(ids)==1 else ''


def abwn_classify(text):
    """Handled + identity: reject ambiguous title marks instead of insert positions."""
    lines=[line.strip() for line in (text or '').splitlines() if line.strip()]
    titles=[m for line in lines if (m:=_ABWN_TITLE.fullmatch(line))]
    handled='Tekla Structures' in (text or '') and (bool(titles) or bool(_ABWN_PROJECT.search(text or '')))
    if not handled:return False,None
    details=[line.upper() for line in lines if re.fullmatch(r'DE-(?:BA|GA)-[0-9]+',line,re.I)]
    if details and any(re.fullmatch(r'Details (?:balkon|galerij)',line,re.I) for line in lines):
        if len(set(details))!=1:return True,None
        mark=details[0];return True,{'rol':'D','eigen':mark,'wap':'','naam':mark+'_D.pdf'}
    if len(titles)!=1:return True,None
    title=titles[0];family={'BALKON':'BA','GALERIJ':'GA','DAKPLAAT':'DP','TRAP':'TR','BORDES':'BD'}[title[2].upper()]
    marks={line.upper() for line in lines if re.fullmatch(family+r'[0-9]+(?:-[0-9]+)?',line,re.I)}
    if len(marks)!=1:return True,None
    mark=next(iter(marks));role='W' if title[1].upper()=='WAPENING' else 'V'
    return True,{'rol':role,'eigen':mark,'wap':mark if role=='W' else '',
                 'naam':mark+'_'+role+'.pdf','naam_v':mark+'_V.pdf' if role=='V' else '',
                 'naam_w':mark+'_W.pdf' if role=='W' else ''}


def abwn_bundle_hint(page,text):
    """Inherit one explicit cover MBS project within this document only."""
    handled,info=abwn_classify(text)
    if not handled or not info or abwn_project(text):return ''
    from mbs_webapp import project_van_blad
    own=project_van_blad(text)
    if re.fullmatch(r'\d{6}_?',own or ''):return ''
    doc=page.parent
    ids=getattr(doc,'_localchat_abwn_cover_projects',None)
    if ids is None:
        ids=set()
        for sheet in doc:
            raw=sheet.get_text('text') or ''
            if 'Tekla Structures' in raw:
                ids.update(_ABWN_PROJECT.findall(raw))
        doc._localchat_abwn_cover_projects=ids
    return 'LOCALCHAT_ABWN_COVER_PROJECT: '+next(iter(ids)) if len(ids)==1 else ''
