# Import din desene in LocalChat

Se aplica la crearea/verificarea unei liste de import din desenele proiectului. Foloseste mai intai uneltele locale; nu recrea parsere Excel/PDF in run_command si nu trimite cataloage intregi la model.

## Succesiunea practica

1. Refoloseste proiectul, fabrica si deciziile deja confirmate in conversatie/PROJECT_INFO.md. Nu intreba din nou fabrica dupa compactare daca nu exista surse noi contradictorii. La aceeasi cerere reia pasul ramas, nu toate procedurile.
2. Verifica inventarul/revizia surselor. Pentru o brosura foloseste pdfcontrol_start_extract si completeaza perechile W confirmate. Un raport de control nu reprezinta automat o lista de import.
3. Apeleaza **pdfcontrol_drawing_import** cu project, factory si drawings (1-30 PDF, maximum 100 pagini). Foloseste caile exacte din inventar. Fara template/output, extrage doar dovezile locale. OCR-ul local este implicit activ cand tabelul nu poate fi citit din text; originalele nu se resalveaza.
4. Citeste sumarul si doar fragmentele necesare din report_json: marca, pagina, Posnr, descriere originala, cantitate, coordonate si potrivirea in catalog. V si W se coreleaza pe marca/pozitie; aceeasi pozitie nu se aduna de doua ori. Diferentele intre surse/versiuni raman conflicte.
Daca OCR citeste gresit marca (de exemplu O in loc de 0), nu ghici. Verifica vizual cartusul si transmite mark_bindings cu path, page, merk si evidence. Asocierea explicita se pastreaza ca dovada de revizuit; tabelul extras ramane accesibil si cand marca este neconfirmata.

5. Pentru XLSX, transmite template (lista/sablon cu o singura fila si coloanele reale), output (fisier NOU local) si hoofdregel numai daca este confirmat. Sablonul este sursa pentru schema; randurile lui vechi NU se transfera in lista noua. Lista generata acopera numai desenele selectate. Sursele raman intacte; nu suprascrie lista existenta si nu copia automat pe server.
6. Unealta redeschide XLSX si verifica celulele scrise. Ruleaza apoi **pdfcontrol_compare_import** cu lista generata, catalogul aceleiasi fabrici si desenele. Remediaza constatarile cu dovezile corecte; nu declara complet doar fiindca XLSX exista. Loturile nemodificate se reutilizeaza prin hash, fara o noua extragere/OCR.
7. Pentru FAST/Veenoord, foloseste **pdfcontrol_prepare_fast_import** si regulile salvate: 1 m = 1000; pentru exceptia PIR confirmata, suprafata PE BUCATA in Lengte, Aantal ramane numarul de bucati, fractia strict peste .5 se rotunjeste in sus. Nu generaliza aceasta exceptie la alte articole/fabrici. Pastreaza dimensiunile originale in Instortvoorziening Merk.

## Completarea in lot dupa interpretarea desenelor

Daca valorile nu sunt etichetate pentru parser, modelul trebuie sa interpreteze cotele din plan/sectiune si documentele disponibile. Transmite reviewed_rows: fiecare rand are merk, pos optional pentru articol, fields cu valorile verificate si evidence care numeste sursa/pagina/detaliul/decizia. Campuri: Aantal, Lengte, Breedte, Hoogte, Dikte, Dikte plaat, Totaal dikte (mm), Inhoud (m3), Hoofdregel, Mengsel, Fase, Instortvoorziening, Instortvoorziening Merk. Valorile sunt siruri; cotele numerice trebuie sa fie pozitive. Articolul de import revizuit trebuie sa fie denumire exacta neambigua din catalogul fabricii.

Aceasta cale completeaza XLSX in lot, fara scriere de scripturi Python de catre model. Pastreaza valoarea extrasa initial, valoarea revizuita si dovada invocata; nu transforma o declaratie a modelului intr-o verificare independenta. Nu ghici ca sa umpli un camp si nu cere utilizatorului date deja prezente in sursele disponibile.

## Ce se completeaza si ce necesita interpretare

- Articolele vin din tabelul/renvooi-ul desenului, nu din toate articolele catalogului. Catalogul ofera denumirea MBS de import; MB este facturare. O potrivire de nume nu confirma fizic PIR/EPS, EV/TV/RVS, diametrul, capacitatea sau varianta P.
- Posnr este provenienta, nu prefix in Instortvoorziening. Originalul integral se pastreaza in audit; detaliile tehnice suplimentare raman in Instortvoorziening Merk.
- Cantitatile pe desen/per element nu sunt automat totalurile proiectului. Verifica sursa de Aantal elemente si distinctia pe bucata versus total; nu inmulti de doua ori.
- Lengte, Breedte, Hoogte, Dikte, Dikte plaat si Totaal dikte sunt distincte. Unealta exporta numai valori etichetate neambiguu, cu unitate explicita (normalizate la mm), iar Inhoud/Volume numai daca m3 este tiparit explicit. Cotele izolate se citesc in plan/sectiune: nu transforma automat Hoogte in Dikte.
- Pentru scari, goluri, izolatii si forme neregulate, volumul NU este L x B x H al gabaritului. Pentru calcul local foloseste geometry: pe fiecare merk, evidence (desen/pagina/sectiune si justificarea descompunerii), components cu shape=box/triangular_prism/cylinder, dimensions_mm (siruri: L,B,H; la cilindru diametru,lungime), count optional si subtract=true pentru goluri. Unealta calculeaza m3 pe bucata, nu inmulteste din nou cu Aantal. Un conflict cu volumul tiparit ramane explicit; forma si cotele propuse de model nu sunt declarate automat verificate. Daca lipseste volumul explicit, verifica geometria si detaliile cu un calcul local documentat; nu completa un m3 aproximativ ca valoare verificata. Lipsa unui extractor geometric general nu inseamna ca desenele nu pot fi citite de modelul cu imagini.
- Un camp gol inseamna dovada lipsa/ambigua, nu zero. OCR-ul necesita control vizual al paginii/zonei. Daca sursa necesara este disponibila, examineaz-o inainte de a cere utilizatorului sa o trimita din nou.
- Hoofdregel se ia din decizia proiectului; Mengsel si Fase se verifica din surse si reguli, fara propagare pe accesorii. Tekenbureau=14337; Status tekening=20 pe randul principal conform regulii existente, fara aprobare tehnica implicita.

**Limita explicita:** acest instrument produce un CONCEPT cu dovezi si restante. Nu executa import ISAH, nu aproba productia si nu inlocuieste interpretarea detaliilor/sectiunilor pentru cote complexe. Faptul ca o unealta de audit a returnat zero randuri NU dovedeste absenta articolelor: foloseste extractia/OCR, verifica pagina si raporteaza cauza concreta.

## Dupa eroarea Codex la compactare

Rezultatele executate raman in arhiva. Nu reexecuta o copiere/scriere/control doar fiindca predarea rezultatului catre model a esuat. Citeste dovezile deja salvate si continua etapa ramasa; nu prelua afirmatii vechi read-only ca permisiune actuala.

## Coloanele reale Netto/Bruto si greutatea

Citeste template_columns din rezultat. Pentru sabloanele cu Netto m2, Bruto m2, Netto m3, Bruto m3 si Gewicht(kg), nu inventa o coloana Inhoud si nu copia automat acelasi volum in ambele coloane m3. Parametrul column_map poate lega Inhoud la Netto m3 SAU Bruto m3 numai dupa verificarea conventiei proiectului din exemplele confirmate/documentatie; altfel ramane volume_column_mapping_required. reviewed_rows accepta numele exacte ale acestor coloane, Totaal lengte, Totaal breedte, Top Ja/Nee si MZ/SZ, pe langa cotele de mai sus. Greutatea explicita in kg din cartus poate fi extrasa; nu se inventeaza dintr-o densitate presupusa.

Pentru calcul, geometry.column selecteaza explicit Inhoud/Netto m3/Bruto m3/Netto m2/Bruto m2. Pentru m2: rectangle sau triangle cu L,B in mm; circle cu diametru in mm. Pentru m3: box sau triangular_prism cu L,B,H; cylinder cu diametru,lungime. count repeta componentele, subtract scade un gol. Descompunerea si selectia net/brut provin din sursele verificate, nu din numele coloanei. Rezultatul este PE ELEMENT; Aantal nu este inmultit automat. Tabelul Excel este recitit, iar campurile care nu exista in sablon sunt semnalate explicit, nu eliminate in tacere.

Schema fixa ISAH folosita in listele existente poate sa NU contina Eenheid. Unitatea se preia atunci din catalogul fabricii, fara adaugarea unei coloane noi si fara o falsa eroare de unitate lipsa. Daca exista o coloana explicita Eenheid, valoarea ei ramane verificata fata de catalog.
