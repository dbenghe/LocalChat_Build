"""Synthetic document fidelity, bounded context, offline conversion and cache tests."""
from pathlib import Path
import argparse,base64,hashlib,http.server,importlib.util,io,json,subprocess,sys,tempfile,threading,time,unittest,zipfile,zlib

parser=argparse.ArgumentParser();parser.add_argument('--package',required=True);parser.add_argument('--output');ARGS=parser.parse_args()
PACKAGE=Path(ARGS.package).resolve();sys.path.insert(0,str(PACKAGE));sys.path.insert(0,str(PACKAGE/'document-markdown/vendor'))
spec=importlib.util.spec_from_file_location('document_service',PACKAGE/'mbs_document_markdown.py');service=importlib.util.module_from_spec(spec);spec.loader.exec_module(service)

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def docx(path):
    ns='http://schemas.openxmlformats.org/wordprocessingml/2006/main';rn='http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    entries={
      '[Content_Types].xml':'<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/></Types>',
      '_rels/.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="'+rn+'/officeDocument" Target="word/document.xml"/></Relationships>',
      'word/document.xml':'<w:document xmlns:w="'+ns+'" xmlns:r="'+rn+'"><w:body><w:p><w:r><w:t>Fixture bewijs SH100RD</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Artikel</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Lengte</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>00123</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>0,048</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:sectPr><w:headerReference w:type="default" r:id="rHeader"/></w:sectPr></w:body></w:document>',
      'word/_rels/document.xml.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rHeader" Type="'+rn+'/header" Target="header1.xml"/></Relationships>',
      'word/header1.xml':'<w:hdr xmlns:w="'+ns+'"><w:p><w:r><w:t>KOP_123456</w:t></w:r></w:p></w:hdr>'}
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as archive:
        for name,value in entries.items():archive.writestr(name,value)

class Documents(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.home=Path(tempfile.mkdtemp(prefix='markdown-documents-'));cls.cache=cls.home/'cache'
        cls.evidence=[]
    def read(self,path,**request):
        before=sha(path);start=time.monotonic()
        result=service.read_isolated(dict(path=str(path),**request),self.cache)
        self.assertEqual(sha(path),before);self.assertTrue(result['source_unchanged']);self.assertEqual(result['api_requests'],0)
        self.evidence.append({'extension':path.suffix,'seconds':round(time.monotonic()-start,3),'cache_hits':result['cache_hits'],'backend':result['backend'],'characters_returned':len(result['text'])})
        return result
    def test_01_text_cache_source_change_and_corruption(self):
        path=self.home/'source.txt';path.write_text('SH100RD\n0,048 m²\nAantal 2\n',encoding='utf8')
        first=self.read(path);second=self.read(path)
        self.assertEqual(first['text'],second['text']);self.assertEqual(second['cache_hits'],1)
        body=self.cache/first['cache_key']/'document.md';body.write_text('CORRUPTED',encoding='utf8')
        self.assertIn('SH100RD',self.read(path)['text'])
        path.write_text('UPDATED_ARTICLE',encoding='utf8');changed=self.read(path)
        self.assertNotEqual(first['cache_key'],changed['cache_key']);self.assertIn('UPDATED_ARTICLE',changed['text'])
    def test_02_xlsx_literal_identifiers_formats_and_formulas(self):
        import openpyxl
        path=self.home/'articles.xlsx';book=openpyxl.Workbook();sheet=book.active;sheet.title='Artikelen'
        sheet.append(['Artikel','Lengte','Aantal','Mengsel']);sheet.append(['00123',0.048,2,'MBS-B24']);sheet.append([7,150,6,'C45/55']);sheet['A3'].number_format='0000';sheet['B4']='=1+1';book.create_sheet('Tweede').append(['SH100RD','Ø12']);book.save(path)
        r=self.read(path)
        for value in ['00123','0007','0.048','MBS-B24','C45/55','SH100RD','Ø12','Tweede']:self.assertIn(value,r['text'])
        self.assertTrue(any('formule' in w.lower() for w in r['warnings']));self.assertIn('rijnummer',r['text'])
    def test_03_docx_headers_and_tables(self):
        path=self.home/'table.docx';docx(path);r=self.read(path)
        for value in ['KOP_123456','SH100RD','00123','0,048']:self.assertIn(value,r['text'])
    def test_04_pdf_all_pages_and_character_pagination(self):
        import pymupdf
        path=self.home/'pages.pdf'
        with pymupdf.open() as document:
            for number in range(5):document.new_page().insert_text((40,60),f'PAGINA_{number+1} SH100RD Lengte 150 Aantal 6')
            document.save(path)
        one=self.read(path,unit='pages',offset=1,limit=2);two=self.read(path,unit='pages',offset=one['next_offset'],limit=2);three=self.read(path,unit='pages',offset=two['next_offset'],limit=2)
        self.assertEqual(one['pages_returned']+two['pages_returned']+three['pages_returned'],[1,2,3,4,5]);self.assertIsNone(three['next_offset'])
        chunks=[];offset=0
        while True:
            r=self.read(path,offset=offset,limit=53);chunks.append(r['text'])
            if r['next_offset'] is None:break
            self.assertGreater(r['next_offset'],offset);offset=r['next_offset']
        for number in range(1,6):self.assertIn('PAGINA_'+str(number),''.join(chunks))
    def test_05_email_body_keeps_url_without_attachment_payload(self):
        from email.message import EmailMessage
        path=self.home/'source.eml';message=EmailMessage();message['From']='fixture@example.invalid';message['Subject']='Definitieve tekeningen';message.set_content('Pakket: https://we.tl/t-synthetic-fixture\n');message.add_attachment(b'EXCLUDED_ATTACHMENT_PAYLOAD'*1000,maintype='application',subtype='pdf',filename='fixture.pdf');path.write_bytes(message.as_bytes())
        r=self.read(path);self.assertIn('https://we.tl/t-synthetic-fixture',r['text']);self.assertNotIn('EXCLUDED_ATTACHMENT_PAYLOAD',r['text'])
    def test_06_html_does_not_fetch_external_images(self):
        hits=[]
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):hits.append(self.path);self.send_response(200);self.end_headers()
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            path=self.home/'offline.html';path.write_text(f'<html><body><p>OFFLINE_BODY</p><img src="http://127.0.0.1:{server.server_port}/not-fetched"></body></html>',encoding='utf8')
            self.assertIn('OFFLINE_BODY',self.read(path)['text'].replace('\\_','_'));self.assertEqual(hits,[])
        finally:server.shutdown();server.server_close()
    def test_07_unsupported_binary_is_not_fake_text(self):
        path=self.home/'unsupported.dwg';path.write_bytes(b'AC1032\x00\x00\xff\xfe')
        with self.assertRaisesRegex(ValueError,'binaire formaat'):self.read(path)
    def test_08_archive_listing_does_not_extract(self):
        path=self.home/'archive.zip'
        with zipfile.ZipFile(path,'w') as z:z.writestr('../../escape.txt','DO_NOT_EXTRACT')
        r=self.read(path);self.assertIn('escape.txt',r['text']);self.assertTrue(r['warnings']);self.assertFalse((self.home.parent/'escape.txt').exists())
    def test_09_old_doc_uses_readonly_isolated_word(self):
        if sys.platform!='win32':self.skipTest('Windows Word converter')
        # Synthetic fixture only: build a genuine old DOC from the minimal DOCX above.
        source=self.home/'fixture.docx';docx(source);legacy=self.home/'fixture.doc'
        cmd=['powershell.exe','-NoProfile','-NonInteractive','-STA','-ExecutionPolicy','Bypass','-File',str(PACKAGE/'legacy_doc_to_docx.ps1'),'-Source',str(source),'-Output',str(legacy),'-OutputFormat','0']
        result=subprocess.run(cmd,capture_output=True,text=True,timeout=70)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertTrue(legacy.read_bytes().startswith(b'\xd0\xcf\x11\xe0'))
        r=self.read(legacy)
        for value in ['SH100RD','00123','0,048']:self.assertIn(value,r['text'])
        self.assertIn('word-readonly-to-docx+markitdown',r['backend'])
    def test_10_old_xls_preserves_article_identifiers(self):
        path=self.home/'old.xls';path.write_bytes(zlib.decompress(base64.b85decode('c%1E6O=uHA6#iy6X_MHdo0<x(P(sD3wTISTytLa2+KbleMG-;MW|5kZ$`TKH&}z+55PDL)6nfB0)r&vagB}DALPZaPAS8P9Bq)fq*}i$ZtEpD#9$G8Rx6C(h-+S|unKzT2c|D&fO+RQU<2Up{N5zSvrb;I$Mg128WOp1X`gw$sqwrrL8lj1-3FB$~t6DjSa=y#Za6j~%82Cu(B+g-IJa47e5&O6gX(9X2ODu4kG;uU@uNs+0nKv`vW6wF}e%4%P?j_%nIDvU{sAI1e#mBWaw#6aqeZ*znv!)wOcqya!@(tnohB07|rE?q8&>E`43~1g4t-NKYb0s8c0%!2eNdaH|mHI5D^y{k^ApQ5{`q~xrQ(6d^fODDyyogPBp-YR7!{;*#*an?nAg6f1E{tWZ9IelTR{l)E3IknT-FtdsWOf&^v1}37b8y!YXVAcMf<xS(ZF=GEmp6w-hRsu~nc$7v-QKs$9>KvCS%M13*`odA4(8p=SGm@$EVnQxDKIH-A8zu51C#7?jjip}P7mX)xuZ(nNpkV?k;kpYPv$O?X9vp{le6#4X|&PbO@_+LXA+&FQ{1{K@x=6V;cW#Un4ACD79yJA_1$p?T{K`3F%}cd?wcGP;rWXd`~~~V8g&{I!Tvukot-O8-7)*LMm*#p>c=m{prD|jprD|jprEkf;C}47rwsY&Psa*AKFD)~yw_QzRQcDJ6bfsGqZp^BKnK(P$Ww0PLZJBBg0S%G600oZ-xo?8qt<O?F~a@G25MV^j(?*1_pG|05xA+pA#nSZApKn9_Ll&;%f!$')))
        r=self.read(path);self.assertIn('00123',r['text']);self.assertIn('0.048',r['text']);self.assertIn('Oud',r['text'])
    def test_11_html_only_outlook_message_keeps_download_link(self):
        import struct
        free=0xffffffff;end=0xfffffffe;fatsector=0xfffffffd
        header=bytearray(512);header[:8]=bytes.fromhex('d0cf11e0a1b11ae1');struct.pack_into('<HHHHH',header,24,0x3e,3,0xfffe,9,6)
        for offset,value in [(44,1),(48,0),(56,4096),(60,end),(68,end)]:struct.pack_into('<I',header,offset,value)
        struct.pack_into('<109I',header,76,1,*([free]*108))
        def entry(name,kind,right,child,start,size):
            data=bytearray(128);encoded=(name+'\x00').encode('utf-16-le');data[:len(encoded)]=encoded;struct.pack_into('<HBBIII',data,64,len(encoded),kind,1,free,right,child);struct.pack_into('<IQ',data,116,start,size);return data
        directory=entry('Root Entry',5,free,1,end,0)+entry('__substg1.0_0037001F',2,2,free,2,4096)+entry('__substg1.0_10130102',2,free,free,10,4096)+bytearray(128)
        table=[free]*128;table[0]=end;table[1]=fatsector
        for start in [2,10]:
            for n in range(start,start+8):table[n]=n+1 if n<start+7 else end
        subject=('HTML_ONLY\x00').encode('utf-16-le').ljust(4096,b'\x00');body=b'<html><body>New drawings <a href="https://we.tl/t-synthetic-only">Download</a></body></html>'.ljust(4096,b'\x00')
        path=self.home/'html-only.msg';path.write_bytes(header+directory+struct.pack('<128I',*table)+subject+body)
        result=self.read(path);self.assertIn('https://we.tl/t-synthetic-only',result['text']);self.assertIn('New drawings',result['text'])
    @classmethod
    def tearDownClass(cls):
        if ARGS.output:Path(ARGS.output).write_text(json.dumps({'fixtures':str(cls.home),'conversions':cls.evidence,'api_requests':0},indent=2),encoding='utf8')

if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Documents));raise SystemExit(0 if result.wasSuccessful() else 1)
