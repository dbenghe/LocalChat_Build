from collections import Counter
from pathlib import Path

import fitz
from mbs_report_layout import ARCHIVE_NOTICE


STATUS = ("NIET_OK", "CONTROLEREN")
CATEGORIES = {
    "MAAT_ONTBREEKT": "Maat ontbreekt",
    "MAATVAK_ZONDER_MAAT": "Maat ontbreekt",
    "MAATVOERING_EEN_AS": "Maat ontbreekt",
    "MAAT_GAINE_LENGTE": "Maat ontbreekt",
    "MAATKETEN_ONTBREEKT": "Maat ontbreekt",
    "SPARING_ZONDER_MAAT": "Maat ontbreekt",
    "TEMPEX_SPARING_ONTBREEKT": "Maat ontbreekt",
    "MAAT_NIET_TE_KOPPELEN": "Maatkoppeling onzeker",
    "PROFIEL_PLAATSMAAT_ONBEVESTIGD": "Maatkoppeling onzeker",
    "MAATVOERING_ONLEESBAAR": "Maat onleesbaar",
    "KOZIJNMAAT_VERSCHIL": "Maat wijkt af",
    "SPARING_DIKTE_ONDUIDELIJK": "Maatkoppeling onzeker",
    "SPARING_3D_ONDUIDELIJK": "Maatkoppeling onzeker",
    "ARTIKEL_HERKEND_PLAATSMAAT_ONBEVESTIGD": "Maatkoppeling onzeker",
    "ARTIKEL_NIET_OP_TEKENING": "Tabel naar tekening",
    "WAPENING_NIET_OP_TEKENING": "Tabel naar tekening",
    "POSNR_NIET_IN_TABEL": "Tekening naar tabel",
    "MERK_NIET_IN_RENVOOI": "Tekening naar tabel",
    "WAPENING_NIET_IN_BUIGSTAAT": "Tekening naar tabel",
    "ARTIKELNAAM_WIJKT_AF": "Cataloguscontrole",
    "ARTIKEL_NIET_IN_FABRIEKSCATALOGUS": "Cataloguscontrole",
}


def status_code(value):
    return str(value or "").strip().upper().replace(" ", "_")


def findings(drawing, layout):
    rows = []
    detailed_positions = set()
    for finding in layout._problemen(drawing):
        code = finding.get("code") or ""
        if code in layout.GEEN_EIGEN_REDEN:
            continue
        article = finding.get("artikel") or ""
        category = CATEGORIES.get(code, "Overige controle")
        position = str(finding.get("pos") or article.partition(" - ")[0]).strip()
        message = finding.get("melding") or finding.get("bericht") or finding.get("tekst") or code
        rows.append((finding["ernst"], category, article, message))
        if position:
            detailed_positions.add((position, finding["ernst"], str(message).strip()))
    for row in drawing.get("artikelen", []):
        status = status_code(row.get("status"))
        if status not in STATUS:
            continue
        severity = "FOUT" if status == "NIET_OK" else "CONTROLEREN"
        position = str(row.get("pos") or "").strip()
        note = str(row.get("note") or "").strip()
        if position and (position, severity, note) in detailed_positions:
            continue
        article = " - ".join(filter(None, (position, row.get("omschrijving"))))
        category = "Artikelcontrole"
        if note.startswith("Ontbrekende / onduidelijke ") and ("X-maat" in note or "Y-maat" in note):
            category = "Maat ontbreekt of is onduidelijk"
        rows.append((severity, category, article, note or "Controleer deze renvooiregel."))
    return list(dict.fromkeys(rows)) or [("-", "Uitkomst", "-", "Geen detailmelding opgeslagen.")]


def save(doc, path):
    doc.save(str(path), garbage=3, deflate=True)
    return doc.page_count


def generate(layout, data, output, include_drawings=True, progress=None):
    drawings = data.get("tekeningen", [])
    if not drawings:
        raise ValueError("Geen gecontroleerde tekeningen gevonden.")
    output = Path(output or layout.rapportnaam(drawings))
    buckets = {status: [] for status in STATUS}
    counts = Counter(status_code(drawing.get("status")) for drawing in drawings)
    notes = Counter()
    for drawing in drawings:
        status = status_code(drawing.get("status"))
        if status in buckets:
            buckets[status].append(drawing)
        notes.update(finding.get("code") for finding in drawing.get("bevindingen", [])
                     if finding.get("ernst") == "INFO")

    reports = {}
    for status, selected in buckets.items():
        label = status.replace("_", " ")
        path = output.with_name(f"{output.stem}_{status}.pdf")
        if progress:
            progress(f"rapport {label}", 0, len(selected))
        if selected and include_drawings:
            detail = layout.genereer_uitgebreid(
                str(path), tekeningen_bijvoegen=True, alleen_problemen=False,
                voortgang=progress, rapportstatus=label,
                data=dict(data, tekeningen=[dict(t, status=status,
                    bureau=t.get("bureau") or "ONBEKEND BUREAU",
                    project=str(t.get("project") or "?")) for t in selected]),
            )
            reports[status] = {key: detail[key] for key in (
                "pad", "paginas", "tekeningen", "bijgevoegde_tekeningen", "ontbrekende_bijlagen")}
            continue
        with fitz.open() as doc:
            sheet = layout.Blad(doc, f"PDFCONTROL | {label}")
            sheet.titel(label)
            if data.get("archief") is True:
                sheet.regel(ARCHIVE_NOTICE)
            sheet.regel(f"{len(selected)} tekeningen in deze groep")
            sheet.wit()
            entries = []
            for drawing in sorted(selected, key=lambda item: (str(item.get("project") or ""), item.get("bestand") or "")):
                name = drawing.get("bestand") or "-"
                for severity, category, article, message in findings(drawing, layout):
                    entries.append((name, severity, category, article, message))
            if entries:
                sheet.tabel([("Tekening", 155), ("Ernst", 75), ("Rubriek", 126),
                             ("Artikel / positie", 155), ("Bevinding", 253)], entries,
                            regelhoogte=10, kopmaat=8, tekstmaat=7.5)
            else:
                sheet.regel(f"Geen tekeningen met status {label}.")
            pages = save(doc, path)
        reports[status] = {"pad": str(path), "paginas": pages, "tekeningen": len(selected)}

    with fitz.open() as doc:
        sheet = layout.Blad(doc, "PDFCONTROL")
        sheet.titel("Controleoverzicht")
        if data.get("archief") is True:
            sheet.regel(ARCHIVE_NOTICE)
        sheet.regel(f"{len(drawings)} tekeningen gecontroleerd | {counts['OK']} OK", size=11, hoogte=20)
        unknown = len(drawings) - sum(counts[status] for status in (*STATUS, "OK"))
        if unknown:
            sheet.regel(f"{unknown} tekeningen met onbekende status. Controleer het resultatenbestand.")
        sheet.wit()
        definitions = {
            "NIET_OK": "Minstens een foutmelding of een renvooiregel met status NIET OK.",
            "CONTROLEREN": "Minstens een te beoordelen punt, zonder fout die tot NIET OK leidt.",
        }
        for status, report in reports.items():
            sheet.titel(f"{status.replace('_', ' ')} | {report['tekeningen']} tekeningen", size=13)
            sheet.regel(definitions[status])
            link_y = sheet.y
            filename = Path(report["pad"]).name
            sheet.regel(f"Open {filename}", kleur=(0.1, 0.32, 0.65))
            link_rect = fitz.Rect(layout.MARGE, link_y - 2, layout.MARGE + min(690, 6 * (5 + len(filename))), link_y + 10)
            sheet.page.insert_link({"kind": fitz.LINK_GOTOR, "from": link_rect, "file": filename, "page": 0, "to": fitz.Point(0, 0)})
            sheet.wit(12)
        sheet.regel("De status is de uitkomst van PDFCONTROL. De revisiestatus in het titelblok staat hier los van.")
        approvals = notes.pop("ARTIKEL_PROJECTEQUIVALENT_BEVESTIGD", 0)
        if approvals:
            sheet.regel(f"{approvals} bevestigde projecteigen artikelequivalenties.")
        if notes:
            sheet.regel(f"{sum(notes.values())} informatieve meldingen zijn opgenomen in het resultatenbestand.")
        pages = save(doc, output)
    info = {"pad": str(output), "paginas": pages, "tekeningen": len(drawings),
            "met_bevinding": sum(len(group) for group in buckets.values()),
            "zonder_bevinding": counts["OK"], "reports_by_status": reports}
    return info
