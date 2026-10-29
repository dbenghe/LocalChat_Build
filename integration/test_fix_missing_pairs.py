"""Synthetic drawings only: the missing-pair tool finds, copies under the pair name and never overwrites."""
import hashlib, importlib.util, json, shutil, subprocess, sys, tempfile, unittest
from pathlib import Path
import pymupdf

HERE = Path(__file__).resolve().parent
PACKAGE = HERE.parent / 'pdfcontrol'
SCRIPT = HERE / 'fix_missing_pairs.py'
spec = importlib.util.spec_from_file_location('fix_missing_pairs', SCRIPT)
fix = importlib.util.module_from_spec(spec); spec.loader.exec_module(fix)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def pdf(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    with pymupdf.open() as doc:
        page = doc.new_page(width=800, height=600); page.insert_text((45, 70), text, fontsize=10)
        doc.save(path)


def v_text(mark, ref=None):
    return 'Projectnummer:\n123456\nVormtekening\n' + mark + '_V\n' + ((ref + '_W\n') if ref else '') + 'Wapeningstekening\n'


def w_text(mark, extra=''):
    return 'Projectnummer:\n123456\nWapeningstekening\n' + mark + '_W\n' + extra


class MissingPairs(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name).resolve() / 'PRODUCTIE' / '123456'
        d, final = self.root / 'PDF', self.root / 'Desene finale'
        pdf(d / '123456_WA224_V.pdf', v_text('WA224', 'WA133'))      # pair missing, mesh WA133 printed on the drawing
        pdf(final / '123456_WA133_W.pdf', w_text('WA133'))            # the mesh sits in the final drawings folder
        pdf(d / '123456_WA300_V.pdf', v_text('WA300')); pdf(d / '123456_WA300_W.pdf', w_text('WA300'))   # pair present
        pdf(d / '123456_WA350_V.pdf', v_text('WA350')); pdf(final / '123456_WA350_W.pdf', w_text('WA350'))   # own mesh, same-stem pair missing
        pdf(d / '123456_WA400_V.pdf', v_text('WA400', 'WA999'))      # mesh WA999 exists nowhere
        pdf(d / '123456_WA500_V.pdf', v_text('WA500', 'WA133')); pdf(d / '123456_WA500_W.pdf', w_text('WA130'))  # wrong W already there
        pdf(d / '123456_WA600_V.pdf', v_text('WA600', 'WA700'))      # two different WA700 files
        pdf(self.root / 'Other' / '123456_WA700_W.pdf', w_text('WA700', 'Revisie A\n')); pdf(self.root / 'Extra' / '123456_WA700_W.pdf', w_text('WA700', 'Revisie B\n'))
        pdf(d / '123456_WA800_V.pdf', v_text('WA800', 'WA900'))      # two different WA900: the one in the final folder wins
        pdf(final / '123456_WA900_W.pdf', w_text('WA900', 'Revisie A\n')); pdf(self.root / 'Extra' / '123456_WA900_W.pdf', w_text('WA900', 'Revisie B\n'))
        pdf(self.root / 'Output' / '123456_WA999_W.pdf', w_text('WA999'))   # generated folder: never a source
        self.d, self.final = d, final
        self.before = {str(p): digest(p) for p in self.root.rglob('*.pdf')}

    def tearDown(self):
        self.tmp.cleanup()

    def run_tool(self, **extra):
        request = dict(project='123456', project_root=str(self.root), **extra)
        req = Path(self.tmp.name) / 'request.json'; req.write_text(json.dumps(request), encoding='utf-8')
        out = Path(self.tmp.name) / 'reports'
        result = subprocess.run([sys.executable, '-I', str(SCRIPT), '--request', str(req), '--output', str(out), '--package', str(PACKAGE)], capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        report = json.loads(max(out.glob('missing-pairs-*.json'), key=lambda p: p.stat().st_mtime_ns).read_text(encoding='utf-8'))
        return report, result.stdout

    def names(self, key, report):
        return sorted(i['drawing'] for i in report[key])

    def test_01_pair_naming_convention(self):
        self.assertEqual(fix.mark_of('123456_WA133_W.pdf'), 'WA133')
        self.assertEqual(fix.V_FILE.match('123456_GB10_V.pdf').group(2), 'GB10')
        sys.path.insert(0, str(PACKAGE))
        import mbs_webapp as web
        self.assertEqual(web.w_naam_van('123456_GB10_V.pdf'), '123456_GB10_W.pdf')

    def test_02_dry_run_changes_nothing(self):
        report, text = self.run_tool(apply=False)
        self.assertEqual(self.names('would_copy', report), ['123456_WA224_V.pdf', '123456_WA350_V.pdf', '123456_WA800_V.pdf'])
        self.assertEqual({str(p): digest(p) for p in self.root.rglob('*.pdf')}, self.before)
        self.assertIn('dry run', text)

    def test_03_copies_mesh_from_final_folder_under_pair_name(self):
        report, text = self.run_tool()
        self.assertEqual(self.names('copied', report), ['123456_WA224_V.pdf', '123456_WA350_V.pdf', '123456_WA800_V.pdf'])
        created = self.d / '123456_WA224_W.pdf'
        self.assertTrue(created.is_file()); self.assertEqual(digest(created), digest(self.final / '123456_WA133_W.pdf'))
        item = report['copied'][0]
        self.assertEqual(item['mesh'], 'WA133'); self.assertEqual(Path(item['source']), self.final / '123456_WA133_W.pdf')
        self.assertEqual(self.names('present', report), ['123456_WA300_V.pdf'])
        wa800 = [i for i in report['copied'] if i['drawing'] == '123456_WA800_V.pdf'][0]
        self.assertEqual(Path(wa800['source']), self.final / '123456_WA900_W.pdf'); self.assertEqual(digest(self.d / '123456_WA800_W.pdf'), digest(self.final / '123456_WA900_W.pdf'))
        self.assertEqual(digest(self.d / '123456_WA350_W.pdf'), digest(self.final / '123456_WA350_W.pdf'))   # same-stem: the mark on the drawing is its own
        self.assertIn('COPIED', text)

    def test_04_not_found_ambiguous_and_conflict_are_reported_not_touched(self):
        report, _ = self.run_tool()
        self.assertEqual(self.names('not_found', report), ['123456_WA400_V.pdf'])   # Output\ copy of WA999 is not a source
        self.assertFalse((self.d / '123456_WA400_W.pdf').exists())
        self.assertEqual(self.names('ambiguous', report), ['123456_WA600_V.pdf'])
        self.assertFalse((self.d / '123456_WA600_W.pdf').exists())
        self.assertEqual(self.names('conflict', report), ['123456_WA500_V.pdf'])
        self.assertEqual(digest(self.d / '123456_WA500_W.pdf'), self.before[str(self.d / '123456_WA500_W.pdf')])

    def test_05_never_overwrites_sources_or_existing_and_is_idempotent(self):
        self.run_tool()
        second, _ = self.run_tool()
        self.assertEqual(second['copied'], []); self.assertIn('123456_WA224_V.pdf', self.names('present', second))
        for name, value in self.before.items():
            self.assertEqual(digest(name), value, name)
        target = self.d / '123456_WA224_W.pdf'
        self.assertIsNone(fix.copy_new(self.final / '123456_WA133_W.pdf', target))   # exclusive create refuses an existing file

    def test_06_confinement_and_bad_input(self):
        outside = Path(self.tmp.name) / 'elsewhere'; outside.mkdir()
        for extra in ({'folders': [str(outside)]}, {'source_folders': [str(outside)]}):
            request = dict(project='123456', project_root=str(self.root), **extra)
            with self.assertRaises(ValueError):
                fix.run(request, Path(self.tmp.name) / 'o', PACKAGE)
        with self.assertRaises(ValueError):
            fix.run(dict(project='12', project_root=str(self.root)), Path(self.tmp.name) / 'o', PACKAGE)
        with self.assertRaises(ValueError):
            fix.run(dict(project='123456', project_root=str(self.root), surprise=1), Path(self.tmp.name) / 'o', PACKAGE)

    def test_07_explicit_source_folder_limits_search(self):
        report, _ = self.run_tool(source_folders=[str(self.root / 'Extra')])
        self.assertNotIn('123456_WA224_V.pdf', self.names('copied', report))      # WA133 lives only in the final folder, outside the chosen search root
        self.assertIn('123456_WA224_V.pdf', self.names('not_found', report))
        self.assertEqual(self.names('copied', report), ['123456_WA600_V.pdf', '123456_WA800_V.pdf'])   # (WA350 W lives in the final folder only) the only WA700 / WA900 in Extra


if __name__ == '__main__':
    unittest.main(verbosity=2)