# Partijgewijze beoordeling van tekenwaarschuwingen

Gebruik deze werkwijze bij een volledig tekenpakket of een verzoek om resterende waarschuwingen te beoordelen. Behandel de volledige gevraagde selectie zelfstandig; vraag niet na iedere pagina of de volgende waarschuwing moet worden bekeken. Werk in beheersbare groepen pagina's zonder de selectie te verkleinen. Dit is een beoordelingsprocedure, geen nieuwe technische goedkeuringsregel.

## Volledigheid en voortgang

1. Leg bronbestand, SHA-256, revisie, oorspronkelijke paginanummers, project per pagina en bestaande job_id vast. Inventariseer iedere geselecteerde pagina, ook onbekende tekenindelingen. Meld onleesbare of onbekende pagina's als onopgelost; sla ze niet stilzwijgend over.
2. In LocalChat: lees pdfcontrol_job_summary en alle relevante pagina's van pdfcontrol_job_findings; volg next_offset tot het einde. Gebruik de bestaande voltooide controle. Start alleen opnieuw bij gewijzigde bronnen, controle-instellingen of een bewezen relevante motorcorrectie.
3. Maak per waarschuwing een bewijsregel: pagina/project/element/revisie, artikel en positiecode, tabelhoeveelheid, gematchte fysieke onderdelen, maat/as, bronverwijzing, conclusie en eventueel ontbrekend bewijs. Bewaar voortgang buiten het actieve modelcontext zodat compacteren geen herstart veroorzaakt.
4. Bekijk alle waarschuwingen en de relevante gehele tekening, tabel, detail en andere aanzichten. Gebruik pdfcontrol_read_page voor bronpagina of bbox; controleer rotatie en paginanummer. OCR en vectoranalyse vullen elkaar aan. Claim geen visuele inspectie als alleen tekst is gelezen. Respecteer de bestaande toestemming voor beeldverwerking.

## Identiteit, aanwezigheid en maatvoering afzonderlijk toetsen

- Onderscheid artikelidentiteit, aanwezigheid, aantal, profielafmetingen en plaatsmaat. Een gevonden profiel bevestigt niet automatisch de artikelcode of plaatsing. Een ontbrekende koppeling bewijst niet dat het artikel fysiek ontbreekt.
- Tabelhoeveelheid, aantal tekstlabels en aantal fysieke onderdelen zijn verschillende gegevens. Gebruik kleur uitsluitend als zoekhulp. Gelijke aantallen, namen of kleuren bewijzen geen alias tussen verschillende codes.
- Verbind de maat met de echte maatlijn, hulplijnen, eindstreepjes en het juiste onderdeel. Volledige aaneengesloten maatketens mogen over verspringende tekstregels lopen. Leen geen getal uit een buurvak, andere positie of niet-verbonden maatlijn.
- Afmetingen en plaatsmaten kunnen in verschillende aanzichten staan. Koppel aanzichten met aantoonbare geometrie, oriëntatie en elementidentiteit. Een nominale profielmaat is geen plaatsmaat. Een uit vectorgeometrie afgeleide coördinaat is geen afgedrukte maat. Meet geen technische maten uit schermpixels.
- Identieke profielen met concurrerende positiecodes blijven onopgelost zolang de individuele toewijzing ontbreekt. Verdeel ze niet uitsluitend op basis van tabelaantallen.
- Bewaar kwalificaties zoals (rd), (s), SZ/MZ en revisie. Ga niet automatisch uit van uitwisselbaarheid. Hetzelfde label op verschillende regels kan verschillende representaties hebben; controleer de echte koppeling en vereiste assen voordat iets wordt goedgekeurd.
- Zoek aanvullende details alleen in toegankelijke en toegestane bronnen. Voor UNI: vergelijk interne project-/elementidentiteit, HEADER, SLABDATE, revisie, contour en openingen; een bestandsnaam of gelijk nummer alleen is onvoldoende. Onvolledige headers vragen onderzoek, geen stilzwijgende goedkeuring of uitsluiting.
- Wanneer IFC uitgesloten is, gebruik of vraag geen IFC als vervolgroute. Geef aan welke koppeling met de toegankelijke tekeningen niet te bewijzen is.

## Uitkomst en leren

Classificeer afzonderlijk: bewezen herkenningsfout, bewezen technische afwijking, of onbevestigd. Behoud echte afwijkingen en onbevestigde koppelingen. Maak geen automatische OK-regel van een onbevestigde hypothese. Voorbeelden van niet-universele gevolgtrekkingen: code 3000 is niet automatisch 3001(rd); SH103 is niet automatisch SH100RD; een herkend profiel van 150x20 bevestigt zijn linker X-plaatsmaat niet.

Een algemene herkenningscorrectie vereist een reproduceerbare oorzaak, positieve en negatieve regressiegevallen en controle van volledige brondekking. Pas relevante gedeelde correcties toe en verifieer ze in zowel de afzonderlijke PDFCONTROL-installatie als de door LocalChat beheerde motor, met backups en behoud van andere wijzigingen. Rapporteer iedere nog niet bijgewerkte installatie. Wijzig geen technische acceptatiecriteria om minder waarschuwingen te krijgen.

Werk alle onafhankelijke controles af. Stel alleen noodzakelijke vragen over concrete ontbrekende feiten; bundel die na de beoordeling en herhaal geen al beantwoorde keuze zonder nieuwe tegenstrijdigheid. Laat iedere onbevestigde waarschuwing met bewijs in het eindoverzicht staan, ook als de gebruiker geen aanvullende bron heeft.

Schrijf gegenereerde documenten, uitsnedebijschriften, galerijen en beoordelingsrapporten in het Nederlands. Chatberichten volgen de expliciet gekozen antwoordtaal. Toon op verzoek uitsneden direct in de chat met bronpagina en artikelregel. Markeer geen willekeurig symbool als het betrokken onderdeel nog niet geïdentificeerd is. Lever een gecombineerd overzicht met aantallen en bronverwijzingen; vraag niet om toestemming om telkens de volgende pagina te bekijken.

Deze werkwijze kan toekomstige beoordelingen sturen wanneer de skill wordt gelezen. Zij vervangt geen programmeercorrectie in de herkenningsmotor en garandeert geen volledige herkenning van iedere tekenindeling.


## Verplichte rapportopmaak

Gebruik voor beoordelingsrapporten uit de chat `pdfcontrol_review_report(project, files, findings)`. Als de tool nog verborgen is, ontsluit de bestandsgroep met localchat_enable_tools(group="files"). Geef de exacte projectrelatieve bron-PDF's en vastgestelde bevindingen door: file, page (1-gebaseerd), article, message in het Nederlands, severity en eventueel een bewezen bbox in ongeroteerde PDF-coordinaten. Een bbox mag ontbreken; verzin geen positie. De tool doet geen nieuwe technische controle en is geen goedkeuring. Alle pagina's van de geselecteerde bronnen blijven in het rapport. Gebruik geen eigen script met grote koppen, afgesneden tekeningen of een uitvergrote artikelstrip als vervanging van het oorspronkelijke blad.

De gedeelde renderer bewaart de volledige oorspronkelijke planpagina als vectorinhoud, inclusief renvooi linksonder en titelblok/project/tekening/merk rechtsonder, ongeacht hun werkelijke plaats op het bronblad. Compacte toelichting staat buiten het brongebied. Details kunnen afzonderlijk worden geraadpleegd, maar vervangen het volledige blad niet. Veel of lange waarschuwingen blijven volledig in de bevindingenpagina's; ze worden niet weggelaten om op de tekening te passen. Rotatie en bestaande zichtbare annotaties worden op een kopie behouden; bronbestanden blijven ongewijzigd. Deze opmaak geldt voor native controles, chatbeoordelingen en OUD/NIEUW-rapporten in beide installaties. Regeneratie bewaart de opgeslagen controlebladen: nieuwe jobs behouden deze opmaak; oude archieven worden niet automatisch herschreven.
