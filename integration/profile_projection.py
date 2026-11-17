"""Conservative orthographic profile association from native vectors and dimension ticks.
This source is embedded by apply_profile_projection; it never aliases article codes.
"""

import pymupdf as fitz

def geometry(page):
 import collections
 h=[];v=[]
 for path in page.get_drawings():
  for item in path['items']:
   edges=[]
   if item[0]=='l':edges=[(item[1],item[2])]
   elif item[0]=='re':
    r=fitz.Rect(item[1]);points=[r.tl,r.tr,r.br,r.bl];edges=list(zip(points,points[1:]+points[:1]))
   for a,b in edges:
    if abs(a.y-b.y)<.03 and abs(a.x-b.x)>.1:h.append(((a.y+b.y)/2,min(a.x,b.x),max(a.x,b.x)))
    if abs(a.x-b.x)<.03 and abs(a.y-b.y)>.1:v.append(((a.x+b.x)/2,min(a.y,b.y),max(a.y,b.y)))
 return {True:h,False:v}

def joined(lines):
 import collections
 groups=collections.defaultdict(list)
 for c,a,b in lines:groups[round(c,2)].append((a,b))
 out=[]
 for c,parts in groups.items():
  intervals=[]
  for a,b in sorted(parts):
   if intervals and a<=intervals[-1][1]+.05:intervals[-1][1]=max(intervals[-1][1],b)
   else:intervals.append([a,b])
  out.extend((c,a,b) for a,b in intervals)
 return out

def cover(lines,coord,a,b,tol=.06):
 intervals=sorted((lo,hi) for c,lo,hi in lines if abs(c-coord)<tol and lo<b+tol and hi>a-tol);end=a
 for lo,hi in intervals:
  if lo>end+tol:break
  end=max(end,hi)
  if end>=b-tol:return True
 return False

def rectangles(g,table):
 import collections
 groups=collections.defaultdict(set)
 for x,a,b in g[False]:
  if b-a>.5:groups[(round(a,2),round(b,2))].add(round(x,3))
 boxes=set()
 for (a,b),xs in groups.items():
  xs=sorted(xs)
  for i,x in enumerate(xs):
   for z in xs[i+1:]:
    if z-x<.5:continue
    box=fitz.Rect(x,a,z,b)
    if table is not None and box.intersects(table):continue
    if cover(g[True],a,x,z) and cover(g[True],b,x,z):boxes.add((x,a,z,b))
 return sorted(boxes)

def readings(ex,page,g,words,table,h,start,end,low,high):
 results=[];tol=min(.35,(end-start)*.18)
 prepared=g.setdefault('_prepared',{});key=(h,tuple(table) if table is not None else None)
 if key not in prepared:
  segments=joined(g[h]);numbers=[]
  for w in words:
   if bool(w['horiz'])!=h or (table is not None and w['rect'].intersects(table)):continue
   try:number=float(w['tekst'].replace(',','.'))
   except ValueError:continue
   rect=w['rect'];along=(rect.x0+rect.x1)/2 if h else (rect.y0+rect.y1)/2;cross=(rect.y0+rect.y1)/2 if h else (rect.x0+rect.x1)/2
   nearer=[abs(c-cross) for c,a,b in segments if a-.06<=along<=b+.06]
   nearest=min(nearer) if nearer else None
   numbers.append((number,w,along,cross,max(12,min(18,1.8*w.get('size',8))) if h else 12,nearest))
  prepared[key]=(segments,numbers)
 segments,numbers=prepared[key]
 for coord,a,b in segments:
  if a>start+.06 or b<end-.06:continue
  if coord<low-max(page.rect.width,page.rect.height)*.45 or coord>high+max(page.rect.width,page.rect.height)*.45:continue
  ticks=sorted({round(c,3) for c,u,z in g[not h] if u<=coord+.08 and z>=coord-.08 and z-u>=1})
  if not all(any(abs(c-edge)<=tol for c in ticks) for edge in (start,end)):continue
  if any(start+tol<c<end-tol for c in ticks):continue
  values=[]
  for number,w,along,cross,band,nearest in numbers:
   # Vertical CAD dimensions use the baseline-facing side of rotated text.
   # A number on the other side belongs to a neighbouring dimension column.
   angle=w.get('hoek',90)%360
   if not h and ((angle==90 and cross>coord) or (angle==270 and cross<coord)):continue
   if abs(coord-cross)>band or (nearest is not None and abs(coord-cross)>nearest+.8):continue
   on=start<=along<=end
   if not on:
    edge=start if along<start else end;lo,hi=sorted((along,edge))
    if abs(along-edge)<=max(25,w.get('size',10)*3) and cover(g[h],coord,lo,hi):
     intermediate=any(lo+.12<c<hi-.12 for c in ticks)
     occupied=any(u+.12<along<v-.12 and cover(g[h],coord,u,v) for u,v in zip(ticks,ticks[1:]))
     on=not intermediate and not occupied
   if on:values.append((number,w))
  unique={number for number,w in values}
  if len(unique)==1 and next(iter(unique))>0:
   number,w=values[0];results.append((number,w['rect'],coord))
 return results


def ordinates(words,table,h,coord,start,end):
 """Read an unambiguous endpoint pair before consulting any expected dimension."""
 import re
 candidates=[]
 for edge in (start,end):
  found=[]
  for word in words:
   if bool(word['horiz'])==h or not re.fullmatch(r'\d+(?:[.,]\d+)?',word['tekst']):continue
   rect=word['rect']
   if table is not None and rect.intersects(table):continue
   lo,hi=(rect.x0,rect.x1) if h else (rect.y0,rect.y1)
   u,v=(rect.y0,rect.y1) if h else (rect.x0,rect.x1)
   anchor=min((0,1),key=lambda i:abs((lo,hi)[i]-edge))
   if abs((lo,hi)[anchor]-edge)>2:continue
   side=1 if u>=coord else -1 if v<=coord else 0
   if side and min(abs(u-coord),abs(v-coord))<=8:found.append((float(word['tekst'].replace(',','.')),anchor,side))
  candidates.append(found)
 pairs={(a[0],b[0]) for a in candidates[0] for b in candidates[1] if a[1:]==b[1:]}
 return next(iter(pairs)) if len(pairs)==1 else None


def wall_scale(ex,page,g,words,table,box):
 x,a,z,b=box;values={n for n,_,_ in readings(ex,page,g,words,table,False,a,b,x,z)}
 for coord,lo,hi in joined(g[False]):
  if lo>a+.06 or hi<b-.06 or not ((coord<x-2 or coord>z+2) and min(abs(coord-x),abs(coord-z))<300):continue
  if not all(any(abs(c-edge)<.35 and u<=coord+.08 and v>=coord-.08 and v-u>=1 for c,u,v in g[True]) for edge in (a,b)):continue
  pair=ordinates(words,table,False,coord,a,b)
  if pair and abs(pair[1]-pair[0])>0:values.add(abs(pair[1]-pair[0]))
 if len(values)!=1:return None
 value=next(iter(values))
 return value/(b-a)


def position_graph(ex,page,g,words,table,wall,scale):
 """Trace explicit adjacent dimension cells from a wall boundary, with no gaps."""
 from collections import defaultdict,deque
 px,pa,pz,pb=wall;graph=defaultdict(list)
 for coord,a,b in joined(g[True]):
  if not pa-300<coord<pa+30:continue
  ticks=sorted({round(c,3) for c,u,v in g[False]
                if px-.06<=c<=pz+.06 and u<=coord+.08 and v>=coord-.08 and v-u>=1})
  if len(ticks)>150:continue
  for left,right in zip(ticks,ticks[1:]):
   if left<a-.06 or right>b+.06:continue
   values={n for n,_,c in readings(ex,page,g,words,table,True,left,right,coord,coord) if abs(c-coord)<.06}
   if len(values)!=1:continue
   value=next(iter(values))
   if abs(value-(right-left)*scale)>max(.8,value*.01):continue
   graph[round(left,2)].append((round(right,2),value,coord))
   graph[round(right,2)].append((round(left,2),-value,coord))
 reachable={};todo=deque()
 for boundary in (px,pz):
  key=round(boundary,2)
  if key in graph:reachable[key]=[];todo.append(key)
 while todo:
  key=todo.popleft()
  for target,value,coord in graph[key]:
   if target in reachable:continue
   reachable[target]=reachable[key]+[(key,target,value,coord)];todo.append(target)
 return reachable


def analyse(ex,page):
 rows,table=ex.lees_artikel_tabel(page);out=[];specs=[]
 for row in rows:
  if 'sparing rechthoek' not in row['omschrijving'].lower():continue
  dims=ex._artikel_maten(row['omschrijving'])
  if len(dims)==1 and dims[0][0]=='box' and len(dims[0][1])==2:specs.append((row,dims[0][1]))
 if not specs:return [],0
 words=ex.tekstwoorden(page);g=geometry(page);boxes=rectangles(g,table);parent_cache={};graph_cache={}
 for box in boxes:
  x,a,z,b=box
  values={n for n,_,_ in readings(ex,page,g,words,table,False,a,b,x,z)}
  if len(values)!=1:continue
  value=next(iter(values));rough_scale=value/(b-a);parents=[]
  for wall in boxes:
   px,pa,pz,pb=wall
   if px>x+.06 or pz<z-.06 or abs(pa-a)>.06 or pb<b+10:continue
   if wall not in parent_cache:parent_cache[wall]=wall_scale(ex,page,g,words,table,wall)
   scale=parent_cache[wall]
   if scale and abs(scale/rough_scale-1)<.01:parents.append((wall,scale))
  if not parents:continue
  # Do not choose a convenient scale from contradictory independently measured parents.
  scales=[scale for _,scale in parents]
  if max(scales)/min(scales)>1.01:continue
  wall,scale=max(parents,key=lambda item:item[0][3]-item[0][1])
  for row,dims in specs:
   if value not in dims:continue
   depth=dims[1] if value==dims[0] else dims[0]
   tops=[t for t in boxes if abs(t[0]-x)<=.06 and abs(t[2]-z)<=.06
         and (t[3]<a-5 or t[1]>b+5) and abs((t[3]-t[1])*scale-depth)<=max(.8,depth*.01)]
   if not tops:continue
   horizontal=False;placement=[];edges=(False,False);length=(z-x)*scale
   for coord,lo,hi in joined(g[True]):
    if lo>x+.06 or hi<z-.06 or not a-250<coord<b+40:continue
    if not all(any(abs(c-edge)<.35 and u<=coord+.08 and v>=coord-.08 and v-u>=1 for c,u,v in g[False]) for edge in (x,z)):continue
    pair=ordinates(words,table,True,coord,x,z)
    if pair and abs(abs(pair[1]-pair[0])-length)<=max(.8,length*.01):horizontal=True;edges=(True,True);placement=[('ordinates',pair,coord)];break
   if not horizontal:
    if wall not in graph_cache:graph_cache[wall]=position_graph(ex,page,g,words,table,wall,scale)
    graph=graph_cache[wall]
    edges=(round(x,2) in graph,round(z,2) in graph)
    if all(edges):
     horizontal=True;placement=[('cells',graph[round(x,2)],graph[round(z,2)])]
   out.append(dict(pos=row['pos'],profile=dims,height=value,depth=depth,scale=scale,box=box,top=tops[0],wall=wall,wall_reference=True,horizontal_proven=horizontal,length=length,placement=placement,x_edges=edges))
 return out,len(boxes)


def confirmed(ex,page,rows,matches=None):
 """All occurrences and unique table ownership are required; partial evidence stays manual."""
 from collections import Counter
 if not any('sparing rechthoek' in r.get('omschrijving','').lower() for r in rows):return {}
 owners=Counter(tuple(ex._artikel_maten(r.get('omschrijving',''))) for r in rows
                if 'sparing rechthoek' in r.get('omschrijving','').lower())
 if matches is None:matches,_=analyse(ex,page)
 result={}
 for row in rows:
  dims=tuple(ex._artikel_maten(row.get('omschrijving','')))
  if owners[dims]!=1:continue
  quantity=row.get('aantal_n')
  if not isinstance(quantity,(float,int)) or isinstance(quantity,bool) or quantity<=0 or int(quantity)!=quantity:continue
  found={}
  for item in matches:
   if item['pos']!=row['pos'] or not item['wall_reference']:continue
   key=tuple(round(n,1) for n in item['box'])
   if key not in found or item['horizontal_proven']:found[key]=item
  if len(found)!=int(quantity) or not all(i['horizontal_proven'] for i in found.values()):continue
  # Competing profile depths for the same footprint cannot establish identity.
  if any(tuple(round(n,1) for n in i['box']) in found and i['pos']!=row['pos']
         and i['wall_reference'] for i in matches):continue
  result[row['pos']]=list(found.values())
 return result


def evaluate(ex,page,rows):
 """Separate confirmed profiles from recognized geometry with unresolved placement/code."""
 matches,_=analyse(ex,page)
 approved=confirmed(ex,page,rows,matches)
 review={};groups={}
 for row in rows:
  if 'sparing rechthoek' not in row.get('omschrijving','').lower():continue
  shape=tuple(ex._artikel_maten(row.get('omschrijving','')))
  groups.setdefault(shape,[]).append(row)
 for shape,members in groups.items():
  quantities=[row.get('aantal_n') for row in members]
  if not all(isinstance(n,(int,float)) and not isinstance(n,bool) and n>0 and int(n)==n for n in quantities):continue
  found={}
  positions={row['pos'] for row in members}
  for item in matches:
   if item['pos'] not in positions or not item['wall_reference']:continue
   key=tuple(round(n,1) for n in item['box'])
   if key not in found or item['horizontal_proven']:found[key]=item
  values=sorted(found.values(),key=lambda item:item['box'])
  if len(values)!=sum(quantities):continue
  if len(members)>1 and all(v['horizontal_proven'] for v in values):
   for row in members:
    review[row['pos']]={'code':'PROFIEL_POSITIECODE_ONBEVESTIGD','rect':values[0]['box'],
     'boxes':[v['box'] for v in values],
     'note':f"{len(values)} profielcontouren en plaatsmaten bevestigd voor de gezamenlijke hoeveelheid. Identieke profielmaten bij meerdere positiecodes: "
      + ', '.join(member['pos'] for member in members)+'. Verdeling per positiecode niet aangetoond; aantallen alleen bepalen de koppeling niet.'}
  elif len(members)==1 and members[0]['pos'] not in approved:
   missing=[v for v in values if not v['horizontal_proven']]
   if missing:
    review[members[0]['pos']]={'code':'PROFIEL_PLAATSMAAT_ONBEVESTIGD','rect':missing[0]['box'],
     'boxes':[v['box'] for v in values],
     'note':f"Alle {len(values)} profielcontouren en hun doorsnede in twee projecties herkend. Bij {len(missing)} exemplaar/exemplaren ontbreekt een bevestigde X-maatkoppeling. "
      + '; '.join('contour '+str(index+1)+': '+', '.join(edge for edge,ok in zip(('linkerrand','rechterrand'),v.get('x_edges',(False,False))) if not ok)
                 for index,v in enumerate(values) if not v['horizontal_proven'])
      + '. Dit is geen ontbrekend artikellabel; controleer de plaatsmaat van het gemarkeerde exemplaar.'}
 return {'approved':approved,'review':review}
