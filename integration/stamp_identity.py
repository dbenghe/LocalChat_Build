"""Read an explicitly labelled element identity; plot footers are never a W identity."""
import re
_MARK=re.compile(r'\d{2}\.\d{4}[A-Za-z]?')

def reading(page):
    labels=[]
    for block in page.get_text('dict').get('blocks',[]):
        for line in block.get('lines',[]):
            for span in line.get('spans',[]):
                if re.fullmatch(r'ELEMENTNR[.:]*',span['text'].strip(),re.I):labels.append((span,line['dir']))
    if not labels:return None
    unknown={'eigen':'onbevestigd','rol':'?','wap':'','bron':'expliciete ELEMENTNR-cel'}
    if len(labels)!=1:return None
    label,direction=labels[0];dx,dy=direction;box=label['bbox'];cx=(box[0]+box[2])/2;cy=(box[1]+box[3])/2
    half=(abs(dx)*(box[2]-box[0])+abs(dy)*(box[3]-box[1]))/2;size=max(1,label['size']);values=[]
    for w in page.get_text('words'):
        if not _MARK.fullmatch(w[4]):continue
        x=(w[0]+w[2])/2-cx;y=(w[1]+w[3])/2-cy;along=x*dx+y*dy;across=abs(-x*dy+y*dx)
        edge=(abs(dx)*(w[2]-w[0])+abs(dy)*(w[3]-w[1]))/2
        gap=along-edge-half
        if -.3*size<=gap<=25*size and across<=.8*size:values.append(w[4].upper())
    if not values:return None
    if len(set(values))!=1:return unknown
    identity=values[0];lines=[' '.join(s.split()).upper() for s in page.get_text().splitlines()]
    explicit_v=any(re.fullmatch(r'(?:VORMTEKENING|BETONVORM)',s) for s in lines)
    explicit_w=any(re.fullmatch(r'(?:WAPENINGSTEKENING|STAVEN LOS BIJ\s*LEGGEN|WAPENINGSMATTEN|WAPENINGSNETTEN)',s) for s in lines)
    role='V' if explicit_v and not explicit_w else 'W' if explicit_w and not explicit_v else '?'
    return dict(eigen=identity,rol=role,wap=identity if role=='W' else '',bron='expliciete ELEMENTNR-cel')

def hint(page):
    result=reading(page)
    return ('LOCALCHAT_STAMP: '+result['eigen']+' '+result['rol']) if result else ''

def classify(text):
    lines=[s for s in (text or '').splitlines() if s.startswith('LOCALCHAT_STAMP:')]
    if not lines:return False,None
    if len(lines)!=1:return True,None
    m=re.fullmatch(r'LOCALCHAT_STAMP: (\S+) ([VW?])',lines[0])
    if not m or m[2]=='?' or not _MARK.fullmatch(m[1]):return True,None
    mark,role=m.groups();return True,{'rol':role,'eigen':mark,'wap':mark if role=='W' else '',
        'naam':mark+'_'+role+'.pdf','naam_v':mark+'_V.pdf' if role=='V' else '', 'naam_w':mark+'_W.pdf' if role=='W' else ''}
