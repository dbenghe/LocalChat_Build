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
 for coord,a,b in joined(g[h]):
  if a>start+.06 or b<end-.06:continue
  if coord<low-max(page.rect.width,page.rect.height)*.45 or coord>high+max(page.rect.width,page.rect.height)*.45:continue
  def tick(edge):
   return any(abs(c-edge)<=tol and u<=coord+.08 and z>=coord-.08 and z-u>=1 for c,u,z in g[not h])
  if not all(tick(edge) for edge in (start,end)):continue
  values=[]
  for w in words:
   if bool(w['horiz'])!=h or (table is not None and w['rect'].intersects(table)):continue
   try:n=float(w['tekst'].replace(',','.'))
   except ValueError:continue
   r=w['rect'];along=(r.x0+r.x1)/2 if h else (r.y0+r.y1)/2;cross=(r.y0+r.y1)/2 if h else (r.x0+r.x1)/2
   on=ex._getal_op_lijn(w,coord,start,end,h,afstand=12)
   if not on and abs(cross-coord)<=12 and (along<start or along>end):
    edge=start if along<start else end;lo,hi=sorted((along,edge))
    if abs(along-edge)<=max(25,w.get('size',10)*3) and cover(g[h],coord,lo,hi):
     intermediate=any(lo+.12<c<hi-.12 and u<=coord+.08 and z>=coord-.08 and z-u>=1 for c,u,z in g[not h])
     ticks=sorted({round(c,2) for c,u,z in g[not h] if u<=coord+.08 and z>=coord-.08 and z-u>=1})
     occupied=any(u+.12<along<v-.12 and cover(g[h],coord,u,v) for u,v in zip(ticks,ticks[1:]))
     on=not intermediate and not occupied
   if on:values.append((n,w))
  unique={n for n,w in values}
  if len(unique)==1 and next(iter(unique))>0:
   n,w=values[0];results.append((n,w['rect'],coord))
 return results

def analyse(ex,page):
 rows,table=ex.lees_artikel_tabel(page);out=[]
 specs=[]
 for row in rows:
  if 'sparing rechthoek' not in row['omschrijving'].lower():continue
  d=ex._artikel_maten(row['omschrijving'])
  if len(d)==1 and d[0][0]=='box' and len(d[0][1])==2:specs.append((row,d[0][1]))
 if not specs:return [],0
 words=ex.tekstwoorden(page);g=geometry(page);boxes=rectangles(g,table)
 for box in boxes:
  x,a,z,b=box
  vals=readings(ex,page,g,words,table,False,a,b,x,z)
  for val,r,c in vals:
   scale=val/(b-a)
   for row,dims in specs:
    if val not in dims:continue
    other=dims[1] if val==dims[0] else dims[0]
    for top in boxes:
     u,s,w,t=top
     if abs(u-x)>.06 or abs(w-z)>.06 or not (t<a-5 or s>b+5):continue
     if abs((t-s)*scale-other)>max(.8,other*.01):continue
     # A larger enclosing wall supplies the independent top/bottom reference.
     parents=[]
     for parent in boxes:
      px,pa,pz,pb=parent
      if px>x+.06 or pz<z-.06 or abs(pa-a)>.06 or pb<b+10:continue
      pv=readings(ex,page,g,words,table,False,pa,pb,px,pz)
      if any(abs(n/(pb-pa)/scale-1)<.01 for n,_,_ in pv):parents.append(parent)
      else:
       for coord,lo,hi in joined(g[False]):
        if lo<=pa+.06 and hi>=pb-.06 and (coord<px-2 or coord>pz+2) and min(abs(coord-px),abs(coord-pz))<300:
         if ex._sparing_ordinaten(page,words,table,False,coord,pa,pb,(pb-pa)*scale) is not None:
          parents.append(parent);break
     length=(z-x)*scale
     horizontal=False
     # A width alone is not X placement: require two endpoint ordinates.
     for coord,lo,hi in joined(g[True]):
      if lo<=x+.06 and hi>=z-.06 and a-250<coord<b+40:
       if not all(any(abs(c-edge)<.35 and u<=coord+.08 and v>=coord-.08 and v-u>=1
                      for c,u,v in g[False]) for edge in (x,z)):continue
       if ex._sparing_ordinaten(page,words,table,True,coord,x,z,length) is not None:horizontal=True;break
     out.append({'pos':row['pos'],'profile':dims,'height':val,'depth':other,'scale':scale,'box':box,'top':top,'wall_reference':bool(parents),'horizontal_proven':horizontal,'length':length})
 return out,len(boxes)


def confirmed(ex,page,rows):
 """All occurrences and unique table ownership are required; partial evidence stays manual."""
 from collections import Counter
 if not any('sparing rechthoek' in r.get('omschrijving','').lower() for r in rows):return {}
 owners=Counter(tuple(ex._artikel_maten(r.get('omschrijving',''))) for r in rows
                if 'sparing rechthoek' in r.get('omschrijving','').lower())
 matches,_=analyse(ex,page)
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
