"""Owned engine builds never refresh from the legacy application implicitly."""
import hashlib,json,subprocess,sys,tempfile,unittest
from pathlib import Path
SCRIPT=Path(__file__).with_name('sync_engine.py')
class OwnedBuild(unittest.TestCase):
    def test_default_preserves_owned_engine_and_validates_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);engine=root/'engine';engine.mkdir();source=engine/'mbs_pdf_checker_pdfcontrol.py';source.write_text('owned source',encoding='utf-8')
            manifest={'origin':'owned test','sha256':{source.name:hashlib.sha256(source.read_bytes()).hexdigest()}}
            (engine/'localchat_engine_manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
            result=subprocess.run([sys.executable,'-I',str(SCRIPT),'--package',str(engine)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(source.read_text(),'owned source');self.assertTrue((engine/'octop_mcp.py').is_file())
            source.write_text('intentional unregistered edit',encoding='utf-8')
            result=subprocess.run([sys.executable,'-I',str(SCRIPT),'--package',str(engine)],capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0);self.assertEqual(source.read_text(),'intentional unregistered edit')
    def test_legacy_import_requires_explicit_source(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);engine=root/'engine';legacy=root/'legacy';legacy.mkdir();(legacy/'mbs_pdf_checker_pdfcontrol.py').write_text('explicit fixture',encoding='utf-8')
            result=subprocess.run([sys.executable,'-I',str(SCRIPT),'--package',str(engine)],capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0);self.assertFalse((engine/'mbs_pdf_checker_pdfcontrol.py').exists())
            result=subprocess.run([sys.executable,'-I',str(SCRIPT),'--package',str(engine),'--import-original',str(legacy)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr);self.assertEqual((engine/'mbs_pdf_checker_pdfcontrol.py').read_text(),'explicit fixture')
if __name__=='__main__':unittest.main(verbosity=2)
