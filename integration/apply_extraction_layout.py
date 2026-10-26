"""Idempotent hooks for the verified owned engine; never imports legacy installations."""
from pathlib import Path


def apply(package):
    path=Path(package)/'mbs_webapp.py'
    if not path.is_file():return
    original=path.read_text(encoding='utf-8');text=original
    hook='    from mbs_localchat_layout import abwn_classify'
    if hook not in text:
        start=text.find('    # ABWN Tekla title families:');end=text.find('    # Tekla full bare BD/TR sheet marks',start)
        if start<0 or end<0:raise ValueError('Owned engine ABWN classifier hook is unsupported')
        text=text[:start]+hook+'\n    handled, abwn = abwn_classify(tekst)\n    if handled:return abwn\n'+text[end:]
    if '    from mbs_localchat_layout import abwn_project' not in text:
        anchor='    from mbs_localchat_layout import project as specials_project'
        if text.count(anchor)!=1:raise ValueError('Owned engine project hook is unsupported')
        text=text.replace(anchor,'    from mbs_localchat_layout import abwn_project\n    abwn = abwn_project(tekst)\n    if abwn:return normaliseer_project(abwn)\n'+anchor)
    if '    from mbs_localchat_layout import abwn_bundle_hint' not in text:
        anchor='    if len(tekst.strip()) >= 40:\n        return tekst'
        if text.count(anchor)!=1:raise ValueError('Owned engine page-reading hook is unsupported')
        text=text.replace(anchor,'    if len(tekst.strip()) >= 40:\n        from mbs_localchat_layout import abwn_bundle_hint\n        hint = abwn_bundle_hint(page, tekst)\n        return (hint + "\\n" + tekst) if hint else tekst')
    text=text.replace('else ("blad" if gelezen_prefix else "")),','else ("bron_voorblad" if "LOCALCHAT_ABWN_COVER_PROJECT:" in tekst else ("blad" if gelezen_prefix else ""))),')
    if text!=original:path.write_text(text,encoding='utf-8')
