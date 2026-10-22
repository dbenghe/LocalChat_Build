"""Stage the owned private engine; importing the legacy installation is explicit only."""
import argparse,hashlib,json,shutil
from pathlib import Path


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--package',required=True)
    parser.add_argument('--import-original',type=Path,help='Explicit migration only; never used by a normal build.')
    args=parser.parse_args();package=Path(args.package).resolve();integration=Path(__file__).resolve().parent
    package.mkdir(parents=True,exist_ok=True)
    if args.import_original:
        original=args.import_original.resolve()
        if original==package or not (original/'mbs_pdf_checker_pdfcontrol.py').is_file():raise SystemExit('Invalid explicit import source')
        for file in original.glob('*.py'):
            if not file.name.startswith('test_'):shutil.copy2(file,package/file.name)
        for name in ['bureaus.json','bureaus_prive.json','mbs_artikelnamen.json','mbs_catalogus.json','kennisbank.json','lower_right_ignored_labels.json']:
            if (original/name).is_file():shutil.copy2(original/name,package/name)
        for name in ['catalogus_alphen.json','catalogus_soest.json','catalogus_veenoord.json','catalogus_lelystad.json']:
            source=original/'data_lokaal'/name
            if source.is_file():(package/'data_lokaal').mkdir(exist_ok=True);shutil.copy2(source,package/'data_lokaal'/name)
        manifest={file.name:hashlib.sha256(file.read_bytes()).hexdigest() for file in package.glob('*.py') if not file.name.startswith(('test_','octop_mcp'))}
        (package/'localchat_engine_manifest.json').write_text(json.dumps({'origin':'LocalChat owned engine, explicitly imported from legacy PDFCONTROL','sha256':manifest},indent=2),encoding='utf-8')
    manifest=package/'localchat_engine_manifest.json'
    if not manifest.is_file():raise SystemExit('Owned engine manifest missing. Restore LocalChat_Build/pdfcontrol or explicitly import the legacy installation.')
    for name,digest in json.loads(manifest.read_text(encoding='utf-8'))['sha256'].items():
        file=package/name
        if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest()!=digest:raise SystemExit('Owned engine differs from development manifest: '+name)
    shutil.copy2(integration/'pdfcontrol_bridge.py',package/'octop_mcp.py')
    shutil.copy2(integration/'catalogue_update.py',package/'catalogue_update.py')
    registered=json.loads(manifest.read_text(encoding='utf-8'));registered['sha256']['catalogue_update.py']=hashlib.sha256((package/'catalogue_update.py').read_bytes()).hexdigest()
    manifest.write_text(json.dumps(registered,indent=2)+'\n',encoding='utf-8')
    print('PASS: LocalChat owned engine manifest verified; bridge staged. Legacy installation was not read or changed.')

if __name__=='__main__':main()
