"""Synthetic regression tests for the owned engine; no production documents."""
import argparse,sys,unittest,tempfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--package',required=True);args=p.parse_args();sys.path.insert(0,str(Path(args.package).resolve()))
import pymupdf
import mbs_tekeningstatus as status
import mbs_webapp as web


def drawing(value='DEFINITIEF',role='W',project='123456',mark='TRK300',legend=False):
    doc=pymupdf.open();page=doc.new_page(width=1190,height=842)
    for point,text,size in [((730,725),'OPDRACHTGEVER',10),((1100,770),'WERKNO',8),((1100,798),'BLADNO',8),((841,760),'merk',12),((1010,640),'MBS Specials nr.',10),((1010,657),project,10),((1100,783),'654321',10),((1100,815),mark,10),((875,760),mark,12)]:page.insert_text(point,text,fontsize=size)
    if value:page.insert_text((860,810),value,fontsize=14)
    if role=='W':
        for i in range(10):page.insert_text((50,60+i*15),'hrsp '+chr(216)+'8-150',fontsize=10)
    if legend:page.insert_text((60,400),'DEFINITIEF',fontsize=18)
    return doc

class OwnedLayout(unittest.TestCase):
    def test_final_status_in_actual_current_cell(self):
        for value in ('DEFINITIEF','VORM DEFINITIEF','Definiitief','DEF'):
            with drawing(value) as doc:self.assertTrue(status.lees_status(doc[0])['ok'])
    def test_vorm_definitief_is_a_common_final_status(self):
        for value in ('VORM DEFINITIEF','vorm definitief','VORMDEFINITIEF','VORM  DEFINITIEF','VORM\nDEFINITIEF','VORM-DEFINITIEF','VORM DEF'):
            self.assertTrue(status.is_definitief(value),value)
        for value in ('NIET DEFINITIEF','VORM NIET DEFINITIEF','VORM CONCEPT','VORM DEFINITIEF TER CONTROLE','VORM VOORLOPIG','VORMDEFINITIEFNOGNIET','VORM DEFINITIEF / CONCEPT'):
            self.assertFalse(status.is_definitief(value),value)
    def test_generic_status_cell_accepts_vorm_definitief(self):
        for value in ('VORM DEFINITIEF','VORMDEFINITIEF','NIET DEFINITIEF','VORM CONCEPT'):
            with pymupdf.open() as doc:
                page=doc.new_page(width=842,height=595);page.insert_text((550,530),'STATUS:',fontsize=12);page.insert_text((615,530),value,fontsize=12)
                found=status.lees_status(page);self.assertEqual(found['ok'],value in ('VORM DEFINITIEF','VORMDEFINITIEF'),(value,found))

    def test_complete_report_keeps_ok_drawings_and_honest_counts(self):
        import mbs_rapport,json
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);rows=[]
            for i,ok in enumerate((True,True,False)):
                name=f'123456_TRK30{i}_V.pdf';attachment=root/name
                with pymupdf.open() as doc:
                    doc.new_page().insert_text((50,60),f'ATTACHMENT_{i}');doc.save(attachment)
                rows.append({'bestand':name,'uitvoer':str(attachment),'bureau':'Synthetic','project':'123456','status':'OK' if ok else 'NIET_OK',
                    'tekeningstatus':{'ok':ok,'tekst':'STATUS: VORM DEFINITIEF; definitief.' if ok else 'STATUS: CONCEPT; niet definitief.'},
                    'artikelen':[{'omschrijving':'Status tekening','status':'OK' if ok else 'NIET_OK','note':'VORM DEFINITIEF' if ok else 'CONCEPT'}],
                    'bevindingen':[] if ok else [{'code':'STATUS_NIET_DEFINITIEF','ernst':'FOUT','artikel':'Status tekening','tekst':'CONCEPT'}]})
            data=root/'resultaten.json';data.write_text(json.dumps({'tekeningen':rows,'bijgewerkt':'fixture'}),encoding='utf-8')
            old=(mbs_rapport.CHECKED,mbs_rapport.GEGEVENS)
            try:
                mbs_rapport.CHECKED=str(root);mbs_rapport.GEGEVENS=str(data)
                full=mbs_rapport.genereer(str(root/'full.pdf'),tekeningen_bijvoegen=True,alleen_problemen=False)
                self.assertEqual((full['tekeningen'],full['met_bevinding'],full['zonder_bevinding'],full['scope']),(3,1,2,'all'))
                with pymupdf.open(full['pad']) as doc:
                    text='\n'.join(page.get_text() for page in doc)
                    self.assertIn('1 met een bevinding',text);self.assertIn('2 zonder bevinding',text)
                    self.assertIn('Uitgevoerde controles per tekening',text)
                    for i in range(3):self.assertIn(f'ATTACHMENT_{i}',text)
                problems=mbs_rapport.genereer(str(root/'findings.pdf'),tekeningen_bijvoegen=True)
                self.assertEqual((problems['tekeningen'],problems['met_bevinding'],problems['zonder_bevinding']),(1,1,2))
                with pymupdf.open(problems['pad']) as doc:
                    text='\n'.join(page.get_text() for page in doc)
                    self.assertIn('ATTACHMENT_2',text);self.assertNotIn('ATTACHMENT_0',text)
            finally:mbs_rapport.CHECKED,mbs_rapport.GEGEVENS=old

    def test_revision_legend_and_empty_cell_are_not_final(self):
        for value in ('','TER CONTROLE','NIET DEFINITIEF','CONCEPT'):
            with drawing(value,legend=True) as doc:self.assertFalse(status.lees_status(doc[0])['ok'])
    def test_same_sheet_under_saved_rotations(self):
        for angle in (0,90,180,270):
            with drawing() as doc:
                doc[0].set_rotation(angle);self.assertTrue(status.lees_status(doc[0],0)['ok']);self.assertEqual(doc[0].rotation,angle)
    def test_title_identity_and_real_reinforcement(self):
        for role,value in [('V','VORM DEFINITIEF'),('W','DEFINITIEF')]:
            with drawing(value,role) as doc:
                text=doc[0].get_text();self.assertEqual(web.classificeer_stempel(text)['rol'],role)
                self.assertEqual(web.project_van_blad(text),'123456_')
    def test_bare_name_does_not_prove_w(self):
        with drawing('DEFINITIEF','V') as doc:self.assertNotEqual((web.classificeer_stempel(doc[0].get_text()) or {}).get('rol'),'W')
    def test_other_project_and_wrong_mark_stay_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            for project,mark in [('999999','TRK300'),('123456','TRK301')]:
                with drawing(project=project,mark=mark) as doc:doc.save(Path(folder)/'123456_TRK300_W.pdf')
                self.assertFalse(web._bevestigd_w_bestand(folder,'123456_TRK300_W.pdf','TRK300','123456'))
                (Path(folder)/'123456_TRK300_W.pdf').unlink()
    def test_real_w_can_pair_without_relabelling_source(self):
        with tempfile.TemporaryDirectory() as folder:
            with drawing() as doc:doc.save(Path(folder)/'123456_TRK300_W.pdf')
            with drawing('VORM DEFINITIEF','V') as doc:doc.save(Path(folder)/'123456_TRK300_V.pdf')
            self.assertTrue(web._bevestigd_w_bestand(folder,'123456_TRK300_W.pdf','TRK300','123456'))
            self.assertEqual(web.plan_wapening(folder)[0]['soort'],'aanwezig')

if __name__=='__main__':unittest.main(argv=[sys.argv[0]],verbosity=2)
