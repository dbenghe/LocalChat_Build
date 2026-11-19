"""Independent factory-name findings; never substitutes physically different articles."""

import hashlib
import json
import logging
import os
from pathlib import Path
import re


APPROVAL_FILE = 'catalogue-approvals.json'


def drawing_approvals(pdf_path, page_number, catalogue):
    """Read project-local decisions for this exact source PDF and page."""
    if not pdf_path or catalogue is None:
        return {}
    pdf = Path(pdf_path)
    identity = re.match(r'(\d{6})_', pdf.name)
    if not identity:
        return {}
    project = identity[1]
    projects = Path(os.environ.get('PDFCONTROL_PRODUCTIE_ROOT', Path.home() / 'Downloads/PRODUCTIE'))
    approval_path = projects / project / 'Controle/PDFCONTROL' / APPROVAL_FILE
    if not approval_path.is_file():
        return {}
    try:
        if approval_path.stat().st_size > 1024 * 1024:
            raise ValueError('Bestand te groot')
        data = json.loads(approval_path.read_text(encoding='utf-8-sig'))
        factory = catalogue.data['factory']
        if data['project'] != project or data['factory'] != factory:
            return {}
        selected = [item for item in data['entries']
                    if item['pdf'].casefold() == pdf.name.casefold() and item['page'] == page_number]
        if not selected:
            return {}
        with pdf.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        approvals = {}
        for item in selected:
            if item['sha256'] != digest or not item['evidence'].strip():
                continue
            key = item['pos'], item['original']
            if key in approvals:
                raise ValueError('Dubbele artikelgoedkeuring')
            if item['code']:
                match = catalogue.zoek(item['approved'], factory)
                if match['status'] != 'catalogus_uniek' or {r['code'] for r in match['matches']} != {item['code']}:
                    continue
            elif not item['approved'].strip():
                continue
            approvals[key] = item
        return approvals
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        logging.getLogger(__name__).warning('Projectgoedkeuringen niet geladen: %s', error)
        return {}


def article_finding(row,catalogue):
    if catalogue is None:return None
    description=str(row.get('omschrijving','')).strip()
    if not description:return None
    result=catalogue.zoek(description,catalogue.data['factory'])
    state=result.get('status')
    if state in {'catalogus_uniek','catalogus_basisnaam'}:return None
    factory=catalogue.data['factory'].title()
    if state=='niet_gevonden':
        return {'code':'ARTIKEL_NIET_IN_FABRIEKSCATALOGUS','ernst':'CONTROLEREN',
          'tekst':f"Niet gevonden in de artikellijst van MBS {factory}: '{description}'. Geen volledige catalogusnaam herkend; lengte, diameter, materiaal en afwerking niet automatisch vervangen. Bevestig het juiste fabrieksartikel vóór import."}
    return {'code':'ARTIKEL_FABRIEKSCATALOGUS_ONBEVESTIGD','ernst':'CONTROLEREN',
      'tekst':f"Artikelkoppeling aan MBS {factory} niet bevestigd ({state}): '{description}'. Geen unieke, conflictvrije catalogusidentiteit; geen automatische vervanging."}

UNAVAILABLE_TEXT='Fabriekscatalogus niet gecontroleerd: geen eenduidig bevestigde fabriek met een geldige actuele artikellijst beschikbaar. Artikelbenamingen zijn niet gevalideerd tegen de fabriekslijst; maatvoering en overige controles blijven afzonderlijk gelden.'


def catalogue_control(original):
    """Keep catalogue findings even when the geometric checker returns early."""
    from functools import wraps
    @wraps(original)
    def checked(page, regels, tabelgebied, woorden, catalogus=None, pdf_path=None):
        findings,overview=original(page,regels,tabelgebied,woorden,catalogus=catalogus)
        if catalogus is not None:
            Finding=original.__globals__['Bevinding'];label=original.__globals__['_toon']
            approvals = drawing_approvals(pdf_path, page.number + 1, catalogus)
            for row in regels:
                approved = approvals.get((row.get('pos'), row.get('omschrijving')))
                if approved:
                    catalogue_ref = approved['code'] or 'alleen projectacceptatie, zonder cataloguscode'
                    message = (f"Goedgekeurd voor dit project: {approved['original']} → {approved['approved']} "
                               f"({catalogue_ref}). Bron: {approved['evidence']}.")
                    row.setdefault('naam_catalogus', {})['projectgoedkeuring'] = approved
                    findings.append(Finding('ARTIKEL_PROJECTEQUIVALENT_BEVESTIGD', 'INFO',
                                            label(row), message, rect=row.get('rect'), bron='A'))
                    continue
                match=catalogus.zoek(row.get('omschrijving',''),catalogus.data['factory'])
                if match.get('notatie_regel'):
                    row.setdefault('naam_catalogus',{})['notatie_regel']=match['notatie_regel']
                if match.get('notatie_regel')=='spaties' and match['status'] in {'catalogus_uniek','catalogus_basisnaam'}:
                    findings=[f for f in findings if not (f.code=='ARTIKELNAAM_WIJKT_AF' and f.artikel==label(row))]
                issue=article_finding(row,catalogus)
                if issue:findings.append(Finding(issue['code'],issue['ernst'],label(row),issue['tekst'],rect=row.get('rect'),bron='A'))
        return findings,overview
    return checked
