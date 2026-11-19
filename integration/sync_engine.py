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
    import importlib.util
    spec=importlib.util.spec_from_file_location('owned_extraction_hooks',integration/'apply_extraction_layout.py')
    hooks=importlib.util.module_from_spec(spec);spec.loader.exec_module(hooks);hooks.apply(package)
    alias_path=package/'mbs_controles_extra.py'
    if alias_path.is_file():
        spec=importlib.util.spec_from_file_location('kozijn_alias_hooks',integration/'apply_kozijn_alias.py')
        alias_hooks=importlib.util.module_from_spec(spec);spec.loader.exec_module(alias_hooks);alias_hooks.apply(alias_path)
        spec=importlib.util.spec_from_file_location("dimension_ink_hooks",integration/"apply_dimension_ink.py")
        ink_hooks=importlib.util.module_from_spec(spec);spec.loader.exec_module(ink_hooks);ink_hooks.apply(alias_path)
        spec=importlib.util.spec_from_file_location("bundle_coverage_hooks",integration/"apply_bundle_coverage.py")
        bundle_hooks=importlib.util.module_from_spec(spec);spec.loader.exec_module(bundle_hooks);bundle_hooks.apply(package)
        spec=importlib.util.spec_from_file_location("article_identity_hooks",integration/"apply_article_identity.py")
        identity_hooks=importlib.util.module_from_spec(spec);spec.loader.exec_module(identity_hooks);identity_hooks.apply(package)
        spec=importlib.util.spec_from_file_location("profile_projection_hooks",integration/"apply_profile_projection.py")
        profile_hooks=importlib.util.module_from_spec(spec);spec.loader.exec_module(profile_hooks);profile_hooks.apply(package)
        spec=importlib.util.spec_from_file_location("ifc_reference_hooks",integration/"apply_ifc_reference.py")
        ref_hooks=importlib.util.module_from_spec(spec);spec.loader.exec_module(ref_hooks);ref_hooks.apply(package)
    spec=importlib.util.spec_from_file_location('stamp_identity_hooks',integration/'apply_stamp_identity.py')
    stamp_hooks=importlib.util.module_from_spec(spec);spec.loader.exec_module(stamp_hooks);stamp_hooks.apply(package)
    spec=importlib.util.spec_from_file_location('report_layout_hooks',integration/'apply_report_layout.py')
    layout_hooks=importlib.util.module_from_spec(spec);spec.loader.exec_module(layout_hooks);layout_hooks.apply(package)
    spec=importlib.util.spec_from_file_location('factory_catalogue_hooks',integration/'apply_factory_catalogue.py')
    factory_hooks=importlib.util.module_from_spec(spec);spec.loader.exec_module(factory_hooks);factory_hooks.apply(package)
    shutil.copy2(integration/'document_markdown.py',package/'mbs_document_markdown.py')
    shutil.copy2(integration/'legacy_doc_to_docx.ps1',package/'legacy_doc_to_docx.ps1')
    shutil.copy2(integration/'pdfcontrol_bridge.py',package/'octop_mcp.py')
    shutil.copy2(integration/'catalogue_update.py',package/'catalogue_update.py')
    shutil.copy2(integration/'import_audit.py',package/'import_audit.py')
    shutil.copy2(integration/'drawing_import.py',package/'drawing_import.py')
    shutil.copy2(integration/'drawing_page.py',package/'drawing_page.py')
    shutil.copy2(integration/'fast_import.py',package/'fast_import.py')
    shutil.copy2(integration/'fix_missing_pairs.py',package/'fix_missing_pairs.py')
    shutil.copy2(integration/'compare_server_revisions.py',package/'compare_server_revisions.py')
    shutil.copy2(integration/'ifc_reference.py',package/'mbs_ifc_reference.py')
    shutil.copy2(integration/'mbs_localchat_layout.py',package/'mbs_localchat_layout.py')
    registered=json.loads(manifest.read_text(encoding='utf-8'));
    for name in ['mbs_document_markdown.py','legacy_doc_to_docx.ps1']:
        registered['sha256'][name]=hashlib.sha256((package/name).read_bytes()).hexdigest()
    for name in ['mbs_artikelcatalogus.py','mbs_catalogue_notation.py']:
        if (package/name).is_file():registered['sha256'][name]=hashlib.sha256((package/name).read_bytes()).hexdigest()
    if (package/'mbs_factory_catalogue.py').is_file():registered['sha256']['mbs_factory_catalogue.py']=hashlib.sha256((package/'mbs_factory_catalogue.py').read_bytes()).hexdigest()
    if alias_path.is_file():registered['sha256']['mbs_controles_extra.py']=hashlib.sha256(alias_path.read_bytes()).hexdigest()
    if (package/'mbs_pdf_checker_pdfcontrol.py').is_file():registered['sha256']['mbs_pdf_checker_pdfcontrol.py']=hashlib.sha256((package/'mbs_pdf_checker_pdfcontrol.py').read_bytes()).hexdigest()
    if (package/'mbs_webapp.py').is_file():registered['sha256']['mbs_webapp.py']=hashlib.sha256((package/'mbs_webapp.py').read_bytes()).hexdigest()
    registered['sha256']['mbs_localchat_layout.py']=hashlib.sha256((package/'mbs_localchat_layout.py').read_bytes()).hexdigest();registered['sha256']['catalogue_update.py']=hashlib.sha256((package/'catalogue_update.py').read_bytes()).hexdigest()
    registered['sha256']['drawing_page.py']=hashlib.sha256((package/'drawing_page.py').read_bytes()).hexdigest()
    registered['sha256']['drawing_import.py']=hashlib.sha256((package/'drawing_import.py').read_bytes()).hexdigest()
    registered['sha256']['import_audit.py']=hashlib.sha256((package/'import_audit.py').read_bytes()).hexdigest()
    registered['sha256']['fast_import.py']=hashlib.sha256((package/'fast_import.py').read_bytes()).hexdigest()
    registered['sha256']['fix_missing_pairs.py']=hashlib.sha256((package/'fix_missing_pairs.py').read_bytes()).hexdigest()
    registered['sha256']['compare_server_revisions.py']=hashlib.sha256((package/'compare_server_revisions.py').read_bytes()).hexdigest()
    registered['sha256']['mbs_ifc_reference.py']=hashlib.sha256((package/'mbs_ifc_reference.py').read_bytes()).hexdigest()
    for name in ['mbs_vergelijk_pdf.py','mbs_report_layout.py','mbs_webapp.py','mbs_stamp_identity.py']:
        if (package/name).is_file():registered['sha256'][name]=hashlib.sha256((package/name).read_bytes()).hexdigest()
    manifest.write_text(json.dumps(registered,indent=2)+'\n',encoding='utf-8')
    print('PASS: LocalChat owned engine manifest verified; bridge staged. Legacy installation was not read or changed.')

if __name__=='__main__':main()
