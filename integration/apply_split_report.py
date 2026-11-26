"""Install status-specific overview and full reports in each PDFCONTROL engine."""
import ast
from pathlib import Path
import shutil


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError("Unsupported report implementation near " + old)
    return source.replace(old, new)


def apply(package):
    package = Path(package)
    target = package / "mbs_rapport.py"
    if not target.is_file():
        return
    raw = target.read_bytes()
    source = raw.decode("utf-8").replace("\r\n", "\n")
    original = "def genereer(uit_pad=None, tekeningen_bijvoegen=True, alleen_problemen=True,"
    if "from mbs_split_report import generate" not in source and "import mbs_split_report as split" not in source:
        wrapper = """def genereer(uit_pad=None, tekeningen_bijvoegen=True, alleen_problemen=True,
             voortgang=None):
    import sys
    from mbs_split_report import generate
    info = generate(sys.modules[__name__], laad(), uit_pad, tekeningen_bijvoegen, voortgang)
    info["scope"] = "problems" if alleen_problemen else "all"
    return info


"""
        source = replace_once(source, original, wrapper + original.replace("genereer(", "genereer_uitgebreid("))
    else:
        source = source.replace("def genereer(uit_pad=None, tekeningen_bijvoegen=False,", "def genereer(uit_pad=None, tekeningen_bijvoegen=True,")

    if "import mbs_split_report as split" not in source:
        source = replace_once(source, "    from mbs_split_report import generate\n    info = generate(",
            '''    import mbs_report_layout as layout
    import mbs_split_report as split
    if not hasattr(layout, "clip_preframed_sheets") or not hasattr(split, "ARCHIVE_NOTICE"):
        from importlib import reload
        reload(layout)
        reload(split)
    info = split.generate(''')

    if 'rapportstatus=""' not in source:
        signature = "def genereer_uitgebreid(uit_pad=None, tekeningen_bijvoegen=True, alleen_problemen=True,"
        a = source.index(signature)
        b = source.index("voortgang=None):", a)
        source = source[:b] + source[b:].replace('voortgang=None):', 'voortgang=None, *, data=None, rapportstatus=""):', 1)
        source = replace_once(source, "    data = laad()\n", "    if data is None:\n        data = laad()\n")
        source = replace_once(source, '    blad.titel("Controlerapport tekeningen", size=20, ruimte_na=4)',
            '    titel = f"Controlerapport tekeningen - {rapportstatus}" if rapportstatus else "Controlerapport tekeningen"\n    blad.titel(titel, size=20, ruimte_na=4)')
        source = replace_once(source, "    from mbs_tekeningstatus import samenvatting\n",
            """    if rapportstatus:
        import sys
        from mbs_split_report import findings
        rubrieken = Counter(category for t in tekeningen
                             for _, category, _, _ in findings(t, sys.modules[__name__]))
        blad.tabel([("Rubriek", 360), ("Bevindingen", 140)], rubrieken.most_common())

    from mbs_tekeningstatus import samenvatting
""")
        source = replace_once(source, "    ruimte = MAX_BIJLAGEN\n",
            "    ruimte = len(te_voegen) if rapportstatus else MAX_BIJLAGEN\n    bijgevoegd = 0\n    ontbrekende_bijlagen = []\n")
        source = replace_once(source, """                if pad and os.path.exists(pad):
                    try:
                        with fitz.open(pad) as bron:
                            doc.insert_pdf(bron)
                    except Exception:
                        pass
""", """                try:
                    if not pad:
                        raise FileNotFoundError(t["bestand"])
                    with fitz.open(pad) as bron:
                        doc.insert_pdf(bron)
                    bijgevoegd += 1
                except Exception:
                    ontbrekende_bijlagen.append(t["bestand"])
""")
        source = replace_once(source, '    melden("opslaan", totaal_mee, totaal_mee)\n',
            """    if ontbrekende_bijlagen:
        blad = Blad(doc, "Controlerapport - bijlagen")
        blad.titel("Ontbrekende of onleesbare tekeningen")
        blad.tabel([("Tekening", 750)], [[naam] for naam in ontbrekende_bijlagen])

    melden("opslaan", totaal_mee, totaal_mee)
""")
        source = replace_once(source, '            "gecontroleerd": len(gecontroleerd),\n',
            '            "gecontroleerd": len(gecontroleerd),\n            "bijgevoegde_tekeningen": bijgevoegd, "ontbrekende_bijlagen": ontbrekende_bijlagen,\n')
        if "Uitgevoerde controles per tekening" not in source:
            source = replace_once(source, "        # ---- 2c. de gecontroleerde tekeningen eronder",
                """            if rapportstatus:
                blad.regel("Uitgevoerde controles per tekening", size=10, font="hebo", hoogte=20)
                volledig = [
                    [tekening["bestand"] if index == 0 else "", regel.get("omschrijving", ""),
                     regel.get("status", ""), regel.get("note", "")]
                    for tekening in groep for index, regel in enumerate(tekening.get("artikelen") or [])
                ]
                if volledig:
                    blad.tabel([("Tekening",145),("Controle / artikel",200),("Resultaat",75),("Toelichting",350)], volledig)

        # ---- 2c. de gecontroleerde tekeningen eronder""")

    if 'if data.get("archief") is True:' not in source:
        source = replace_once(source, "    blad.titel(titel, size=20, ruimte_na=4)\n",
            '''    blad.titel(titel, size=20, ruimte_na=4)
    if data.get("archief") is True:
        from mbs_report_layout import ARCHIVE_NOTICE
        blad.regel(ARCHIVE_NOTICE)
''')

    source = source.replace('if not hasattr(layout, "ARCHIVE_NOTICE") or not hasattr(split, "ARCHIVE_NOTICE"):',
                            'if not hasattr(layout, "clip_preframed_sheets") or not hasattr(split, "ARCHIVE_NOTICE"):')
    if "clip_preframed_sheets(bron)" not in source:
        source = replace_once(source, "                        doc.insert_pdf(bron)\n",
            "                        from mbs_report_layout import clip_preframed_sheets\n"
            "                        clip_preframed_sheets(bron)\n"
            "                        doc.insert_pdf(bron)\n")
    ast.parse(source)
    shutil.copy2(Path(__file__).with_name("split_report.py"), package / "mbs_split_report.py")
    data = (source.replace("\n", "\r\n") if b"\r\n" in raw else source).encode("utf-8")
    if raw != data:
        target.write_bytes(data)
