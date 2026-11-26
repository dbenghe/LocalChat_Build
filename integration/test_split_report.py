import hashlib
import json
import sys
import tempfile
from pathlib import Path

import fitz

sys.path.insert(0, str(Path(sys.argv[1]).resolve()))
import mbs_rapport


def pdf_text(path):
    with fitz.open(path) as doc:
        return " ".join(" ".join(page.get_text().split()) for page in doc)


def finding(code, severity, article):
    return {"code": code, "ernst": severity, "artikel": article, "tekst": "Detail " + article}


with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    data = root / "resultaten.json"
    records = [
        {"bestand": "DRAW_FAILED.pdf", "status": "NIET OK", "bevindingen": [
            finding("MAAT_ONTBREEKT", "FOUT", "MISSING_X"),
            finding("ARTIKEL_NIET_OP_TEKENING", "CONTROLEREN", "MISSING_ARTICLE"),
        ]},
        {"bestand": "DRAW_REVIEW.pdf", "status": "CONTROLEREN", "bevindingen": [
            finding("MAAT_NIET_TE_KOPPELEN", "CONTROLEREN", "UNCERTAIN_Y"),
            finding("POSNR_NIET_IN_TABEL", "CONTROLEREN", "NO_ARTICLE_ROW"),
            finding("ARTIKEL_NIET_IN_FABRIEKSCATALOGUS", "CONTROLEREN", "UNKNOWN_CATALOGUE"),
        ]},
        {"bestand": "DRAW_ROWS_ONLY.pdf", "status": "NIET_OK", "artikelen": [
            {"status": "NIET OK", "pos": "XYZ1", "omschrijving": "Check rij", "note": "Ontbrekende / onduidelijke Y-maat."}
        ]},
        {"bestand": "DRAW_OK.pdf", "status": "OK", "bevindingen": [finding("INFO_ONLY", "INFO", "APPROVED")]},
    ]
    data.write_text(json.dumps({"tekeningen": records}), encoding="utf-8")
    before = hashlib.sha256(data.read_bytes()).hexdigest()
    mbs_rapport.GEGEVENS = str(data)
    main = root / "RAPPORT.pdf"
    info = mbs_rapport.genereer(main, alleen_problemen=False)
    assert info["scope"] == "all" and info["paginas"] == 1
    reports = info["reports_by_status"]
    assert (info["met_bevinding"], info["zonder_bevinding"]) == (3, 1)
    assert reports["NIET_OK"]["tekeningen"] == 2
    assert reports["CONTROLEREN"]["tekeningen"] == 1
    failed = pdf_text(reports["NIET_OK"]["pad"])
    review = pdf_text(reports["CONTROLEREN"]["pad"])
    for expected in ("Maat ontbreekt", "Tabel naar tekening", "MISSING_X", "MISSING_ARTICLE", "XYZ1", "onduidelijke Y-maat."):
        assert expected in failed, expected
    for expected in ("Maatkoppeling onzeker", "Tekening naar tabel", "Cataloguscontrole", "UNKNOWN_CATALOGUE"):
        assert expected in review, expected
    assert "DRAW_REVIEW" not in failed and "DRAW_FAILED" not in review and "DRAW_OK" not in failed + review
    assert "annex" not in info and hashlib.sha256(data.read_bytes()).hexdigest() == before
    with fitz.open(main) as doc:
        assert {link["file"] for link in doc[0].get_links()} == {"RAPPORT_NIET_OK.pdf", "RAPPORT_CONTROLEREN.pdf"}
    data.write_text(json.dumps({"tekeningen": records[-1:]}), encoding="utf-8")
    empty = mbs_rapport.genereer(root / "RAPPORT_EMPTY.pdf")
    for status, report in empty["reports_by_status"].items():
        assert report["tekeningen"] == 0 and "Geen tekeningen" in pdf_text(report["pad"]), status
print("PASS: status, severity, category, article rows, relative links, empty groups, immutable input")
