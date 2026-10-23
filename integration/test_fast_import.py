import importlib.util,unittest,tempfile,json,zipfile,hashlib
from pathlib import Path
from xml.etree import ElementTree as ET
spec=importlib.util.spec_from_file_location('fast',Path(__file__).with_name('fast_import.py'));fast=importlib.util.module_from_spec(spec);spec.loader.exec_module(fast)
def fixture(path,description='PIR ISO80*160*1420',qty='2',old='0.2272'):
 ns=fast.NS;root=ET.Element(fast.T('worksheet'));data=ET.SubElement(root,fast.T('sheetData'))
 rows=[['Merk','Instortvoorziening','Aantal','Lengte','Instortvoorziening Merk'],['P1','Fixture thermal edge',qty,old,description],['P2','Other article','3','1250','untouched']]
 for r,values in enumerate(rows,1):
  row=ET.SubElement(data,fast.T('row'),{'r':str(r)})
  for c,v in enumerate(values):
   cell=ET.SubElement(row,fast.T('c'),{'r':chr(65+c)+str(r),'t':'inlineStr'});ET.SubElement(ET.SubElement(cell,fast.T('is')),fast.T('t')).text=v
 with zipfile.ZipFile(path,'w') as z:
  z.writestr('xl/worksheets/sheet1.xml',ET.tostring(root))
  z.writestr('xl/workbook.xml','<workbook xmlns="'+ns+'" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="importlijst" sheetId="1" r:id="r1"/></sheets></workbook>')
  z.writestr('xl/_rels/workbook.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Target="worksheets/sheet1.xml" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"/></Relationships>')
  z.writestr('xl/styles.xml','untouched fixture styles')
 return path
class Tests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.source=fixture(self.root/'source.xlsx');self.rules=self.root/'rules.json';self.rules.write_text(json.dumps({'rules':[{'factory':'veenoord','article':'Fixture thermal edge','calculation':'pir_area_as_fast_length','units_per_metre':1000,'rounding':'half_down'}]}));self.req={'project':'123456','factory':'veenoord','input':str(self.source),'output':str(self.root/'out.xlsx'),'source_sha256':fast.sha(self.source)}
 def tearDown(self):self.temp.cleanup()
 def test_rounding_and_source_preservation(self):
  for text,expected in [('PIR ISO80*160*300',48),('PIR ISO80*160*1420',227),('PIR ISO80*230*1550',356),('PIR ISO80*230*1250',287),('PIR ISO80*230*960',221),('PIR ISO80*230*2780',639)]:
   with self.subTest(text=text):
    fixture(self.source,text);self.req['source_sha256']=fast.sha(self.source);out=self.root/(str(expected)+'.xlsx');self.req['output']=str(out);before=self.source.read_bytes();r=fast.prepare(self.req,self.rules);a=json.loads(Path(r['audit']).read_text());self.assertEqual(a['changes'][0]['FAST_integer'],expected);self.assertEqual(a['changes'][0]['Aantal'],'2');self.assertEqual(a['changes'][0]['original'],text);self.assertEqual(self.source.read_bytes(),before)
    with zipfile.ZipFile(out) as z,zipfile.ZipFile(self.source) as src:
     for n in src.namelist():
      if n!='xl/worksheets/sheet1.xml':self.assertEqual(z.read(n),src.read(n))
     tree=ET.fromstring(z.read('xl/worksheets/sheet1.xml'));cells={c.get('r'):c for c in tree.iter(fast.T('c'))};self.assertEqual(cells['D2'].find(fast.T('v')).text,str(expected));self.assertEqual(ET.tostring(cells['E2']),ET.tostring(ET.fromstring(src.read('xl/worksheets/sheet1.xml')).find('.//'+fast.T('c')+'[@r="E2"]')))
 def test_namespace_binding_used_only_by_ignorable_is_preserved(self):
  import re
  with zipfile.ZipFile(self.source) as z:parts={n:z.read(n) for n in z.namelist()}
  sheet=parts['xl/worksheets/sheet1.xml']
  sheet=re.sub(rb'(<(?:[a-zA-Z0-9_]+:)?worksheet)(\s)',rb'\1 xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:x14ac="http://schemas.microsoft.com/office/spreadsheetml/2009/9/ac" mc:Ignorable="x14ac"\2',sheet,count=1)
  parts['xl/worksheets/sheet1.xml']=sheet
  with zipfile.ZipFile(self.source,'w') as z:
   for name,data in parts.items():z.writestr(name,data)
  self.req['source_sha256']=fast.sha(self.source);fast.prepare(self.req,self.rules)
  with zipfile.ZipFile(self.req['output']) as z:
   xml=z.read('xl/worksheets/sheet1.xml');self.assertIn(b'xmlns:x14ac=',xml);self.assertIn(b'Ignorable="x14ac"',xml);ET.fromstring(xml)
 def test_formula_target_rejected(self):
  with zipfile.ZipFile(self.source) as z:parts={n:z.read(n) for n in z.namelist()}
  tree=ET.fromstring(parts['xl/worksheets/sheet1.xml']);cell=tree.find('.//'+fast.T('c')+'[@r="D2"]');ET.SubElement(cell,fast.T('f')).text='1+1';parts['xl/worksheets/sheet1.xml']=ET.tostring(tree)
  with zipfile.ZipFile(self.source,'w') as z:
   for name,data in parts.items():z.writestr(name,data)
  self.req['source_sha256']=fast.sha(self.source);self.assertRaises(ValueError,fast.prepare,self.req,self.rules)
 def test_no_overwrite(self):
  out=Path(self.req['output']);out.write_text('keep');self.assertRaises(ValueError,fast.prepare,self.req,self.rules);self.assertEqual(out.read_text(),'keep')
 def test_no_in_place(self):self.req['output']=str(self.source);self.assertRaises(ValueError,fast.prepare,self.req,self.rules)
 def test_unknown_factory(self):self.req['factory']='soest';self.assertRaises(ValueError,fast.prepare,self.req,self.rules)
 def test_ambiguous_dimensions(self):fixture(self.source,'PIR ISO80*160');self.req['source_sha256']=fast.sha(self.source);self.assertRaises(ValueError,fast.prepare,self.req,self.rules);self.assertFalse(Path(self.req['output']).exists())
 def test_changed_source(self):self.req['source_sha256']='old';self.assertRaises(ValueError,fast.prepare,self.req,self.rules)
 def test_invalid_piece_count(self):fixture(self.source,qty='1.5');self.req['source_sha256']=fast.sha(self.source);self.assertRaises(ValueError,fast.prepare,self.req,self.rules)
 def test_no_round_to_zero(self):fixture(self.source,'PIR ISO80*1*1');self.req['source_sha256']=fast.sha(self.source);self.assertRaises(ValueError,fast.prepare,self.req,self.rules)
 def test_unknown_rule(self):d=json.loads(self.rules.read_text());d['rules'][0]['rounding']='guess';self.rules.write_text(json.dumps(d));self.assertRaises(ValueError,fast.prepare,self.req,self.rules)
if __name__=='__main__':unittest.main(verbosity=2)
