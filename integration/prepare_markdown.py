"""Reproducible, isolated MarkItDown dependencies; never changes the native Python site-packages."""
import argparse,hashlib,json,os,shutil,subprocess,sys,tempfile
from pathlib import Path

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def verify(root):
    manifest=json.loads((root/'manifest.json').read_text(encoding='utf8'))
    if manifest['version']!='0.1.9':raise ValueError('Unexpected MarkItDown version')
    for name,digest in manifest['sha256'].items():
        path=(root/'vendor'/name).resolve()
        if not path.is_relative_to((root/'vendor').resolve()) or not path.is_file() or sha(path)!=digest:raise ValueError('Markdown runtime checksum mismatch: '+name)
    print('PASS: isolated MarkItDown runtime verified ('+str(len(manifest['sha256']))+' files)')
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--package',required=True,type=Path);parser.add_argument('--wheels',type=Path);args=parser.parse_args()
    root=args.package.resolve()/'document-markdown'
    if not args.wheels:verify(root);return
    if sys.platform!='win32' or sys.version_info[:2]!=(3,13):raise ValueError('Dependency lock targets Windows x64 Python 3.13')
    lock=json.loads(Path(__file__).with_name('document-markdown-wheels.json').read_text(encoding='utf8'))
    for item in lock['packages']:
        wheel=args.wheels/item['wheel']
        if not wheel.is_file() or sha(wheel)!=item['sha256']:raise ValueError('Wheel checksum mismatch: '+item['wheel'])
    if root.exists():raise ValueError('Runtime exists; verify it or choose a clean staging package. No implicit overwrite.')
    root.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='markdown-stage-',dir=root.parent) as temp:
        stage=Path(temp);vendor=stage/'vendor'
        subprocess.run([sys.executable,'-I','-m','pip','install','--no-index','--find-links',str(args.wheels),'--require-hashes','--only-binary=:all:','--no-compile','--target',str(vendor),'-r',str(Path(__file__).with_name('requirements-markdown-win-py313.txt'))],check=True)
        manifest={'version':'0.1.9','sha256':{p.relative_to(vendor).as_posix():sha(p) for p in vendor.rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.relative_to(vendor).parts[0] not in {'bin','Scripts'}}}
        (stage/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf8');verify(stage);os.replace(stage,root)
if __name__=='__main__':main()
