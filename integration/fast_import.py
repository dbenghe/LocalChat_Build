"""Prepare a separate FAST XLSX copy using explicitly saved factory/article rules. No API."""
import argparse,hashlib,json,os,re,tempfile,zipfile,posixpath,copy,io
from decimal import Decimal,ROUND_HALF_DOWN
from pathlib import Path
from xml.etree import ElementTree as ET
NS='http://schemas.openxmlformats.org/spreadsheetml/2006/main'
T=lambda n:'{'+NS+'}'+n
PIR=re.compile(r'PIR\s+ISO\s*(\d+(?:[.,]\d+)?)\s*[*x\u00d7]\s*(\d+(?:[.,]\d+)?)\s*[*x\u00d7]\s*(\d+(?:[.,]\d+)?)',re.I)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def prepare(request,rules_path):
 source=Path(request['input']).resolve(strict=True);output=Path(request['output']).resolve();report=Path(str(output)+'.fast-audit.json')
 if source.suffix.lower()!='.xlsx' or output.suffix.lower()!='.xlsx' or source==output:raise ValueError('Select distinct input/output XLSX files')
 if source.stat().st_size>100*1024*1024:raise ValueError('XLSX too large')
 if output.exists() or report.exists():raise ValueError('Output already exists; choose a new name')
 source_hash=sha(source)
 if source_hash!=request['source_sha256']:raise ValueError('Source changed before processing')
 rules=json.loads(Path(rules_path).read_text(encoding='utf-8-sig'));matched=[r for r in rules.get('rules',[]) if r.get('factory')==request['factory']]
 if not matched:raise ValueError('No confirmed FAST rule for this factory')
 by_article={}
 for r in matched:
  if r.get('calculation')!='pir_area_as_fast_length' or r.get('units_per_metre')!=1000 or r.get('rounding')!='half_down':raise ValueError('Unsupported confirmed rule')
  if r['article'] in by_article:raise ValueError('Ambiguous saved article rule')
  by_article[r['article']]=r
 with zipfile.ZipFile(source) as z:
  if sum(i.file_size for i in z.infolist())>200*1024*1024:raise ValueError('Expanded XLSX too large')
  if len(z.namelist())!=len(set(z.namelist())):raise ValueError('Duplicate ZIP entries')
  wb=ET.fromstring(z.read('xl/workbook.xml'));sheets=wb.find(T('sheets'))
  if sheets is None or len(sheets)!=1:raise ValueError('Expected exactly one import sheet')
  rid=sheets[0].get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id')
  rel=ET.fromstring(z.read('xl/_rels/workbook.xml.rels'));target=next((n.get('Target') for n in rel if n.get('Id')==rid),None)
  if not target:raise ValueError('Missing worksheet')
  entry=posixpath.normpath(target.lstrip('/') if target.startswith('/') else posixpath.join('xl',target))
  if not entry.startswith('xl/worksheets/'):raise ValueError('Invalid sheet relationship')
  shared=[]
  if 'xl/sharedStrings.xml' in z.namelist():
   shared=[''.join(t.text or '' for t in n.iter(T('t'))) for n in ET.fromstring(z.read('xl/sharedStrings.xml'))]
  original_xml=z.read(entry);namespaces={}
  for _,binding in ET.iterparse(io.BytesIO(original_xml),events=['start-ns']):
   prefix,uri=binding
   if prefix in namespaces and namespaces[prefix]!=uri:raise ValueError('Rebound XML namespace requires review')
   namespaces[prefix]=uri
   try:ET.register_namespace(prefix,uri)
   except ValueError:pass
  tree=ET.fromstring(original_xml);data=tree.find(T('sheetData'))
  if data is None:raise ValueError('Missing worksheet rows')
  def value(c):
   if c is None:return ''
   if c.find(T('f')) is not None:raise ValueError('Formula in relevant import cell')
   if c.get('t')=='inlineStr':return ''.join(n.text or '' for n in c.iter(T('t')))
   v=c.find(T('v'));text='' if v is None else v.text or ''
   return shared[int(text)] if c.get('t')=='s' else text
  def columns(row):return {re.sub(r'\d','',c.get('r','')):c for c in row if c.tag==T('c')}
  rows=list(data);headers={value(c).strip():col for col,c in columns(rows[0]).items()}
  required=['Merk','Instortvoorziening','Aantal','Lengte','Instortvoorziening Merk']
  if any(n not in headers for n in required):raise ValueError('Missing required import columns')
  if len(headers)!=len(columns(rows[0])):raise ValueError('Duplicate import headers')
  changes=[];mark='';allowed=[]
  for row in rows[1:]:
   cols=columns(row);get=lambda n:value(cols.get(headers[n]))
   mark=get('Merk') or mark;article=get('Instortvoorziening')
   if article not in by_article:continue
   original=get('Instortvoorziening Merk');m=PIR.fullmatch(original.strip())
   if not m:raise ValueError('Unrecognized original PIR dimensions at row '+row.get('r',''))
   thickness,width,length=[Decimal(x.replace(',','.')) for x in m.groups()];qty=Decimal(get('Aantal').replace(',','.'))
   if min(thickness,width,length,qty)<=0 or qty!=qty.to_integral_value():raise ValueError('Invalid dimensions/piece count')
   exact=width*length/Decimal(1000000);scaled=exact*1000;fast=int(scaled.quantize(Decimal(1),rounding=ROUND_HALF_DOWN))
   if fast<=0:raise ValueError('Rounded quantity is zero; explicit review required')
   key=headers['Lengte'];cell=cols.get(key);old=get('Lengte')
   if cell is None:
    cell=ET.SubElement(row,T('c'),{'r':key+row.get('r')})
    # Keep cells ordered by numeric spreadsheet column index.
    def index(c):
     n=0
     for char in re.sub(r'\d','',c.get('r','')):n=n*26+ord(char)-64
     return n
    row[:]=sorted([c for c in row if c.tag==T('c')],key=index)+[c for c in row if c.tag!=T('c')]
   for child in list(cell):cell.remove(child)
   cell.attrib.pop('t',None);ET.SubElement(cell,T('v')).text=str(fast)
   changes.append({'row':int(row.get('r')),'mark':mark,'original':original,'Aantal':str(qty),'previous_Lengte':old,'m2_per_piece':str(exact),'FAST_exact':str(scaled),'FAST_integer':fast,'difference_m2_per_piece':str(Decimal(fast)/1000-exact)})
   allowed.append(cell.get('r'))
  if not changes:raise ValueError('No articles match the confirmed factory rules')
  modified=ET.tostring(tree,encoding='utf-8',xml_declaration=True)
  # Preserve namespace bindings referenced only by mc:Ignorable or similar attribute values.
  for prefix,uri in namespaces.items():
   name='xmlns'+(':'+prefix if prefix else '')
   if (name+'=').encode() not in modified:tree.set(name,uri)
  modified=ET.tostring(tree,encoding='utf-8',xml_declaration=True)
  # Reparse and verify integer numeric cells before creating any output.
  check=ET.fromstring(modified)
  actual={c.get('r'):value(c) for row in check.find(T('sheetData')) for c in row if c.tag==T('c') and c.get('r') in allowed}
  if any(not re.fullmatch(r'\d+',actual[k]) for k in allowed):raise ValueError('FAST integer validation failed')
  output.parent.mkdir(parents=True,exist_ok=True)
  fd,tmp=tempfile.mkstemp(suffix='.xlsx',dir=output.parent);os.close(fd);tmp=Path(tmp)
  try:
   with zipfile.ZipFile(tmp,'w') as dest:
    for info in z.infolist():dest.writestr(copy.copy(info),modified if info.filename==entry else z.read(info.filename))
   with zipfile.ZipFile(tmp) as dest:
    if set(dest.namelist())!=set(z.namelist()) or any(dest.read(n)!=z.read(n) for n in z.namelist() if n!=entry):raise ValueError('Unrelated XLSX parts changed')
   if sha(source)!=source_hash:raise ValueError('Source changed during processing')
   # Exclusive copy: never overwrite an existing production or user file.
   with output.open('xb') as dest,tmp.open('rb') as inp:
    while chunk:=inp.read(1024*1024):dest.write(chunk)
   audit={'factory':request['factory'],'project':request['project'],'source':str(source),'source_sha256':source_hash,'output':str(output),'output_sha256':sha(output),'rounding':'fraction > .5 up; .5 and below down','units_per_metre':1000,'changes':changes,'changed_column':'Lengte','original_quantities_and_descriptions_preserved':True}
   with report.open('x',encoding='utf-8') as f:json.dump(audit,f,ensure_ascii=False,indent=2)
   return {'output':str(output),'audit':str(report),'changed_rows':len(changes),'factory':request['factory'],'api_requests':0,'source_unchanged':sha(source)==source_hash}
  finally:tmp.unlink(missing_ok=True)
def main():
 p=argparse.ArgumentParser();p.add_argument('--request',required=True);p.add_argument('--rules',required=True);a=p.parse_args()
 try:print(json.dumps(prepare(json.loads(Path(a.request).read_text(encoding='utf-8-sig')),a.rules),ensure_ascii=True))
 except Exception as e:print(json.dumps({'error':str(e)},ensure_ascii=True));raise SystemExit(1)
if __name__=='__main__':main()
