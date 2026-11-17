"""Read-only, hash-bound IFC companion evidence. It cannot substitute model coordinates for drawn dimensions."""
from pathlib import Path
import re,math,json,hashlib,functools,collections,unicodedata
import pymupdf as fitz

class Rejected(ValueError):pass
class Ref(int):pass
TOKEN=re.compile(r"\s*(\#\d+|'(?:''|[^'])*'|[A-Za-z_][A-Za-z0-9_]*|[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][+-]?\d+)?|\.[A-Za-z_]+\.|[$*(),])")

def parse(text):
    tokens=[];end=0
    for match in TOKEN.finditer(text):
        if text[end:match.start()].strip():raise Rejected('Unsupported STEP token')
        tokens.append(match[1]);end=match.end()
    if text[end:].strip():raise Rejected('Unsupported STEP suffix')
    index=0
    def group():
        nonlocal index
        if tokens[index]!='(':raise Rejected('STEP group expected')
        index+=1;out=[]
        while tokens[index]!=')':
            if tokens[index]==',':index+=1;continue
            out.append(value())
        index+=1;return out
    def value():
        nonlocal index
        token=tokens[index]
        if token=='(':return group()
        index+=1
        if token.startswith('#'):return Ref(token[1:])
        if token.startswith("'"):return token[1:-1].replace("''", "'")
        if token in ('$','*'):return None
        if re.fullmatch(r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][+-]?\d+)?',token):
            number=float(token)
            if not math.isfinite(number):raise Rejected('Non-finite IFC coordinate')
            return number
        if index<len(tokens) and tokens[index]=='(':return (token,group())
        return token
    try:result=group()
    except (IndexError,RecursionError) as error:raise Rejected('Invalid STEP nesting') from error
    if index!=len(tokens):raise Rejected('Unexpected STEP tail')
    return result

def records(text):
    start=0;quoted=False;index=0
    while index<len(text):
        c=text[index]
        if c=="'":
            if quoted and index+1<len(text) and text[index+1]=="'":index+=2;continue
            quoted=not quoted
        if c==';' and not quoted:
            yield text[start:index].strip();start=index+1
        index+=1
    if quoted:raise Rejected('Unterminated STEP string')

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def cross(a,b):return (a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
def unit(v):
    length=math.sqrt(sum(x*x for x in v))
    if abs(length-1)>.001:raise Rejected('Unsupported non-unit IFC direction')
    return tuple(x/length for x in v)
def transform(matrix,p):return tuple(matrix[0][i]+sum(matrix[j+1][i]*p[j] for j in range(3)) for i in range(3))
def bbox(points):return tuple(min(p[i] for p in points) for i in range(3))+tuple(max(p[i] for p in points) for i in range(3))
def decode(value):
    value=value.replace('\\\\','\\')
    value=re.sub(r'\\X2\\([0-9A-Fa-f]+)\\X0\\',lambda m:bytes.fromhex(m[1]).decode('utf-16-be'),value)
    value=re.sub(r'\\X4\\([0-9A-Fa-f]+)\\X0\\',lambda m:bytes.fromhex(m[1]).decode('utf-32-be'),value)
    return re.sub(r'\\S\\(.)',lambda m:chr(ord(m[1])+128),value)
def identity(value):
    value=unicodedata.normalize('NFKC',decode(value)).upper().replace('×','X')
    value=re.sub(r'\b(?:MALZIJDE|STORTZIJDE|LIJSTZIJDE|RONDOM|TRANSPORTBALK)\b','',value)
    return ''.join(c for c in value if c.isalnum())
def supported(value):return bool(re.match(r'(?i)^(?:Stekanker\b|T\d{2,4}\s*-)',value))

class Model:
    def __init__(self,path):
        path=Path(path)
        if path.stat().st_size>20_000_000:raise Rejected('IFC exceeds bounded reference size')
        text=path.read_text(encoding='utf8')
        if not re.search(r"FILE_SCHEMA\s*\(\s*\(\s*'IFC2X3'",text,re.I):raise Rejected('Only IFC2X3 companion references are supported')
        self.entities={};self.point_cache={};self.edge_cache={};self.placement_cache={};self.unsupported=[]
        for record in records(text):
            match=re.fullmatch(r'#(\d+)\s*=\s*(\w+)\s*(\(.*\))',record,re.S)
            if record.startswith('#') and not match:raise Rejected('Invalid IFC entity record')
            if match:
                key=int(match[1])
                if key in self.entities:raise Rejected('Duplicate IFC entity identifier')
                self.entities[key]=(match[2],parse(match[3]))
            if len(self.entities)>100_000:raise Rejected('IFC entity limit')
        projects=[a for kind,a in self.entities.values() if kind=='IFCPROJECT']
        if len(projects)!=1 or len(projects[0])<9:raise Rejected('Unconfirmed IFC project units')
        assignment=self.get(projects[0][8],'IFCUNITASSIGNMENT')[0]
        units=[self.entities[ref] for ref in assignment if self.entities[ref][0]=='IFCSIUNIT' and self.entities[ref][1][1]=='.LENGTHUNIT.']
        if len(units)!=1 or units[0][1][2:]!=['.MILLI.','.METRE.']:raise Rejected('Unconfirmed millimetre units')
        self.products=[]
        for key,(kind,args) in self.entities.items():
            if kind!='IFCWALL' and (kind not in ('IFCBEAM','IFCBUILDINGELEMENTPROXY') or not supported(str(args[2]))):continue
            try:
                raw=self.points(args[6]);world=tuple(transform(self.placement(args[5]),p) for p in raw)
                if not world:raise Rejected('Empty geometry')
                self.products.append(dict(id=key,kind=kind,name=decode(args[2]),points=world,box=bbox(world),local_points=raw,local_box=bbox(raw),matrix=self.placement(args[5]),edges=tuple((transform(self.placement(args[5]),a),transform(self.placement(args[5]),b)) for a,b in self.edges(args[6])) if kind!='IFCWALL' else ()))
            except (Rejected,KeyError,IndexError,TypeError) as error:self.unsupported.append((str(args[2]),str(error)))
    def get(self,ref,kind=None):
        actual,args=self.entities[ref]
        if kind and actual!=kind:raise Rejected('Unsupported IFC entity linkage')
        return args
    def placement(self,ref,depth=0):
        if depth>30:raise Rejected('IFC placement recursion')
        if ref is None:return ((0,0,0),(1,0,0),(0,1,0),(0,0,1))
        if ref in self.placement_cache:return self.placement_cache[ref]
        parent,axisref=self.get(ref,'IFCLOCALPLACEMENT');a=self.get(axisref,'IFCAXIS2PLACEMENT3D');origin=tuple(self.get(a[0],'IFCCARTESIANPOINT')[0]);z=unit(tuple(self.get(a[1],'IFCDIRECTION')[0])) if a[1] else (0,0,1);x=unit(tuple(self.get(a[2],'IFCDIRECTION')[0])) if a[2] else (1,0,0);y=unit(cross(z,x));p=self.placement(parent,depth+1)
        result=(transform(p,origin),*[tuple(sum(p[j+1][i]*v[j] for j in range(3)) for i in range(3)) for v in (x,y,z)])
        self.placement_cache[ref]=result;return result
    def edges(self,ref,depth=0):
        if depth>30:raise Rejected('IFC geometry recursion')
        if ref in self.edge_cache:return self.edge_cache[ref]
        name,args=self.entities[ref]
        if name=='IFCPOLYLOOP':
            points=[tuple(self.get(v,'IFCCARTESIANPOINT')[0]) for v in args[0]]
            result=tuple(zip(points,points[1:]+points[:1]))
        else:
            if name=='IFCPRODUCTDEFINITIONSHAPE':refs=args[2]
            elif name=='IFCSHAPEREPRESENTATION':refs=args[3]
            elif name in ('IFCFACETEDBREP','IFCCLOSEDSHELL','IFCFACE','IFCFACEOUTERBOUND','IFCFACEBOUND'):refs=args[0] if isinstance(args[0],list) else [args[0]]
            else:raise Rejected('Unsupported IFC edge geometry: '+name)
            result=tuple({pair for child in refs for pair in self.edges(child,depth+1)})
        if len(result)>100_000:raise Rejected('IFC edge limit')
        self.edge_cache[ref]=result;return result

    def points(self,ref,depth=0):
        if depth>30:raise Rejected('IFC geometry recursion')
        if ref in self.point_cache:return self.point_cache[ref]
        name,args=self.entities[ref]
        if name=='IFCCARTESIANPOINT':return (tuple(args[0]),)
        if name=='IFCPRODUCTDEFINITIONSHAPE':refs=args[2]
        elif name=='IFCSHAPEREPRESENTATION':refs=args[3]
        elif name in ('IFCFACETEDBREP','IFCCLOSEDSHELL','IFCFACE','IFCFACEOUTERBOUND','IFCFACEBOUND','IFCPOLYLOOP'):refs=args[0] if isinstance(args[0],list) else [args[0]]
        else:raise Rejected('Unsupported IFC geometry: '+name)
        result=tuple({p for child in refs for p in self.points(child,depth+1)})
        if len(result)>100_000:raise Rejected('IFC point limit')
        self.point_cache[ref]=result;return result

@functools.lru_cache(maxsize=4)
def load_model(path,digest):
    if sha(path)!=digest:raise Rejected('IFC hash changed')
    return Model(path)

def fingerprint(page):
    words=[];geometry=[]
    for w in page.get_text('words'):
        if re.fullmatch(r'\d{2}-\d{2}-\d{4}',w[4]) and w[0]<250 and w[1]<100:continue
        words.append([w[4],*[round(x,3) for x in w[:4]]])
    for path in page.get_drawings():
        if path.get('fill') and fitz.Rect(0,0,250,100).contains(path['rect']):continue
        for item in path['items']:
            values=[item[0]]
            for value in item[1:]:
                if isinstance(value,(fitz.Point,fitz.Rect,fitz.Quad)):values.extend(round(x,3) for p in value for x in (p if isinstance(p,fitz.Point) else (p,)))
                elif isinstance(value,(int,float)):values.append(value)
            geometry.append(values)
    return hashlib.sha256(json.dumps([list(page.rect),sorted(words),sorted(geometry,key=repr)],ensure_ascii=True,separators=(',',':')).encode()).hexdigest()

def capture_source(page):
    # Called only by the trusted checker, before OCR and its own legend/markings.
    page._mbs_reference_source_fingerprint=fingerprint(page)


@functools.lru_cache(maxsize=16)
def drawing_fingerprint(path,digest,page_number):
    if sha(path)!=digest:raise Rejected('Reference drawing hash changed')
    with fitz.open(path) as doc:return fingerprint(doc[page_number-1])

def hull(points):
    points=sorted(set(points));turn=lambda a,b,c:(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);low=[];high=[]
    for p in points:
        while len(low)>1 and turn(low[-2],low[-1],p)<=0:low.pop()
        low.append(p)
    for p in reversed(points):
        while len(high)>1 and turn(high[-2],high[-1],p)<=0:high.pop()
        high.append(p)
    return low[:-1]+high[:-1]
def distance(p,a,b):
    dx=b[0]-a[0];dy=b[1]-a[1];t=max(0,min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/(dx*dx+dy*dy))) if dx or dy else 0
    return math.hypot(p[0]-a[0]-t*dx,p[1]-a[1]-t*dy)
def segments(page):
    result=[];vertices=set()
    for path in page.get_drawings():
        for item in path['items']:
            if item[0]=='l':line=[tuple(item[1]),tuple(item[2])]
            elif item[0]=='re':r=item[1];line=[tuple(v) for v in (r.tl,r.tr,r.br,r.bl,r.tl)]
            elif item[0]=='qu':r=item[1];line=[tuple(v) for v in (r.ul,r.ur,r.lr,r.ll,r.ul)]
            elif item[0]=='c':
                a,b,c,d=item[1:];line=[tuple((1-t)**3*a[i]+3*(1-t)**2*t*b[i]+3*(1-t)*t*t*c[i]+t**3*d[i] for i in range(2)) for t in [i/12 for i in range(13)]]
            else:continue
            vertices.update(line);result.extend(zip(line,line[1:]))
    return result,vertices

def vertical_silhouette(edges,lines):
    # A few coincident vertices are insufficient: check both extreme contour
    # edges of each vertical mesh interval, including their interior strokes.
    groups=collections.defaultdict(set)
    for a,b in edges:
        if abs(a[0]-b[0])>.001 or abs(a[1]-b[1])<.05:continue
        groups[(round(min(a[1],b[1]),2),round(max(a[1],b[1]),2))].add((a[0]+b[0])/2)
    if not groups:return False
    for (start,end),positions in groups.items():
        for x in (min(positions),max(positions)):
            for fraction in (0,.25,.5,.75,1):
                point=(x,start+(end-start)*fraction)
                if min((distance(point,a,b) for a,b in lines),default=999)>.03:return False
    return True

def dimension_x(page,words,table,g,engine,wall,center,scale):
    x0,y0,x1,y1=wall
    for coord,a,b in engine.joined(g[True]):
        if not(coord<y0-10 or coord>y1+10) or a>x0+.06 or b<center-.06:continue
        if not all(any(abs(c-edge)<.25 and u<=coord+.08 and z>=coord-.08 and z-u>=1 for c,u,z in g[False]) for edge in (x0,center)):continue
        pair=engine.ordinates(words,table,True,coord,x0,center)
        if pair and abs(abs(pair[1]-pair[0])-(center-x0)/scale)<1:return (coord,pair)
    return None

def evaluate(page,rows,table,engine,registry_path=None):
    registry_path=Path(registry_path or Path(__file__).with_name('data_lokaal')/'reference_models.json')
    if not registry_path.is_file():return {}
    data=json.loads(registry_path.read_text(encoding='utf8'))
    if data.get('schema')!=1:raise Rejected('Unsupported reference registry')
    current=getattr(page,'_mbs_reference_source_fingerprint',None) or fingerprint(page);entry=None;project_words={w[4] for w in page.get_text('words')}
    for candidate in data.get('projects',[]):
        if not re.fullmatch(r'\d{6}',candidate.get('project','')):raise Rejected('Invalid reference project')
        if candidate['project'] not in project_words:continue
        for drawing in candidate.get('drawings',[]):
            path=Path(drawing['path'])
            if not path.is_file() or sha(path)!=drawing['sha256']:continue
            if drawing_fingerprint(str(path),drawing['sha256'],drawing.get('page',1))!=current:continue
            with fitz.open(path) as d:
                if candidate['project'] not in {w[4] for w in d[drawing.get('page',1)-1].get_text('words')}:raise Rejected('Unconfirmed project scope')
            if entry is not None and entry!=candidate:raise Rejected('Ambiguous reference projects')
            entry=candidate
    if entry is None:return {}
    ifc=Path(entry['ifc']['path'])
    if not re.search(r'(?<!\d)'+re.escape(entry['project'])+r'(?!\d)',ifc.name):raise Rejected('IFC project name mismatch')
    if not ifc.is_file() or sha(ifc)!=entry['ifc']['sha256']:raise Rejected('IFC hash changed')
    model=load_model(str(ifc),entry['ifc']['sha256']);g=engine.geometry(page);boxes=engine.rectangles(g,table);words=engine.words(page);lines,vertices=segments(page);walls=[p for p in model.products if p['kind']=='IFCWALL'];parts=[p for p in model.products if p['kind']!='IFCWALL'];candidates=[]
    for box in boxes:
        x0,y0,x1,y1=box
        if x1-x0<100 or y1-y0<100:continue
        calibration=engine.wall_scale(engine.adapter,page,g,words,table,box)
        if not calibration:continue
        scale=1/calibration;near=[v for v in vertices if x0-.03<=v[0]<=x1+.03 and y0-.03<=v[1]<=y1+.03]
        for wall in walls:
            b=wall['local_box'];width=b[3]-b[0];height=b[5]-b[2]
            if width<=0 or height<=0 or b[4]-b[1]>min(width,height)*.2:continue
            matrix=wall['matrix']
            if abs(matrix[3][2])<.99:continue
            def to_local(point):
                delta=tuple(point[i]-matrix[0][i] for i in range(3))
                return tuple(sum(delta[i]*matrix[j+1][i] for i in range(3)) for j in range(3))
            if abs(width*scale-(x1-x0))>.03 or abs(height*scale-(y1-y0))>.03:continue
            if not near:continue
            for mirror in (False,True):
                projection=lambda v:(x0+((width-(v[0]-b[0])) if mirror else v[0]-b[0])*scale,y1-(v[2]-b[2])*scale)
                projected=set(projection(v) for v in wall['local_points'])
                if not all(min(math.hypot(v[0]-p[0],v[1]-p[1]) for p in near)<.03 for v in projected):continue
                matched=[];expected=collections.Counter()
                for part in parts:
                    relative=tuple(to_local(v) for v in part['points']);q=bbox(relative);anchor=part['name'].lower().startswith('stekanker')
                    if not(q[0]>=b[0]-1 and q[3]<=b[3]+1 and q[2]>=b[2]-1 and q[5]<=b[5]+1):continue
                    if anchor:
                        if q[1]<b[1]-1 or q[4]>b[4]+1:continue
                    elif not(q[1]>=b[1]-1 and q[4]<=b[4]+1) and min(abs(q[4]-b[1]),abs(q[1]-b[4]))>1:continue
                    expected[identity(part['name'])]+=1
                    length=re.search(r'\bM\d+\s*[x×]\s*(\d+(?:[.,]\d+)?)',part['name'],re.I)
                    if anchor and length and not any(word in part['name'].lower() for word in ('buigen','gebogen')):
                        declared=float(length[1].replace(',','.'))
                        if abs(q[5]-q[2]-declared)>max(.5,declared*.002):continue
                    outline=hull([(round(v[0],5),round(v[1],5)) for v in map(projection,relative)])
                    if len(outline)<4:continue
                    rect=fitz.Rect(min(v[0] for v in outline),min(v[1] for v in outline),max(v[0] for v in outline),max(v[1] for v in outline));near_segments=[(a,z) for a,z in lines if (fitz.Rect(min(a[0],z[0]),min(a[1],z[1]),max(a[0],z[0]),max(a[1],z[1]))+(-.03,-.03,.03,.03)).intersects(rect+(-.03,-.03,.03,.03))]
                    if not near_segments or any(min(distance(v,a,z) for a,z in near_segments)>.03 for v in outline):continue
                    projected_edges=[(projection(to_local(a)),projection(to_local(b))) for a,b in part['edges']]
                    if not vertical_silhouette(projected_edges,near_segments):continue
                    xproof=dimension_x(page,words,table,g,engine,box,(rect.x0+rect.x1)/2,scale)
                    matched.append(dict(name=part['name'],id=part['id'],rect=list(rect),hull=outline,x_proof=xproof))
                candidates.append(dict(matched=matched,expected=expected))
    owners=collections.Counter(identity(row.get('omschrijving','')) for row in rows);result={}
    for row in rows:
        name=row.get('omschrijving','');key=identity(name);quantity=row.get('aantal_n')
        if owners[key]!=1 or not supported(name) or not isinstance(quantity,(int,float)) or isinstance(quantity,bool) or quantity<=0 or int(quantity)!=quantity:continue
        if any(identity(n)==key for n,_ in model.unsupported):continue
        choices=[]
        for candidate in candidates:
            found=[p for p in candidate['matched'] if identity(p['name'])==key]
            if candidate['expected'][key]==quantity and len(found)==quantity:choices.append(found)
        # Every accepted pose must be the same current native-ink constellation.
        poses={tuple(sorted(tuple(round(n,2) for n in p['rect']) for p in found)) for found in choices}
        if len(poses)!=1:continue
        found=choices[0];one_axis=engine.one_axis(row) and not any(v in name.lower() for v in ('buigen','gebogen','lb='));approved=one_axis and all(p['x_proof'] for p in found)
        note=(f"IFC-companion '{ifc.name}': alle {len(found)} benoemde componenten geometrisch gekoppeld aan de actuele tekening; referentietekening, modelhash en project gecontroleerd. ")
        if approved:note+='X-plaatsing bevestigd door getekende ordinaten op dezelfde geverifieerde projectie. Bestaande ankerregel: één fysieke as volstaat.'
        else:note+='Artikelidentiteit herkend, maar eigen plaatsmaten niet volledig in de tekening bevestigd. Modelcoördinaten vervangen geen getekende plaatsmaten.'
        result[row['pos']]=dict(approved=approved,parts=found,note=note,source=str(ifc),source_sha256=entry['ifc']['sha256'])
    return result
