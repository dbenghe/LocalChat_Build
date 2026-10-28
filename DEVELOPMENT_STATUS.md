# LocalChat — raport de dezvoltare si probleme cunoscute

Actualizat: 28 octombrie 2026. Livrare: 1.28.3.

Acest raport sintetizeaza dezvoltarea aplicatiei si diagnosticele confirmate. Cronologia completa a modificarilor functionale ramane in CHANGELOG.md; sursele si commiturile sunt in repository-ul personal LocalChat_Build. Datele proiectelor, conversatiile, cheile, cataloagele private si jurnalele nu fac parte din publicarea Git.

## Sinteza seriei de imbunatatiri 1.27.0-1.28.3 si lucrari ramase

Scop al seriei: mai putini tokeni si mai putina latenta pe modelul local, context sub control, unelte PDFCONTROL de citire fara rerulare, igiena de cod si teste stabile. Toate valorile sunt masurate in repo (harness sintetic, caractere ~ tokeni, sau serverul real unde este precizat); nu inlocuiesc contorul furnizorului. Fara chei sau date de productie in aceasta sectiune.

### Ce s-a facut pe versiune (cu rezultate masurate)
- 1.27.0 - prefix stabil pentru cache-ul KV: prompt de sistem identic intre ture, politica limbii o singura data (era de trei ori), date volatile intr-un bloc unic dupa ultimul mesaj (numai MBS), reasoning_content citit si numarat (nu se retrimite). Masurat: Normal tur 1: 3253 -> 1490 caractere; MBS tur 1 cu 30 unelte: 30107 -> 28344. StablePrefixTest 35.
- 1.27.1 - overhead MBS redus fara pierdere de reguli: prompt MBS ~4,8k caractere (era ~9k), descrieri de unelte mai scurte, unelte care modifica fisiere trimise numai la cerere (relevanta, sase limbi). Masurat: cerere MBS de proba 28348 -> 10891 caractere (~-62%); cu toate uneltele 28348 -> 19810 (~-30%). ToolRelevanceTest 38, RuleCoverageTest 80 (42 reguli), TokenBudgetTest cu praguri.
- 1.27.2 - context sub control: rezultate vechi de unelte reduse deterministic numai in vizualizarea cererii (arhiva intacta), plafon 16000 caractere (24000 MCP) cu paginare offset/limit, autocompactare implicita pentru chaturi noi. Masurat pe bucla sintetica: 40 rezultate 297427 -> 28635 caractere (~-90%); 20 rezultate 148743 -> 23727; 10 rezultate 74401 -> 21285. ContextReductionTest.
- 1.27.3 - indicator de gandire in UI, control verificat pe serverul real: numai reasoning_effort="none" opreste gandirea; rezerva adaptiva de iesire; mesaj clar la finish_reason=length. Masurat (server real): ~50-80 s si ~1,6k-2,7k tokeni de gandire pentru un raspuns de 2 caractere -> 0,7-1,9 s si 0 tokeni. ReasoningTruncationTest 62.
- 1.28.0 - patru unelte PDFCONTROL numai citire din iesirea reala a jobului (job_summary, job_findings, list_projects, catalogue_status), status pentru model fara PID/cai, erori tacute ca silent_checks (fara a schimba starea jobului), ToolLoopGuard le trateaza ca citiri. Masurat: lista de unelte a conectorului 11 -> 15 unelte, ~6,8k -> ~8,8k caractere; set implicit 23 unelte / 12289 caractere, read-only 15 / 7578, toate 32 / 16588. Teste: 24 teste punte Python, PdfAgentTest, WorkProgressTest.
- 1.28.1 - skill local `productie-projecten` (11 fisiere, NU in git, instalat prin robocopy in C:\LocalChat\skills), corectie `Skills.parse` pentru descrieri YAML pe mai multe randuri (SkillProgressTest 50), in prompt doar nume si descriere (274 caractere).
- 1.28.2 - performanta si igiena: citiri read-only independente in paralel (`ToolBatch`, max 4, ordine pastrata, mutatiile inchid lotul; 31 verificari), cache de skill-uri (scanare ~11,7 ms -> ~0,74 ms din cache; 66 verificari), imagini trimise ca base64 numai in ultimul mesaj (corp cerere 4 ture/4 imagini 411775 -> 103576 octeti, ~-75%; 18 verificari), stabilizari de teste (AgentIntegrationTest asteapta lantul asincron, timeout Tesseract cu reincercare 60 s, teste native cu pozitionare fixa si diagnostic pointer/focus, ComposerSplitTest limitat la zona de lucru a ecranului).
- 1.28.3 - LocalChat.java: verificat ca este deja UTF-8 valid (conversie inutila); prima extragere mecanica: `AppPaths` (appDir, resolveDownloads, isWindows), 237342 -> 235479 octeti.

### Rezultate de verificare (build.bat complet, 1.28.2)
ChatNavigationTest 416, ComposerShortcutsTest 229 (native=true), ChatPresentationTest 58, ComposerSplitTest 77, ContextUsagePopupTest 220 (native=true), ToolBatchTest 31, SkillsCacheTest 66, ImageResendTest 18, AgentIntegrationTest 11; build fara niciun FAIL, instalat in C:\LocalChat. 1.28.3 are rezultatele proprii in sectiunea de livrare/CHANGELOG.

### Lucrari ramase, in ordinea prioritatii
1. Rularea cu un model real (sanity) a modificarilor 1.28.2: citiri paralele, reducerea imaginilor vechi si cache-ul de skill-uri sunt acoperite de teste sintetice, NU au fost rulate pe un model real. Singurele masuratori pe server real sunt cele de gandire din 1.27.3. Compromis cunoscut: modelul nu mai vede imaginile vechi fara reatasare.
2. Neconcordante `productie-projecten` fata de uneltele `pdfcontrol_*`: skill-ul si references/pdfcontrol.md descriu aplicatia veche (webapp, `mbs_webapp.py`, `START_WEBAPP.bat`, interfata locala, apeluri directe `mbs_*`); rutinele de chat ChatGPT per proiect nu au echivalent in LocalChat (transferul trebuie marcat restant, nu publicat); mentiuni istorice Codex/LM Studio; suprapunere cu `pdfcontrol-octop` la reguli. Procedura NU a fost rescrisa: curatarea o decide Dumitru; datele de catalog ar trebui mutate in `data_lokaal` privat.
3. Restul planului de taiere LocalChat.java (acum ~235 KB): o extragere mecanica pe versiune, cu build.bat complet dupa fiecare. Candidate fara stare si fara reflexie din teste: clasa `Turn`, `pickFontFamily`, `streamingHint`, apoi `describeCall`/`preview`/`describe` (folosesc `t(...)`, atentie la cheile I18n). Metodele/campurile accesate prin reflexie din teste (UiTest/FeatureTest `call(app,"nume",...)`, `requestHistory`) raman pana la adaptarea testelor.
4. Elemente nevalidate sau partiale: testele native (mouse/tastatura reala) trec numai cu desktopul liber si fara atingerea mouse-ului, validate pe sesiune Remote Desktop 1152x713, nu pe consola fizica 2560x1600 (clamp-ul ComposerSplitTest a fost verificat doar simulat la 2560x1560); testele cu dimensiuni fixe mai mari decat ecranul (lista din sectiunea 1.28.2) trec in RDP dar nu sunt limitate la zona de lucru; AgentIntegrationTest are un esec sporadic istoric, acum cu asteptare limitata, neconfirmat sub sarcina mare.
5. Propuneri PDFCONTROL amanate (cu aprobarea lui Dumitru): rezumat read-only al listei de import `mbs_importlijst`, OCR de articole ca job explicit, `kennisbank.json` doar ca numaratori, `mbs_productie` in afara conectorului pana exista politica pentru joburi conduse de Codex.
6. Optional: criptarea cheilor API cu Windows DPAPI (acum raman in fisierul local de setari, in afara git); nu este implementata.
7. Amanat din planul de performanta: lista de unelte a conectorului a crescut cu ~2,0k caractere (justificat, in praguri TokenBudgetTest); reducerea ei este o optimizare ulterioara.

## Intretinere LocalChat.java (1.28.3)
- LocalChat.java: verificat in 1.28.3 — este deja UTF-8 valid (fara BOM, fara octeti invalizi, 10 caractere non-ASCII legitime: … — · × » → „ ” ✓ si un U+FEFF in text); nota anterioara despre octeti non-UTF-8 era gresita, deci nu exista conversie de facut. Plan de taiere: extragere mecanica, o singura bucata pe versiune, cu build.bat complet dupa fiecare. Facut in 1.28.3: `AppPaths` (appDir, resolveDownloads, isWindows). Candidate urmatoare fara reflexie din teste si fara stare: `describeCall`/`preview`/`describe` (folosesc `t(...)`, atentie la cheile I18n), `pickFontFamily`, `streamingHint`, clasa `Turn`. Metodele/campurile accesate prin reflexie din teste (UiTest/FeatureTest `call(app,"nume",...)`, `requestHistory`) raman in LocalChat.

## Performanta si igiena (1.28.2)
- Paralelism: `ToolBatch` preia un sir contiguu (min. 2) de apeluri read-only dintr-un pas; rezultatele sunt consumate in ordine, deci ordinea mesajelor, ToolLoopGuard si evenimentele UI nu se schimba. `Tools.notes` este thread-local; `Tools.allow` serializeaza intrebarile (verifica din nou alwaysAllowed in lacat). Masurat in test: sarcinile se suprapun real (latch), 31 verificari.
- Cache skill-uri: scanarea ~11,7 ms, incarcarea din cache ~0,74 ms in test (66 verificari). Invalidare: stampile director/SKILL.md, `/skills`, reload unelte, `Skills.invalidate()`.
- Imagini: inainte, FIECARE cerere retrimitea base64 pentru toate imaginile din istoric. Acum doar ultimul mesaj al utilizatorului; cele vechi au marcaj text. Masurat cu build-ul HEAD vs nou pe aceleasi 4 imagini/4 ture: 411775 -> 103576 octeti (OpenAI), 412001 -> 103838 (Anthropic). Compromis: modelul nu mai vede imaginile vechi fara reatasare; cache-ul `ImageInput` nu detecteaza modificarea cu aceeasi marime si aceeasi data a unui snapshot din directorul aplicatiei.
- Teste instabile: AgentIntegrationTest nu a esuat in 13 rulari izolate consecutive; cauza probabila a esecului sporadic (rularea completa, sarcina mare) este citirea `server.requests` imediat dupa `idle`; acum se asteapta limitat. Timeout Tesseract: reincercare 60 s. Testele native: pozitionare fixa, verificarea pointerului si a focusului; NEVALIDATE nativ in aceasta sesiune (calculatorul trebuie liber), doar compilate si rulate cu SkipNativeQA.
- ComposerSplitTest a esuat intr-o sesiune Remote Desktop de 1152x713 (zona de lucru 1152x665): Windows limita fereastra "mare" de 1320x1050 la ~1172x733. Corectat prin limitarea dimensiunilor la zona de lucru (aceeasi asertiune, plus verificarea ca fereastra mica micsoreaza editorul; prag minim 900x560). Alte teste cu dimensiuni fixe peste acest ecran (AgentPanelTest 1500x1000, ComposerAssistTest 1500x1050, ContextUsagePopupTest 1050x850, InteractionCardsTest 1300x950, InternationalChatTest 1320x950, PdfPreviewTest 1600x1000, ProviderManagementTest 1400x950, QueueNotificationsTest 1400x1000, StatusActivityTest 1450x950, CompactCardsTest 1200x430) au trecut in aceeasi sesiune si nu au fost modificate.

## Skill productie-projecten (1.28.1)
- Instalare: sursa locala `packaging\skills\productie-projecten\` (gitignored), instalat in `C:\LocalChat\skills\productie-projecten\`. Fisierele nu sunt in git: toate contin date de productie sau personale (SKILL.md: cai si numere de proiect; PROCEDURA_PROIECTE_MBS.md si SKILL_MBS_PDFCONTROL.md: numere de proiect, cai; references/catalog-isah.json si isah-artikelen.md: catalog ISAH; mengsel-dovezi.json: cai UNC si proiecte; proiecte-curente.md: identificatori ChatGPT; conventii.md, pdfcontrol.md, operare-si-documentare.md, conversatii-chatgpt.md: cai si proceduri interne). Cai ale altui calculator (`C:\Users\<alt utilizator>\...`) sunt traduse de PathMap la citire.
- Buget: in prompt intra numele si descrierea (274 caractere). SKILL.md are circa 7,1k caractere si cere citirea PROCEDURA_PROIECTE_MBS.md (circa 40k caractere) inainte de prelucrare; se citeste paginat cu `read_file` (fisierele de referinta sunt doar listate).
- Neconcordante fata de uneltele LocalChat (nerezolvate, procedura nu a fost rescrisa): (1) skill-ul si references/pdfcontrol.md descriu aplicatia veche (`C:\PDFCONTROL`, `mbs_webapp.py`, `START_WEBAPP.bat`, interfata 127.0.0.1:8756, apeluri directe de module `mbs_*` intr-un proces separat); in LocalChat modelul foloseste `pdfcontrol_*`: Wapening Checker = `pdfcontrol_start_check`, Extract PDF = `pdfcontrol_start_extract`, Tekeningen vergelijken = `pdfcontrol_start_compare`, import = `pdfcontrol_compare_import`/`pdfcontrol_prepare_fast_import`, rezultate = `pdfcontrol_job_summary`/`job_findings`, catalog = `pdfcontrol_catalogue_status`; (2) rutinele "chat ChatGPT per proiect" (creare/actualizare/publicare chat, Pentru_ChatGPT_<numar>.txt) cer integrarea cu aplicatia ChatGPT, care nu exista in LocalChat: modelul trebuie sa marcheze transferul ca restant, nu sa pretinda publicarea; (3) mentiunile Codex/LM Studio sunt istorice; (4) skill-ul cere proceduri de retea/copiere (UNC) care raman sub controlul comenzii `run_command`/accesului din LocalChat; (5) `pdfcontrol-octop` si `productie-projecten` se suprapun la reguli PDFCONTROL: prioritate pentru confirmarea fabricii pe proiect si pentru uneltele `pdfcontrol_*`.
- Propuneri: curatarea sectiunilor vechi din skill (aplicatia web) de catre Dumitru; mutarea datelor de catalog in `data_lokaal` privat, nu in skill.

## Unelte PDFCONTROL de citire (1.28.0)

Patru unelte noi, numai citire, calculate din iesirea reala a jobului (`job.json`, `resultaten.json`, `stille_fouten.json`): job_summary, job_findings (paginat, filtru AND, max_chars), list_projects (paginat, fabrica numai daca e confirmata) si catalogue_status (catalog + reguli FAST + cautare articol cu `Catalogus.zoek` original). Statusul pentru model nu mai contine PID-uri, trasee sau cai de pachet; diagnosticul ramane in pdfcontrol/runtime/octop_status_diagnostic.json si in `--self-test`. Erorile tacute ale motorului apar ca silent_checks fara a schimba starea jobului.

Evaluarea modulelor neexpuse: expuse (catalog articole, fabrica confirmata, status desene, erori tacute). Propuneri pentru o versiune viitoare, cu aprobarea lui Dumitru: (1) rezumat read-only al listei de import (`mbs_importlijst`) pe un fisier ales explicit, fara a o copia in rezultat; (2) OCR de articole (`mbs_ocr_artikelen`) numai ca job pornit explicit, deoarece este costisitor si scrie cache; (3) `kennisbank.json` numai ca numaratori, nu continut, fiind date invatate private; (4) `mbs_productie` ramane in afara conectorului pana exista o politica pentru joburile conduse de Codex. Skill-ul `productie-projecten` este in Downloads si in .lmstudio\skills (11 fisiere), nu in repository si nu in C:\LocalChat\skills.

Limite: lista de unelte a conectorului este ~8,8k caractere (+~2,0k); catalogue_status nu confirma si nu deduce fabrica; cautarea de articol nu aproba echivalenta fizica; silent_checks nu modifica starea.

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

## Reducerea overhead-ului MBS (1.27.1)

Masurat pe cererea de proba (primul tur, MBS, doar citire): 28348 -> 10891 caractere (~-62%); cu toate uneltele 19810 (~-30%); implicit cu scriere permisa 15502 (20 unelte). Promptul de sistem MBS ~4,8k caractere (inainte ~9k). Tokenii sunt estimari (~).

Regulile PDFCONTROL sunt acum in skill-ul pdfcontrol si in descrierile esentiale ale uneltelor; RuleCoverageTest verifica faptul ca regulile cerute de AGENTS.md exista in prompt sau in skill. Uneltele care modifica fisiere si run_command apar dupa relevanta (selectie fara stare, doar crestere in conversatie); modul doar citire si agentii copil nu pot debloca. Optiunea tools.all trimite lista completa. Aliasul pdfcontrol_live_status este ascuns, dar apelurile arhivate functioneaza. Risc ramas: un cuvant-cheie lipsa ascunde temporar o unealta de scriere; modelul poate cere localchat_enable_tools, iar utilizatorul poate activa trimiterea tuturor uneltelor.

## Context sub control (1.27.2)

Masurat pe o bucla sintetica de unelte (rezultate de 4k-70k caractere; tokeni estimati ~): cu 40 de rezultate cererea scade de la ~297427 la ~28635 tokeni (~-90%); cu 20, de la ~148743 la ~23727; cu 10, de la ~74401 la ~21285. Numai reducerea rezultatelor vechi: ~60748; numai plafonul de 16000: ~128797 (40 de rezultate). Estimarile ~ nu inlocuiesc contorul furnizorului.

Rezultatele vechi ale uneltelor (peste ultimele 6) sunt reduse deterministic numai in cerere, la inceputul turului; arhiva, exportul si ecranul raman intregi, intrebarea exacta nu se atinge, iar stub-ul pastreaza unealta, argumentele, caile, identificatorii, status, delivery_verified, job_id, erorile si indicii de continuare. Reducerea intra in semnatura ancorelor si a punctelor de control (invalideaza ancora). Setarea context.reduceOld (meniul Unelte) o opreste.

Plafonul per rezultat este 16000 de caractere (MCP 24000) cu paginare: offset pentru list_dir, find_files, search_text si read_file, pagini PDF taiate la limita paginii. Compactarea automata este pornita implicit numai in chaturile noi; alegerile explicite raman, iar la ~80% din limita cu compactarea oprita apare un avertisment.

Teste: ContextReductionTest in Build.ps1; FeatureTest si ContextGuardTest adaptate. Amanat: reducerea in mijlocul turului curent si reducerea prefixului /compact manual.
## Gandire si trunchiere (1.27.3)

Streamingul ramane implicit; deltele reasoning_content alimenteaza un indicator in bara de stare ("Modelul gandeste: ~N tokeni ...") si mentin conexiunea activa (fara HTTP 524 la ~100 s). Gandirea nu se afiseaza ca raspuns, nu se salveaza si nu se retrimite.

Control verificat pe serverul real (qwen3.6-35b-a3b): numai reasoning_effort="none" opreste gandirea (~1 s, 0 tokeni, fata de ~50-80 s si ~1,6k-2,7k tokeni); celelalte variante (chat_template_kwargs, enable_thinking, reasoning {effort}, thinking {type}, /no_think, minimal/low/high, bugete) sunt acceptate dar ignorate, deci nu sunt oferite. Meniul "Gandire" din setari rapide: Automata sau Oprita, doar API custom. 400/422 pe parametru => o reluare fara el, memorata per url|model in thinking.rejected numai dupa ce reluarea reuseste.

Rezerva adaptiva pentru gandire (~1,25 x observat, plafon 8192, sub limita API/context) se adauga numai la max_tokens; la finish_reason=length fara raspuns vizibil mesajul explica ca gandirea a consumat limita; nicio unealta dintr-un raspuns trunchiat nu ruleaza; reincercari numai 502/503/504, maximum 3; sfat dedicat la HTTP 524.

Teste: ReasoningTruncationTest in Build.ps1 (62 verificari). Amanat: controlul de gandire pentru clientii de compactare/MemoryReducer si mod "buget" (niciun parametru de buget verificat). Testul de tastatura reala nu poate rula pe sesiune blocata (build cu -SkipNativeQA).
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
