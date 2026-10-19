"""Stage the existing private engine and the versioned bridge; never upload legacy data."""
import argparse,hashlib,json,shutil
from pathlib import Path

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--package',required=True);parser.add_argument('--original',default='C:/PDFCONTROL');args=parser.parse_args()
    package=Path(args.package).resolve();original=Path(args.original).resolve();integration=Path(__file__).resolve().parent
    package.mkdir(parents=True,exist_ok=True)
    if (original/'mbs_pdf_checker_pdfcontrol.py').is_file() and original!=package:
        for file in original.glob('*.py'):
            if not file.name.startswith('test_'):shutil.copy2(file,package/file.name)
        for name in ['bureaus.json','bureaus_prive.json','mbs_artikelnamen.json','mbs_catalogus.json','kennisbank.json','lower_right_ignored_labels.json']:
            if (original/name).is_file():shutil.copy2(original/name,package/name)
        manifest={file.name:hashlib.sha256(file.read_bytes()).hexdigest() for file in original.glob('*.py') if not file.name.startswith('test_')}
        (package/'localchat_engine_manifest.json').write_text(json.dumps({'origin':'Original PDFCONTROL engine','sha256':manifest},indent=2),encoding='utf-8')
    manifest=package/'localchat_engine_manifest.json'
    if not manifest.is_file():raise SystemExit('Original engine manifest missing. Import the original PDFCONTROL package first.')
    for name,digest in json.loads(manifest.read_text(encoding='utf-8'))['sha256'].items():
        file=package/name
        if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest()!=digest:raise SystemExit('Engine differs from original manifest: '+name)
    shutil.copy2(integration/'pdfcontrol_bridge.py',package/'octop_mcp.py')
    print('PASS: original engine manifest verified; complete bridge staged.')

if __name__=='__main__':main()
