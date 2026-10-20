# Changelog

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
