"""Independent factory-name findings; never substitutes physically different articles."""

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
    def checked(page,regels,tabelgebied,woorden,catalogus=None):
        findings,overview=original(page,regels,tabelgebied,woorden,catalogus=catalogus)
        if catalogus is not None:
            Finding=original.__globals__['Bevinding'];label=original.__globals__['_toon']
            for row in regels:
                match=catalogus.zoek(row.get('omschrijving',''),catalogus.data['factory'])
                if match.get('notatie_regel'):
                    row.setdefault('naam_catalogus',{})['notatie_regel']=match['notatie_regel']
                if match.get('notatie_regel')=='spaties' and match['status'] in {'catalogus_uniek','catalogus_basisnaam'}:
                    findings=[f for f in findings if not (f.code=='ARTIKELNAAM_WIJKT_AF' and f.artikel==label(row))]
                issue=article_finding(row,catalogus)
                if issue:findings.append(Finding(issue['code'],issue['ernst'],label(row),issue['tekst'],rect=row.get('rect'),bron='A'))
        return findings,overview
    return checked
