import sys,tempfile,unittest,hashlib,json
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
import drawing_page as tool
import pymupdf
class PageTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.pdf=self.root/'fixture.pdf'
  with pymupdf.open() as d:
   for number in range(2):
    p=d.new_page(width=800,height=600);p.insert_text((30,50),'Synthetic page '+str(number+1)+'; printed length 1000 mm; width 800 mm; not pixel measurements. '*2)
   d.save(self.pdf)
  self.before=self.pdf.read_bytes()
 def tearDown(self):self.tmp.cleanup()
 def render(self,**kw):return tool.render(dict(path=str(self.pdf),**kw),self.root/'cache')
 def test_page(self):
  a=self.render(page=2);self.assertIn('page 2',a['text']);self.assertEqual(a['page_count'],2);self.assertEqual(max(a['width'],a['height']),2200);self.assertTrue(Path(a['image_path']).is_file());self.assertEqual(a['api_requests'],0);self.assertEqual(self.before,self.pdf.read_bytes());self.assertEqual(a['source_sha256'],hashlib.sha256(self.before).hexdigest())
 def test_crop(self):
  a=self.render(bbox=[20,20,300,100],max_edge=1000);self.assertEqual(a['bbox'],[20.,20.,300.,100.]);self.assertTrue(990<=max(a['width'],a['height'])<=1000);self.assertIn('printed',a['text'])
 def test_rejection(self):
  for args in [dict(page=0),dict(page=3),dict(page=True),dict(max_edge=4000),dict(bbox=[0,0,900,900]),dict(bbox=[0,0,float('nan'),10]),dict(bbox=[0,0,1])]:
   with self.subTest(args=args),self.assertRaises((ValueError,TypeError)):self.render(**args)
 def test_rotated(self):
  with pymupdf.open(self.pdf) as d:
   d[0].insert_text((690,300),'EDGE_MARKER');d[0].set_rotation(90);d.save(self.root/'rotate.pdf')
  a=tool.render(dict(path=str(self.root/'rotate.pdf')),self.root/'cache');self.assertEqual(a['rotation'],90);self.assertEqual(a['page_rect'],[0.,0.,600.,800.]);self.assertIn('EDGE_MARKER',a['text'])
 def test_repeat(self):
  a=self.render();b=self.render();self.assertEqual(a,b);self.assertEqual(self.before,self.pdf.read_bytes())
if __name__=='__main__':unittest.main()
