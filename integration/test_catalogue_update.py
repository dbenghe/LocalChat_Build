"""Factory isolation and catalogue deltas on synthetic XLSX only."""
import hashlib,importlib.util,json,sys,tempfile,unittest
from pathlib import Path
from zipfile import ZipFile
ENGINE=Path(__file__).resolve().parents[1]/'pdfcontrol';sys.path.insert(0,str(ENGINE));import mbs_artikelcatalogus as reader
spec=importlib.util.spec_from_file_location('catalogue_update',Path(__file__).with_name('catalogue_update.py'));up=importlib.util.module_from_spec(spec);spec.loader.exec_module(up)
def xlsx(path,rows):
 headers=['Artikel','Omschrijving','Eenheid','Artikelstatus'];rows=[headers]+rows
 cells=[]
 for i,row in enumerate(rows,1):
  cells.append('<row r="%d">'%i+''.join('<c r="%s%d" t="inlineStr"><is><t>%s</t></is></c>'%(chr(65+j),i,v) for j,v in enumerate(row))+'</row>')
 with ZipFile(path,'w') as z:
  z.writestr('xl/workbook.xml','<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Fixture" sheetId="1" r:id="s"/></sheets></workbook>')
  z.writestr('xl/_rels/workbook.xml.rels','<Relationships><Relationship Id="s" Target="worksheets/sheet1.xml"/></Relationships>')
  z.writestr('xl/worksheets/sheet1.xml','<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'+''.join(cells)+'</sheetData></worksheet>')
class CatalogueUpdates(unittest.TestCase):
 def test_factory_loader_all_four_and_no_fallback(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);state=root/'123456/Controle/PDFCONTROL/project-state.json';state.parent.mkdir(parents=True);catalogues=root/'catalogues';catalogues.mkdir()
   for factory in ['soest','alphen','veenoord','lelystad']:
    source=root/(factory+'.xlsx');xlsx(source,[['MBS1','Fixture article','st','Actief']]);target=catalogues/('catalogus_'+factory+'.json');up.update(source,factory,target,reader,True);state.write_text(json.dumps({'project':'123456','factory':factory}),encoding='utf-8');loaded=reader.catalogus_voor_project('123456',root,catalogues);self.assertEqual(loaded.data['factory'],factory)
   (catalogues/'catalogus_veenoord.json').unlink();state.write_text(json.dumps({'project':'123456','factory':'veenoord'}));self.assertIsNone(reader.catalogus_voor_project('123456',root,catalogues))
   state.write_text(json.dumps({'project':'123456','destinations':[{'factory':'soest'},{'factory':'alphen'}]}));self.assertIsNone(reader.catalogus_voor_project('123456',root,catalogues))
 def test_delta_archive_original_and_explicit_absence_review(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);source=root/'source.xlsx';target=root/'catalogus_veenoord.json';xlsx(source,[['MBS1','Original','st','Actief'],['MBS2','Second','st','Actief']]);before=source.read_bytes();up.update(source,'veenoord',target,reader,True);self.assertEqual(source.read_bytes(),before);old=target.read_bytes()
   xlsx(source,[['MBS1','Changed','m','Inactive'],['MBS3','New','st','Actief']]);preview,changes=up.update(source,'veenoord',target,reader);self.assertEqual((preview['new_codes'],preview['changed_codes'],preview['absent_codes']),(1,1,1));self.assertEqual(target.read_bytes(),old)
   with self.assertRaises(ValueError):up.update(source,'veenoord',target,reader,True)
   self.assertEqual(target.read_bytes(),old);up.update(source,'veenoord',target,reader,True,True);self.assertTrue((root/'catalogue_sources/veenoord'/(hashlib.sha256(before).hexdigest()+'.xlsx')).is_file())
   with self.assertRaises(ValueError):up.update(source,'alphen',target,reader,True)
 def test_conflicting_codes_block_apply(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);source=root/'source.xlsx';xlsx(source,[['MBS1','Original','st','Actief'],['MBS1','Different','m','Actief']]);target=root/'catalogus_veenoord.json'
   with self.assertRaises(ValueError):up.update(source,'veenoord',target,reader,True)
   self.assertFalse(target.exists())
if __name__=='__main__':unittest.main(verbosity=2)
