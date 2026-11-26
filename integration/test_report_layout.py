"""Generic full-sheet layout and real report-tool tests. Synthetic PDFs; no production writes or model calls."""
import argparse,collections,hashlib,importlib.util,json,os,sys,tempfile,unittest
from pathlib import Path
import pymupdf as pdf
parser=argparse.ArgumentParser();parser.add_argument('--package');args=parser.parse_args();HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE));import report_layout as layout,apply_report_layout as hooks

def make(path,size=(842,595),rotation=0,image=False):
    with pdf.open() as d:
        p=d.new_page(width=size[0],height=size[1]);p.draw_rect(pdf.Rect(5,5,size[0]-5,size[1]-5),width=.5)
        if image:
            small=pdf.Pixmap(pdf.csRGB,20,20,bytes([245,230,215])*400,False);p.insert_image(pdf.Rect(80,80,140,140),pixmap=small)
        for point,text in [((16,24),'BOVENRAND - SOURCE'),((16,size[1]-25),'RENVOOI ARTICLE-X M12 6 STUKS'),((size[0]-210,size[1]-25),'PROJECT 123456 MERK AA-99'),((80,120),'ORIGINAL DIMENSION 150 310')]:p.insert_text(point,text,fontsize=8)
        p.set_rotation(rotation);d.save(path)

def words(page):return collections.Counter(w[4] for w in page.get_text('words'))
class LayoutTests(unittest.TestCase):
    def setUp(self):self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name).resolve()
    def tearDown(self):self.tmp.cleanup()
    def test_all_formats_rotations_keep_both_cartouches(self):
        for size in [(595,842),(842,595),(1191,842),(1684,1191),(1300,700)]:
            for rotation in [0,90,180,270]:
                with self.subTest(size=size,rotation=rotation):
                    path=self.root/'input.pdf';make(path,size,rotation);before=path.read_bytes()
                    with pdf.open(path) as src,pdf.open() as doc:
                        p=layout.append_sheet(doc,src,0,'Project 123456 | AA-99','CONTROLEREN',['Controleer deze koppeling.'])
                        self.assertFalse(words(src[0])-words(p));meta=p._mbs_report_layout;area=pdf.Rect(meta['drawing_rect'])
                        for rect in meta['caption_rects']:self.assertFalse(area.intersects(pdf.Rect(rect)))
                        self.assertGreater(len(p.get_drawings()),0);self.assertEqual(p.rect,src[0].rect)
                    self.assertEqual(path.read_bytes(),before)
    def test_source_annotations_remain_visible(self):
        path=self.root/'annotated.pdf';make(path)
        with pdf.open(path) as d:
            a=d[0].add_freetext_annot(pdf.Rect(220,40,400,80),'ORIGINAL ANNOTATION',fontsize=9);a.update();d.save(self.root/'annotated2.pdf')
        with pdf.open(self.root/'annotated2.pdf') as s,pdf.open() as out:
            p=layout.append_sheet(out,s,0);self.assertIn('ORIGINAL ANNOTATION',p.get_text())
    def test_source_cropbox_and_scan_preserved(self):
        p=self.root/'input.pdf';make(p,(1000,800),image=True)
        with pdf.open(p) as d:d[0].set_cropbox(pdf.Rect(10,10,990,790));d.save(self.root/'crop.pdf')
        with pdf.open(self.root/'crop.pdf') as s,pdf.open() as d:
            p=layout.append_sheet(d,s,0);self.assertFalse(words(s[0])-words(p));self.assertGreater(len(p.get_images()),0)
    def test_many_and_long_warnings_not_removed(self):
        path=self.root/'source.pdf';make(path);findings=[dict(file='source.pdf',page=1,article='A'+str(n),message='Waarschuwing '+str(n)+' '+('lange toelichting '*30),severity='CONTROLEREN') for n in range(65)]
        findings.append(dict(file='source.pdf',page=1,article='LONG',message='Z'*900,severity='FOUT'))
        result=layout.review_report(self.root,['source.pdf'],findings)
        with pdf.open(result['report']) as d:
            self.assertGreater(len(d),3);text=' '.join(p.get_text() for p in d)
            for n in range(65):self.assertIn('Waarschuwing '+str(n),text)
            self.assertEqual(text.count('Z'),900);self.assertIn('FOUT',d[0].get_text())
        self.assertFalse(result['technical_approval']);self.assertTrue(result['delivery_verified'])
    def test_complete_bundle_even_without_known_layout(self):
        path=self.root/'bundle.pdf'
        with pdf.open() as d:
            for n in range(45):d.new_page().insert_text((20,30),'UNKNOWN LAYOUT '+str(n))
            d.save(path)
        result=layout.review_report(self.root,['bundle.pdf'],[])
        self.assertEqual(result['source_pages'],45)
        with pdf.open(result['report']) as d:
            self.assertEqual(len(d),45)
            for n in range(45):self.assertIn('UNKNOWN LAYOUT '+str(n),d[n].get_text());self.assertIn('GEEN OORDEEL',d[n].get_text())
    def test_repeated_reports_new_files_no_overwrite(self):
        make(self.root/'input.pdf');a=layout.review_report(self.root,['input.pdf'],[]);before=Path(a['report']).read_bytes();b=layout.review_report(self.root,['input.pdf'],[]);self.assertNotEqual(a['report'],b['report']);self.assertEqual(Path(a['report']).read_bytes(),before)
    def test_bad_sources_pages_bboxes_fail(self):
        make(self.root/'input.pdf')
        for files,findings in [(['../outside.pdf'],[]),([str(self.root/'input.pdf')],[]),(['input.pdf'],[dict(file='input.pdf',page=2)]),(['input.pdf'],[dict(file='input.pdf',page=True)]),(['input.pdf'],[dict(file='input.pdf',page=1,bbox=[float('nan'),0,20,30])]),(['input.pdf'],[dict(file='other.pdf',page=1)])]:
            with self.subTest(files=files,findings=findings):
                with self.assertRaises(ValueError):layout.review_report(self.root,files,findings)
    def test_finding_collection_never_writes_text_on_source(self):
        from types import SimpleNamespace
        make(self.root/'input.pdf')
        with pdf.open(self.root/'input.pdf') as d,pdf.open() as out:
            p=d[0];before=p.get_text();finding=SimpleNamespace(code='REAL_ERROR',ernst='FOUT',artikel='ARTICLE-X',tekst='Werkelijke maat wijkt af',rect=pdf.Rect(20,20,90,30))
            layout.collect_finding(p,finding);self.assertEqual(before,p.get_text());self.assertEqual(len(p._mbs_report_marks),1)
            new=layout.append_sheet(out,d,0,status='NIET_OK',marks=p._mbs_report_marks)
            self.assertTrue(list(new.annots()));self.assertEqual(list(new.annots())[0].info['subject'],'REAL_ERROR')
            self.assertFalse(words(p)-words(new))
    def test_hooks_idempotent_preserve_other_code_crlf(self):
        if not args.package:self.skipTest('real owned engine required')
        for name,patch in [('mbs_pdf_checker_pdfcontrol.py',hooks.patch_checker),('mbs_vergelijk_pdf.py',hooks.patch_comparison)]:
            text=(Path(args.package)/name).read_bytes().decode('utf8');self.assertEqual(text,patch(text));self.assertEqual(patch(text),patch(patch(text)))
            import ast
            plain=text.replace('\r\n','\n')+'\n\ndef unrelated_layout_fixture():\n    return "UNCHANGED"\n';changed=patch(plain.replace('\n','\r\n'));self.assertIn('return "UNCHANGED"\r\n',changed);ast.parse(changed)
    def test_comparison_label_prefers_blank_margin(self):
        sys.path.insert(0,str(Path(args.package)))
        import mbs_vergelijk_pdf as comparison
        with pdf.open() as doc:
            page=doc.new_page(width=340,height=220)
            area=pdf.Rect(20,113,320,205)
            page.insert_text((30,140),'MAAT 150 310',fontsize=10)
            rect=comparison._mark(page,area,(1,5,40,28),'T1',(1,0,0))
            self.assertFalse(rect.intersects(area))
            label=page.get_texttrace()[-1]
            self.assertAlmostEqual(label['opacity'],1)
            self.assertIn('MAAT 150 310',page.get_text())

    def test_dense_scan_uses_transparent_label_without_a_background(self):
        sys.path.insert(0,str(Path(args.package)))
        import mbs_vergelijk_pdf as comparison
        with pdf.open() as doc:
            page=doc.new_page(width=340,height=220)
            area=pdf.Rect(20,103,320,205)
            scan=pdf.Pixmap(pdf.csRGB,24,24,bytes([180,180,180])*24*24,False)
            page.insert_image(page.rect,pixmap=scan)
            page.insert_text((30,140),'MAAT 150 310',fontsize=10)
            text_rects=[pdf.Rect(word[:4]) for word in page.get_text('words')]
            label=comparison._mark(page,area,(1,12,40,31),'T1',(1,0,0),text_rects=text_rects)
            self.assertTrue(area.contains(label))
            self.assertAlmostEqual(page.get_texttrace()[-1]['opacity'],layout.OVERLAY_OPACITY,places=2)
            self.assertIn('MAAT 150 310',page.get_text())
            self.assertTrue(all(shape['fill'] is None for shape in page.get_drawings()))
            self.assertTrue(all(shape['stroke_opacity'] <= .46 for shape in page.get_drawings()))

    def ruled_table(self, doc):
        page=doc.new_page(width=640,height=440)
        for y in (255,275,295,315):
            page.draw_line((40,y),(280,y),width=.5)
            page.draw_line((280,y),(440,y),width=.5)
        for x in (40,96,238,280,440):
            page.draw_line((x,255),(x,315),width=.5)
        for xy,value in [((44,270),'Pos.'),((100,270),'Omschrijving'),((243,270),'Aantal'),
                         ((44,290),'S031'),((100,290),'Stekanker'),((248,290),'2'),
                         ((286,290),'Uitgewerkt door'),((44,310),'S032'),
                         ((100,310),'Boutanker'),((248,310),'4')]:
            page.insert_text(xy,value,fontsize=8)
        return page

    def test_table_marks_clip_to_row_with_rotation_and_keep_view_mark(self):
        with pdf.open() as src,pdf.open() as out:
            page=self.ruled_table(src)
            page.set_rotation(90)
            row=pdf.Rect(40,275,280,295)
            view=pdf.Rect(355,95,390,110)
            marks=[dict(bbox=[44,277,368,300],table=True,article='S031 - Stekanker'),
                   dict(bbox=list(view),table=False,article='S031 - Stekanker')]
            p=layout.append_sheet(out,src,0,marks=marks)
            frames=list(p.annots())
            self.assertEqual(len(frames),2)
            target=pdf.Rect(p._mbs_report_layout['drawing_rect'])
            scale=target.width/page.rect.width
            transform=page.rotation_matrix * pdf.Matrix(scale,scale) * pdf.Matrix(1,0,0,1,target.x0,target.y0)
            self.assertTrue((row*transform).contains(frames[0].rect))
            expected=view*transform
            self.assertAlmostEqual(frames[1].rect.x0,expected.x0-1,places=2)
            self.assertIn('Uitgewerkt door',p.get_text())

    def test_saved_table_overflow_is_clipped_in_copy(self):
        original=self.root/'saved-checked.pdf'
        with pdf.open() as src,pdf.open() as old:
            self.ruled_table(src)
            marks=[dict(bbox=[44,277,368,300],table=False,article='S031 - Stekanker',message=message)
                   for message in ('Controle maat', 'Controle artikel')]
            layout.append_sheet(old,src,0,marks=marks)
            old.save(original,garbage=3)
        before=original.read_bytes()
        with pdf.open(original) as doc:
            page=doc[0]
            old=list(page.annots())[0].rect
            layout.clip_preframed_sheets(doc)
            page=doc.reload_page(page)
            corrected=[a.rect for a in page.annots()]
            self.assertEqual(len(corrected),2)
            self.assertEqual(corrected[0],corrected[1])
            new=corrected[0]
            self.assertLess(new.x1,old.x1-40)
            self.assertLess(new.y1,old.y1)
            self.assertIn('Uitgewerkt door',page.get_text())
            self.assertTrue(any(box['rect'].x1 < old.x1-40 and abs(box.get('stroke_opacity',1)-layout.OVERLAY_OPACITY)<.01
                                for box in page.get_drawings()))
        self.assertEqual(original.read_bytes(),before)

    def test_ruled_renvooi_without_position_column_clips_to_row(self):
        with pdf.open() as src,pdf.open() as out:
            page=src.new_page(width=640,height=440)
            for y in (255,275,295,315):
                page.draw_line((40,y),(280,y),width=.5)
            page.insert_text((44,252),'Instortvoorzieningen',fontsize=8)
            page.insert_text((44,270),'Omschrijving',fontsize=8)
            page.insert_text((242,270),'Aantal',fontsize=8)
            page.insert_text((44,290),'Boutanker',fontsize=8)
            page.insert_text((242,290),'2',fontsize=8)
            mark=dict(bbox=[44,281,360,292],table=True,article='Boutanker')
            rendered=layout.append_sheet(out, src, 0, marks=[mark])
            contour=list(rendered.annots())[0].rect
            target=pdf.Rect(rendered._mbs_report_layout['drawing_rect'])
            self.assertLess(contour.x1,target.x0+280*target.width/page.rect.width)

    def test_unconfirmed_table_bounds_keep_report_note(self):
        with pdf.open() as src,pdf.open() as out:
            src.new_page(width=640,height=440)
            mark=dict(bbox=[44,277,368,300],table=True,article='S031 - Stekanker')
            page=layout.append_sheet(out,src,0,notes=['Controleer S031'],marks=[mark])
            self.assertFalse(list(page.annots() or ()))
            self.assertIn('Controleer S031',page.get_text())

    def test_native_checked_report_and_comparison(self):
        if not args.package:self.skipTest('engine required')
        sys.path.insert(0,str(Path(args.package)))
        import mbs_pdf_checker_pdfcontrol as checker,mbs_vergelijk_pdf as comparison
        from types import SimpleNamespace
        path=self.root/'input.pdf';make(path,rotation=90)
        finding=SimpleNamespace(code='POSITION',ernst='CONTROLEREN',artikel='ARTICLE-X',tekst='Plaats niet bevestigd',rect=pdf.Rect(20,20,90,30))
        source=pdf.open(path)
        original=words(source[0]);p=source[0];before=p.get_text();checker.markeer_bevinding(p,finding)
        self.assertEqual(p.get_text(),before)
        checked=layout.frame_checked_document(source,p,'input.pdf','CONTROLEREN',[finding])
        checker.maak_rapport(checked,'input.pdf',[],[finding],False)
        self.assertFalse(original-words(checked[0]));self.assertIn('Plaats niet bevestigd',' '.join(p.get_text() for p in checked))
        checked.close()
        info=dict(pad=str(path),pagina=0,naam='input.pdf',revisie={},datum=None)
        with pdf.open() as doc:
            p=comparison.comparison_page(doc,dict(id='123456-AA99',old=info,new=info,rows=[]),1)
            self.assertEqual(words(p)['AA-99'],2);self.assertEqual(words(p)['ARTICLE-X'],2)
            for span in [s for b in p.get_text('dict')['blocks'] if 'lines' in b for l in b['lines'] for s in l['spans'] if s['text'].startswith('Tekeningen vergelijken')]:self.assertLessEqual(span['size'],9)
    def test_actual_mcp_tool_schema_and_scope(self):
        spec=importlib.util.spec_from_file_location('layout_bridge',HERE/'pdfcontrol_bridge.py');bridge=importlib.util.module_from_spec(spec);spec.loader.exec_module(bridge)
        sys.path.insert(0,str(Path(args.package)));bridge.PROJECTS=self.root;project=self.root/'123456';project.mkdir();make(project/'input.pdf')
        tool=next(t for t in bridge.TOOLS if t['name']=='pdfcontrol_review_report');self.assertFalse(tool['annotations']['readOnlyHint'])
        result=bridge.run_tool('pdfcontrol_review_report',dict(project='123456',files=['input.pdf'],findings=[dict(file='input.pdf',page=1,article='A',message='Onbevestigd',severity='CONTROLEREN')]))
        self.assertTrue(Path(result['report']).is_relative_to(project/'Controle'));self.assertTrue(result['delivery_verified'])
        for request in [dict(project='000000',files=['input.pdf'],findings=[]),dict(project='123456',files=['../input.pdf'],findings=[]),dict(project='123456',files=['input.pdf'],findings=[],output='outside.pdf')]:
            with self.assertRaises(ValueError):bridge.run_tool('pdfcontrol_review_report',request)
        self.assertLess(sum(len(json.dumps(t,ensure_ascii=False)) for t in bridge.TOOLS if t['name']!='pdfcontrol_review_report'),9000)
        self.assertLess(len(json.dumps(tool,ensure_ascii=False)),1100)

if __name__=='__main__':unittest.main(argv=[sys.argv[0]],verbosity=2)
