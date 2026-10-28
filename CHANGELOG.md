# 1.28.11 - 2026-10-28
- Cerere aprobata de Dumitru dupa raportul din productie (fisiere ajutatoare `_tmp_*.py` in folderul proiectului, `.md`/`.py` ajunse pe server, apoi CSV-uri modificate pe `J:` de MiniMax-M2.7 in "Permite tot"): LocalChat tine acum scripturile si fisierele temporare departe de proiect si de server.
- Folder de lucru temporar pe conversatie (`FileGuard.scratchFor`): `%TEMP%\LocalChat\<conversatie>`, creat la cerere, variabila `LOCALCHAT_SCRATCH` in `run_command`; scripturile se scriu acolo si pot rula cu proiectul ca folder curent (calea scriptului este absoluta). Specificatia `run_command` si eroarea "python multi-linie" trimit acum in scratch (nu in folderul proiectului); livrabilele raman numai in `Output\` / `Controle\`. `read_file`/`write_file` accepta scratch fara card.
- Paza dura (`FileGuard`, si in "Permite tot"; "Da mereu" nu o ocoleste): in `write_file`, `append_file`, `edit_file`, `make_dir`, `copy_path`, `move_path` si in redirectari/`Out-File`/`Set-Content`/`Add-Content`/`Tee`/`New-Item`/`mkdir`/`copy`/`move`/`xcopy`/`robocopy`/`Copy-Item` din `run_command`, fisierele noi `*.py|*.ps1|*.bat|*.cmd|*.vbs|*.js|*.md|_*|tmp*|temp*` din folderul proiectului (`Downloads\PRODUCTIE\<nr>` si folderul de lucru configurat) sau pe cai de retea (UNC, unitati mapate detectate cu `net use`, plus `workguard.networkdrives`) sunt refuzate cu mesaj care arata scratch. Excepti: `PROJECT_INFO.md`, `Pentru_ChatGPT_<nr>.txt`, tot ce este sub `Output\` si `Controle\`, fisierele create de uneltele `pdfcontrol`.
- Server: orice copiere/mutare/scriere/modificare pe server arata MEREU un card de confirmare cu lista de fisiere si extensiile (si in "Permite tot"; raspunsul nu se retine, doar Permite/Refuza). Pe server merg numai `pdf, xlsx, xls, csv, dwg, dxf, ifc, docx, zip`; `.md/.py/.ps1/.log/_*/tmp*/OLD` sunt refuzate. Copierea unui folder intreg copiaza numai fisierele permise si spune ce a ramas deoparte; mutarea unui folder cu fisiere refuzate este oprita.
- Corectie dupa dovada noua (CSV-uri modificate pe `J:\Projecten\...\Import` si `Complete Lijst` cu `[IO.File]::ReadAllText` + `-replace` + `WriteAllText`/`Set-Content` si `Copy-Item`): orice comanda care poate scrie, modifica, muta sau sterge pe server (`WriteAllText/WriteAllLines`, `Set-Content`, `Out-File`, `Add-Content`, `Copy-Item`, `Move-Item`, `Remove-Item`, `robocopy`, `xcopy`, `copy`, `move`, `del`, redirectari, `shutil`, `open(...,'w')`) cere card cu fisierele tinta, chiar si in "Permite tot"; la `.py/.ps1/.bat/.cmd/.vbs/.js` rulate din `run_command` se scaneaza si textul scriptului. Analiza este euristica (cale de server + verb de scriere), deci poate cere confirmare in plus, dar nu mai lasa o modificare pe server fara card.
- Reguli care supravietuiesc compactarii: bloc scurt "Hard rules" in promptul de sistem, memento in `volatileControl` dupa prima compactare, iar promptul de rezumare (`ContextGuard.KEEP_RULES`) cere pastrarea literala a regulilor si a restrictiilor din skill-uri. Dupa o compactare `read_skill` da din nou textul complet (`ToolLoopGuard.afterCompaction`), iar mesajul din `LocalChat` este reformulat. `Hotfolder`: formularea despre copierea pe server este inmuiata (numai dupa confirmarea explicita a listei); `Output` nu mai declanseaza notificari (`Controle` ramane monitorizat).
- Unelte noi `list_stray_files` (citire) si `cleanup_stray_files` (stergere numai dupa card cu lista, mereu, nu se retine; nu sterge niciodata fisierele din lista permisa).
- Text nou in 6 limbi, romana fara diacritice. Bugete: toate uneltele +2 in `TokenBudgetTest`, promptul in `RuleCoverageTest`/`StablePrefixTest` (vezi testele).
- Teste: `Release11211Test` (nou: cai proiect/UNC/`J:`, "Permite tot", "Da mereu" fara ocolire, lista de extensii, folder intreg cu `.py`/`.md`, scratch + folder curent, comenzi de scriere pe server si scripturi, curatare, prompt in 6 limbi, reguli dupa compactare, `read_skill` dupa compactare, hotfolder fara regresii) plus suitele existente.
- Neverificat: testele native (`-SkipNativeQA`); detectia comenzilor si scripturilor este euristica (o cale construita la executie nu se vede; singura plasa este nota despre fisiere ramase in proiect dupa comanda); nicio rulare cu un model real; unitatile mapate se citesc cu `net use` (cache 30 s).

# 1.28.10 - 2026-10-28
- Compactarea nu mai moare la primul `finish_reason=length` (cerere aprobata dupa raportul din productie: GonkaRouter, MiniMax-M2.7, context 54,8%, "Compactarea a fost oprita de limita de iesire"). Cauza: cererea de compactare nu trimitea `max_tokens`, iar modelele care nu pot opri gandirea (MiniMax, GLM, gpt-oss, magistral) au consumat plafonul implicit al serverului in gandire, deci rezumatul ramanea gol/taiat si era respins.
- Limita de iesire explicita: `MemoryReducer.outputLimit` trimite `max_tokens` (`max_completion_tokens` pe OpenAI) = min(limita de iesire din metadate daca se stie, 16384, fereastra - intrare - marja), cel putin 256. Pentru modelele care nu pot opri gandirea (`ThinkingKnowledge` NO_OFF, sau gandirea nu se trimite) limita (8192) se trimite de la prima cerere; pentru celelalte prima cerere ramane fara plafon artificial (neschimbat) si limita explicita vine la reincercare. Anthropic isi pastreaza plafonul propriu. HTTP 400 pentru limita: cererea se repeta o singura data fara limita si se tine minte pe durata fragmentului; un 400 fara limita trimisa nu este inghitit.
- Reincercare la taiere in `MemoryReducer.reduce()`: cel mult 2 reincercari. Reincercarea 1 foloseste limita explicita mai mare (16384, plafonata); reincercarea 2 injumatateste fragmentul (~4000 tokeni, numai daca fragmentul are >= 512 tokeni estimati) si cere "Do not reason ... reply immediately with the factual memory only, at most N words". Fiecare jumatate are checkpoint propriu (`CompactionCheckpoint`), iar un fragment a carui prima jumatate este deja salvata reia direct de la etapa injumatatita, deci nu se pierde munca. Rezumatele taiate NU se accepta si NU se salveaza.
- Mesaj de esec clar (6 limbi, romana fara diacritice): "gandirea modelului a consumat limita de iesire (~N tokeni, motiv oprire: length) ... chiar dupa 2 reincercari ...", cu tokenii de gandire din `Agent.reasoningSpent` si `finish_reason`; fara gandire, mesajul spune doar limita de iesire si motivul. Istoricul si memoria raman neatinse. Cheia veche a mesajului a fost inlocuita (738 chei pe limba).
- Bara de stare explica de ce porneste compactarea (`Agent.Ui.compactReason`): "Compactare automata: estimare ~72,31% dupa rezultatele noi (prag 70%)", "estimare ~X% a atins pragul de 70%" sau "estimare ~X% plus rezerva de raspuns depaseste limita de context"; pe calea manuala/pre-trimitere textul se adauga la starea fragmentului ("Compactare: fragment 1/3 - ..."). Reincercarile apar in bara ("Reincerc 1/2 cu limita explicita de iesire" / "cu fragmente mai mici si fara gandire"). `ContextGuard.driven` spune cand estimarea (ancora + rezultate noi numarate de 4 ori) a depasit pragul numai din rezultate noi. Pragurile si multiplicatorul 4x NU s-au schimbat.
- Teste: `CompactionTruncationTest` (nou, 148 verificari, server fals: length + `reasoning_content` apoi succes; `max_tokens` in corpul reincercarii si plafonarea la metadate/fereastra; limita explicita de la prima cerere pentru modele fara oprire; fallback HTTP 400 (inclusiv ca primul 400 pe `fixture` este reincercarea parametrului de gandire); doua reincercari exact; injumatatirea fragmentului, checkpointuri pe jumatati si reluare; mesajele in 6 limbi cu diacritice absente in romana; motivul compactarii din `ContextGuard`); `CompactionBudgetTest` adaptat (3 cereri la taiere, mesaj nou). Neverificat: comportamentul real al lui `max_tokens` explicit pe GonkaRouter/MiniMax-M2.7 (numai servere false); testele native nu au rulat.

# 1.28.9 - 2026-10-28
- Limite de context (cerere aprobata "TOATE"): regula de nume qwen3.x/3.5-3.9 = 262144 nativ, plafonata de contextul incarcat (sugestia de buget ramane plafonata la 131072, deci prudenta); pentru gazde custom non-LAN se citeste si `/api/v1/models` nativ LM Studio cand `/v1/models` nu da contextul (404 ignorat, negativ memorat o ora). Avertisment ne-modal in 6 limbi cand limita manuala depaseste contextul incarcat pe server; pragurile folosesc valoarea mai mica (`LocalChat.cappedLimit`). Valorile din `setari.properties` ale utilizatorului nu sunt modificate.
- `ToolResultReducer`: `KEEP_RECENT` si pragul de declansare cresc cu dimensiunea contextului si cu contextul liber (`keepRecent(limit,used)`, max 40; la >=70% folosit revine la 6); sub 40% folosit rezultatele raman neatinse; contextul mic (<=65536) se comporta ca pana acum.
- `ToolRelevance`: modelele cu unelte verificate sau declarate primesc implicit si uneltele de scriere/comanda (fiecare modificare tot cere aprobare); modelele necunoscute sau slabe pastreaza selectia. Comutator nou "Trimite toate uneltele" (`tools.all`) in setarile rapide.
- Filtrul MBS din lista de modele arata acum modelele cu unelte necunoscute ca "MBS neconfirmat" (sugerate doar dupa test) si ascunde in continuare tools=false, modelele specializate pe sarcina si cele blocate pentru cont.
- Mistral: `capabilities.reasoning` boolean este citit in `ThinkingKnowledge.fromRow`; regula de nume acopera si `mistral-medium*`.
- Unealta noua `download_url` (mutatie cu aprobare, serializata): despacheteaza linkurile Outlook safelinks, urmeaza redirectionari publice (max 8), descarca numai raspunsuri de tip fisier direct, cu limita de dimensiune, SHA-256, fisier `.part` mutat atomic, nume `fisier (1).ext` si inregistrare in `GeneratedFiles`; nu parseaza HTML si nu apeleaza API-uri private. WeTransfer este recunoscut inainte de orice cerere sau prompt: mesaj neutru "are nevoie de browser" plus oferta de deschidere in browser (`open_in_browser`, aprobare RUN). Unealta nu afirma niciodata parola, expirare sau procesare. Regula din descrierea uneltei, independenta de skill: nu scrie scripturi si nu face inginerie inversa pe WeTransfer sau pe alte API-uri private de descarcare.
- Hotfolder si fisierele scrise de model (corectie dupa raportul din productie: folderul de lucru este folderul proiectului de 6 cifre, deci si hotfolder): (a) `Hotfolder.helperName` exclude din notificari fisierele ajutatoare ale agentului: nume care incep cu `_` sau `Bronnen_`, `temp`/`tmp` urmat de separator (`temp_x.csv`, `tmp-1.xlsx`; nu `tempest.csv`), `*.tmp/.part/.crdownload`, directoarele `__pycache__`/`node_modules` (fisierele `.`/`~$` erau deja excluse); scripturile `.ps1/.py/.bat/.cmd/.js/.vbs` nu au fost niciodata surse de notificare. (b) `GeneratedFiles.Scope` nu mai parcurge folderul de lucru (fara inventar inainte/dupa, deci fara limita de 10000 si fara nota "IOException"); inregistreaza linistit tintele declarate in comanda (redirectari `>`/`>>`, `Out-File`/`Set-Content`/`Add-Content`/`Export-Csv`/`Export-Clixml`, `-OutFile`/`-o`, `Copy-/Move-Item`, `copy`/`move`, `to_csv`/`open(...,"w")`/`save(`...) in registrul hotfolder fara notificare; comenzile clar read-only (dir, type, git status etc.) nu fac nimic. Nu se mai afiseaza nicio nota pentru inventar. (c) Fisierele puse de utilizator in timpul unei comenzi raman vizibile (nu exista marcare globala). Limite: o scriere ascunsa (cale construita in variabila, cod ofuscat) cu nume care nu e de ajutor poate notifica; un fisier de client numit `_x.pdf`, `Bronnen_*` sau `temp*.csv` nu va notifica niciodata. `git config` fara `--get/--list` nu mai este considerat read-only.
- Apeluri de unelte scrise ca text (MiniMax `<minimax:tool_call>` etc.): LocalChat nu le executa niciodata, iar acum asta se vede si e sigur: notificare pentru utilizator (6 limbi), instructiune REPAIR catre model ("NICIUN apel nu a rulat; orice rezultat descris de tine nu e real"), iar textul final dupa un apel malformat este marcat `[NEVERIFICAT: ...]`. Cauza raportului "WeTransfer cere parola" a fost exact acest text halucinat de model, nu un raspuns real.
- Regula de raspuns in prompt-ul de sistem stabil (aprobata de Dumitru): "Answer as an expert in the asked domain: first gather what tools, files and skills can supply; if important details are still missing and would materially change the answer, ask one precise question and stop (never for trivial details). Then answer clearly and structured, with practical examples and actionable recommendations." Inlocuieste vechea linie "If essential information is missing...". Masurat: prompt-ul agentului 4494 -> 4740 caractere (+246 caractere, ~+62 tokeni), prefix identic intre ture (cache-ul KV ramane valid); `RuleCoverageTest` are patru reguli noi.
- Paza pentru comenzi sensibile (`SensitiveGuard`, aprobata de Dumitru): inainte de `run_command` (si `read_file` pe magazii de cookie/parole de browser) se cauta, fara a tine cont de litere mari/mici: `net use`, `New-/Remove-PSDrive` cu `-Persist`/`-Credential`/radacina UNC, `New-/Remove-SmbMapping`, `mountvol`/`subst` pe UNC, `Get-Credential`, `cmdkey`, `runas`, `ConvertTo/From-SecureString`, `PSCredential`, managerul de credentiale/`VaultCmd`/`PasswordVault`, DPAPI (`CryptUnprotectData`, `ProtectedData.Unprotect`), `mimikatz`/`lsass`, magazii de browser (`Cookies`, `Login Data`, `Local State`, `Web Data`, `cookies.sqlite`, `logins.json`, `key4.db`, `browser_cookie3`). Se scaneaza si continutul `-EncodedCommand`/`-enc`/`-ec` (decodat, inclusiv imbricat) si scripturile rulate dupa cale (`.ps1 .psm1 .py .bat .cmd .vbs .js .wsf .hta .sh`, relativ la folderul comenzii, UTF-8/UTF-16, apeluri imbricate pana la 3 niveluri), deci si un script scris chiar atunci de model este verificat la rulare. Nu sunt marcate: `net user/view/share`, `subst`/`New-PSDrive` locale fara credentiale, Select-String pe un `.msg`, cuvinte obisnuite.
- Cardul de aprobare apare MEREU, si la modul de aprobari automat/"permite tot", si spune in 6 limbi ce face comanda si de ce e sensibila, cu tinta. Optiuni: Permite (o data) / "Permite mereu pentru aceasta tinta" / Refuza. "Mereu" se retine per categorie + tinta normalizata (`netdrive`: `\\server\share` + litera unitatii; `credentials`: tinta `cmdkey`/utilizator `runas`; `browser-store`: browser + profil), nu global: alt server, alta categorie sau alt profil intreaba din nou. Setare noua (setari rapide): "Comenzi sensibile: retine alegerile" (implicit) sau debifat = intreaba mereu (alegerile retinute sunt ignorate si nu se mai retin), plus actiunea "Sterge aprobarile sensibile retinute (N)". Alegerile se salveaza in config (`sensitive.mode`, `sensitive.allowed`: numai etichete `categorie|tinta`, fara secrete). Refuzul intoarce modelului un rezultat clar: comanda NU a fost executata, nu o repeta, nu o reface pe alta cale si nu ocoli refuzul; refuzul nu se retine.
- HTTP 429/503 = furnizorul este saturat, nu contextul (corectie dupa captura de ecran din productie: fereastra cu alternative OpenRouter venea din HTTP 429 GonkaRouter, nu din pragul de 70%): sfatul spune explicit furnizorul este saturat (HTTP N), nu contextul, conduce cu reincercarea (peste putin timp, reincarca lista si reselecteaza modelul), alt model doar la urma, iar compactarea este mentionata ca separata si automata. Cardul cu alternative spune la fel si ramane optional (se afiseaza si la 503). HTTP 429 se reincearca automat ca 502/503/504, cel mult 3 incercari, aceeasi cerere, pauza 3 s x incercare, numai inainte de orice raspuns (uneltele nu se repeta, Stop functioneaza). La >= prag (70%) LocalChat compacteaza singur (cu auto-compactarea oprita arata avertismentul de la 80%); nu a propus niciodata alt model din cauza pragului. Dupa Incarca modele sau reselectarea modelului, daca exista conversatie, bara de stare spune ca procentul de context include conversatia curenta (reincarcarea nu o goleste).
- Teste: `Release1289Test` (nou, 237: limite de context, reducer, relevanta, filtru MBS, Mistral, `download_url` pe servere false, hotfolder fara inventar/nume ajutatoare/declarari de scriere/folder urias, protocol malformat, 429/503 si pragul de context, 6 limbi); `InferenceRetryTest` (+6: 429 reincercat, 3 incercari, Stop in pauza), `ErrorHintTest`, `ConcurrentChatsTest` adaptate, `SensitiveGuardTest` (nou, 184: matrice de detectie pozitive/negative, tinte, comenzi codate, scripturi dupa cale/imbricate/UTF-16, auto/always tot intreaba, retinere per categorie+tinta, tinta diferita intreaba din nou, "intreaba mereu", stergere, refuz, mod doar citire, `read_file`, 6 limbi); adaptate `ModelSuitabilityTest` (145), `ProviderSetupTest` (40), `RuleCoverageTest` si `TokenBudgetTest` (plafoane ridicate deliberat dupa masurare: toate uneltele 33 / 18.099 caractere, implicit 23 / 12.289, doar citire 15 / 7.578, overhead 17.100 = 60,3%; cauze: unealta `download_url` si regula de raspuns).
- Neverificat: nicio cerere reala catre WeTransfer, Mistral, LM Studio sau alt API (numai servere false si parsare pura pentru safelinks); detectia sensibila nu poate vedea cod ofuscat (concatenari, base64 in interiorul altui script) si nu acopera unelte MCP externe; relay-ul workers.dev nu a fost atins.

# 1.28.8 — 2026-10-28
- Detectie automata a gandirii per model (cerere: "fa un test inainte de a porni chatul sau cand incarci modelul, opreste gandirea doar cand stii, si arata utilizatorului ca o poate porni"). Rafineaza 1.28.7: gandirea implicit OFF pe custom nu mai este generala. Un model custom este OFF implicit DOAR cand se stie ca gandeste SI ca parametrul de oprire functioneaza; altfel ramane Automata (nu se trimite nimic). Alegerea explicita din Setari rapide > Gandire (sau un `thinking=off` vechi) castiga mereu; cheia de cunoastere este `baseUrl|model`.
- Surse, in ordine: (1) test scurt salvat (`ThinkingProbe`), (2) metadate de catalog (`ThinkingKnowledge.fromRow`): obiectul `reasoning` OpenRouter (`mandatory:true` = nu se opreste niciodata; altfel `reasoning:{effort:none}` daca `supported_efforts` contine `none`, altfel `reasoning:{enabled:false}`, niciodata combinat cu effort/max_tokens), `capabilities.reasoning` din LM Studio nativ `/api/v1/models` (`allowed_options`, `default`), `supported_parameters` fara reasoning = nu gandeste; (3) reguli de nume pentru furnizorii verificati: Mistral (`mistral-small-*`, `mistral-medium-3-5*`, `mistral-large-4*` = `reasoning_effort:none`; `magistral-*` si GLM = fara oprire), NVIDIA (`step-5-preview` = `reasoning_effort:none`, nemotron/qwen3 = `chat_template_kwargs.enable_thinking:false`, `gpt-oss` = fara oprire), gonkarouter (MiniMax = fara oprire, DeepSeek-V4-Flash = nu gandeste implicit), Tileward necunoscut. Adevarat/fals/necunoscut se combina camp cu camp.
- Test automat in fundal (`ThinkingProbe`, la alegerea modelului prin `autoInspectModel`): numai cand metadatele si regulile nu dau un raspuns sigur, numai pe custom, nu cat ruleaza o tura, o data pe model si sesiune, niciodata daca utilizatorul a ales deja. Doua cereri scurte in streaming (`max_tokens` 32, o linie de prompt): una fara parametru si una cu parametrul candidat. Gandirea se vede din `reasoning_content`/`reasoning`, tokeni de gandire in `usage`, bucati `thinking` Mistral sau eticheta inline de gandire. Oprirea este "eficace" doar daca prima cerere gandeste si a doua nu; 400/422 = respins; ignorare tacuta = nu se poate controla. Un model care nu gandeste costa o singura cerere; erorile (401, retea, 5xx) nu invata nimic si nu se salveaza. Rezultatul se salveaza ca `thinking.probe.<hash>` (metadatele ca `thinking.meta.<hash>`, fara url/model in clar). Nu se foloseste gateway-ul workers.dev in teste.
- UX: cand oprirea implicita se aplica, apare o data pe model, in bara de stare (nu modal): "Gandirea este oprita pentru raspunsuri mai rapide. Porneste-o din setari rapide > Gandire pentru raspunsuri mai atente." (`thinking.notice.<hash>`). Meniul Gandire arata starea detectata (gandeste si se poate opri / nu se poate opri / nu gandeste / oprire neconfirmata / necunoscut) si un element "Testeaza gandirea" care ruleaza testul pe loc (suprascrie rezultatul vechi).
- Parametrul de oprire urmeaza stilul modelului (`ThinkingControl.Style`: `reasoning_effort`, `reasoning{effort}`, `reasoning{enabled:false}`, `chat_template_kwargs`); un model care nu se poate opri nu primeste niciodata parametru (nici la Oprita explicit), iar un model fara nicio informatie incearca `reasoning_effort:none` ca inainte (cu rezerva 400/422 si memorarea respingerii).
- Raspunsuri cu continutul ca lista de bucati (Mistral: `[{type:thinking,...},{type:text,text}]`), in streaming si fara: textul este raspunsul, bucatile `thinking` sunt gandire (doar observabilitate). `ModelCatalog` recunoaste acum `reasoning` ca obiect si probeaza si raspunsurile cu lista.
- Teste: `ThinkingDetectTest` (nou, 77: metadate OpenRouter/LM Studio, reguli de nume, stiluri de cerere, tabel de clasificare, proba pe server SSE fals: gandeste+opreste, ignorat, 400, fara gandire, usage, eticheta inline, bucati Mistral, 401, cache si reincarcare, notificare o singura data, liste de bucati), `ThinkingDefaultTest` 16 (adaptat: model necunoscut = Automata).
- Neverificat: comportamentul real al API-urilor Mistral/NVIDIA/OpenRouter/gonkarouter/Tileward si al serverului LM Studio (nu s-au facut apeluri reale; numai servere fictive). Relay-ul workers.dev nu a fost atins.

# 1.28.7 — 2026-10-28
- Cauza (caz real: `run_agents` si compactare esuate cu `HTTP 524` pe un server LM Studio din spatele unui proxy Cloudflare): proxy-ul inchide conexiunea dupa ~100 s fara niciun octet; LM Studio nu trimite nimic pana la primul token (incarcare, coada si prefill sunt tacute), iar gandirea modelului lungea exact aceasta faza. Remedieri, toate cu teste:
- Gandirea implicit OFF pe serverele custom (compatibile OpenAI): fara o alegere explicita pentru profil, se trimite `reasoning_effort:none`. Alegerea explicita Automata sau Oprita din Setari rapide > Gandire se salveaza (`<profil>.thinking.explicit=true`) si castiga mereu. Migrare: un profil salvat inainte de 1.28.7 are `thinking=auto` fara marcajul explicit, deci conteaza ca implicitul vechi si devine OFF o data (cine vrea gandire alege Automata). Furnizorii non-custom nu se schimba. Daca serverul respinge parametrul (400/422), cererea se reia fara el (rezerva `ThinkingControl` pastrata); rezerva de iesire pentru gandire si observarea gandirii se opresc numai cand parametrul chiar se trimite. Text nou la tooltip-ul Gandire (6 limbi). `ThinkingControl.effective/choose`.
- `run_agents`: cererile copiilor sunt acum in streaming (`stream:true`), deci tokenii de raspuns/gandire mentin conexiunea activa. Paralelism implicit 1 pe servere custom (2 pe celelalte); setare `agents.parallelism` (1-3) in setari. Rezultatul grupului raporteaza `parallelism`.
- Compactare (automata `ContextGuard` si manuala `/compact`): cererea cere gandire oprita pe servere custom (cu aceeasi rezerva la respingere), iar fragmentele sunt de cel mult ~8000 tokeni estimati (`ContextGuard.COMPACTION_FRAGMENT`; garda de numar de fragmente neschimbata). Fara plafon de iesire adaugat (un server care ignora parametrul ar fi trunchiat).
- HTTP 522/523/524 (proxy, inainte de orice raspuns sau unealta) se reincearca de cel mult doua ori (3 incercari in total) cu pauza 4 s x incercare; 502/503/504 ca inainte. Mesajul este o linie citibila `HTTP 524 (proxy timeout ~100 s)` in loc de pagina HTML Cloudflare. Sfatul pentru 524 spune ce s-a intamplat (compactare/sub-agenti, server tacut ~100 s) si unde se opreste gandirea (butonul cu slidere > Gandire > Oprita); text nou in 6 limbi, RO fara diacritice.
- Teste: `AgentGroupTest` 62 (stream:true la copii, paralelism, 524 cu `error_detail` si rezultat partial), `ThinkingDefaultTest` (nou: implicit OFF pe custom, Automata explicita respectata, migrare, cerere si copii), `CompactionThinkingTest` (nou: `reasoning_effort:none` la compactare, rezerva la 400, fragment limitat), `ReasoningTruncationTest` (asertiunile 524 inversate), `InferenceRetryTest` (524/523/522 reincercate), `ErrorHintTest` (sfatul 524).
- Neimplementat (idee deschisa, neaprobata): un proxy keep-alive pe Mac care trimite octeti catre Cloudflare cat timp LM Studio tace.

# 1.28.6 — 2026-10-28
- Skill `productie-projecten` (copia locala, in afara git): textul vechi a fost curatat, cu aprobarea lui Dumitru, doar la mecanica invechita: aplicatia `C:\PDFCONTROL`, `127.0.0.1:8756`, `START_WEBAPP.bat` si apelurile directe `mbs_*` trimit acum la uneltele `pdfcontrol_*` (apelurile urmeaza skillul `pdfcontrol-octop`); mentiunile istorice Codex/LM Studio au fost scoase; sectiune noua despre ce reglementeaza fiecare skill; datele de catalog/productie au fost mutate in `data_lokaal/` privat (acelasi folder al skill-ului, ignorat de git, instalat de Build.ps1 prin robocopy; `read_skill` le listeaza in SKILL_RESOURCES). Regulile de business, verificarile, pragurile si limitele de autorizare sunt pastrate; nu s-a comis niciun fisier al skill-ului.
- `SkillNotes.forSkill(name, skillText)`: cand textul skill-ului trimite deja la `pdfcontrol-octop` (copia adaptata), nota `read_skill` pastreaza doar ce textul nu poate sti (publicarea ChatGPT = `pending_cloud_transfer`, copiere/UNC prin `run_command`): ~500 in loc de ~1500 caractere. Orice alta copie a skill-ului (fara trimitere) primeste nota completa neschimbata, deci plasa de siguranta ramane. `forSkill(name)` ramane nota completa. `SkillNotesTest` 45 verificari (15 noi); `Skills.read` transmite textul deja citit, fara o a doua citire de fisier.

# 1.28.5 — 2026-10-28
- Verificare cu model real (fara modificari de cod in ChatClient/Agent): ruleaza intr-un harness extern (nu in repo) calea reala de cerere a LocalChat, cu cheia configurata local (necitata in clar nicaieri), pe tunelul `https://api.xorvapi.dpdns.org/v1` catre qwen3.6-35b-a3b din LM Studio. Chat scurt cu stream:true si gandire OFF: 2,7 s, primul text la 2,05 s, 35 tokeni intrare / 8 iesire / 0 gandire (`reasoning_effort="none"` acceptat); cu AUTO pentru comparatie: 49 s, 544 din 553 tokeni de iesire sunt gandire. Bucla cu unelte (Agent + MCP PDFCONTROL real, pdfcontrol_status si pdfcontrol_list_projects): 2 apeluri reusite, 0 erori, 2 cereri catre model, 5165 tokeni intrare / 13 iesire, 7,9 s cu modelul cald (51 s la prima cerere, incarcarea modelului). Nu s-a gasit niciun bug; detalii in DEVELOPMENT_STATUS.md.
- Skill `productie-projecten`: nota generica de adaptare, adaugata numai la rezultatul `read_skill` pentru acest skill (`SkillNotes`, ~1500 caractere, fara date de productie). Mapeaza pasii vechi (webapp `C:\PDFCONTROL`, `mbs_webapp.py`/`mbs_*`, `START_WEBAPP.bat`, `127.0.0.1:8756`, `start_wapening`) la uneltele `pdfcontrol_*` ale LocalChat, marcheaza publicarea in proiectele ChatGPT ca neintegrata (`pending_cloud_transfer`) si lasa pasii de copiere/UNC/arhive in `run_command` sub controlul de acces. Fisierele skill-ului raman neatinse (locale, in afara git); promptul de baza si bugetul de tokeni nu se schimba. Test nou `SkillNotesTest` (30 verificari).
- Intretinere: a doua extragere mecanica din LocalChat.java: `ToolTranscript` (pachet-privat) contine fara nicio modificare `describeCall` si `preview` (textul liniei de unealta din transcript); apelurile `t(...)` au devenit `I18n.text(...)`, cheile raman aceleasi. Test nou `ToolTranscriptTest` (11 verificari).

# 1.28.4 — 2026-10-28
- Remediere: pe un PC de lucru, o variabila de utilizator `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` (mostenita de la Explorer, chiar si dupa `reg delete`) strica tot HTTPS-ul din LocalChat cu `NoSuchAlgorithmException` (SunJSSE DefaultSSLContext), pentru ca runtime-ul jlink inclus nu are modulul `jdk.crypto.mscapi`. `Porneste LocalChat.bat` foloseste acum `setlocal` si goleste `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` si `JDK_JAVA_OPTIONS` doar pentru procesul launcherului (nu modifica mediul utilizatorului), deci nu mai trebuie `set JAVA_TOOL_OPTIONS=` manual. Este singurul punct de pornire (nu exista relansare/updater in aplicatie; procesele copil mostenesc mediul deja curatat). Fara modificari de logica in aplicatie.
- Mesaj clar: daca SSLContext-ul implicit nu poate fi creat, eroarea afisata mentioneaza variabilele de mediu Java si launcherul in loc de `NoSuchAlgorithmException` brut (cheie noua in cele sase limbi; RO fara diacritice; `SslDiagnostics`).
- Test nou `LauncherEnvTest` (45 verificari): construieste un runtime jlink cu aceleasi module ca cel livrat, ruleaza launcherul real cu variabilele setate la valoarea gresita si cere SSLContext functional si variabile nesetate in procesul pornit; controlul (runtime direct) si launcherul 1.28.3 trebuie sa cada cu `NoSuchAlgorithmException`; fiecare variabila este testata si singura. Verificat si invers: cu launcherul vechi in locul celui nou, testul pica.
- Test: `SkillsCacheTest` ("a dropped zip is unpacked and listed") era intermitent (2 din 6 rulari): timpul de modificare al directorului are granularitate mare, iar zip-ul era creat in acelasi tick cu scanarea anterioara, deci cache-ul il considera proaspat. Pauza de 80 ms inainte de zip in test (25/25 rulari reusite); nicio modificare in `Skills.java`. Limita ramane in produs doar pentru un zip lasat in primele milisecunde dupa o scanare.
- Documentatie: sectiune de depanare in README (CITESTE.md) si constatarea de retea in DEVELOPMENT_STATUS.md.

# 1.28.3 — 2026-10-28
- Intretinere: prima extragere mecanica din LocalChat.java (237342 -> ~235 KB): `AppPaths` (pachet-privat) contine fara nicio modificare de logica `appDir()` (directorul aplicatiei / `localchat.home`), `resolveDownloads()` (folderul Downloads din registry) si `isWindows()`. Apelurile din LocalChat folosesc acum `AppPaths.*`. Fara texte vizibile mutate si fara acces prin reflexie din teste la aceste metode.
- Sinteza seriei 1.27.0-1.28.3 (masuratori, rezultate si lucrari ramase prioritizate) in DEVELOPMENT_STATUS.md, sectiunea "Sinteza seriei de imbunatatiri 1.27.0-1.28.5 si lucrari ramase".
- Verificare codificare: LocalChat.java este deja UTF-8 valid (fara BOM, LF, fara U+FFFD; la fel in HEAD~1), deci conversia planificata nu a fost necesara; nu s-a modificat niciun octet de text in afara extragerii.

# 1.28.2 — 2026-10-28
- Performanta: apelurile de unealta independente, numai citire (`read_file`, `list_dir`, `find_files`, `search_text` si uneltele MCP marcate readOnly, fara `pdfcontrol_start_*`/`regenerate_report`), dintr-un singur pas al modelului ruleaza in paralel (max 4, `ToolBatch`). Rezultatele, contoarele ToolLoopGuard si evenimentele din interfata raman in ordinea originala; o mutatie (sau orice apel neeligibil, `read_skill`, `run_agents`, duplicat) incheie lotul, deci nicio citire nu depaseste o scriere anterioara; mutatiile raman serializate sub MUTATION_LOCK. Intrebarile de permisiune sunt serializate, iar "ALWAYS" de la prima acopera citirile paralele.
- Performanta: descoperirea skill-urilor este memorata intre mesaje (`Skills.load`), validata prin timpul/marimea directoarelor si a fisierelor SKILL.md (fara reparcurgere). Comanda `/skills` si reincarcarea uneltelor/setarilor forteaza reinspectarea (`Skills.reload`).
- Performanta: imaginile atasate sunt trimise cu base64 doar in tura curenta (ultimul mesaj al utilizatorului, inclusiv bucla de unelte si compactarea). Mesajele anterioare cu imagini primesc un marcaj text scurt in loc de imagine (`[N image attachment(s) ... not resent ...]`); daca sunt necesare, atasati din nou. Ref.data() are un cache LRU mic (4) validat prin dimensiune si data. Masurat (4 ture, 4 imagini): corpul cererii 411775 -> 103576 octeti (-75%).
- Stabilitate teste: AgentIntegrationTest asteapta (limitat) lantul asincron parinte/copii/consolidare inainte de aceleasi verificari; proba Tesseract (`--list-langs`) reincearca o data cu 60 s dupa timeout-ul de 20 s (pornire la rece); testele native (ChatNavigationTest, ComposerShortcutsTest) pozitioneaza fereastra la 40,40, o aduc in fata si verifica ajungerea reala a pointerului / focusul editorului, fara a slabi verificarile.
- ComposerSplitTest: ferestrele fixe (1320x950/1050, 1100x650) sunt limitate la zona de lucru a ecranului (`GraphicsEnvironment.getMaximumWindowBounds`), deoarece Windows micsoreaza ferestrele mai mari decat ecranul (sesiune Remote Desktop 1152x713) si asertiunea "growing window restores preferred input height" nu putea fi satisfacuta. Aceeasi asertiune; fereastra mica se deriva proportional din cea mare, se verifica explicit ca editorul chiar se micsoreaza sub preferinta (altfel esec cu mesaj clar), iar o zona de lucru sub 900x560 esueaza cu mesaj in loc sa treaca tacit.
- Teste noi inregistrate in Build.ps1: ToolBatchTest, SkillsCacheTest, ImageResendTest. Fara text nou in interfata (I18n neschimbat). LocalChat.java neimpartit in aceasta versiune (plan in DEVELOPMENT_STATUS).

# 1.28.1 — 2026-10-28
- Skill-ul `productie-projecten` (11 fisiere, copia din LM Studio) este integrat in LocalChat ca dependenta LOCALA: sta in `packaging\skills\productie-projecten\` (ignorat de git, ca `pdfcontrol\`) si Build.ps1 il instaleaza in `C:\LocalChat\skills\productie-projecten\` prin robocopy existent (/XO: nu suprascrie fisiere mai noi din instalare). NIMIC din el nu este in repository: toate cele 11 fisiere contin date de productie/clienti sau cai personale (numere de proiect, cai de retea/UNC, identificatori de proiecte ChatGPT, catalogul ISAH, importuri cu cai reale). Textul skill-ului nu a fost modificat; copiile sunt identice octet cu octet cu sursa.
- Corectie in `Skills.parse`: descrierea YAML pe mai multe randuri (scalar simplu sau intre ghilimele, continuat pe randuri indentate, cum o scrie LM Studio) era taiata la primul rand; acum randurile indentate sunt unite si pentru scalari simpli. SkillProgressTest are 6 fixture-uri noi (scalar impartit, `>`, CRLF, ghilimele, un rand, `name` dupa descriere); fara corectie testul pica pe fixture-ul scalar impartit.
- Buget de tokeni: in prompt intra doar numele si descrierea (274 caractere); continutul se citeste la cerere cu `read_skill`/`read_file`, iar fisierele de referinta sunt doar listate cu calea.
- Neconcordante intre skill si uneltele 1.28.x (raportate, procedura nemodificata): vezi DEVELOPMENT_STATUS, sectiunea "Skill productie-projecten (1.28.1)".
- Fara schimbari la motor, punte sau unelte; restul lucrarilor de performanta planificate pentru 1.28.1 raman pentru o versiune ulterioara.

# 1.28.0 — 2026-10-28
- Unelte noi PDFCONTROL, toate numai citire (readOnlyHint, deci permise si in modul read-only si in agentii de analiza), din iesirea REALA a jobului, fara rerulare: `pdfcontrol_job_summary(project, job_id)` (stare, delivery_verified, cai raport/livrare, primele 5 artefacte, numaratori: desene, constatari, per status al desenului calculat de `mbs_tekeningstatus.samenvatting` original, per severitate, coduri frecvente, silent_checks; transfer_approved ramane false, manual_review_required true); `pdfcontrol_job_findings(project, job_id, offset, filter, max_chars)` (constatari paginate, filter = cuvinte AND fara majuscule, pagina limitata de max_chars 500-12000, implicit 6000, cu total/matched/next_offset); `pdfcontrol_list_projects(offset)` (foldere de sase cifre, cele mai noi intai, 50 pe pagina, cu numarul de joburi si fabrica DOAR daca e confirmata explicit in project-state.json, dupa aceeasi regula ca motorul); `pdfcontrol_catalogue_status(factory, project?, article?)` (catalogul activ al fabricii: inregistrari, coduri de import, conflicte, sha sursa si daca sursa mai corespunde; regulile FAST din fast-import-rules.json: disponibile, fara ambiguitate, gata; fabrica confirmata a proiectului; cautare optionala de articol prin `Catalogus.zoek` original, cu physical_equivalence_verified=false; nu confirma si nu deduce fabrica).
- Cand un job inca ruleaza, summary/findings trimit la pdfcontrol_job_status; fara rezultate, cu rezultate in afara proiectului sau JSON stricat nu se inventeaza constatari (total 0 si nota). Un worker mort este raportat ca failed si aici.
- `pdfcontrol_status` pentru model este redus: ready, bridge_version, checked_at_utc, projects_root (cerut de interfata), ocr {available, limbi cerute/lipsa}, workflows, reports si nota. Fara PID-uri, fisier trace/adapter, cale de pachet/python sau executabil. Diagnosticul complet ramane local in `pdfcontrol/runtime/octop_status_diagnostic.json` (scris la fiecare apel) si in `octop_mcp.py --self-test`.
- Erorile tacute ale motorului (`mbs_stille_fouten`, procesate in worker) sunt acum salvate in `<run>/stille_fouten.json` (max 2000), apar ca `silent_checks` si ca limitare `silent_checks`, si in constatari ca SILENT_<unde>; NU schimba starea jobului (done ramane done), ca sa nu rupa comportamentul existent.
- ToolLoopGuard trateaza noile unelte ca citiri: cinci apeluri identice opresc bucla, dar paginarea cu offset nou este progres.
- Lista de unelte a conectorului: 11 -> 15 unelte, ~6,8k -> ~8,8k caractere (+~2,0k). Justificare: patru unelte noi, toate numai citire; TokenBudgetTest: toate uneltele 32/16588 caractere (prag 33/16800, era 29/15900), setul implicit 23/12289 (prag 24/12400, era 20/11400), read-only 15/7578 (prag 16/7700, era 12/6500), cu toate uneltele 74,8% din baza (prag 76%, era 70%); overhead implicit 16908 sub pragul neschimbat de 17000; pragul testului din test_18 creste de la 7000 la 9000 si pragul skill-ului RuleCoverageTest de la 9000 la 9500 (sectiune noua ~1,3k minus ~0,7k eliminat din text redundant). Fiecare apel de rezumat inlocuieste citirea unui resultaten.json sau a unor PDF-uri de zeci de mii de caractere.
- Skill pdfcontrol-octop: sectiune compacta despre cand folosesti summary/findings in loc de fisiere mari, list_projects la descoperire, catalogue_status inainte de FAST/import, silent_checks raportate onest.
- Module ale motorului evaluate pentru expunere read-only: expuse catalogul de articole (in catalogue_status), fabrica confirmata a proiectului, statusul desenelor (in summary) si erorile tacute. Propuse, neexpuse: lista de import `mbs_importlijst` (date de productie), OCR de articole `mbs_ocr_artikelen` (costisitor, scrie cache), baza de cunoastere `kennisbank.json` (date invatate private) si `mbs_productie` (joburi conduse de Codex). Detalii in DEVELOPMENT_STATUS.md.
- Skill-ul `productie-projecten` (citat in README) NU este in repository si nu este distribuit de build; exista ca director cu 11 fisiere in `C:\Users\Local User\Downloads\productie-projecten` si `C:\Users\Local User\.lmstudio\skills\productie-projecten`. Nu este in `C:\LocalChat\skills` (acolo exista numai pdfcontrol-octop). Continutul nu a fost citit sau comparat; sursa de adevar o stabileste Dumitru.
- Teste: integration/test_pdfcontrol_bridge.py are 24 de teste (6 noi: status fara PID/cai si diagnostic local, rezumat+constatari pe un job real si paginare, plafon/filtru/erori/confinare, argumente respinse si numai citire, paginarea proiectelor, catalog/FAST pe fabrica); PdfAgentTest (sumar+constatari in mod read-only prin conectorul real, profile read-only/analiza, status fara PID); WorkProgressTest (bucla si paginare); RuleCoverageTest si TokenBudgetTest verzi.
- Nu exista text nou in interfata.
# 1.27.3 — 2026-10-28
- Indicator vizibil de gandire: deltele reasoning_content (si inline <think>) din streaming ajung acum in UI; bara de stare arata "Modelul gandeste: ~N tokeni de ratiocinare, S s. Raspunsul vizibil urmeaza." cu actualizare limitata la ~4 pe secunda, apoi revine la "Se genereaza raspunsul." cand incepe textul vizibil. Gandirea ramane request-local: nu apare ca raspuns, nu se salveaza in arhiva si nu se retrimite (verificat in test). Streamingul ramane implicit (bifat), deci octetii curg si in fazele lungi de gandire (pe serverul real prima delta de gandire apare in ~0,5-2 s, iar raspunsul dupa ~50-80 s), fara risc de HTTP 524 la ~100 s de tacere; daca totusi apare 524, eroarea primeste un sfat clar.
- Control de gandire verificat pe serverul real (LM Studio, qwen3.6-35b-a3b, https://api.xorvapi.dpdns.org/v1, mereu stream:true): numai reasoning_effort="none" opreste gandirea (0 tokeni de ratiocinare, raspuns in ~1 s, in loc de ~50-80 s). chat_template_kwargs.enable_thinking, enable_thinking de nivel superior, reasoning {effort}, thinking {type}, /no_think, minimal/low/high si bugetele NU schimba nimic (HTTP 200 dar ignorate), deci nu sunt oferite. Setari rapide, meniul "Gandire": Automata (nu trimite nimic, ca pana acum) sau Oprita (reasoning_effort=none), numai pentru API custom compatibil OpenAI si numai cand nu e ocupat. Nu exista mod "buget" pentru ca nu a fost gasit niciun parametru de buget acceptat real.
- Daca un server raspunde 400/422 la o cerere care continea parametrul, cererea se reia automat o singura data fara el (ca la stream_options); respingerea se tine minte per provider/model (setarea thinking.rejected) numai dupa ce reluarea reuseste, iar o eroare 400 care persista fara parametru nu este pusa pe seama lui.
- Limite de iesire adaptive: profilul de raspuns primeste o rezerva de ~1,25 x gandirea observata (medie netezita pe profil/model, plafon 8192 si niciodata peste limita API sau fereastra de context) adaugata numai la max_tokens al cererii, nu la pragurile de compactare; cu gandirea oprita rezerva este 0. La finish_reason=length fara niciun raspuns vizibil dupa gandire, mesajul explica clar (~N tokeni de gandire, nimic executat, mareste profilul sau opreste gandirea); orice raspuns trunchiat tot nu executa niciodata apeluri de unelte. Reincercarile raman numai la 502/503/504, maximum 3 (524 nu se reincearca).
- Masurat pe serverul real (secunde, tokeni din usage): fara parametru ~50-80 s si ~1,6k-2,7k tokeni de gandire pentru un raspuns de 2 caractere; cu reasoning_effort=none 0,7-1,9 s si 0 tokeni (3 rulari), inclusiv 17*23+49=440 corect in 1,4 s, apel de unealta reusit in 4,8 s, 2 propozitii cu max_tokens=300 in 2,7 s; fara parametru acelasi max_tokens=300 se termina cu finish_reason=length, 300 tokeni de gandire si 0 caractere de raspuns.
- Text nou in cele sase limbi. Test nou: ReasoningTruncationTest (62 de verificari cu server SSE fals: flux de gandire, nimic retrimis, trunchiere in toate limbile, 400 pe parametru necunoscut cu revenire si memorare, parametru trimis numai pentru custom+oprit, rezerva si limitele, reincercari 502/503/504). Rulare Build: ComposerShortcutsTest (tastatura reala) nu poate rula cat timp sesiunea Windows este blocata, deci build-ul a rulat cu -SkipNativeQA.# 1.27.2 — 2026-10-28
- Context sub control: rezultatele VECHI ale uneltelor sunt reduse deterministic numai in vizualizarea cererii (arhiva, exportul si ecranul raman intregi). Reducerea se aplica la inceputul turului, deci prefixul ramane stabil in tot turul (cache KV). Ultimele 6 rezultate raman intacte, intrebarea exacta a utilizatorului nu se atinge, iar numarul de mesaje si perechile apel/rezultat raman neschimbate. Un rezultat redus (sub ~75% din original, peste 1500 caractere) pastreaza uneltea, argumentele, identificatorii, caile, status, delivery_verified, job_id, erorile si indicii de continuare, plus mesajul de reapelare cu offset/limit. Setarea context.reduceOld (meniul Unelte, implicit pornita) opreste reducerea.
- Plafon implicit mai mic cu paginare in loc de 60000 de caractere: 16000 la rezultatul unei unelte locale (24000 la MCP), cu capat si coada si cu mesaj explicit "continua cu offset/limit". list_dir, find_files si search_text au parametrul offset si afiseaza "continua cu offset=N" (150 intrari, respectiv 100 potriviri pe pagina); read_file pastreaza offset/limit; citirea PDF se opreste la limita paginii cu offset spre pagina urmatoare. pdfcontrol_bridge: project_files 100 pe pagina, read_document 12000 caractere pe pagina.
- Compactarea automata este pornita implicit numai pentru chaturile NOI (default.autoCompact lipsa = pornit). Alegerea explicita a unui chat existent si valoarea salvata explicit in setari (inclusiv oprit) se pastreaza; pragul ramane cel ales (fara plafon de timp). Cand compactarea automata e oprita, la ~80% din limita apare un avertisment o singura data (/autocompact on sau /compact). Regulile 1.3.0, 1.10.0 si 1.25.x raman valabile.
- Ancore si puncte de control: reducerea intra in semnatura cererii (cheia "reduced:N"), deci orice schimbare a reducerii invalideaza ancora si checkpoint-ul de compactare; fara reducere semnatura este identica cu cea din 1.27.1, deci ancorele existente raman valide.
- Masurat pe o bucla sintetica de unelte (rezultate de 4k-70k caractere, tokeni estimati ~): 40 de rezultate 297427 -> 28635 (~-90%); 20 de rezultate 148743 -> 23727; 10 rezultate 74401 -> 21285. Numai reducerea: 60748; numai plafonul de 16000: 128797 (40 de rezultate).
- Text nou in cele sase limbi. Teste noi: ContextReductionTest (bucla lunga, arhiva intacta, ancore invalidate, paginare, implicit compactare); FeatureTest si ContextGuardTest adaptate la noul implicit.
# 1.27.1 — 2026-10-28
- Overhead MBS redus fara a pierde reguli: promptul de sistem MBS are ~4,8k caractere (inainte ~9k), iar regulile PDFCONTROL stau doar in skill-ul pdfcontrol si in descrierile esentiale ale uneltelor, nu de trei ori. Cererea MBS de proba (primul tur, doar citire) scade de la 28348 la 10891 caractere (~-62%); cu toate uneltele trimise, de la 28348 la 19810 (~-30%). Valorile sunt masurate local pe caractere; tokenii sunt estimari (~).
- Descrieri si scheme de unelte mai scurte (localchat_interaction ~0,85k, inainte ~1,1k). Prefixul "[MCP server]" nu se mai adauga cand numele uneltei incepe deja cu numele serverului. Aliasul duplicat pdfcontrol_live_status nu mai apare in lista trimisa modelului, dar apelurile arhivate sunt acceptate in continuare (puntea si Tools.resolve il mapeaza la pdfcontrol_status). Textul job_status nu mai contrazice asteptarea automata: uneltele start_* asteapta singure starea finala, deci nu mai exista instructiune de polling.
- Unelte dupa relevanta: in MBS, uneltele care modifica fisiere (write_file, append_file, edit_file, make_dir, copy_path, move_path, extract_attachments, save_rule, pdfcontrol_prepare_fast_import) si run_command apar numai cand cererea sau istoricul le cere (cuvinte-cheie in cele sase limbi, apeluri anterioare sau localchat_enable_tools). Selectia este fara stare, creste doar in cadrul conversatiei si nu schimba prefixul de la tur la tur. Modul doar citire ramane doar citire (nu poate fi deblocat), agentii copil nu primesc unealta de deblocare, iar permisiunile raman aceleasi. Meniul Unelte are optiunea "Trimite toate uneltele (fara selectie dupa relevanta)" (cheia tools.all) care trimite lista completa ca inainte.
- Ancorele (1.10.0) se compara acum cu lista reala de unelte a agentului, inclusiv grupul de agenti si cardurile interactive.
- Text nou in cele sase limbi. Teste noi: ToolRelevanceTest (38 verificari), RuleCoverageTest (80 verificari: fiecare regula din AGENTS.md ramane in prompt sau in skill) si TokenBudgetTest (praguri pentru overhead MBS si Normal); PdfAgentTest si pdfcontrol_bridge extinse (alias arhivat, fara prefix).
# 1.27.0 — 2026-10-28
- Prefix stabil pentru cache-ul KV al modelului local: promptul de sistem este identic de la un tur la altul. Nu mai contine indexul de progres sau memoriile, iar mesajul de control care urma dupa fiecare ultim mesaj de utilizator a fost eliminat. Datele volatile (indexul de progres si memoriile confirmate) merg intr-un singur bloc "LocalChat session data" dupa ultimul mesaj al utilizatorului, numai in MBS si numai cand exista continut. Blocul este doar pe retea, nu intra in arhiva/export si este inclus in semnatura ancorelor.
- Politica limbii de raspuns apare o singura data, compact, la finalul promptului de sistem (inainte aparea de trei ori: cap, coada si control). Limba fixa isi pastreaza prioritatea, iar limba interfetei nu ajunge niciodata in cerere.
- Raspunsurile de tip reasoning: se citeste reasoning_content (SSE si raspuns complet; si campul reasoning si thinking_delta Anthropic). Continutul nu se salveaza si nu se retrimite in istoric. reasoning_tokens din usage se aduna in registrul de consum si apar in linia de utilizare ca Gandire.
- Fereastra de context arata patru componente cu valori estimate marcate ~: prompt de sistem, definitii unelte, conversatie si rezultate unelte. Procentul si bugetul includ rezultatele uneltelor.
- Text nou in cele sase limbi (Rezultate unelte, Gandire). Teste noi: StablePrefixTest (35 verificari) plus InternationalChatTest adaptat.
# 1.26.6 — 2026-10-26
- Extragere Tekla/ABWN: foloseste marca unica din titlul plansei (BA/GA/DP/TR/BD), fara confundarea cu pozitiile de accesorii. Titlurile ambigue raman neconfirmate.
- Plansele fara numar MBS pot prelua numarul explicit, unic, din coperta aceleiasi brosuri. Nu foloseste numarul biroului, nu suprascrie un proiect propriu valid si nu inventeaza armari W.

# 1.26.5 — 2026-10-26
- La raspunsuri scurte, proiectul confirmat are prioritate fata de un folder numerotat mostenit de la alt chat. Corecteaza folderul local numai spre fratele deja existent al proiectului curent; nu creeaza directoare si nu ghiceste radacini.
- Fabrica deja confirmata este reutilizata pentru acelasi proiect fara verificari repetate de disponibilitate si fara intrebari noi dupa compactare/comparatie/reluare. Schimbarea explicita a destinatiei sau eliminarea locatiei din configuratie necesita reverificare.
- Cardurile de fabrica ramase de la alt proiect nu mai intercepteaza raspunsurile conversatiei curente. Alegerea confirmata, istoricul si ciorna sunt pastrate.
- Raspunsurile libere nerecunoscute la alegerea fabricii raman in card, cu explicatie vizibila; rezultatele verificarilor asincrone apartin numai conversatiei care le-a pornit.

# 1.26.4 — 2026-10-24
- Panoul agentilor pastreaza numai grupul curent. Un grup nou inlocuieste cardurile vechi, inclusiv cele oprite sau fara rezultat. Chatul si arhivele private raman integrale; reluarea reconstruieste ultimul grup.

# 1.26.3 — 2026-10-24
- HTTP 429 explica ocuparea furnizorului si ofera reincearcarea sau alt model. Cuvantul upstream nu mai este tratat ca o eroare de streaming.
- Sfatul de debifare Streaming ramane pentru erorile de transport SSE.

# 1.26.2 — 2026-10-24
- Manualul de utilizare descrie limitele curente ale agentilor: 3 sarcini, 2 simultane, 16 pasi, iesire dupa profilul selectat si durata cererilor catre furnizor. Termenul comun de 180 de secunde si plafonul fix de 4096 pentru copil raman doar in istoricul versiunilor in care au existat.
- Sectiunile de compactare din manual trimit la politica 1.25.2. Comportamentul aplicatiei ramane neschimbat.

# 1.26.1 — 2026-10-24
- Dashboard si popup de context: actualizari neschimbate fara rescrierea repetata a documentelor Swing; actualizare periodica la o secunda si iconite stabile. Nu modifica procentajele sau contextul selectat.
- Registru privat de provenienta pentru rezultatele uneltelor: hotfolderul nu considera din nou surse copiile/scrierile/extragerile/FAST identificate; schimbarea ulterioara a rezultatului de catre utilizator ramane detectabila. Shell: numai destinatii literale identificabile, fara excluderea folderelor de surse.
- Evenimentele hotfolder recunoscute ale aceluiasi folder sunt grupate intr-un singur bloc de ciorna, fara stergerea textului liber sau a istoricului.
- Eliminata limita comuna de 180 secunde a grupurilor de agenti. Stop, limitele de pasi, izolarea si protectiile conexiunii raman; rezultatele finalizate se pastreaza la oprirea altor sarcini.
- Agenti: stari vizibile Asteapta API / Compactare context, profil de raspuns respectat fara plafonul suplimentar 2K/4K. Un raspuns trunchiat nu executa apelurile din acel raspuns si nu confirma un final complet.
- Indexul local de reluare retine explicit starea fiecarui agent, erorile si arhivele; proza lunga a sarcinilor nu mai ascunde rezultatele copiilor in primele caractere.
- DEVELOPMENT_STATUS.md documenteaza dezvoltarea, problemele intalnite, corectiile si limitarile ramase. Testele fizice si furnizorii reali sunt distincti de verificarile automate locale.

# 1.26.0 — 2026-10-23

- Imaginile PNG/JPEG/GIF statice atasate sunt trimise ca pixeli catre modelele vizuale prin protocolul OpenAI compatibil sau Anthropic, inclusiv in Normal. Copii locale cu hash pastreaza imaginea intre apelurile de unelte si la reluare; arhiva nu include base64. Fara enumerarea imaginilor din foldere. Maximum 4 imagini/mesaj, 5 MiB/imagine si 16 megapixeli. Metadatele explicit negative blocheaza trimiterea; suportul necunoscut este incercat fara a inventa capabilitati.
- Unealta pdfcontrol_prepare_fast_import si Unelte > Pregateste import FAST: conversie determinista locala, fara model/API/compactare, dupa reguli private confirmate per fabrica/articol.
- Calculeaza din dimensiunile PIR originale, converteste suprafata pe bucata in reprezentarea Lengte FAST (x1000) si aplica regula confirmata: fractie strict peste .5 in sus, .5 si sub in jos. Aantal si descrierile originale se pastreaza; numai Lengte se schimba.
- Produce XLSX separat si audit JSON cu sursa, hashuri, valori exacte/rotunjite si diferente. Nu suprascrie sursa sau fisiere existente si nu copiaza automat pe server. Reguli lipsa/dimensiuni ambigue sunt erori, nu conversii ghicite.
- Disponibila modelelor MBS compatibile; blocata pentru doar-citire si agenti de analiza. Acces direct din meniu fara schimbarea chatului, memoriei sau ciornei. Texte in sase limbi, teme Dark/Light.

# 1.25.2 — 2026-10-23
- Eliminat plafonul total artificial de timp al compactarii si limita mica de generare calculata din lungimea rezumatului. Pentru API compatibil OpenAI, capacitatea de generare ramane la valoarea serverului/modelului. Anthropic pastreaza parametrul obligatoriu al protocolului. Rezumatul final trebuie sa incapa in context.
- finish_reason=length, stop_reason=max_tokens si alte finalizari nevalide nu mai sunt acceptate ca memorie. Nu se scurteaza/retrimite un rezultat trunchiat ca si cum ar fi complet; arhiva si ciorna raman intacte.
- Checkpointuri complete-v2: invalidarea cheilor si schemei vechi fara stergerea arhivei. Thinking separat sau blocuri think deschise nu devin memorie finala.
- Ramane protectia Stop si timeoutul de inactivitate al conexiunii (10 minute), distinct de un plafon total. Fara modificarea procentajului/contextului ales sau a setarilor utilizatorului.

# 1.25.1 — 2026-10-23
- Compactarea solicita raspuns in streaming, consumand progresul API fara sa publice rezumatul partial sau sa execute unelte. Rutele care raspund JSON raman acceptate.
- Bugetul total de compactare se calculeaza dupa numarul fragmentelor: minimum 5 minute, 2 minute/fragment, maximum 15 minute; limita de inactivitate de 120 secunde ramane. Bugetul nu se reseteaza la retry. Generarea normala nu este modificata.
- Timeout si Stop pastreaza arhiva, memoria si ciorna; nu se reia automat inferenta dupa timeout. Furnizorul poate continua sa expire; nu exista garantie de finalizare.

# 1.25.0 — 2026-10-23



- Verificari independente MBS delegate automat de aplicatie, fara un apel suplimentar pentru planificare si fara lista fixa de modele. Selectia separa surse/istoric, import/catalog si dovezi PDF; simpla lungime a mesajului nu declanseaza agenti. Cel mult trei sarcini, doua simultane, cu modelul/furnizorul ales, rezultate si consum reale in panoul agentilor.
- Agentii automati nu modifica proiecte, nu ruleaza shell si nu pornesc controale noi: inspecteaza dovezi si pot genera rapoarte locale private de comparatie. Coordonatorul pastreaza operatiile de productie si permisiunile. Optiune Unelte pentru dezactivare; o cerere explicita fara agenti are prioritate. Normal ramane fara unelte sau agenti MBS.
- Propunerile sunt in aceeasi zona derulabila cu discutia, cu text mic si colturi rotunjite. Sugestiile optionale sunt legaturi text care editeaza ciorna, fara trimitere sau selectii predefinite. Editorul ramane utilizabil. Nu mai apar formulare deduse automat din proza istorica a modelului.
- Deciziile necesare folosesc optiuni text si raspuns liber, fara checkbox/radio vizibil. Confirmarile reale de unelte apar punctual in chat; inchiderea/Stop refuza, iar alternativa libera este pastrata ca draft si nu reprezinta aprobare de executie.
- Fabrica confirmata se pastreaza independent de compactare, inclusiv cand cererea compara doua fabrici sau reteaua este temporar indisponibila. O schimbare explicita ramane posibila; o locatie disponibila dar disparuta necesita reverificare.
- Include corectia 1.24.2 pentru fragmentarea si checkpointurile private ale compactarii. Texte noi in sase limbi si teste offline de flux complet, permisiuni, teme si persistenta.

# 1.24.2 — 2026-10-23

- Compactarea automata foloseste aceleasi unitati pentru buget si fragmentare; un istoric de aproximativ 205 KB la context 131072 necesita trei fragmente in loc de noua, cu verificarea spatiului pentru memorie, raspuns si marja de siguranta.
- Fragmentele rezumate cu succes sunt salvate privat per conversatie/profil, furnizor, cheie, model, instructiuni si continut exact. O reluare explicita reutilizeaza numai rezultate identice; nu retrimite fragmentele deja rezumate si nu inventeaza consum API.
- Compactarea manuala si automata pastreaza checkpointurile maximum 30 de zile, cel mult 128 per director. Rezumatul incomplet nu devine memorie activa; Stop/eroare pastreaza ciorna si arhiva si nu trimit automat. Transportul normal si pragul configurat raman neschimbate.
- Elimina impartirea dubla a bugetului rezumatului automat prin factorul de calibrare. Nu garanteaza disponibilitatea furnizorului si nu elimina timeouturile externe.
- Indicator de recuperare locala in sase limbi; teste cu API simulat pentru esec, reluare, izolare, invalidare si Stop.

# 1.24.1 — 2026-10-23

- Unelte > Cataloage fabrici: selectia explicita a fabricii si listei XLSX, previzualizarea articolelor noi/modificate/absente si Importa si activeaza. Primul import functioneaza si pentru Lelystad fara catalog anterior.
- Afiseaza data, numarul de articole si sursa catalogului activ; sursa noua si snapshotul precedent se arhiveaza local. Fara API si fara modificarea listelor de productie.
- Fabrica necesita confirmare; codurile absente necesita confirmarea listei complete. Fisierul sau catalogul schimbat dupa previzualizare cere o noua comparatie. Importurile concurente sunt serializate; eroarea la registru restaureaza datele precedente.
- Buildurile pastreaza cataloagele, registrul si arhivele importate de utilizator. La instalari noi se initializeaza numai cataloagele lipsa din pachet, fara fabrici substituite.
- Texte in toate cele sase limbi, teme Dark/Light si blocarea activarii in modul doar citire.

# 1.24.0 — 2026-10-23

- Instrument local `pdfcontrol_compare_import`: liste XLSX/CSV, catalog explicit al unei singure fabrici si tabele de articole din PDF-urile selectate. Citire locala fara inferenta, surse pastrate, rapoarte JSON/HTML cu randuri, pagini si hashuri.
- Rezumat scurt in chat si cache invalidat de schimbarea surselor/motorului. Potrivirile fizice, scanarile nerecunoscute, unitatile lipsa si cotele nelegate sigur raman de verificat, fara corecturi sau aprobari inventate.
- Unelte > Compara local listele de import permite executia directa fara model si fara compactarea chatului. Selectie explicita de proiect/fabrica/fisiere, permisiuni si Stop, texte in sase limbi si ambele teme.
- Compactarea are o limita separata de asteptare la citire de 120 s si buget comun de 300 s verificat intre fragmente/reincercari si la citirea raspunsului. Timeout nu retrimite automat si nu modifica memoria/istoricul. Generarea normala isi pastreaza limita existenta. Nu elimina erorile 504 ale furnizorului.

# 1.23.6 — 2026-10-22

- Recuperarea din bucle permite cel mult sase pasi suplimentari de unelte, apoi cere un rezultat partial fara unelte. Nu reporneste actiunile confirmate.
- Daca modelul raspunde gol sau continua sa ceara unelte dupa fallback, LocalChat afiseaza si salveaza un diagnostic propriu, cu starea incompleta si ultimul apel repetat.
- Statusuri explicite de recuperare si rezultat partial, cu suspendarea cozii. Un raspuns gol dupa recuperare nu mai este afisat ca Gata. Texte in toate cele sase limbi.

# 1.23.5 — 2026-10-22

- Hotfolderul reutilizeaza conversatia proiectului dupa folderul local exact, inclusiv conversatii procesate, deschise sau arhivate; nu asociaza fabrici diferite numai dupa numarul proiectului.
- Sursele noi marcheaza conversatia existenta Repornit si necitita, pastrand istoricul, ciorna, modelul si memoria. In ferestrele deschise pregatirea se pastreaza separat pana la accesare, fara intreruperea lucrului sau trimitere automata.
- Monitorizarea functioneaza si cand Folder de lucru este chiar folderul numeric al proiectului.
- Fisierele MSG/EML pregatesc mesajul «Am adaugat un email nou…» sau «Am adaugat emailuri noi…», pe baza extensiei, fara a citi continutul emailurilor.

# 1.23.4 — 2026-10-22

- Citirile repetitive declanseaza o singura recuperare ghidata, apoi constatari si blocaj in text fara unelte daca modelul persista. Nu repeta operatiile confirmate; pastreaza arhiva si Stop.
- Navigarea cu mouse-ul asteapta terminarea secventei de clicuri si deschide chatul o singura data. Tastatura si navigarea programatica raman directe.
- Lista filtrata goala explica pastrarea conversatiilor si ofera resetarea filtrelor. Optiunea Arhivate explica faptul ca afiseaza exclusiv arhiva, in toate cele sase limbi.

# 1.23.3 — 2026-10-22

- Alegerea explicita a fabricii ramane prioritara dupa reverificarea locatiilor, inclusiv cand aceeasi ciorna compara doua fabrici. Schimbarea ciornei sau contextului cere o noua verificare.
- Carduri de clarificare compacte, limitate la 720 px, aliniate cu editorul, cu fundal unitar si colturi rotunjite in ambele teme.
- Numerele si selectorii sunt aliniati la primul rand al optiunii; numerotarea deja inclusa in text nu se dubleaza vizual. Valorile originale trimise raman intacte.
- Optiunile lungi se infasoara dupa latimea disponibila, iar continutul extins se deruleaza intr-o zona compacta. Ciorna, selectiile si raspunsul liber raman separate.

# 1.23.2 — 2026-10-22

- Un card interactiv invalid nu mai opreste imediat tura: o singura reparare structurata, apoi raspuns in text daca repararea esueaza.
- Recuperarea nu executa operatii, nu repeta actiuni confirmate si nu transforma propunerile in aprobari. Apelurile alaturate unui card primesc rezultate explicite de neexecutare inaintea cererii urmatoare.
- Originalele si parametrii respinsi raman in arhiva; nu sunt executate fragmente JSON/XML deteriorate. Stop ramane prioritar.

# 1.23.1 — 2026-10-22

- Butonul + deschide un formular independent pentru nume, protocol, URL si cheie, fara mostenirea configuratiei furnizorului curent.
- Modelele si selectia memorata pastreaza provenienta endpointului. Schimbarea URL-ului nu promoveaza lista veche, iar un catalog incarcat cu succes inlocuieste selectia care nu mai exista.
- Cand niciun model nu are suport MBS declarat, catalogul permite inspectarea tuturor si selectorul pastreaza modelele de chat neconfirmate cu avertisment. Modelele explicit incompatibile/specializate nu devin compatibile automat.
- Autentificarea xAI foloseste Bearer inclusiv pentru formatul Anthropic compatibil. Cheile Grok/Cursor Agents introduse pe api.x.ai sunt diagnosticate local, fara transmitere; erorile de catalog nu reproduc cheia din raspunsul serverului.
- Formulare, explicatii si diagnostice in cele sase limbi, cu teme Light/Dark. Cererile de modele ramase in fundal nu suprascriu statusul altui furnizor.

# 1.23.0 — 2026-10-22
- Consultarea altui chat in timpul lucrului deschide o fereastra independenta; Chat nou permite o a doua sesiune fara oprirea primeia. Marcajul colorat si bold indica executiile live in listele tuturor ferestrelor.
- Istoricul, ciorna, coada, uneltele si consumul raman separate. Un chat deja deschis este focalizat, nu deschis pentru scriere de doua ori; acelasi proiect MBS asteapta terminarea executiei existente.
- Alternative din furnizorii/modelele salvate, filtrate dupa profil si dovezi locale, inclusiv dupa HTTP 429. Alegerea nu trimite mesaje si nu repeta operatii; cost zero apare numai cand ambele preturi sunt explicit zero in catalog.
- Metadate de cereri simultane declarate pentru fiecare model; LM Studio citeste GET /api/v1/models si loaded_instances.config.parallel, fara schimbarea parametrilor de incarcare. Lipsa informatiilor ramane necunoscuta.
- Folderul de lucru salvat recent are prioritate fata de vechiul default.workdir. Enter/iesirea din camp si alegerea folderului persista preferinta; salvarile ferestrelor se combina fara restaurarea unor setari vechi sau a furnizorilor stersi.
- Memoria locala serializeaza scrierile intre ferestre. Aspectul global ramane comun, iar limba raspunsurilor ramane per chat.

# 1.22.0 — 2026-10-22

- File drops work over chat viewports and dynamically created cards as well as the composer. Attachments, screenshots and text can be prepared while the model is busy; references remain separate from the active request. Explorer drops negotiate COPY, never move the source.
- Explicit sends while busy create a per-chat FIFO queue (maximum 10 messages), retaining text and up to 20 attachment references per message. Successful replies drain the queue. Stop, errors, clarification cards, an unsent draft or changed model/profile/folder pause it. Restart/loading never sends; the queue menu supports resume, edit and removal.
- Notification bell moved to the main window's top-right. Optional hotfolder monitoring follows the selected Working folder instead of a separately fixed path, remains metadata-only and can notify while the model is busy. First scan of a newly selected root establishes its baseline; stable later changes create unread drafts only.
- Optional monthly factory-catalogue notification lives inside LocalChat, enabled in Tools. First MBS use in a new month creates one unread draft without inference or automatic download. No Codex automation is required.
- PDFCONTROL factory lookup and isolated jobs now support all four known factories; a missing catalogue still remains unavailable. Build deployment includes private factory snapshots. Local catalogue maintenance previews new/changed/absent article codes, preserves XLSX/JSON archives and requires explicit review before accepting absent codes.
- Bounded native-tool protocol repair for malformed provider XML text (two retries, no markup execution or implicit re-execution). Multiline Python inside cmd.exe inline quotes returns actionable .py script guidance instead of executing broken shell fragments.
- Six-language UI, portable-Java and real-engine regression tests; private catalogues, source workbooks and skill copies remain excluded from Git.

# 1.21.0 — 2026-10-22

- Interactive composer cards support independent action checkboxes, exclusive choices and a free-text alternative. No defaults; multi-selection and qualifiers persist per conversation. Submitted answers distinguish selected and unselected actions.
- Optional next-step proposals are selectable cards with an explicit Send button and free response, grounded by model instructions in confirmed progress, loaded skill procedures and permitted rules/memories. The separate composer remains editable; selection never inserts or sends text automatically. Existing optional Tab acceptance remains undoable.
- Compatibility fallback presents explicit unanswered prose questions, literal alternatives and pending numbered action lists as cards without another inference. Explicit next-step headings can become optional proposals. Quoted/code examples and ordinary completed lists are excluded; raw history is preserved.
- Wrapped option labels, six-language UI and Dark/Light regression coverage. No change to PDFCONTROL or provider credentials. Normal remains free of MBS tool injection.

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
