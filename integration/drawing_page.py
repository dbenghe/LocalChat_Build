"""Read-only PDF page rendering for model inspection. No production changes, no model/API."""
import argparse,hashlib,io,json,contextlib,re,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))

def render(request, cache):
 import pymupdf
 path=Path(request['path']).resolve(strict=True)
 if path.suffix.lower()!='.pdf' or path.stat().st_size>200*1024*1024:raise ValueError('Select a PDF up to 200 MiB')
 page_number=request.get('page',1)
 if type(page_number) is not int or page_number<1:raise ValueError('Page must be a positive integer')
 edge=request.get('max_edge',2200)
 if type(edge) is not int or not 600<=edge<=3000:raise ValueError('max_edge must be 600-3000')
 raw=path.read_bytes();digest=hashlib.sha256(raw).hexdigest()
 with pymupdf.open(stream=raw,filetype='pdf') as document:
  if page_number>len(document):raise ValueError('Requested page does not exist')
  page=document[page_number-1];clip=page.rect
  if request.get('bbox') is not None:
   bbox=request['bbox']
   if not isinstance(bbox,list) or len(bbox)!=4 or not all(type(x) in (float,int) and abs(x)<100000 for x in bbox):raise ValueError('bbox requires four finite page-point coordinates')
   clip=pymupdf.Rect(bbox)
   if clip.is_empty or clip.is_infinite or not page.rect.contains(clip):raise ValueError('bbox must be inside the displayed page')
  key=hashlib.sha256(json.dumps([digest,page_number,list(clip),edge]).encode()).hexdigest()
  directory=Path(cache)/key;directory.mkdir(parents=True,exist_ok=True);image=directory/'page.png'
  scale=edge/max(clip.width,clip.height)
  pix=page.get_pixmap(matrix=pymupdf.Matrix(scale,scale),clip=clip,alpha=False)
  while True:
   data=pix.tobytes('png')
   if len(data)<=5*1024*1024 and max(pix.width,pix.height)<=edge:break
   scale*=min(.75 if len(data)>5*1024*1024 else 1.,(edge-1)/max(pix.width,pix.height));pix=page.get_pixmap(matrix=pymupdf.Matrix(scale,scale),clip=clip,alpha=False)
   if pix.width<400 or pix.height<100:raise ValueError('Cannot render within image size limit')
  image.write_bytes(data)
  text_clip=clip*page.derotation_matrix
  text=page.get_text(clip=text_clip).strip();ocr_note='not_needed'
  if len(text)<80:
   sys.modules.setdefault('fitz',pymupdf)
   try:
    import mbs_ocr_artikelen as ocr
    ocr.aanvullen(page,page.rotation);text=page.get_text(clip=text_clip).strip();ocr_note='local_ocr_requires_visual_review'
   except Exception as e:ocr_note='unavailable: '+str(e)[:180]
  result={'path':str(path),'page':page_number,'page_count':len(document),'source_sha256':digest,'page_rect':list(page.rect),'bbox':list(clip),'rotation':page.rotation,
    'image_path':str(image),'width':pix.width,'height':pix.height,'text':text[:8000],'text_truncated':len(text)>8000,'ocr':ocr_note,
    'source_unchanged':hashlib.sha256(path.read_bytes()).hexdigest()==digest,'api_requests':0,
    'note':'Rendered source page/crop for visual interpretation. Read printed dimensions and their view/section; do not measure technical dimensions from pixels. This is not a control report or production approval.'}
  if not result['source_unchanged']:raise ValueError('Source changed while reading page')
  return result

def main():
 p=argparse.ArgumentParser();p.add_argument('--request',required=True);p.add_argument('--cache',required=True);a=p.parse_args();logs=io.StringIO()
 try:
  with contextlib.redirect_stdout(logs),contextlib.redirect_stderr(logs):result=render(json.loads(Path(a.request).read_text(encoding='utf-8-sig')),a.cache)
  if logs.getvalue():result['diagnostics']=logs.getvalue()[-800:]
  print(json.dumps(result,ensure_ascii=True))
 except Exception as e:print(json.dumps({'error':str(e)},ensure_ascii=True));raise SystemExit(1)
if __name__=='__main__':main()
