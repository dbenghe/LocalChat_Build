"""Local drawing-table -> factory catalogue -> template XLSX. Sources are never modified.

Automatic dimensions require explicit labels. Reviewed data and geometric decompositions are source-linked model interpretations, never independently verified by arithmetic.
Unresolved values stay blank and are recorded with page/position evidence, never a false pass.
"""
import argparse, collections, contextlib, copy, io, json, posixpath, re, sys, tempfile, time, zipfile
from decimal import Decimal
from pathlib import Path
from xml.etree import ElementTree as ET
import import_audit as audit

VERSION = 1
NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
T = lambda n: '{'+NS+'}'+n
NUM = r'\d+(?:[.,]\d+)?'
FIELDS = {'Lengte': ('lengte',), 'Breedte': ('breedte',), 'Hoogte': ('hoogte',),
          'Dikte': ('dikte',), 'Dikte plaat': ('dikte plaat', 'plaatdikte'),
          'Totaal dikte': ('totaal dikte', 'totale dikte'), 'Inhoud': ('inhoud', 'volume')}


def decimal(v):
    return Decimal(str(v).replace(',', '.'))


def text_number(v):
    return format(v, 'f').rstrip('0').rstrip('.') if '.' in format(v, 'f') else format(v, 'f')


def labelled_fields(text):
    """Only unique named values with explicit units, never arbitrary dimension lines."""
    fields, evidence, issues = {}, {}, []
    for field, aliases in FIELDS.items():
        units = r'(?:m3|m\u00b3)' if field == 'Inhoud' else r'(?:mm|cm|m)'
        pattern = r'(?im)^\s*(?:'+ '|'.join(re.escape(a) for a in aliases)+r')\s*[:=]?\s*('+NUM+r')\s*('+units+r')\s*$'
        hits = list(re.finditer(pattern, text))
        values = {decimal(m[1]) * (Decimal(1) if field=='Inhoud' else {'mm':Decimal(1),'cm':Decimal(10),'m':Decimal(1000)}[m[2].lower()]) for m in hits}
        if len(values) == 1:
            value = next(iter(values))
            if value > 0:
                fields[field] = text_number(value)
                evidence[field] = {'quotes': [m[0].strip() for m in hits], 'unit': 'm3' if field=='Inhoud' else 'mm'}
        elif len(values)>1:
            issues.append({'code':'dimension_conflict','field':field,'quotes':[m[0].strip() for m in hits]})
    counts = list(re.finditer(r'(?im)^\s*aantal\s+(?:elementen|element|stuks)\s*[:=]?\s*(\d+)\s*(?:st|stk|stuks)?\s*$',text))
    values = {int(m[1]) for m in counts}
    if len(values)==1 and next(iter(values))>0:
        fields['Aantal']=str(next(iter(values)));evidence['Aantal']={'quotes':[m[0].strip() for m in counts],'unit':'st'}
    elif len(values)>1:issues.append({'code':'element_quantity_conflict'})
    return fields,evidence,issues


def identity(text, path, project, single):
    titles = set(re.findall(r'(?im)^\s*(?:merk|elementmerk|element)\s*[:=]\s*([A-Z][A-Z0-9_.-]{0,30})\s*$',text))
    titles = {x.upper() for x in titles}
    filename = re.fullmatch(re.escape(project)+r'[_ -]+([A-Za-z][A-Za-z0-9_.-]*?)[_ -]+([VW])(?:[_ -]+rev[^/]*)?',path.stem,re.I)
    mark = filename[1].upper() if filename and single else None
    role = filename[2].upper() if filename else None
    title_marks = {re.sub(r'[_-][VW]$','',m) for m in titles}
    if title_marks:
        if len(title_marks)!=1 or (mark and mark not in title_marks):return None,role
        mark=next(iter(title_marks))
    return mark,role


def title_cells(page):
    """Read explicit unit-bearing values beside/below named title cells, not geometric dimensions."""
    words=page.get_text('words');fields={};evidence={}
    for field,labels,units in [('Inhoud',{'volume','inhoud','betoninhoud'},r'm(?:3|\u00b3)'),('Aantal',{'aantal'},r'(?:st|stk|stuks)'),('Gewicht(kg)',{'gewicht'},r'kg')]:
        values=[]
        for label in words:
            if label[4].casefold().strip(':') not in labels:continue
            if field=='Aantal' and (label[0]<page.rect.width*.6 or label[1]<page.rect.height*.65):continue
            h=max(3,label[3]-label[1]);cy=(label[1]+label[3])/2
            regions=[(label[2],label[1]-h*.3,min(page.rect.width,label[2]+110),label[3]+h*.3),
                     (max(0,label[0]-20),label[3]-1,min(page.rect.width,label[2]+25),label[3]+h*2)]
            for x0,y0,x1,y1 in regions:
                selected=[w for w in words if w is not label and w[0]>=x0-1 and w[2]<=x1+1 and (w[1]+w[3])/2>=y0 and (w[1]+w[3])/2<=y1]
                baselines=[]
                for word in sorted(selected,key=lambda w:(w[1]+w[3])/2):
                    center=(word[1]+word[3])/2
                    line=next((line for line in baselines if abs(line[0]-center)<2.5),None)
                    if line is None:line=[center,[]];baselines.append(line)
                    line[1].append(word)
                for _,line in baselines:
                    text=' '.join(w[4] for w in sorted(line,key=lambda w:w[0]))
                    match=re.fullmatch(r'\s*('+NUM+r')\s*('+units+r')\s*',text,re.I)
                    if match and decimal(match[1])>0 and (field!='Aantal' or decimal(match[1])==int(decimal(match[1]))):values.append((text_number(decimal(match[1])),label,text))
        unique={v[0] for v in values}
        if len(unique)==1:
            fields[field]=next(iter(unique));evidence[field]={'method':'labelled_title_cell','quotes':[v[1][4]+' '+v[2] for v in values],'bbox':[list(v[1][:4]) for v in values],'unit':'m3' if field=='Inhoud' else 'kg' if field=='Gewicht(kg)' else 'st'}
    return fields,evidence


def repair_encoding(value):
    if not any(c in value for c in ('\u00c3','\u00c2')):return value
    try:
        repaired=value.encode('latin1').decode('utf-8')
        return repaired if '\ufffd' not in repaired else value
    except (UnicodeError,ValueError):return value


def spatial_text(page):
    # OCR inserts separate words. Reassemble baselines before interpreting labels; never values alone.
    lines=[]
    for word in sorted(page.get_text('words'),key=lambda w:((w[1]+w[3])/2,w[0])):
        center=(word[1]+word[3])/2
        line=next((row for row in reversed(lines) if abs(row[0]-center)<=max(2,(word[3]-word[1])*.35)),None)
        if line is None:line=[center,[]];lines.append(line)
        line[1].append(word)
    return '\n'.join(' '.join(w[4] for w in sorted(row[1],key=lambda w:w[0])) for row in lines)


def read_pages(paths, project, use_ocr, bindings=None):
    import pymupdf
    sys.modules.setdefault('fitz',pymupdf)
    import mbs_controles_extra as extractor
    import mbs_ocr_artikelen as ocr
    pages, issues, count = [], [], 0
    for path in paths:
        nums=set(re.findall(r'(?<!\d)\d{6}(?!\d)',path.stem))
        if nums and project not in nums:raise ValueError('Drawing belongs to another project: '+str(path))
        with pymupdf.open(path) as document:
            count+=len(document)
            if count>100:raise ValueError('Maximum 100 drawing pages per call; select a bounded batch')
            for index,page in enumerate(document):
                where={'path':str(path),'page':index+1};method='pdfcontrol_text_table';ocr_error=None
                with contextlib.redirect_stdout(sys.stderr):rows,_=extractor.lees_artikel_tabel(page)
                # The PDF is open in memory only; OCR never saves back into the original.
                if use_ocr and (not rows or ocr.nodig(page)):
                    try:
                        with contextlib.redirect_stdout(sys.stderr):added=ocr.aanvullen(page,page.rotation);rows,_=extractor.lees_artikel_tabel(page)
                        if added:method='pdfcontrol_ocr_table'
                    except Exception as error:ocr_error=str(error)[:300]
                text=spatial_text(page);mark,role=identity(text,path,project,len(document)==1)
                binding=(bindings or {}).get((str(path),index+1))
                detected_mark=mark
                if binding:mark=binding['merk']
                item=dict(where,merk=mark,role=role,method=method,articles=[])
                if binding:
                    item['mark_binding']=dict(binding,detected_mark=detected_mark)
                    issues.append(dict(where,merk=mark,code='mark_binding_review_required',evidence=binding['evidence']))
                if ocr_error:issues.append(dict(where,code='ocr_failed',detail=ocr_error))
                if not mark:issues.append(dict(where,code='drawing_merk_unconfirmed'))
                fields,evidence,di=labelled_fields(text)
                cell_fields,cell_evidence=title_cells(page)
                for field,value in cell_fields.items():
                    if field in fields and fields[field]!=value:di.append({'code':'dimension_conflict','field':field,'values':[fields[field],value]});fields.pop(field,None)
                    else:fields[field]=value;evidence[field]=cell_evidence[field]
                item.update(fields=fields,field_evidence=evidence)
                issues.extend(dict(where,merk=mark,**v) for v in di)
                if not rows:issues.append(dict(where,merk=mark,code='drawing_table_unreadable'))
                for row in rows:
                    rect=row.get('rect')
                    item['articles'].append({'pos':str(row.get('pos','')),'description':str(row.get('omschrijving','')),
                       'quantity':str(row.get('aantal','')),'bbox':list(rect) if rect is not None else None,
                       'ocr_uncertain':bool(row.get('ocr_diameter'))})
                pages.append(item)
    return pages,issues


def make_records(pages, catalogue, hoofdregel=None):
    records,issues=[],[]
    groups=collections.defaultdict(list)
    for item in pages:
        if item['merk']:groups[item['merk']].append(item)
    for mark, sources in groups.items():
        page=next((item for item in sources if item['role']=='V'),sources[0])
        where={k:page[k] for k in ('path','page','merk')}
        for role in ('V','W',None):
            if sum(item['role']==role for item in sources)>1:
                issues.append(dict(where,code='duplicate_mark_source',role=role,note='Repeated pages/revisions require source selection; quantities are never summed'))
        if page['role']!='V':issues.append(dict(where,code='form_drawing_required'))
        main={'Merk':mark,'Tekenbureau':'14337','Status tekening':'20',**page['fields']}
        if hoofdregel:main['Hoofdregel']=hoofdregel
        else:issues.append(dict(where,code='hoofdregel_required'))
        for field in ('Aantal','Lengte','Breedte','Dikte','Inhoud'):
            if not main.get(field):issues.append(dict(where,code='element_field_unresolved',field=field))
        if page['role'] is None:issues.append(dict(where,code='drawing_role_unconfirmed'))
        records.append({'fields':main,'source':where,'field_evidence':page['field_evidence'],'kind':'element'})
        positions={}
        for source_page,article in [(item,article) for item in sources for article in item['articles']]:
            desc=repair_encoding(article['description']);found,codes=audit.canonical(desc,catalogue)
            location={k:source_page[k] for k in ('path','page','merk')}
            source=dict(location,pos=article['pos'],bbox=article['bbox'],original_description=article['description'],normalized_description=desc,quantity=article['quantity'],method=source_page['method'])
            key=article['pos'] or audit.norm(desc)
            signature=(audit.norm(desc),article['quantity'])
            if key in positions:
                if positions[key]!=signature:issues.append(dict(source,code='article_position_conflict',note='Repeated position has different description/quantity; not added or summed'))
                continue
            positions[key]=signature
            fields={'Tekenbureau':'14337','Instortvoorziening':desc,'Instortvoorziening Merk':desc}
            if len(codes)==1 and found['status']!='catalogus_conflict':
                fields['Instortvoorziening']=found['basisnaam']
                fields['Instortvoorziening Merk']=found.get('tekeninggegevens','')
                units={audit.unit(r['fields'].get('eenheid') or r['fields'].get('verkoopeenheid')) for r in found['matches']}-{''}
                if len(units)==1:fields['Eenheid']=next(iter(units))
                else:issues.append(dict(source,code='catalogue_unit_unconfirmed'))
                source['catalogue_codes']=codes
            else:issues.append(dict(source,code='article_unresolved',catalogue_status=found['status'],candidates=[{'code':r['code'],'description':r['description']} for r in (found.get('matches',[]) or found.get('candidates',[]))][:5]))
            # A named length in an article is evidence for that article, never for the parent element.
            for field, label in [('Lengte',r'(?:L|LB|LG|lengte)'),('Breedte',r'(?:B|breedte)'),('Hoogte',r'(?:H|hoogte)'),('Dikte',r'(?:D|dikte)')]:
                found_dims=list(re.finditer(r'(?i)\b'+label+r'\s*[:=]\s*('+NUM+r')\s*(mm|cm|m)\b',desc))
                dims={decimal(m[1])*{'mm':Decimal(1),'cm':Decimal(10),'m':Decimal(1000)}[m[2].lower()] for m in found_dims}
                if len(dims)==1:fields[field]=text_number(next(iter(dims)))
                elif len(dims)>1:issues.append(dict(source,code='article_dimension_conflict',field=field))
            quantity=re.fullmatch(r'\s*('+NUM+r')\s*(st|stk|stuks|pcs)?\s*',article['quantity'],re.I)
            if quantity and decimal(quantity[1])>0:fields['Aantal']=text_number(decimal(quantity[1]))
            else:issues.append(dict(source,code='article_quantity_unresolved'))
            if fields.get('Eenheid','') not in ('st',''):
                issues.append(dict(source,code='article_dimension_conversion_required',unit=fields.get('Eenheid'),note='Use confirmed factory FAST rules; never treat pieces as metres or m2'))
            if article['ocr_uncertain'] or source_page['method']=='pdfcontrol_ocr_table':issues.append(dict(source,code='ocr_visual_review_required'))
            records.append({'fields':fields,'source':source,'kind':'article'})
    if not records:issues.append({'code':'no_bound_drawing_records'})
    return records,issues


def apply_reviewed(records, issues, reviewed, geometry, catalogue):
    """Bulk model-reviewed evidence and deterministic geometric arithmetic, no inferred shape."""
    allowed={'Aantal','Lengte','Breedte','Hoogte','Dikte','Dikte plaat','Totaal dikte','Inhoud','Hoofdregel','Mengsel','Fase','Instortvoorziening','Instortvoorziening Merk','Netto m2','Bruto m2','Netto m3','Bruto m3','Gewicht(kg)','Totaal lengte','Totaal breedte','Top Ja/Nee','MZ/SZ'}
    numeric={'Aantal','Lengte','Breedte','Hoogte','Dikte','Dikte plaat','Totaal dikte','Inhoud','Hoofdregel','Netto m2','Bruto m2','Netto m3','Bruto m3','Gewicht(kg)','Totaal lengte','Totaal breedte'}
    if not isinstance(reviewed,list) or len(reviewed)>500 or not isinstance(geometry,list) or len(geometry)>100:raise ValueError('Reviewed data batch exceeds limits')
    for item in reviewed:
        if not isinstance(item,dict) or set(item)-{'merk','pos','fields','evidence'} or not isinstance(item.get('fields'),dict) or not isinstance(item.get('evidence'),str) or not 8<=len(item['evidence'])<=2000:raise ValueError('Reviewed rows require merk, fields and concrete source evidence')
        candidates=[r for r in records if r['source']['merk']==item.get('merk') and (r['kind']=='article' and r['source'].get('pos')==item.get('pos') if 'pos' in item else r['kind']=='element')]
        if len(candidates)!=1:raise ValueError('Reviewed row must identify exactly one existing mark/position')
        row=candidates[0];changes=[]
        for field,value in item['fields'].items():
            if field not in allowed or (row['kind']=='article' and field in {'Hoofdregel','Mengsel','Fase','Inhoud'}) or (row['kind']=='element' and field.startswith('Instortvoorziening')):raise ValueError('Reviewed field is not applicable to this row: '+field)
            if not isinstance(value,str) or not 1<=len(value)<=300:raise ValueError('Reviewed values must be bounded text')
            if field in numeric:
                if not re.fullmatch(NUM,value) or decimal(value)<=0:raise ValueError('Reviewed numeric fields must be positive numeric values')
                if (field=='Hoofdregel' or field=='Aantal' and (row['kind']=='element' or row['fields'].get('Eenheid')=='st')) and decimal(value)!=int(decimal(value)):raise ValueError('Element/piece quantities and Hoofdregel require integers')
                value=text_number(decimal(value))
            if field=='Instortvoorziening':
                found,codes=audit.canonical(value,catalogue)
                if len(codes)!=1 or found['status']!='catalogus_uniek':raise ValueError('Reviewed import article must be one exact unambiguous factory catalogue name')
                row['source']['catalogue_codes']=codes
                units={audit.unit(r['fields'].get('eenheid') or r['fields'].get('verkoopeenheid')) for r in found['matches']}-{''}
                if len(units)==1:row['fields']['Eenheid']=next(iter(units))
            changes.append({'field':field,'old':row['fields'].get(field,''),'new':value});row['fields'][field]=value
        row.setdefault('reviewed_changes',[]).append({'evidence':item['evidence'],'changes':changes,'method':'model_review_not_independent_validation'})
        issues.append(dict(row['source'],code='reviewed_source_verification_required',evidence=item['evidence']))
    for item in geometry:
        if not isinstance(item,dict) or set(item)-{'merk','components','evidence','column'} or not {'merk','components','evidence'}<=set(item) or not isinstance(item['evidence'],str) or not 8<=len(item['evidence'])<=2000 or not isinstance(item['components'],list) or not 1<=len(item['components'])<=200:raise ValueError('Geometry requires merk, bounded components and source evidence')
        targets=[r for r in records if r['kind']=='element' and r['source']['merk']==item['merk']]
        if len(targets)!=1:raise ValueError('Geometry mark must identify one element')
        target=item.get('column','Inhoud')
        if target not in {'Inhoud','Netto m3','Bruto m3','Netto m2','Bruto m2'}:raise ValueError('Explicit area/volume target column required')
        area=target.endswith('m2');total=Decimal(0);parts=[]
        for component in item['components']:
            if not isinstance(component,dict) or set(component)-{'shape','dimensions_mm','count','subtract'}:raise ValueError('Invalid geometry component')
            shape=component.get('shape');dims=component.get('dimensions_mm');count=component.get('count',1);subtract=component.get('subtract',False)
            allowed_shapes={'rectangle':2,'triangle':2,'circle':1} if area else {'box':3,'triangular_prism':3,'cylinder':2}
            if shape not in allowed_shapes or not isinstance(dims,list) or len(dims)!=allowed_shapes[shape] or type(count) is not int or not 1<=count<=10000 or not isinstance(subtract,bool):raise ValueError('Invalid shape dimensions/count for target unit')
            if not all(isinstance(d,str) and re.fullmatch(NUM,d) and decimal(d)>0 for d in dims):raise ValueError('Positive dimensions in mm required')
            numbers=[decimal(v) for v in dims]
            pi=Decimal('3.141592653589793238462643383279502884')
            if area:volume=(numbers[0]*numbers[0]*pi/4 if shape=='circle' else numbers[0]*numbers[1]/(2 if shape=='triangle' else 1))*count/Decimal(1000000)
            else:volume=(numbers[0]*numbers[0]*numbers[1]*pi/4 if shape=='cylinder' else numbers[0]*numbers[1]*numbers[2]/(2 if shape=='triangular_prism' else 1))*count/Decimal(1000000000)
            total+=-volume if subtract else volume
            parts.append(dict(component,**{'area_m2' if area else 'volume_m3':text_number(volume)}))
        if total<=0:raise ValueError('Geometry net volume must be positive')
        row=targets[0];value=text_number(total)
        if row['fields'].get(target) and decimal(row['fields'][target])!=total:
            issues.append(dict(row['source'],code='printed_calculated_volume_conflict',field=target,printed=row['fields'][target],calculated=value))
        else:row['fields'][target]=value
        calculation={'components':parts,'column':target,'net_m2' if area else 'net_m3':value,'dimensions_unit':'mm','evidence':item['evidence'],'shape_interpretation_independently_verified':False}
        row.setdefault('geometry_calculations',[]).append(calculation);row['geometry_calculation']=calculation
        issues.append(dict(row['source'],code='geometry_source_verification_required',note='Arithmetic is deterministic; model-supplied shape/dimensions still require source verification'))
    # Remove only missing-value flags resolved by the reviewed data; keep OCR/technical conflicts.
    elements={r['source']['merk']:r for r in records if r['kind']=='element'}
    issues[:]=[i for i in issues if not ((i.get('code')=='element_field_unresolved' and elements.get(i.get('merk'),{}).get('fields',{}).get(i.get('field'))) or (i.get('code')=='hoofdregel_required' and elements.get(i.get('merk'),{}).get('fields',{}).get('Hoofdregel')))]


def column(n):
    out=''
    while n:n,r=divmod(n-1,26);out=chr(65+r)+out
    return out


def build_xlsx(template, output, records):
    """Reuse exactly the template's single-sheet columns; create a new complete selected-drawings draft."""
    import mbs_artikelcatalogus as reader
    with zipfile.ZipFile(template) as archive:
        if sum(i.file_size for i in archive.infolist())>200*1024*1024:raise ValueError('Expanded template too large')
        if len(archive.namelist())!=len(set(archive.namelist())):raise ValueError('Duplicate ZIP entries')
        workbook=ET.fromstring(archive.read('xl/workbook.xml'));sheets=workbook.find(T('sheets'))
        if sheets is None or len(sheets)!=1:raise ValueError('Select a single-sheet import template')
        rid=sheets[0].get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id')
        rels=ET.fromstring(archive.read('xl/_rels/workbook.xml.rels'));target=next((r.get('Target') for r in rels if r.get('Id')==rid),None)
        if not target:raise ValueError('Template worksheet missing')
        entry=posixpath.normpath(target.lstrip('/') if target.startswith('/') else posixpath.join('xl',target))
        if not entry.startswith('xl/worksheets/'):raise ValueError('Invalid worksheet path')
        tables=list(reader.xlsx_bladen(template));rows=tables[0][1]
        headers=[r for r in rows if {'merk','instortvoorziening','instortvoorziening merk','aantal'}<=set(audit.norm(v) for v in r['cells'])]
        if len(headers)!=1:raise ValueError('Expected one header with Merk, Instortvoorziening, Instortvoorziening Merk and Aantal')
        header=headers[0];names=header['cells'];normal=[audit.norm(n) for n in names if str(n).strip()]
        if len(normal)!=len(set(normal)):raise ValueError('Duplicate template columns')
        raw=archive.read(entry);tree=ET.fromstring(raw);data=tree.find(T('sheetData'))
        if data is None:raise ValueError('Template has no sheetData')
        if tree.find(T('mergeCells')) is not None or tree.find(T('tableParts')) is not None:raise ValueError('Merged/table-formatted template requires explicit preparation')
        for row in list(data):
            if int(row.get('r','0'))>header['row']:data.remove(row)
        written=[]
        for idx,record in enumerate(records,header['row']+1):
            node=ET.SubElement(data,T('row'),{'r':str(idx)});fields={audit.norm(k):v for k,v in record['fields'].items()}
            for j,name in enumerate(names,1):
                value=fields.get(audit.norm(name),'')
                if value=='':continue
                c=ET.SubElement(node,T('c'),{'r':column(j)+str(idx)})
                if audit.norm(name) in ('aantal','lengte','breedte','hoogte','dikte','dikte plaat','totaal dikte','inhoud','netto m2','bruto m2','netto m3','bruto m3','gewicht(kg)','totaal lengte','totaal breedte','tekenbureau','status tekening','hoofdregel') and re.fullmatch(NUM,str(value)):
                    ET.SubElement(c,T('v')).text=str(value)
                else:
                    c.set('t','inlineStr');ET.SubElement(ET.SubElement(c,T('is')),T('t')).text=str(value)
            written.append({'row':idx,**record,'unmapped_fields':[k for k,v in record['fields'].items() if v and audit.norm(k) not in normal and k not in record.get('column_mapping',{}) and k!='Eenheid']})
        dim=tree.find(T('dimension'))
        if dim is not None:dim.set('ref','A1:'+column(len(names))+str(header['row']+len(records)))
        af=tree.find(T('autoFilter'))
        if af is not None:af.set('ref','A'+str(header['row'])+':'+column(len(names))+str(header['row']+len(records)))
        # Retain namespace declarations referenced only in compatibility attributes.
        namespaces=dict(binding for _,binding in ET.iterparse(io.BytesIO(raw),events=['start-ns']))
        modified=ET.tostring(tree,encoding='utf-8',xml_declaration=True)
        for prefix,uri in namespaces.items():
            name='xmlns'+(':'+prefix if prefix else '')
            if (name+'=').encode() not in modified:tree.set(name,uri)
        modified=ET.tostring(tree,encoding='utf-8',xml_declaration=True)
        output.parent.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='drawing-import-') as tmp:
            staged=Path(tmp)/'draft.xlsx'
            with zipfile.ZipFile(staged,'w') as dest:
                for info in archive.infolist():dest.writestr(copy.copy(info),modified if info.filename==entry else archive.read(info.filename))
            # Re-read the real generated workbook, not the intended Python records.
            actual=list(reader.xlsx_bladen(staged))[0][1]
            by_row={r['row']:r['cells'] for r in actual}
            for record in written:
                cells=by_row[record['row']];expected={audit.norm(k):str(v) for k,v in record['fields'].items()}
                for j,name in enumerate(names):
                    if str(cells[j] if j<len(cells) else '')!=expected.get(audit.norm(name),''):raise ValueError('XLSX re-read differs from source records')
            with output.open('xb') as dest:dest.write(staged.read_bytes())
    return written,names


def run(request, reports, package=None):
    started=time.monotonic();package=Path(package or Path(__file__).parent).resolve()
    if str(package) not in sys.path:sys.path.insert(0,str(package))
    if set(request)-{'project','factory','drawings','catalogue','template','output','hoofdregel','ocr','mark_bindings','reviewed_rows','geometry','column_map'}:raise ValueError('Unknown drawing import arguments')
    project,factory=request.get('project'),request.get('factory')
    if not isinstance(project,str) or not re.fullmatch(r'\d{6}',project) or factory not in audit.FACTORIES:raise ValueError('Explicit six-digit project and confirmed factory required')
    selected=request.get('drawings')
    if not isinstance(selected,list) or not 1<=len(selected)<=30 or not all(isinstance(p,str) for p in selected):raise ValueError('Select 1-30 PDF drawings')
    paths=[audit.file_input(p,{'.pdf'}) for p in selected]
    if len(paths)!=len(set(paths)):raise ValueError('Duplicate drawing selection')
    catalogue_path=audit.file_input(request.get('catalogue',package/'data_lokaal'/('catalogus_'+factory+'.json')),{'.json','.xlsx'})
    import mbs_artikelcatalogus as reader
    catalogue=audit.catalog(catalogue_path,factory,reader)
    template=None;output=None
    if bool(request.get('template'))!=bool(request.get('output')):raise ValueError('Select both template and new output, or neither for extraction only')
    if request.get('template'):
        template=audit.file_input(request['template'],{'.xlsx'});output=Path(request['output']).resolve()
        if output.suffix.lower()!='.xlsx' or output==template:raise ValueError('Output must be a new distinct XLSX')
    hoofdregel=request.get('hoofdregel')
    if hoofdregel is not None and not re.fullmatch(r'\d{1,8}',str(hoofdregel)):raise ValueError('Hoofdregel must be an explicitly confirmed numeric code')
    if 'ocr' in request and not isinstance(request['ocr'],bool):raise ValueError('ocr must be boolean')
    sources={str(p):audit.digest(p) for p in paths+[catalogue_path]+([template] if template else [])}
    import hashlib
    cache_key=hashlib.sha256(json.dumps({'version':VERSION,'request':request,'sources':sources,'implementation':{str(p):audit.digest(p) for p in [Path(__file__),package/'mbs_controles_extra.py',package/'mbs_ocr_artikelen.py',package/'mbs_artikelcatalogus.py']}},sort_keys=True).encode()).hexdigest()
    reports=Path(reports);reports.mkdir(parents=True,exist_ok=True)
    path=reports/('drawing-import-'+project+'-'+cache_key+'.json')
    if path.is_file():
        cached=json.loads(path.read_text(encoding='utf-8'))
        if cached.get('summary') and (not output or (not cached.get('output') and not output.exists()) or (output.is_file() and audit.digest(output)==cached.get('output_sha256'))):
            return dict(cached['summary'],cached=True)
    if output and output.exists():raise ValueError('Output already exists or changed; select a new XLSX')
    bindings={}
    if not isinstance(request.get('mark_bindings',[]),list) or len(request.get('mark_bindings',[]))>100:raise ValueError('Maximum 100 explicit mark bindings')
    for binding in request.get('mark_bindings',[]):
        if not isinstance(binding,dict) or set(binding)!={'path','page','merk','evidence'}:raise ValueError('Binding requires path/page/merk/evidence')
        source=Path(binding['path']).resolve(strict=True);page=binding['page'];mark=binding['merk'];evidence=binding['evidence']
        if source not in paths or type(page) is not int or not 1<=page<=100 or not isinstance(mark,str) or not re.fullmatch(r'[A-Z][A-Z0-9_.-]{0,30}',mark) or not isinstance(evidence,str) or not 8<=len(evidence)<=1000:raise ValueError('Invalid drawing mark binding')
        key=(str(source),page)
        if key in bindings:raise ValueError('Duplicate drawing mark binding')
        bindings[key]=dict(merk=mark,evidence=evidence)
    pages,issues=read_pages(paths,project,request.get('ocr',True),bindings)
    if set(bindings)-{(item['path'],item['page']) for item in pages}:raise ValueError('Binding page does not exist')
    records,ri=make_records(pages,catalogue,str(hoofdregel) if hoofdregel is not None else None);issues+=ri
    apply_reviewed(records,issues,request.get('reviewed_rows',[]),request.get('geometry',[]),catalogue)
    column_map=request.get('column_map',{})
    if not isinstance(column_map,dict) or set(column_map)-{'Inhoud'} or any(value not in {'Netto m3','Bruto m3'} for value in column_map.values()):raise ValueError('Only an explicit Inhoud -> Netto m3 or Bruto m3 mapping is supported')
    for row in records:
        for source,target in column_map.items():
            if row['kind']=='element' and row['fields'].get(source):
                if row['fields'].get(target) and decimal(row['fields'][target])!=decimal(row['fields'][source]):issues.append(dict(row['source'],code='volume_column_mapping_conflict',field=target))
                else:row['fields'][target]=row['fields'][source];row['column_mapping']=column_map

    report={'version':VERSION,'project':project,'factory':factory,'status':'review_required','pages':pages,'records':records,'issues':issues,'sources_sha256':sources,
       'api_requests':0,'production_sources_changed':False,'server_copy_performed':False,'ready_for_isah':False,
       'limitations':['Draft only. Verify source role/revision, geometry, OCR, quantities per element versus totals, factory units and project decisions.',
          'Automatic extraction uses explicit labelled dimensions. Reviewed field values and component geometry are model-supplied evidence, not independently proved geometry. Hoogte is not Dikte; irregular shapes are not cuboids.',
          'No guessed Hoofdregel, Mengsel, Fase, technical alias or replacement of PIR/EPS/EV/TV/RVS. Missing values stay blank.']}
    if any(audit.digest(Path(p))!=sha for p,sha in sources.items()):raise ValueError('A source changed during extraction; no workbook created')
    if output and records:
        report['written_rows'],report['template_columns']=build_xlsx(template,output,records);report['output']=str(output);report['output_sha256']=audit.digest(output)
        for row in report['written_rows']:
            if row['unmapped_fields']:issues.append(dict(row['source'],code='fields_not_in_template',fields=row['unmapped_fields']))
        if 'Inhoud' not in report['template_columns'] and any(r['fields'].get('Inhoud') for r in records) and not column_map:issues.append({'code':'volume_column_mapping_required','choices':[c for c in report['template_columns'] if c in ('Netto m3','Bruto m3')],'note':'Select the documented project convention; never copy one volume blindly into both columns'})
    elif output:report['requested_output']=str(output)
    if any(audit.digest(Path(p))!=sha for p,sha in sources.items()):
        if output and output.is_file() and report.get('output_sha256')==audit.digest(output):output.unlink()
        raise ValueError('A source changed during processing; generated draft was discarded')
    counts=dict(collections.Counter(i['code'] for i in issues))
    summary={'tool':'pdfcontrol_drawing_import','cached':False,'project':project,'factory':factory,'status':'review_required','report_json':str(path),'output':report.get('output'),
        'pages':len(pages),'elements':sum(r['kind']=='element' for r in records),'articles':sum(r['kind']=='article' for r in records),'extracted_table_rows':sum(len(p['articles']) for p in pages),
        'template_columns':report.get('template_columns',[]),'issue_counts':counts,'examples':issues[:6],'elapsed_seconds':round(time.monotonic()-started,3),'api_requests':0,'source_unchanged':True,'ready_for_isah':False,
        'next_step':'Read only the required report fragments. Resolve the exact missing evidence, compare the XLSX with pdfcontrol_compare_import, then apply confirmed FAST rules. Do not rerun unchanged batches or claim production approval.'}
    report['summary']=summary;path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    return summary


def main():
    p=argparse.ArgumentParser();p.add_argument('--request',required=True);p.add_argument('--reports',required=True);args=p.parse_args()
    logs=io.StringIO()
    try:
        with contextlib.redirect_stdout(logs),contextlib.redirect_stderr(logs):
            result=run(json.loads(Path(args.request).read_text(encoding='utf-8-sig')),args.reports)
        if logs.getvalue():
            report=Path(result['report_json']);data=json.loads(report.read_text(encoding='utf-8'));data['worker_diagnostics']=logs.getvalue()[-4000:];report.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result,ensure_ascii=True))
    except Exception as error:
        print(json.dumps({'error':str(error),'diagnostics':logs.getvalue()[-1000:]},ensure_ascii=True));raise SystemExit(1)
if __name__=='__main__':main()
