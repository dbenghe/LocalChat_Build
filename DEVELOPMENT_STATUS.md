# LocalChat — raport de dezvoltare si probleme cunoscute

Actualizat: 28 octombrie 2026. Livrare: 1.27.0.

Acest raport sintetizeaza dezvoltarea aplicatiei si diagnosticele confirmate. Cronologia completa a modificarilor functionale ramane in CHANGELOG.md; sursele si commiturile sunt in repository-ul personal LocalChat_Build. Datele proiectelor, conversatiile, cheile, cataloagele private si jurnalele nu fac parte din publicarea Git.

## Corectie 1.26.6: extragere Tekla/ABWN

Titlul VORM/WAPENING si familia marcii au prioritate fata de pozitiile din tabelele de accesorii. Numarul MBS din campul dublu ABWN/MBS este citit distinct de numarul de cinci cifre al biroului. Plansele fara proiect propriu pot mosteni numai numarul MBS unic, etichetat in aceeasi brosura; copertele conflictuale nu autorizeaza asocierea. Un numar propriu valid ramane prioritar. Coperta nu este desen de productie, iar W lipsa nu este fabricat.

Regresia foloseste brosuri sintetice cu coperta separata, BA1/BA2, H20/SU2, numere concurente si documente succesive. Diagnosticul pe sursa utilizatorului si copiile de proba raman private. Modificarile sunt in motorul propriu, nu in instalatia legacy. Interfata Java ramane neschimbata; versiunea provine din VERSION. Validarea acestei livrari foloseste testele relevante motorului, conectorului si JAR-ului cu versiunea noua; suita generala UI din 1.26.5 nu a fost repetata integral. Un test separat de catalog/OCR a depasit initial cele 20 de secunde la pornirea Tesseract; reluarea lui izolata a trecut. Eroarea initiala ramane in jurnal, distinct de extragerea ABWN.

## Corectie 1.26.5: izolarea cardurilor de fabrica

Cauza confirmata: folderul numerotat mostenit avea prioritate fata de proiectul deja confirmat la raspunsuri scurte. Proiectul confirmat are acum prioritate, iar o cerere explicita pentru alt proiect ramane prioritara. Folderul local se aliniaza numai spre un frate deja existent cu numarul proiectului, fara creare de directoare sau radacini fixe. Daca folderul-frate lipseste, mesajul ramane netrimis, cu explicarea nepotrivirii; nu executa operatii relative in celalalt proiect.

Un card de fabrica salvat pentru alt proiect putea intercepta raspunsul liber al conversatiei curente. Restaurarea si trimiterea invalideaza acum alegerea care nu corespunde proiectului mesajului/folderului/conversatiei. Nu sterg istoricul, ciorna sau fabrica confirmata. Rezultatul unei verificari asincrone apartine numai starii care a pornit-o; schimbarea chatului invalideaza generatia si cache-urile verificarii.

Fabrica confirmata se reutilizeaza pentru acelasi proiect fara o noua cautare de disponibilitate. Un server momentan offline nu declanseaza repetarea intrebarii. Numai schimbarea explicita a destinatiei/proiectului sau eliminarea locatiei din configuratie necesita o noua alegere; aceasta nu autorizeaza operatii noi si nu afirma ca serverul este accesibil.

Un raspuns liber nerecunoscut la alegerea fabricii ramane vizibil si editabil, cu explicatie in card. Numele cu prefix MBS si numarul optiunii sunt acceptate numai daca identifica unic fabrica. Nu transforma textul arbitrar in aprobare. Cardurile modelului continua sa trimita raspunsul liber impreuna cu ciorna.

Regresia dedicata FactoryReplyTest foloseste date sintetice, server loopback si Swing: stare salvata incompatibila, pastrarea fabricii/ciornei, raspuns valid/invalid si schimbarea intre doua chaturi cu intrebari separate. Nu modifica documente de productie. La verificarea generala, AgentIntegrationTest a avut o asertiune tranzitorie privind numarul cererilor loopback; reluarea izolata a trecut (11 verificari), iar restul pipeline-ului este executat fara schimbari ale implementarii. Prima nereusita ramane in jurnalul local. Verificarile ramase au trecut, inclusiv 16 fluxuri PDFCONTROL, iar JAR-ul 1.26.5 a fost generat. Testarea fizica a mouse-ului/tastaturii ramane neexecutata; erorile furnizorilor nu sunt eliminate prin aceasta corectie.

## Optimizare 1.27.0: prefix stabil si masurare

Auditul a confirmat doua cauze ale consumului inutil: mesajul de control mutat dupa ultimul mesaj al utilizatorului si indexul de progres/memoriile din promptul de sistem schimbau prefixul la fiecare tur, iar politica limbii era scrisa de trei ori. Acum sistemul este identic intre ture, politica apare o singura data, iar datele volatile sunt un bloc unic la final (numai MBS). Anchorele includ blocul in semnatura.

Masuratoare pe harness sintetic (API simulat, estimari in caractere, ~): Normal tur 1: 3253 -> 1490 caractere (sistem 1990 -> 1299). MBS tur 1 cu 30 unelte: 30107 -> 28344 caractere (sistem 9744 -> 9053; definitiile uneltelor, 19017 caractere, raman cea mai mare componenta si sunt tinta urmatoarei etape). Estimarea in tokeni depinde de tokenizerul modelului.

reasoning_content se citeste, se numara prin reasoning_tokens si nu se retrimite. Fereastra de context are patru componente.

Teste: StablePrefixTest (35 verificari) in Build.ps1, InternationalChatTest adaptat. Limita cunoscuta: estimarile ~ nu inlocuiesc contorul furnizorului.
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
| Agenti | Delegare explicita sau automata a verificarilor independente in MBS, cu modelul selectat, contexte separate, doua sarcini simultane, panou de progres si arhive private. Din 1.26.4 panoul arata numai grupul curent, maximum 18 sarcini; un grup nou inlocuieste cardurile anterioare. Istoricul chatului si arhivele private raman integrale. Agentii automati de analiza nu modifica fisierele de productie. |

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
| Manualul descria limite vechi ale agentilor | README, copiat la instalare ca CITESTE.md, inca spunea 180 de secunde pe lot si plafon fix de 4096 pentru copil. Codul din 1.26.1 nu mai are termen comun, iar iesirea urmeaza profilul selectat. | 1.26.2 alinaza sectiunile curente de utilizare. CHANGELOG pastreaza formularea din versiunea in care limita a existat. Comportamentul aplicatiei nu s-a schimbat. |
| HTTP 429 cerea debifarea Streaming | Sfatul cauta textul „stream” oriunde. Mesajul GonkaRouter „Upstream capacity is currently saturated” contine „upstream”. | 1.26.3 explica ocuparea furnizorului si ofera reincearcarea sau alt model, in sase limbi. Sfatul Streaming ramane pentru erorile SSE. Capacitatea rutei nu este reparata de aplicatie. Retry-ul automat ramane doar pentru HTTP 502/503/504. |
| Cardurile de agenti se adunau in panou | La fiecare reluare, panoul reconstruieste toate apelurile run_agents din chat, pana la 18, inclusiv grupurile oprite cu „Termen depasit” sau rezultat partial. | 1.26.4 pastreaza in panou numai grupul curent. Un grup nou inlocuieste cardurile vechi. Mesajele, arhivele private si dovezile vechi raman in conversatie. |

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

### Rezultatele verificarilor pentru 1.26.3

ErrorHintTest confirma ca textul HTTP 429 cu „upstream” nu mai cere debifarea Streaming, iar o eroare SSE pastreaza sfatul vechi. Traducerea este in sase limbi. Build-ul instalat a folosit SkipNativeQA, pentru ca LocalChat era deschis; prima incercare s-a oprit la un AccessDenied tranzitoriu pe fisierul temporar al registrului, reluarea a trecut suitele automate. Cataloagele existente au ramas pastrate. HTTP 429 ramane o limita a furnizorului.

### Rezultatele verificarilor pentru 1.26.4

AgentPanelTest confirma 69 de verificari: un grup nou inlocuieste cardurile vechi, iar reluarea pastreaza numai ultimul grup. Arhivele si mesajele chatului nu sunt sterse. Build-ul de instalare foloseste SkipNativeQA cat timp LocalChat este deschis.

## Livrarile din 24 octombrie 2026

Aceste trei versiuni sunt instalate in `C:\LocalChat` si publicate pe `main` in repository-ul personal https://github.com/dbenghe/LocalChat_Build. Ultima versiune instalata este 1.26.4. Aplicatia deschisa trebuie repornita prin `Porneste LocalChat.bat` ca titlul si Despre sa citeasca VERSION. Setarile, conversatiile, cheile si cataloagele existente nu au fost inlocuite: seed-ul de instalare a raportat `preserved: soest, alphen, veenoord` si `initialized: []`.

| Versiune | Commit | Ce s-a rezolvat | Ce nu s-a schimbat |
|---|---|---|---|
| 1.26.2 | `bbb1ae3` | Manualul de utilizare. Sectiunile curente spun 3 sarcini, 2 simultane, 16 pasi, iesire dupa profilul selectat si durata cererilor catre furnizor. Compactarea din manual trimite la politica 1.25.2. Test nou: `ManualCurrentLimits`. | Codul agentilor si al compactarii. Termenul de 180 de secunde si plafonul de 4096 raman doar in istoricul CHANGELOG, acolo unde au fost introduse. |
| 1.26.3 | `18b3c20` | Sfatul afisat la HTTP 429. Cazul vazut: GonkaRouter, model `MiniMaxAI/MiniMax-M2.7`, unealta `run_agents`, raspuns „Upstream capacity is currently saturated”. Grupul a ramas partial. Test nou: `ErrorHintTest`, sase limbi. | Ocuparea rutei. Aplicatia nu retrimite singura un 429 si nu face modelul disponibil. Proiectul 506556 nu a fost reluat sau declarat finalizat. |
| 1.26.4 | `154555d` | Panoul din dreapta. In sesiune erau cinci carduri, intre ele „Surse si istoric / Gata.”, doua carduri „Termen depasit” si „Liste si articole / Rezultat partial”. Panoul arata acum numai grupul curent. | Istoricul conversatiei si fisierele din `agent-runs/`. Un rezultat partial sau „Termen depasit” nu devine succes. |

### Cum s-a verificat

- 1.26.2: `ManualCurrentLimits` a trecut. O suita completa a trecut verificarile native de shortcut (229), navigarea si popup-ul, apoi proba Tesseract copiata a depasit 20 de secunde. Reluarea izolata a fluxului PDFCONTROL a trecut 16 verificari. Doua rulari ulterioare nu au putut scrie in editor, pentru ca fereastra de test nu avea focus. Instalarea a folosit `Build.ps1 -SkipNativeQA` si a trecut suitele automate, inclusiv motorul PDFCONTROL pe date sintetice.
- 1.26.3: `ErrorHintTest` a trecut. Prima suita s-a oprit la `AccessDeniedException` pe mutarea temporara `hotfolder-generated.json`; reluarea a trecut. Instalarea a folosit din nou `SkipNativeQA`, pentru ca LocalChat era deschis.
- 1.26.4: `AgentPanelTest` a trecut 69 de verificari, fata de 67 inaintea curatarii. Instalarea a folosit `SkipNativeQA` din acelasi motiv.

Tastele reale si masurarea de mouse nu fac parte din aceste instalari. Testele locale nu confirma finalizarea proiectului de productie 506556. HTTP 429 ramane limita furnizorului.
