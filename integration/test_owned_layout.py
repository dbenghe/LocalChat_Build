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
