"""Narrow, idempotent hooks; ambiguous stamped pages remain explicit manual-review entries."""
from pathlib import Path
import ast,shutil

def patch(text):
    newline='\r\n' if '\r\n' in text else '\n'
    def insert(anchor,body,marker):
        nonlocal text
        if marker in text:return
        assert text.count(anchor)==1,'Unsupported stamp hook: '+anchor
        text=text.replace(anchor,body.replace('\n',newline)+anchor)
    insert('    from mbs_rpt import reading as rpt_reading',
      '    from mbs_stamp_identity import hint as stamp_hint\n    stamped = stamp_hint(page)\n    if stamped:return stamped + "\\n" + tekst\n','from mbs_stamp_identity import hint as stamp_hint')
    if 'from mbs_stamp_identity import classify as stamp_classify' not in text:
        node=next(n for n in ast.parse(text).body if isinstance(n,ast.FunctionDef) and n.name=='classificeer_stempel')
        first=node.body[1] if isinstance(node.body[0],ast.Expr) and isinstance(node.body[0].value,ast.Constant) and isinstance(node.body[0].value.value,str) else node.body[0]
        lines=text.splitlines(keepends=True)
        lines.insert(first.lineno-1,('    from mbs_stamp_identity import classify as stamp_classify\n    stamp_handled, stamped = stamp_classify(tekst)\n    if stamp_handled:return stamped\n').replace('\n',newline));text=''.join(lines)
    insert('                # Tekenrijk + geen Instort-tabel = kandidaat-_W (AWK-padenstempel).',
      '                if "LOCALCHAT_STAMP:" in tekst:\n                    plan.append({"soort":"onbevestigd", "vorm":bestand + " | bronpagina " + str(nr+1), "bron":bestand, "pagina":nr, "nummer":gelezen, "totaal":totaal, "reden":"Elementcel gelezen; bladrol of identiteit onbevestigd. Plotcode niet als armatuurblad gebruikt."})\n                    continue\n','Plotcode niet als armatuurblad gebruikt.')
    insert('                    elif soort == "bron_mist":',
      '                    elif soort == "onbevestigd":\n                        ontbreekt += 1\n                        meld_ontbrekend(stap["vorm"], stap["reden"])\n                        log("Handmatige controle: " + stap["vorm"] + " | " + stap["reden"])\n',
      'log("Handmatige controle: " + stap["vorm"]')
    ast.parse(text);return text

def apply(package):
    package=Path(package);p=package/'mbs_webapp.py'
    if p.exists():
        text=p.read_bytes().decode('utf8')
        if 'def _pagina_tekst_voor_classificatie' in text:p.write_bytes(patch(text).encode('utf8'))
    shutil.copy2(Path(__file__).with_name('stamp_identity.py'),package/'mbs_stamp_identity.py')
