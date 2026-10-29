"""Synthetic drawings only: the server revision comparison classifies per drawing and writes nothing next to the drawings."""
import hashlib, importlib.util, json, os, tempfile, unittest
from pathlib import Path
import pymupdf

HERE = Path(__file__).resolve().parent
PACKAGE = HERE.parent / 'pdfcontrol'
spec = importlib.util.spec_from_file_location('compare_server_revisions', HERE / 'compare_server_revisions.py')
rev = importlib.util.module_from_spec(spec); spec.loader.exec_module(rev)


def pdf(path, text='plan', field=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    with pymupdf.open() as doc:
        page = doc.new_page(width=842, height=595); page.insert_text((45, 70), text, fontsize=10)
        if field:
            page.insert_text((640, 545), 'Revisie', fontsize=9); page.insert_text((700, 545), field, fontsize=9)
        doc.save(path)


def snapshot(root):
    return {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in Path(root).rglob('*') if p.is_file()}


class ServerRevisions(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); base = Path(self.tmp.name).resolve()
        self.local, self.server, self.out = base / 'local' / '123456', base / 'J' / '123456', base / 'out'
        pdf(self.local / '123456_A1_V_rev2.pdf', 'a'); pdf(self.server / '123456_A1_V_rev1.pdf', 'b')       # newer locally
        pdf(self.local / '123456_A2_V_revA.pdf', 'c'); pdf(self.server / '123456_A2_V_revC.pdf', 'd')       # older locally
        pdf(self.local / '123456_A3_V.pdf', 'same'); (self.server / '123456_A3_V.pdf').write_bytes((self.local / '123456_A3_V.pdf').read_bytes())           # identical
        pdf(self.local / '123456_A4_V.pdf', 'only local'); pdf(self.server / '123456_A5_V.pdf', 'only server')
        pdf(self.local / '123456_A6_V_rev3.pdf', 'e'); pdf(self.server / '123456_A6_V_rev3.pdf', 'f')       # same revision, content differs
        pdf(self.local / '123456_A7_V_revX1.pdf', 'g'); pdf(self.server / '123456_A7_V_rev2.pdf', 'h')      # unordered
        pdf(self.local / '123456_A8_V.pdf', 'title block', 'C'); pdf(self.server / '123456_A8_V.pdf', 'title block changed', 'B')   # field in PDF
        pdf(self.server / '123456_A9_V_rev1.pdf', 'x'); pdf(self.server / 'Sub' / '123456_A9_V_rev2.pdf', 'y'); pdf(self.local / '123456_A9_V.pdf', 'z')
        pdf(self.local / 'OLD' / '123456_A1_V_rev0.pdf', 'excluded'); pdf(self.server / 'Controle' / '123456_A5_V.pdf', 'excluded')

    def tearDown(self):
        self.tmp.cleanup()

    def run_tool(self):
        before = (snapshot(self.local.parent), snapshot(self.server.parent))
        report, path = rev.run({'project': '123456', 'local_folder': str(self.local), 'server_folder': str(self.server)}, self.out, PACKAGE)
        self.assertEqual(before, (snapshot(self.local.parent), snapshot(self.server.parent)), 'read-only: nothing changed under the project folders')
        return report, path

    def names(self, report, category):
        return sorted(e['drawing'] for e in report['items'][category])

    def test_classification(self):
        report, path = self.run_tool()
        self.assertTrue(path.is_file() and path.parent == self.out)
        self.assertEqual(['123456_a1_v', '123456_a8_v'], self.names(report, 'newer_local'))
        self.assertEqual(['123456_a2_v'], self.names(report, 'older_local'))
        self.assertEqual(['123456_a3_v'], self.names(report, 'same'))
        self.assertEqual(['123456_a4_v'], self.names(report, 'only_local'))
        self.assertEqual(['123456_a5_v'], self.names(report, 'only_server'))
        self.assertEqual(['123456_a6_v'], self.names(report, 'same_revision_different_content'))
        self.assertEqual(['123456_a7_v'], self.names(report, 'different_revision_unordered'))
        self.assertEqual(['123456_a9_v'], self.names(report, 'ambiguous'))

    def test_title_block_field_wins_over_name(self):
        report, _ = self.run_tool()
        item = next((e for c in report['items'] for e in report['items'][c] if e['drawing'] == '123456_a8_v'), None)
        self.assertIsNotNone(item)
        self.assertEqual(('revisieveld', 'revisieveld'), (item['local_revision_source'], item['server_revision_source']))
        self.assertEqual(('C', 'B'), (item['local_revision'], item['server_revision']))
        self.assertIn(item, report['items']['newer_local'])

    def test_excluded_folders_and_json(self):
        report, path = self.run_tool()
        flat = json.dumps(report)
        self.assertNotIn('excluded', flat); self.assertNotIn('Controle', flat); self.assertNotIn('rev0', flat)
        self.assertTrue(report['read_only'])
        self.assertEqual(report['counts'], json.loads(path.read_text(encoding='utf-8'))['counts'])
        text = rev.summary(report, path)
        self.assertIn('NEWER LOCALLY', text); self.assertIn('ONLY ON SERVER', text); self.assertNotIn('SAME (identical', text)

    def test_revision_order(self):
        self.assertEqual(1, rev.compare_revisions('10', '9')); self.assertEqual(-1, rev.compare_revisions('B', 'AA'))
        self.assertEqual(1, rev.compare_revisions('c', 'B')); self.assertEqual(0, rev.compare_revisions('2', '02'))
        self.assertIsNone(rev.compare_revisions('A', '1')); self.assertIsNone(rev.compare_revisions('X1', '2')); self.assertIsNone(rev.compare_revisions('', 'A'))
        self.assertEqual('123456_a1_v', rev.drawing_key('123456_A1_V_rev12.pdf')); self.assertEqual('123456_a1_v', rev.drawing_key('123456_A1_V-Revisie-B.pdf'))

    def test_bad_requests(self):
        for bad in ({'project': '12', 'local_folder': str(self.local), 'server_folder': str(self.server)},
                    {'project': '123456', 'local_folder': str(self.local), 'server_folder': str(self.local)},
                    {'project': '123456', 'local_folder': str(self.local), 'server_folder': str(self.server / 'nope')},
                    {'project': '123456', 'local_folder': str(self.local), 'server_folder': str(self.server), 'apply': True}):
            with self.assertRaises(ValueError):
                rev.run(bad, self.out, PACKAGE)


if __name__ == '__main__':
    unittest.main()
