"""Initialize missing factory snapshots only; never overwrite runtime user catalogues or archives."""
import argparse,json,os,shutil
from pathlib import Path
FACTORIES=('soest','alphen','veenoord','lelystad')
def seed(source,target):
 source,target=Path(source),Path(target);created=[];preserved=[]
 for factory in FACTORIES:
  name='catalogus_'+factory+'.json';src=source/name;dst=target/name
  if dst.exists():preserved.append(factory);continue
  if not src.is_file():continue
  data=json.loads(src.read_text(encoding='utf-8-sig'))
  if data.get('factory')!=factory or not isinstance(data.get('records'),list):raise ValueError('Invalid seed catalogue: '+name)
  target.mkdir(parents=True,exist_ok=True)
  try:handle=dst.open('xb')
  except FileExistsError:preserved.append(factory);continue
  try:
   with handle:handle.write(src.read_bytes());handle.flush();os.fsync(handle.fileno())
  except Exception:dst.unlink(missing_ok=True);raise
  created.append(factory)
 return {'initialized':created,'preserved':preserved}
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',required=True);p.add_argument('--target',required=True);a=p.parse_args();print(json.dumps(seed(a.source,a.target)))
