# LocalChat — raport de dezvoltare si probleme cunoscute

Actualizat: 24 octombrie 2026. Livrare: 1.26.2.

Acest raport sintetizeaza dezvoltarea aplicatiei si diagnosticele confirmate. Cronologia completa a modificarilor functionale ramane in CHANGELOG.md; sursele si commiturile sunt in repository-ul personal LocalChat_Build. Datele proiectelor, conversatiile, cheile, cataloagele private si jurnalele nu fac parte din publicarea Git.

## Ce este implementat pana acum

| Domeniu | Functionalitate disponibila |
|---|---|
| Aplicatie portabila | Build din surse, VERSION ca sursa unica, versiune in titlu/Despre, instalare cu backup. Setarile utilizatorului se pastreaza. PDFCONTROL foloseste pachetul propriu din distributie, verificat prin manifest; instalatia separata istorica nu este dependinta buildului. |
| Interfata | Dark/Light, sase limbi, colturi rotunjite, editor redimensionabil, Markdown, detalii pliabile, timeline, salt la mesaj si semne de carte. |
| Conversatii | Istoric, model, ciorna, consum si memorie separate; arhivare, stergere recuperabila, export, chaturi independente, marcaj de lucru si statusuri colorate. Schimbarea chatului nu porneste inferenta. |
| Furnizori si modele | Adaugare, redenumire si stergere; modele izolate per furnizor/endpoint, metadate LM Studio si servere compatibile, catalog de capabilitati si compatibilitate cu profilurile MBS/Normal. Limitele necunoscute nu sunt inventate din nume. |
| Profiluri | Normal exclude uneltele, regulile MBS si memoriile de productie. MBS utilizeaza uneltele, skillurile si regulile disponibile. Limba interfetei este distincta de limba raspunsurilor. |
| Context | Compactare optionala per chat, prag configurabil, arhiva originala integrala, checkpointuri private validate, Stop si pastrarea ciornei. Rezumatele trunchiate/incomplete nu devin memorie valida. Nu exista plafonul total artificial de timp introdus in vechile versiuni ale compactarii. |
| Fisiere si imagini | Atasare, lipire de capturi, drag-and-drop, coada de mesaje in timpul lucrului. Din 1.26.0, PNG/JPEG/GIF statice se trimit efectiv ca imagini catre rute vizuale compatibile, inclusiv in Normal; snapshoturi private cu hash pastreaza imaginea intre unelte si la compactarea intrebarii curente. |
| Intrebari interactive | Carduri inline cu optiuni si text liber; sugestiile nu constituie aprobare. Editorul ramane activ. Selectia fabricii confirmate se pastreaza in conversatie. |
| Proiecte si fabrici | Identificare pe folder exact, confirmare cand numarul apare in mai multe fabrici, cataloage per fabrica cu import/previzualizare/arhivare, notificari lunare in LocalChat. |
| Hotfolder | Urmareste Folderul de lucru ales; pregateste drafturi si notificari, fara pornirea automata a modelului. Reutilizeaza chatul proiectului si actualizeaza statusul. |
| PDFCONTROL | Unelte pentru extragere, V/W, control, comparatie OUD/NIEUW, progres, anulare si verificarea livrarii rapoartelor reale. Completarea unei perechi nu permite inventarea armarii. |
| Liste de import | Comparatie locala import/catalog/tabele PDF, rapoarte si audit. Din 1.26.0, conversia FAST este determinista si necesita regula confirmata per fabrica/articol; creeaza o copie XLSX si audit, pastrand sursa, Aantal si descrierea originala. |
| Agenti | Delegare explicita sau automata a verificarilor independente in MBS, cu modelul selectat, contexte separate, doua sarcini simultane, panou de progres si arhive private. Agentii automati de analiza nu modifica fisierele de productie. |

## Probleme intalnite si masurile luate

| Problema | Cauza confirmata / masura | Stare si limita |
|---|---|---|
| Setari sau folder schimbate la instalare | Separarea datelor locale de build, verificarea hashurilor si backup. | Implementat; instalarea trebuie verificata la fiecare livrare. |
| Limba raspunsurilor nu urma selectia | Separarea limbii interfetei de politica raspunsului si controlul cererii active. | Implementat; modelul poate totusi sa nu respecte complet instructiunile. |
| Raspunsuri ascunse de blocuri think | Tratarea separata a rationamentului si raspunsului vizibil. | Corectii livrate; lipsa unui raspuns final real ramane diagnosticata. |
| Mod Normal contaminat de context MBS | Separarea epocilor de context si eliminarea instructiunilor/uneltelor MBS din cererea Normal. | Implementat si verificat prin payloaduri sintetice. |
| Cai PDFCONTROL gresite | Diagnostic si recuperare numai catre artefactele reale, verificate. | Implementat; un fisier absent nu este declarat generat. |
| Bucla de recitire fara progres | Index local de progres, detectarea repetitiilor si recuperare limitata. | Limitata, nu eliminata pentru orice model. Oprirea nu inseamna ca proiectul este finalizat. |
| Comenzi Python invalide in cmd.exe | Instructiuni pentru script .py scris separat, verificarea comenzilor si refuzul anumitor forme invalide. | Modelul poate produce inca JSON/shell invalid; nu este executat textul arbitrar ca unealta nativa. |
| Compactare cu HTTP 503/504 sau timeout | Erori ale furnizorului, cereri mari si limite artificiale vechi. Streaming, fragmentare, checkpointuri, validarea finalizarii si eliminarea plafonului total de timp. | Protejati istoricul si memoria; disponibilitatea API nu este garantata. |
| Poza trimisa doar ca o cale locala | Payloadul utilizatorului era doar text. Continut multimodal si snapshoturi reale adaugate in 1.26.0. | Necesita model si ruta API vizuale. Mesajele vechi necesita reatasare. |
| Lengte FAST respingea zecimale | Diferenta intre cantitatea fizica si reprezentarea numerica ceruta de FAST. Unealta locala aplica numai regula confirmata, inclusiv rotunjirea. | Implementat pentru regulile salvate. Nu este import efectuat in FAST/ISAH si nu este conversie universala. |
| Interfata consuma CPU in asteptarea API | Actualizarea periodica rescria aceleasi documente Swing si repeta invalidari vizuale. | Corectat in 1.26.1: valori neschimbate nu rescriu textele, actualizare periodica mai rara, iconite stabile. Masurarea fizica in sesiunea utilizatorului ramane distincta de testele automate. |
| Hotfolderul trata propriile rezultate ca surse noi | Lipsea provenienta fisierelor produse de unelte. | 1.26.1 retine privat calea si amprenta rezultatului identificat. Modificarea ulterioara de catre utilizator devine din nou sursa. Nu este exclus intregul folder Importlijst/DWG. |
| Procedura completa se repeta in ciorna | Mai multe evenimente erau concatenate ca mesaje integrale. | 1.26.1 grupeaza sursele recunoscute ale aceluiasi folder intr-un singur bloc; pastreaza textul liber si istoricul anterior. |
| run_agents devenea partial dupa circa trei minute | Limita comuna de 180 secunde anula sarcini active si sarcini aflate in coada. | Eliminata in 1.26.1. Raman Stop, maximum trei sarcini/grup, doua simultane, 16 pasi/copil si protectiile conexiunilor. |
| Reluarea pierdea distinctia dintre agenti finalizati si neterminati | Indexul limita dovada la primele caractere ale rezultatului, uneori ocupate de descrierea sarcinii. | 1.26.1 extrage local statusurile, erorile, numerele de rezultate si arhivele, fara repetarea prozei sarcinilor. Nu inventeaza finalizarea. |
| Nu era clar daca agentul lucreaza sau asteapta | Panoul afisa predominant In curs. | 1.26.1 afiseaza Asteapta API si Compactare context ca stari active, cu detalii de eroare in raportul privat. |
| Limite ascunse de generare la agenti | Copiii aveau plafoane suplimentare de 2K/4K, distincte de profilul selectat. | 1.26.1 respecta profilul selectat si spatiul real disponibil. Raspunsurile trunchiate opresc executarea apelurilor din acel raspuns. |

## Ce ramane de rezolvat sau de verificat

1. Disponibilitatea si latenta API: HTTP 429/503/504, intreruperile sau raspunsurile neconforme ale furnizorilor pot continua. Retry si conservarile locale reduc pierderile, nu garanteaza executia.
2. Finalizarea tehnica a unui proiect trebuie verificata pe livrabile. Un rezultat partial al agentilor, o analiza in text sau succesul unui script de test nu certifica o lista de import corecta.
3. Restructurarea completa a listelor pe faze de livrare nu are in aceasta corectie un generator local dedicat. Comparatia locala si conversia FAST nu inlocuiesc acel generator ori validarea completa a maparii articolelor.
4. Publicarea unui chat in proiectul ChatGPT al fabricii necesita un conector autorizat disponibil. Documentarea locala nu este declarata publicare in cloud.
5. Provenienta shell este conservatoare: se recunosc destinatiile de scriere literale si scripturile locale proprii invocate explicit. Numele calculate dinamic ori programele externe fara destinatii identificabile pot produce inca o notificare. Nu ascundem preventiv fisierele noi ale utilizatorului.
6. Registrul hotfolderului foloseste amprenta dimensiune/data modificarii. Nu este o analiza semantica a continutului si nu este un scanner antivirus. Ciornele si mesajele istorice nu sunt rescrise retroactiv.
7. Intelegerea vizuala depinde de model si ruta. Sunt verificate transportul si integrarea; testele sintetice nu demonstreaza interpretarea corecta a oricarei capturi reale.
8. Testele fizice de mouse/tastatura si masurarea pe desktopul utilizatorului trebuie raportate separat; nu sunt inlocuite prin teste Swing sintetice.

## Verificare si livrare

Modificarile se verifica prin teste relevante, cu date sintetice si servere loopback; rezultatele si hashurile buildului sunt pastrate privat in auditul instalarii. Nu se trimit istorice private catre furnizori pentru diagnostic. Conversatiile, cheile, folderul ales, cataloagele si regulile private se pastreaza la instalare. Copiile de test nu devin fisierele active ale proiectelor.

Pentru detalii exacte ale versiunilor anterioare: CHANGELOG.md. Pentru utilizare/build si limitele imaginilor/FAST: README.md (CITESTE.md in distributie). Din 1.26.2, sectiunile de utilizare descriu limitele curente ale agentilor si durata compactarii din 1.25.2; CHANGELOG pastreaza formularea istorica.

### Rezultatele verificarilor pentru 1.26.1

Au trecut regresiile relevante cu Java-ul portabil: hotfolder si reutilizare chat, provenienta rezultatelor, comenzi/copiere, grupuri de agenti, arhive, delegare, statusuri, reluare, coada, compactare, profil Normal, imagini, FAST si interfata in sase limbi/ambele teme. Compilarea finala a trecut din nou 47 verificari de stabilitate, 48 de continuitate, 49 ale grupurilor de agenti, 11 de integrare completa, 67 de panou, 329 functionale si 13.241 de interfata.

A fost corectat un fixture care folosea callbackul parintelui pentru textul unui raspuns trunchiat. A fost actualizata si asteptarea veche a testului privind plafonul ascuns de 4K, pentru a verifica profilul selectat. Cazurile corectate au fost reexecutate cu succes; jurnalele initiale raman private.

Nu au fost executate teste fizice cu mouse/tastatura, inferenta la furnizori externi ori o noua suita completa a motorului PDFCONTROL nemodificat. Testele locale nu reprezinta confirmarea finalizarii proiectului de productie activ.

### Rezultatele verificarilor pentru 1.26.2

ManualCurrentLimits confirma ca sectiunile curente din README corespund limitelor din cod. O rulare completa a trecut verificarile native de shortcut (229), navigare si popup, apoi s-a oprit la timeout-ul de 20 de secunde al probei Tesseract copiate. Reluarea izolata a fluxului PDFCONTROL a trecut 16 verificari. Doua rulari ulterioare nu au putut livra tastele reale in editor, din cauza focusului. Build-ul instalat in C:\LocalChat a folosit SkipNativeQA si a trecut din nou suitele automate, inclusiv motorul PDFCONTROL pe date sintetice; cataloagele existente au ramas pastrate. Tastele native nu fac parte din aceasta instalare.
