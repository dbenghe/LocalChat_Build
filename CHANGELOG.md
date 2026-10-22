# 1.20.1 — 2026-10-22

- Shared theme-aware status icon palette: slate draft, blue in progress, amber partial, green completed and purple restarted. Applied to chat toolbar, sidebar, status menus and notifications, including disabled in-progress toolbar icons; labels and shapes remain distinct.
- Agent toolbar icon shows live working/queued counts and a subtle pulse only while actual current-chat child tasks are queued or running. It remains visible when the panel is hidden; Stop, terminal states, chat changes and disposal stop animation. Historical pending work never restarts the pulse.
- Agent card status icons and colours use the same semantic palette, including errors, cancellations and unconfirmed results. Six localized accessible activity tooltips; no additional inference or changes to stored data.

# 1.20.0 — 2026-10-22

- Deterministic MBS project-location preflight before model inference. Scan configured private roots read-only; multiple server folders require an explicit choice in the composer card. Unreachable roots remain unverified rather than absent.
- Per-chat factory/root/folder selection and pending clarification persistence. Explicit user destination overrides previous selection; unknown factory roots require confirmation rather than inferring identity from the mapped drive/share.
- Bounded background discovery, Stop/draft-change protection and no source/network modifications. Normal remains unaffected. Root mappings are private configuration, excluded from Git.

# 1.19.0 — 2026-10-22

- Optional persisted project hotfolder: baseline at first enable, stable new/changed source files create local MBS drafts, grouped per project. No inference or automatic sending. Exact project paths work when the working directory is PRODUCTIE or its parent.
- Local notification bell with unread count and subtle pulse, direct access to pending chats, persistent workflow status icons and context menus. Completion is user-confirmed rather than inferred from a model response.
- Rounded clarification card above the composer with numbered visible choices, free answers, explicit submission, pending message preservation and existing Tab/Undo suggestion support.
- Six interface languages and both themes; source inventory is private, persisted and excludes generated reports, journals, temporary locks and linked folders outside the project.

## 1.18.0 - 2026-10-22

- Formular de clarificari in zona mesajului, cu maximum trei intrebari, optiuni fara preselectare si camp liber. localchat_interaction foloseste contextul turei MBS, regulile si memoriile permise; opreste tura fara a executa alte apeluri din acelasi raspuns. Mesajul scris intre timp este pastrat si inclus numai la trimiterea explicita a raspunsurilor. Normal ramane fara unelte; intrebarile numerotate da/nu pot fi preluate local din raspunsurile clasice.
- Sugestii optionale de pas urmator, afisate discret in editor si acceptate cu Tab sau prin buton, fara trimitere automata ori cerere API suplimentara pentru predictie. Tab nu inlocuieste o ciorna existenta, iar acceptarea este reversibila prin Undo. Intrebarile partial completate si inchiderea propunerilor sunt separate per conversatie si restaurate fara inferenta. Raspunsurile formularului nu sunt capturate automat ca reguli permanente din textul intrebarilor generate de model.
- Controale compacte langa Furnizor: + pentru adaugare si meniu pentru salvarea modificarilor, redenumire si stergere a furnizorilor salvati. Actiunile sunt traduse in sase limbi si respecta temele Dark/Light.
- Redenumirea pastreaza ID-ul, cheia, modelele, favoritele si asocierile conversatiilor. Numele duplicate sunt respinse fara suprascrierea altui furnizor.
- Stergerea confirmata elimina configuratia si cheia furnizorului si dezleaga selectia implicita aferenta, pastrand ceilalti furnizori si istoricul. Chaturile cu un furnizor eliminat se deschid fara cheie si fara apeluri catre acel API; cer o selectie disponibila inainte de trimitere. Salvarile ulterioare nu recreeaza configuratia stearsa.

## 1.17.0 - 2026-10-21

- Panou lateral de agenti cu iconita in antetul conversatiei; se deschide la pornirea reala a sarcinilor, poate fi ascuns/redeschis si redimensionat, separat de previzualizarea PDF.
- Carduri compacte cu colturi rotunjite, titlu, model, sarcina, stare live si detalii pliabile pentru rezultate/diagnostic. Suporta sase limbi si temele Dark/Light, inclusiv titluri duplicate prin ID-uri separate.
- Rezultatele loturilor sunt reconstruite din istoricul local la reluarea conversatiei, fara apeluri API sau amestec intre chaturi. Stop pastreaza sarcinile si dovezile partiale, fara a prezenta operatiile neconfirmate ca finalizate. Butonul din panou opreste intreaga tura.

## 1.16.1 - 2026-10-21

- read_skill afiseaza calea SKILL.md si inventarul exact al resurselor inaintea instructiunilor; reamintirea unui skill deja citit pastreaza aceste cai fara a retrimite procedura completa.
- read_file recupereaza explicit un fisier plasat gresit in references numai daca acelasi nume exista la radacina skillului instalat, fara iesire din skill, fara aliasuri sau modificari. Pastreaza permisiunile, protectia cheilor, paginarea si fisierele existente; nota de corectie este disponibila in sase limbi.
- Protectia contra buclelor recunoaste citirea reusita a unui document sau a unei pagini noi ca progres. Inventarierea repetata, aceeasi pagina, erorile si reamintirea skillului nu reseteaza contorul; ciclurile fara progres raman limitate.

## 1.16.0 - 2026-10-21

- Selectia modelelor se adapteaza profilului MBS/Normal pentru fiecare furnizor, inclusiv API-uri adaugate ulterior. MBS afiseaza implicit modele cu suport pentru unelte declarat/verificat; Normal include modele de chat fara unelte. Catalogul permite afisarea tuturor cu motivul clasificarii, in toate cele sase limbi.
- Separa modelele specializate de chat, accesul blocat per cont de erorile temporare, capabilitatile neconfirmate de recomandarile de utilizare. Evaluarea locala Muse este marcata distinct, fara anularea capabilitatilor declarate.
- Filtrarea nu sterge modele, favorite, selectii sau conversatii si nu porneste probe la schimbarea profilului. Trimiterea blocheaza modelele specializate/incompatibile confirmate, pastrand ciorna. Probele manuale din MBS pot confirma apelurile de unelte pentru modele noi.

- Agentii runtime sunt disponibili la cerere explicita in profilul MBS, prin prompt sau `/agents`. Folosesc furnizorul/modelul selectat pentru orice API compatibil; fiecare copil are context propriu si permisiunile chatului. Maximum 3 sarcini/lot, 2 simultan, 16 pasi/copil, 4096 tokeni de iesire/cerere/copil si 180 secunde/lot. Rezultatele si arhivele private sunt pastrate, cererile sunt contabilizate, Stop se propaga, iar acelasi lot nu se executa repetat.
- Tileward primeste explicit dezactivarea memoriei serverului (`X-Tileward-Conversation: off`), pentru a pastra izolarea locala a chaturilor, profilurilor, compactarilor si agentilor. Transportul Gonka evita substituirea tacita a modelului.
- Un index factual limitat reconstruit din arhiva originala pastreaza progresul recent la reluare, separat de rezumatul modelului si de regulile permanente. Repetarea fara progres a aceleiasi citiri este oprita dupa cinci cereri; citirea repetata a skillului returneaza un memento scurt. Inventarierea nu reseteaza bucla.
- copy_path verifica SHA-256, omite fisiere identice fara rescriere si reia foldere partiale, pastrand conflictele diferite fara overwrite implicit. Foloseste staging pentru copiere, verifica destinatia si nu declara succes dupa anulare. O aprobare tardiva dupa Stop nu mai executa modificarea.
- Buildul permite `-SkipNativeQA` explicit pentru desktop indisponibil; testele deterministe ruleaza, iar lipsa verificarilor native este semnalata, fara a pretinde ca au trecut.

## 1.15.1 - 2026-10-21

- Citirea rezultatelor PDFCONTROL recupereaza automat greseala exacta `<run>/checked/resultaten.json` atunci cand `job.json` confirma pentru acelasi job terminat sau partial fisierul existent `<run>/engine/checked/resultaten.json`, in folderul de lucru autorizat.
- Returneaza continutul citit cu o nota explicita despre calea ceruta si cea folosita, in toate cele sase limbi. Nu mai produce o eroare falsa si o noua cerere la model pentru aceasta corectie verificata. Pastreaza offset/limit, originalele si erorile reale; nu creeaza aliasuri si nu reruleaza controlul.

## 1.15.0 - 2026-10-21

- Testele cu taste/clicuri native asteapta stabilizarea focusului si procesarea evenimentelor dupa schimbarea temei sau deschiderea cardului, pastrand verificarile functionale.
- Normal devine conversatie curata: fara unelte, skill-uri, reguli permanente MBS, memorii globale/de proiect sau capturare automata de memorii. Limba fixa ramane prioritara; Automat urmeaza mesajul curent.
- Schimbarea MBS/Normal delimiteaza contextul activ, rezumatul compactat si ancorele de consum, fara a sterge arhiva, ciorna sau semnele de carte. Conversatiile Normal vechi primesc o delimitare la prima deschidere, pentru a exclude contaminarea MBS anterioara.
- Prompturile de sistem editabile sunt separate intre moduri si pastrate la reluare. Compactarea si estimarea de context folosesc numai segmentul activ; regulile si memoriile MBS raman disponibile in modul MBS.

## 1.14.2 - 2026-10-21

- Adauga Undo/Redo in caseta mesajului: Ctrl+Z, Ctrl+Y si Ctrl+Shift+Z, inclusiv in meniul contextual tradus. Lipirea peste selectie se anuleaza intr-un singur pas.
- Copierea si decuparea folosesc explicit textul original selectat, cu reincercari limitate la clipboard temporar blocat. Decuparea nu sterge textul daca scrierea in clipboard esueaza. Pastreaza Ctrl+C/X/V/A, Ctrl+Insert, Shift+Insert si Shift+Delete.
- Schimbarea/crearea chatului si trimiterea/restaurarea mesajului delimiteaza istoricul Undo; acesta nu poate readuce ciorna altei conversatii sau text deja trimis. Schimbarea temei pastreaza scurtaturile si editarile curente.

## 1.14.1 - 2026-10-21

- Rotunjeste zona de raspunsuri si caseta mesajului, plus campurile, butoanele si panourile de derulare/listare/tabele din ferestrele aplicatiei, cu pastrarea temelor si a redimensionarii.

- Inlocuieste meniul lat al reperului cu un card de 320 x 110 px, colturi rotunjite, titlu scurt si extras din raspuns (sau intrebare pana exista raspuns).
- Semnul de carte este o iconita vectoriala de 15 px in coltul dreapta sus, cu stare marcata distincta. Clicul pe iconita nu navigheaza; clicul pe titlu sau extras sare la discutie.
- Pastreaza salvarea per chat, originalele si lipsa apelurilor API; ambele teme au culori explicite si texte lizibile.

## 1.14.0 - 2026-10-21

- Diagnosticheaza calea interna PDFCONTROL ghicita fara engine folosind job.json confirmat, fara redirectionare sau rerulare automata. Agentul foloseste campul results din status.

- Detaliile tehnice delimitate ale furnizorului raman in chat, pliate implicit si deschise prin clic. Raspunsul final ramane vizibil in toate limbile si modurile; originalul este pastrat.
- Timeline compact in stanga conversatiei, cu titlul intrebarii la hover, salt direct si semne de carte locale per chat. Meniul de navigare include si discutiile foarte lungi.
- Navigarea, plierea si semnele de carte nu apeleaza API-ul si nu modifica memoria/contextul. Temele Dark/Light si cele sase limbi sunt acoperite.


## 1.13.3 - 2026-10-21

- Stabilize the native popup screenshot test by keeping its own QA window foreground and waiting for native painting, retaining the same color assertions.

- Keep completed undelimited provider text visible when neither a closing think tag nor a translated quote exists; avoid discarding a real automatic-language answer. Keep incomplete streaming technical text hidden until a known final boundary or completion.
- Request an explicit final-answer marker in both language modes. Remove it only in presentation/copying, preserve originals and support older quoted answers.
- Carry relevant saved language preferences in automatic mode's temporary request control so earlier replies in another language do not select the output language.

## 1.13.2 - 2026-10-21

- Restore final translated answers when compatible streaming routes omit the closing think tag. Recognize the blank-line-separated quoted answer boundary outside code fences, including partial streaming and Windows line endings.
- Apply the same presentation/copy boundary to saved history without changing original messages or sending another inference. Avoid false missing-final errors for a complete quoted answer.

## 1.13.1 - 2026-10-21

- Hide leading provider think blocks from normal chat rendering/copying, including streamed prefixes, while preserving raw archive and source-selection offsets. Report a missing visible final answer instead of silent completion.

- Resolve fixed response-language conflicts before inference: omit older global conversation-language preferences from the active memory/rules context without changing stored preferences. Keep report/file language requirements and project notes.
- Add a temporary per-request language control for providers/models that ignore the system language policy; count it in context and retain the original question/archive through compaction. No additional inference or tool replay.
- Place a short instruction in the selected language before both MBS and Normal prompts, with explicit precedence over historical answers. Automatic mode still uses saved language preferences.

## 1.13.0 - 2026-10-21

- Add an Automatic response cap using explicit API output limits or a labelled 16000-token local fallback, always bounded by free context; retain existing fixed profile indices.
- Name the tool-enabled project mode MBS and the general tool-free mode Normal, with per-chat persistence.

- Separate interface language from response language, with six fixed languages and an automatic memory/message mode. Persist choices and the optional user-message translation per chat and in explicit defaults.
- Request the translated question as a quoted italic paragraph in the same inference, with theme-specific blue text. Keep original user messages, tool access, project IDs, paths and report-language requests unchanged.
- Preserve response-language display metadata locally per assistant message so Turkish/Spanish and other text retains its characters when the interface is Romanian. Loading or changing selections does not infer or translate old messages.

## 1.12.1 - 2026-10-21

- Retry transient Windows access-denied locks when replacing settings, with bounded attempts and interruption support. Preserve the existing settings on failure and clean temporary files.
- Add regression checks for transient/permanent locks, unrelated IO failures and interrupted saves.

## 1.12.0 - 2026-10-21

- Add Tools > Local memories: enable/use local memory, allow automatic preference capture from tool-assisted chats, inspect/edit scoped entries and delete individual/all memories.
- Store explicit reminders locally without depending on a model response or extra inference. Capture declared preferences only from user messages; require exact user grounding for save_rule. Keep read-only enforcement, project isolation, secret rejection and bounded injection.
- Apply manual rules and enabled memories in plain chat as well as tool chat. Preserve current user overrides, separately scoped project notes and PROJECT_INFO.md as project evidence.
- Persist memories in the private memorie/memorii.json store; default off in clean source builds. Deleting memories does not regenerate them from chat history or delete manually managed project files/rules.
- Includes the pending 1.11.5 bounded composer resizing. Installation remains held at the user's request.

## 1.11.5 - 2026-10-21

- Make the composer taller by default (144-pixel text viewport), with a draggable divider and a 96-pixel minimum when space allows.
- Cap the editable viewport at the answer viewport height, including attachment/report bars and window resizing. Preserve the chosen height across restarts and theme changes.
- Keep composer actions and status controls outside the draggable area. Resizing preserves drafts, selections and attachments and makes no API requests.

## 1.11.4 - 2026-10-20

- Refresh the detached context popup UI on theme changes and opening. Paint its explicit themed surface so a stale light popup delegate cannot leave white behind dark-theme text.
- Reduce the context card to 360 pixels wide, tighter typography and spacing, with rounded corners and a small shadow. API details remain collapsed.
- Verify actual on-screen popup opening, cold dark startup, theme transitions, API expansion and dismissal, in addition to offscreen rendering.
- Run the factory catalogue isolation test with a synthetic catalogue in a separate engine/OCR test package, so rebuilding does not require private factory catalogues or install test data.

## 1.11.3 - 2026-10-20

- Replace the modal context message box with an anchored, live context card: occupancy percentage, rounded segmented bar and color-coded system/tool/conversation estimates.
- Match dark/light themes and all six languages. Keep billed API counters in a separate, collapsed section. Unknown limits remain explicit; no unsupported subagent counters are invented.
- Share the dashboard estimates with the sidebar and ring; update the open card during drafts, generation, tools and compaction without extra API calls.

## 1.11.2 - 2026-10-20

- Retry explicit HTTP 502/503/504 gateway failures up to three attempts for each model request, including manual and automatic compaction. Preserve the exact request and all completed tool results; never replay operations or interrupted partial streams.
- Show which stage is retrying, distinguish compaction API failures from context overflow, account for every actual attempt, and honor Stop during backoff.
- Includes the pending 1.11.1 clipboard correction. Installation is held until the user finishes reviewing the compaction issue.

## 1.11.1 - 2026-10-20

- Add explicit Ctrl+V and Shift+Insert clipboard actions and a translated composer context menu (cut, copy, paste, select all), preserved across theme changes.
- Accept clipboard screenshots as local PNG attachments with pixel limits; preserve text paste and Explorer file lists. Selection does not send image pixels to the model.
- Keep drafts on clipboard errors; selecting or pasting attachments makes no API calls.

## 1.11.0 - 2026-10-20

- Add PDF report cards to the conversation and a resizable right preview panel, with page navigation, fit-width/zoom, save copy, external open and file copy.
- Add closable PDF tabs, another-PDF selection, expand/restore and Ctrl+Shift+B to hide/show the panel. Add conversation actions and quick model/profile/tool/approval settings behind working header icons.
- Render one page locally in an isolated, bounded process; previewing does not call the model or consume tokens. Close/switch cancels old work and clears the preview.
- Restore cards from saved conversations without automatically reading PDFs. Translate cards and preview controls in all six languages and both themes.
- Includes the 1.10.3 chat improvements and 1.10.4 PDFCONTROL corrections. Installed and pushed to the personal repository with tag v1.11.0.

## 1.10.4 - 2026-10-20

- Apply VORM DEFINITIEF = DEFINITIEF in the shared status predicate, including generic status cells and compact/spacing variants. Preserve rejection of non-final, provisional and review statuses.
- PDFCONTROL control and regenerated PDFs include every selected drawing, detailed executed checks and available annotated drawing appendices. Count findings independently of included drawings; retain visible catalogue/partial limitations.
- Includes all 1.10.3 chat, Markdown, copying and compaction changes; installation and publication remain pending.

## 1.10.3 - 2026-10-20

- Render assistant Markdown headings, emphasis, code, quotes and tables in the chat, including streaming and restored conversations. Preserve source text and copyable Markdown.
- Show PDF outputs inline after successful tool results, with open, save copy, copy file (Explorer) and copy path actions. Restore actions with the conversation without API calls.
- Add copy response and file clipboard actions in all six languages. Do not present unfinished or unverified PDFCONTROL job outputs as delivered reports.
- Retry oversized summaries up to three times with tighter output budgets; split oversized conversations before inference, preserve the exact new question and retain the original archive. Stop/failure never sends the pending question.
- Make the original control PDF and the old/new comparison PDF explicit, separate deliverables in agent instructions.

# Changelog

## 1.10.2 — 2026-10-20

- Motorul propriu din LocalChat_Build/pdfcontrol devine autoritar: buildul normal nu mai importa automat din C:/PDFCONTROL. Manifestul sursei si copiile instalate se verifica; importul originalului este explicit.
- Corectie pentru cartusul MBS Specials: statusul curent VORM DEFINITIEF/DEFINITIEF este citit din celula sa, fara a imprumuta valori din legende/revizii. Identitatea TRK si armarea reala sunt recunoscute separat; numarul MBS explicit prevaleaza fata de WERKNO al clientului. Sursele private originale nu sunt modificate.
- Apelurile PDFCONTROL din chat sunt urmarite automat pana la starea terminala. Modelul primeste livrarea verificata; apelurile identice reusite reutilizeaza acelasi job in turul curent. Trei erori identice opresc bucla. Stop solicita si anularea jobului activ.

- Conector PDFCONTROL corectat: accepta surse locale din Controle/Atasamente, cu cai relative complete; exclude directoarele de rezultate si PDF-urile deja marcate. Inventarul indica eligibilitatea surselor. Fluxul complet accepta explicit include_drawings.
- PDF-ul livrat pentru un rezultat partial include o pagina explicita cu limitele si sursele selectate. Regenerarea unui raport pastreaza limitarile jobului initial; PDF-ul tehnic original ramane intact.
- Rapoartele si desenele rezultate se livreaza automat in Controle/PDFCONTROL/Rapoarte, separat per job, cu hashuri si manifestul exact al surselor. Erorile de export nu sunt raportate drept succes, rularea partiala este marcata distinct si originalele raman intacte.
- Cataloagele locale Soest/Alphen sunt incluse in snapshotul izolat; fabrica se selecteaza explicit in panou sau prin parametrul factory, fara modificarea altui proiect. Lipsa catalogului este raportata ca limitare si rezultat partial. Referintele lipsa ale skillului productie sunt incluse numai local, fara publicarea datelor private.
- Instructiunile modelului disting pornirea jobului de finalizare si cer dovezi de livrare pentru selectia solicitata, fara substituirea unui alt set de desene.

- Favorite locale pentru modele, separate per profil de furnizor si URL API. Steluta langa selector adauga/elimina modelul curent; favoritele apar primele in selector si in meniul de jos.
- Catalogul ofera steluta pentru modelul selectat si filtrul Doar favorite. Marcarea nu apeleaza API-ul, nu schimba modelul implicit si pastreaza selectia si istoricul. Preferintele sunt pastrate la repornire si reincarcarea catalogului.
- Pictograma desenata functioneaza in Dark/Light; textele sunt traduse in toate cele sase limbi.

## 1.10.1 — 2026-10-20

- Corectie de afisare in chat: raspunsurile modelelor, istoricul vechi, mesajele utilizatorului si previzualizarile uneltelor folosesc litere normale in interfata romana. Liniile de arbori Unicode se afiseaza ca +-- si |, pentru compatibilitate cu fonturile Windows.
- Conversia este strict vizuala, inclusiv in timpul streamingului. Originalul se pastreaza in memorie, in istoricul JSON si in cererile catre model; caile reale si documentele nu sunt modificate. Click dreapta ofera Copiaza textul afisat si Copiaza textul original. Schimbarea limbii reface afisarea fara API si fara pierderea textului.
- Schimbarea modelului nu mai lasa procentul necunoscut doar pentru ca API-ul omite metadatele. Modelele fara date primesc un buget local de lucru de 32768, etichetat cu limita reala API necunoscuta; acesta nu este un maxim dedus al modelului. Metadatele descoperite ulterior inlocuiesc bugetul de lucru. Valorile manuale pozitive sunt pastrate, iar /context 0 ramane o alegere explicita pentru limita necunoscuta.
- NVIDIA Nemotron 3.5 Lightning are acum informatiile documentare verificate: 1M publicat, distinct de limita rutei API; buget local initial 131072. Sursa: https://build.nvidia.com/nvidia/nemotron-3.5-lightning-30b-a3b/modelcard .
- Textele noi sunt traduse in sase limbi. Teste pentru streaming, arborele PDFCONTROL_Rapoarte, selectii/copiere originala, fisiere salvate, schimbarea limbii/temei si trecerea intre modele documentate/nedocumentate.

## 1.10.0 — 2026-10-20

- Verificarea contextului ruleaza inaintea fiecarei cereri catre model, inclusiv dupa citiri PDF si alte rezultate de unelte. Planificarea include intrebarea, sistemul, definitiile uneltelor, rezerva raspunsului si o marja de siguranta.
- Autocompactarea activa per chat compacteaza inainte de continuare daca pragul sau spatiul ramas o cer. Rezultatele mari sunt rezumate in fragmente prin acelasi model, fara unelte operationale; intrebarea curenta ramane exacta. Arhiva originala si perechile apel/rezultat raman integrale, separat de vederea compactata folosita la API.
- Consumul de intrare raportat de API calibreaza estimarile per profil/model. Ultima valoare cunoscuta se pastreaza separat per chat; rezultatele nou adaugate sunt planificate conservator. Ancora este invalidata la schimbarea modelului, sistemului, uneltelor sau memoriei. Estimarile raman marcate cu ~, separat de consumul API facturat.
- Stop si esecul compactarii nu reiau cererea; nu sunt repetate operatiile deja executate. Daca intrebarea si definitiile uneltelor nu pot incapea, cererea este oprita cu explicatie. Contextul necunoscut nu este inventat; autocompactarea oprita este respectata.
- Meniul / are lista completa derulabila cu 24 de comenzi, navigare Sus/Jos, Enter si Esc, selectie cu mouse-ul si contrast corect in Dark/Light. Sunt disponibile /archive, /export, /delete, /attach, /folder, /pdfcontrol, /settings si /autocompact, pe langa comenzile existente, inclusiv /compact.
- Textele noi sunt traduse in toate cele sase limbi; interfata romana ramane fara diacritice. Teste offline pentru bugetul raspunsului, PDF-uri simulate mari citite prin unelte reale, pastrarea intrebarii/arhivei, anulare, reluare si meniul complet in ambele teme.

## 1.9.1 — 2026-10-20

- Textele aplicatiei in romana folosesc litere normale fara diacritice: meniuri, butoane, dialoguri, mesaje de stare/eroare si etichete generate de unelte. Selectorul afiseaza „Romana”, cu acelasi cod de limba ro si aceeasi ordine.
- Traducerile din celelalte cinci limbi se pastreaza. Cheile de traducere sunt compatibile atat cu forma veche cu diacritice, cat si cu forma noua fara diacritice.
- Normalizarea se aplica textelor proprii aplicatiei inainte de inserarea parametrilor. Numele fisierelor, caile, documentele, mesajele utilizatorului si conversatiile salvate nu sunt rescrise sau modificate.
- Include atasamentele si drag-and-drop din 1.9.0 si procentul live din 1.8.1. Pachet pregatit separat, fara inlocuirea ferestrei deschise pana la confirmarea utilizatorului.

## 1.9.0 — 2026-10-20

- Buton compact de atașare lângă mesaj, cu selecție multiplă de fișiere/foldere și drag-and-drop din Explorer peste mesaj sau conversație. Referințele apar ca etichete cu nume, cale în tooltip și eliminare individuală; maximum 20 per mesaj.
- Atașamentele nestrimise se salvează cu ciorna și sunt izolate per chat, inclusiv după repornire. Trimiterea fără text propune analiza selecției. Selectarea nu citește documentele, nu schimbă folderul de lucru și nu trimite cereri către API.
- Referințele trimise sunt disponibile uneltelor reale, inclusiv citirea PDF, DOCX, XLSX/XLSM, MSG și text. Fișierele/folderele selectate explicit primesc acces de citire pentru conversația respectivă, inclusiv la continuare. Selectarea unui fișier nu autorizează citirea vecinilor sau modificări; setările cu chei rămân protejate.
- Modificarea selecției este blocată în timpul lucrului. Eșecul fără răspuns util reface ciorna și selecția pentru reîncercare; autocompactarea verifică și selecția înainte de retrimitere. Clipboard-ul de text rămâne funcțional.
- Interfață tradusă în toate cele șase limbi și testare offline a transferurilor, persistenței, accesului restrâns și citirii reale prin unelte. Include corecția procentului live din 1.8.1, încă neinstalată anterior.

## 1.8.1 — 2026-10-20

- Procentul estimat funcționează automat pentru modelele cu recomandări documentate sau metadate cunoscute, inclusiv NVIDIA Muse Glimmer: buget local inițial 131072 tokeni, etichetat explicit ca recomandare automată. Limita reală a API-ului rămâne distinctă; modelele necunoscute păstrează „~?”.
- Bugetele manuale pozitive și setările separate per model/furnizor se păstrează. Bugetul automat respectă limitele API cunoscute; nu declanșează inferență sau compactare la încărcarea chatului.
- Cercul și panoul afișează același procent cu două zecimale, actualizat cu textul primit și fiecare rezultat de unealtă, fără dublarea textului sau a consumului API. Actualizarea estimării nu reprezintă usage facturat în timp real.
- Eticheta nouă este tradusă în toate cele șase limbi. Teste offline pentru bugete automate/manuale, schimbarea modelului, limite API, procente mici, streaming și rezultate de unelte.

## 1.8.0 — 2026-10-20

- Added a searchable model catalog with response-test status, responded-only filtering, active-first ordering and image/tool/reasoning vector badges with explicit unknown states and sources.
- Manual model loading can test uncached models with tiny synthetic prompts; startup, chat resume and metadata refresh never infer. Tests use two paced workers, cancellation, rate backoff and account-specific results that expire after 24 hours.
- Distinguished HTTP 403/404 account access failures, 401 authentication, 429 rate limits, incompatible test requests, empty responses, different served IDs and transport/EOF errors.
- Added a harmless tool-call test that verifies a returned call without executing any action. Probes never receive user history, files or operational tools.
- Show documented NVIDIA DeepSeek V4.1 Flash capabilities and the model's published 1048576-token maximum separately from the unreported route limit; offer an explicit conservative 131072-token local working budget.
- Clarified cumulative API usage, the last completed turn and estimated local context occupancy; read input_tokens/output_tokens aliases in compatible API usage.
- Added synthetic HTTP/cache/UI regression tests and six-language, two-theme catalog rendering.

## 1.7.0 — 2026-10-20

- Added named provider profiles with separate URLs, protocols, credentials, cached model lists and defaults; conversation state stores only the profile ID, never credentials.
- Enabled read-only metadata inspection for every protocol, including Anthropic-compatible gateways; remote providers use their standard models catalog.
- Distinguished declared context, loaded instance context, API input caps and local working budgets. Missing context fields and authentication errors are now explicit.
- Added an optional conservative GonkaRouter working budget of 180000 tokens, based on the published approximate 200K input cap; it is not presented as API-confirmed context.
- Preserve manual budgets when a catalog omits context; prevent late model lists from overwriting another provider selection.
- Use the saved provider name or gateway host in new reply labels instead of calling every Anthropic-format model Claude.
- Added HTTP and UI tests for named providers, credential isolation, chat resume, restart and external metadata, with six-language/theme rendering.

## 1.6.0 — 2026-10-20

- Verify installed engine contents against the tested snapshot regardless of legacy file dates.
- Connected extraction, confirmed V/W pairing, checking, visual revision comparisons and report regeneration to the original PDFCONTROL engine.
- Restored original PDF reports with available annotated drawing annexes; reports are actual files, not chat substitutes.
- Added a direct PDFCONTROL panel in six languages and both themes, with progress, cancellation and opening saved PDFs; no model request required.
- Added isolated jobs, source integrity manifests, explicit partial results and bounded paginated status responses.
- Detect workers that exited unexpectedly and retry atomic status writes on Windows sharing conflicts.
- Added real-engine MCP and Java panel tests on synthetic drawings, alongside existing UI/session tests.
- Made chat selection restoration transactional to prevent intermediate controls from saving incomplete state.

## 1.5.0 — 2026-10-19

- Slimmed footer controls to 26px, added vector send/stop/new-chat/folder icons and a rotating busy indicator.
- Added right-click rename and removed internal IDs from visible sidebar titles.
- Replaced the compose Downloads action with the selected working folder and its name.
- Context estimates update on draft edits and streamed deltas, with a 200ms dashboard refresh.
- Automatically read LM Studio metadata after a stable model/server selection and once per minute; fixed a stuck in-flight flag preventing further refreshes. Stale responses cannot change another model profile.
- Explicit settings snapshots now restore response profile, stream, tool mode, approvals, system prompt and auto-compaction alongside model/API/folder.
- Clarified that files produced by tools are already on disk; saving an extra copy remains optional.
- Verified all six languages, both themes, metadata retry/recovery, live streaming activity and complete settings restoration.

## 1.4.0 — 2026-10-19

- Added recoverable deletion, archived/active views, context menus and JSON/ZIP chat export.
- Added explicit settings/default model saving, cached model lists and portable historical project path correction.
- Added native LM Studio metadata inspection with separate theoretical maximum and loaded context; unknown stays unknown and chat loading remains offline.
- Added bottom context ring and model, reply-budget, tool-mode and permission selectors. Reply budgets are not reasoning levels.
- Added enforced read-only mode, including saved rules, writes and commands.
- Translated every new control into all six languages and extended offline integration tests.

## 1.3.0 — 2026-10-19

- Added per-chat automatic compaction controls in all six UI languages: enable checkbox and 30–95% threshold (default 70%).
- Requires an explicitly configured context limit; no automatic API request is made on chat load.
- Before a user message, compacts eligible older history at the threshold and sends the unchanged pending message once after success.
- Editing the draft or pressing Stop prevents automatic sending. Failed compaction preserves both draft and existing memory.
- Kept original archives, token accounting and automatic preferences isolated per conversation.
- Added a context preflight guard to subsequent tool-loop requests as well as the initial user request.

## 1.2.0 — 2026-10-19

- Added a searchable session sidebar with single-click resume, custom titles and pins.
- Saved each chat's model/provider, working folder, draft, context limit, usage and compact memory independently.
- Preserved old conversation files; loading a chat never makes an API request.
- Added an estimated next-request context breakdown; unknown limits remain unknown until configured.
- Captured server token usage for OpenAI-compatible and Anthropic requests, including streaming, caching and tool loops.
- Kept last-response and per-chat totals separate; missing or interrupted usage is marked incomplete.
- Added /status, /model, /new, /stop, /token, /history, /compact, /list, /switch, /resume, /title, /pin, /skills, /connectors, /context and /help, with localized suggestions.
- Added manual per-chat compaction: a factual model-generated summary plus the latest two exchanges is sent next time; the original archive is retained in full.
- Added verified local report references with Open, Open folder and Save a copy actions.
- Translated all new controls, descriptions and statuses into all six UI languages.
- Added offline loopback API and session/compaction integration tests before deployment.

## 1.1.0 — 2026-10-19

- Added FlatLaf Light and Dark themes, switchable without restarting.
- Added offline UI languages: Nederlands, Română, Español, Polski, English, Türkçe.
- Preserved drafts, streaming transcript, provider settings and permissions when changing appearance.
- Saved language and theme preferences beside the portable application.
- Displayed the version in the window title, appearance bar and About menu; VERSION is the single build source.
- Added an offline UI regression suite and build gate before updating the working distribution.
- Build output defaults to the sibling LocalChat folder; local settings and conversation history are retained.
