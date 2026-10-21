# LocalChat — chat cu ChatGPT, Claude sau un API propriu, cu acces la fișiere și PDFCONTROL

Aplicație desktop portabilă (Java). Nu se instalează nimic: folderul `LocalChat` conține și Java
(`runtime\`) și PDFCONTROL cu Python-ul și OCR-ul lui (`pdfcontrol\`). Îl poți copia oriunde,
inclusiv pe un stick (~250 MB).

## Pornire

Dublu-click pe **`Porneste LocalChat.bat`** (în `C:\LocalChat`).

- **Enter** = trimite, **Shift+Enter** = rând nou
- **Stop** oprește imediat (și comenzile pornite); ce s-a făcut până atunci rămâne în conversație
- **Conversație nouă**, iar meniul **Conversații > Istoric** redeschide o conversație veche, ca s-o continui
- **Deschide Downloads** deschide folderul Downloads al utilizatorului de pe PC-ul curent

## Furnizori

| Furnizor | Ce pui în aplicație |
|---|---|
| ChatGPT (OpenAI) | cheie de la https://platform.openai.com/api-keys |
| Claude (Anthropic) | cheie de la https://console.anthropic.com/settings/keys |
| API custom (compatibil OpenAI) | URL-ul serverului + cheia, dacă e nevoie (Ollama `http://localhost:11434/v1`, LM Studio `http://localhost:1234/v1`, OpenRouter `https://openrouter.ai/api/v1`) |

Abonamentele ChatGPT Plus / Claude Pro **nu** includ acces API; cheile API se plătesc separat, după consum.
Cu tool-uri, un singur mesaj poate face multe cereri la model (fiecare pas = o cerere), deci costă mai mult.

## Folder de lucru și tool-uri

Bifează **Tool-uri** și alege **Folderul de lucru** (de ex. `Downloads\PRODUCTIE`). Modelul primește unelte și le
folosește singur, indiferent de furnizor:

| Tool | Ce face |
|---|---|
| `list_dir`, `find_files`, `search_text` | listează foldere, caută fișiere (`*.pdf`, `507*/**/*.eml`), caută text în fișiere |
| `read_file` | citește text, `.eml`, CSV, JSON, **PDF** (stratul de text), **.docx**, **.xlsx** |
| `write_file`, `append_file`, `edit_file`, `make_dir` | creează și modifică fișiere text |
| `copy_path`, `move_path` | copiază sau mută fișiere și foldere (și pe server / unități de rețea) |
| `run_command` | rulează o comandă Windows (cmd) și vede rezultatul |
| `read_skill`, `save_rule` | încarcă un skill; salvează o regulă permanentă |
| `pdfcontrol_*` (MCP) | inventar proiect, citire documente, verificare PDF cu OCR, status job |

**Permisiuni:** citirea în folderul de lucru e liberă. Scrierea, copierea, mutarea, comenzile, citirea în afara
folderului de lucru și verificările PDFCONTROL cer confirmare; în fereastra de confirmare poți alege
„Permite tot de acest tip în conversație”. Varianta **Permite tot, fără întrebări** nu mai întreabă nimic.
Tool-urile nu pot șterge fișiere și nu pot citi `setari.properties` (unde sunt cheile).

Exemplu:

> Am pus un proiect nou: 507123, în Downloads\PRODUCTIE. Folosește skillul productie-projecten, citește
> regulile salvate și procesează-l complet de la zero. Actualizează PROJECT_INFO.md și Pentru_ChatGPT_507123.txt.
> Întreabă-mă punctual dacă lipsește Hoofdregel.

Modelul trebuie să suporte tool calling (GPT-4.1/5, Claude, Qwen 2.5+/3, Llama 3.1+ etc.). Dacă un model local nu
suportă tool-uri, debifează **Tool-uri**.

## Skill-uri, reguli, istoric

Toate stau lângă `LocalChat.jar`, deci merg cu folderul pe stick:

- `skills\<nume>\SKILL.md` — instrucțiuni pentru un tip de lucru (același format ca skill-urile Claude). Un `.zip`
  de skill pus în `skills\` e despachetat automat. Incluse: `productie-projecten` și `pdfcontrol-octop`.
- `reguli.md` — regulile salvate (de tine sau de model cu `save_rule`); se aplică în fiecare conversație.
  Meniul **Unelte > Reguli salvate** le deschide în Notepad.
- `conversatii\` — fiecare conversație, salvată automat (JSON), cu tot ce au făcut tool-urile.
- `unelte.json` — serverele MCP (format ca la Claude Desktop) și variabilele pentru comenzi. După modificare:
  **Unelte > Repornește serverele MCP**. În căi poți folosi `${app}` (folderul aplicației), `${downloads}`, `${home}`.
- `setari.properties` — setările și **cheile API, ca text simplu**. Nu da fișierul altcuiva.

Conversațiile pot conține texte din fișierele tale (emailuri, documente): ține cont de asta când dai folderul mai departe.

## PDFCONTROL

`pdfcontrol\` e o copie a proiectului, cu Python 3.13 portabil (PyMuPDF) și Tesseract OCR. Proiectele sunt căutate
în `Downloads\PRODUCTIE\<număr>` (se schimbă în `unelte.json`). Comenzile modelului au la dispoziție
`%PDFCONTROL_DIR%`, `%PDFCONTROL_PYTHON%` și `%PDFCONTROL_PRODUCTIE_ROOT%`, de exemplu:

    "%PDFCONTROL_PYTHON%" "%PDFCONTROL_DIR%\mbs_productie_pdf.py" 507123

Ce nu se poate din aplicație: crearea chatului în proiectul ChatGPT al fabricii (ChatGPT nu are API pentru asta;
textul rămâne în `Pentru_ChatGPT_<nr>.txt`), `.xls`/`.doc` vechi, importul în ISAH.

## Probleme frecvente

- **HTTP 401** — cheie greșită sau pentru alt furnizor.
- **Eroare care pomenește „stream”** — debifează **Streaming**.
- **Eroare care pomenește „tool”** — modelul nu suportă tool-uri: alt model sau debifează **Tool-uri**.
- **MCP: eroare** (în bara de jos) — vezi `unelte.json`; PDFCONTROL trebuie să fie în `pdfcontrol\` lângă aplicație.

## Recompilare

Codul sursă se dezvoltă în `C:\LocalChat_Build\src\localchat`. `build.bat` compilează cu JDK 17+,
rulează testele UI și apoi actualizează distribuția în `C:\LocalChat`. Păstrează folderul `lib\`
lângă JAR. Versiunea se citește din `VERSION`. Setările, regulile și conversațiile existente se păstrează.

## Appearance and development (1.1.0)

Use the language dropdown in the top-right corner: Nederlands, Română, Español, Polski, English, Türkçe.
The adjacent theme selector switches between Light and Dark immediately. Choices persist across restarts.
UI translation leaves documents, model messages, drafts, filenames and saved conversation content intact.
The version appears in the window title and About menu and comes from the root VERSION file.

Sources live in LocalChat_Build. Run build.bat with JDK 17+ (JAVA_HOME, PATH or a portable JDK in .tools).
Build.ps1 compiles sources and runs the offline UI tests before replacing the sibling LocalChat distribution.
Use build.bat -SkipDeploy to test and package without changing the installed distribution;
use -DistributionPath C:\OtherFolder for a separate portable copy. No API key or network is used in UI tests.
Generated screenshots are placed in build/test-artifacts. Settings and conversation files are not deployed.

The PDFControl package and production-specific skills/documents are intentionally local dependencies, excluded
from Git. To build a complete portable distribution on another PC, provide an approved PDFControl package
in pdfcontrol/ and approved skills in packaging/skills/. Source compilation and UI tests work without them.

Release workflow: implement, update VERSION and CHANGELOG, run build.bat, review staged files for private data,
then commit and push. Never commit API keys, conversation history, runtime packages or production documents.


## Conversații, context și rapoarte (1.2.0)

Discuțiile apar în stânga imediat după prima întrebare. Apasă pe o discuție pentru a o relua;
poți căuta, schimba titlul sau fixa o discuție. Modelul, folderul, ciorna, statisticile și memoria compactată
se restaurează separat pentru fiecare chat. Deschiderea listei sau încărcarea unui chat nu apelează API-ul.
Fișierele vechi din conversatii/ rămân compatibile; consumul istoric necunoscut este marcat explicit.

Indicatorul de context estimează instrucțiunile de sistem, definițiile tool-urilor și conversația care va
fi trimisă. Valorile ~ sunt estimări UTF-8, nu o tokenizare exactă. Configurează limita reală încărcată
în LM Studio sau limita modelului API; 0 înseamnă necunoscut. Limita nu reconfigurează serverul.
Consumul raportat de server este separat: /token afișează ultimul răspuns și totalul conversației.
Fiecare răspuns poate include mai multe cereri datorită tool-urilor. Datele absente sau întrerupte sunt parțiale;
tokenii de cache sunt afișați separat și nu sunt adunați de două ori în totalul de intrare OpenAI.

Comenzi locale (numele rămân identice în toate limbile; descrierile sunt traduse):

| Comandă | Acțiune |
| --- | --- |
| /help | Lista comenzilor |
| /status | Sesiunea, modelul și folderul curent |
| /model custom:nume | Schimbă furnizorul/modelul; acceptă openai, anthropic și custom |
| /model reset | Modelul implicit al furnizorului |
| /new Titlu | Discuție nouă |
| /list text | Caută discuții și afișează ID-urile scurte |
| /switch ID | Încarcă o discuție |
| /resume [ID] | Reia discuția anterioară sau una indicată |
| /title Titlu | Redenumește discuția |
| /pin | Fixează sau desprinde discuția |
| /history | Statistici despre mesaje și tool-uri |
| /token | Estimarea contextului și consumul raportat |
| /context 64000 | Setează limita contextului pentru estimare |
| /compact | Cere modelului un rezumat al istoricului vechi |
| /stop | Oprește generarea sau compactarea |
| /skills | Skill-urile efectiv instalate |
| /connectors | Starea conectorilor MCP configurați |

/compact face o cerere API folosind modelul curent, iar consumul ei aparține aceluiași chat.
Păstrează ultimele două schimburi și toate mesajele originale pe disc. Rezumatul se aplică doar dacă
reduce contextul și nu se transferă în alt chat. Rezultatul trebuie verificat când deciziile sunt importante.
Comenzile de inspectare și navigare nu trimit conținut modelului.

Fișierele existente găsite în rezultatele tool-urilor, în folderul de lucru, apar în zona Rapoarte și fișiere.
Poți deschide raportul, folderul lui sau salva o copie; suprascrierea unei copii existente cere confirmare.
Nu se deschid automat căi inventate în textul modelului.

Istoricul mare este rezumat în fragmente, cu un buget de ieșire și memoria intermediară rezervate în limita
configurată. Toate cererile de compactare intră în statisticile acelui chat. Dacă o etapă eșuează, rezumatul
activ anterior rămâne valabil. Cererile normale estimate peste limita configurată sunt oprite înainte de API.


## Compactare automată (1.3.0)

În panoul din stânga setează limita reală de context, activează Compactare automată și alege pragul procentual.
Pragul implicit este 70% (configurabil între 30% și 95%); opțiunea este dezactivată implicit și se salvează per chat.
De exemplu, la un context configurat de 64.000, pragul de 70% înseamnă aproximativ 44.800 de tokeni.
Limita 0/necunoscută nu declanșează autocompactarea. Configurarea limitei nu schimbă setarea din LM Studio.

Verificarea se face înainte de trimiterea unui mesaj. Sunt rezumate doar schimburile vechi eligibile;
instrucțiunile de sistem, definițiile tool-urilor și ultimele două schimburi rămân. Dacă acestea ocupă singure
prea mult context, trebuie ajustată limita sau ales un model cu context suficient.
După compactare, mesajul încă neschimbat se trimite o singură dată. Dacă îl editezi, apeși Stop sau rezumatul
eșuează, nu se trimite automat. Compactarea consumă tokeni cu modelul curent, în statisticile aceluiași chat.
Nu pornește la simpla încărcare a unei discuții și nu întrerupe o operație activă pentru a o rezuma pe ascuns.
Cereri ulterioare dintr-o buclă de tool-uri sunt oprite dacă depășesc limita estimată, păstrând pașii executați.


## Gestionare și context (1.4.0)

Butonul − șterge conversația după confirmare, mutând fișierul în conversatii/deleted pentru recuperare manuală.
Click dreapta pe un chat: Arhivează/Restaurează, Exportă chat JSON, Șterge. Bifa Arhivate schimbă lista afișată.
Unelte → Exportă toate chaturile creează un ZIP cu JSON pentru fiecare chat activ sau arhivat, fără cele șterse.
Exporturile includ istoricul integral, ciorna și rezumatul. Nu includ cheile din setari.properties.
Pentru recuperare, copiază fișierul JSON în conversatii și repornește aplicația.

Unelte → Salvează setările și modelul implicit (Ctrl+S) păstrează local cheia/API-ul, lista de modele,
folderul ales și modelul implicit. La pornire sau într-un chat nou se folosește acest model; reluarea unui chat
restaurează modelul acelui chat. Cheile rămân în fișierul local setari.properties și nu se includ în Git.

Verifică contextul modelului citește /api/v1/models din LM Studio, cu fallback la /api/v0/models.
Documentație: https://lmstudio.ai/docs/developer/rest/list . Nu încarcă modelul și nu trimite mesaje.
Maximul teoretic și contextul instanței încărcate se afișează separat. Limita încărcată detectată este folosită
pentru estimare și autocompactare; doar maximul teoretic nu setează automat limita. Dacă sunt mai multe instanțe
și ID-ul ales nu identifică una singură, se folosește conservator cea mai mică limită încărcată.
Metadatele sunt memorate local per server/model. Din 1.5.0 se citesc automat după stabilizarea selecției
și periodic (o dată pe minut); butonul manual poate forța actualizarea. Încărcarea unui chat nu generează
răspunsuri sau compactări. Alte servere pot necesita configurare manuală;
aplicația nu ghicește maximul după numele modelului. Câmpul manual nu modifică serverul.

Bara de jos: procent estimat și detalii la click, model, buget Rapid 1024/Echilibrat 4096/Detaliat 8192,
chat simplu/cu unelte, aprobări. Aceste profiluri limitează ieșirea, nu activează un nivel de reasoning.
Bugetul este redus la spațiul estimat rămas în context. Modelul poate răspunde mai scurt decât bugetul.
Doar citire blochează modificări, comenzi și memorarea regulilor chiar dacă există aprobări anterioare.
Întreabă păstrează confirmările, Permite tot autorizează tool-urile fără întrebări; selecția este explicită.

Căile istorice lipsă sub Downloads/PRODUCTIE sunt rezolvate față de folderul de lucru selectat,
inclusiv când profilul vechi încă există. Căile care există deja au prioritate; verificările de permisiune
rămân active. Erorile vechi rămân în istoricul original și nu sunt transformate în rezultate reușite.


## Interfață compactă și selecții complete (1.5.0)

Lista din stânga afișează titlul conversației; ID-urile rămân doar pentru comenzile /list și /switch.
Click dreapta → Redenumește conversația permite un titlu mai scurt. Bara de jos are controale de 26px,
iar trimiterea și oprirea folosesc pictograme. Un indicator se rotește pe durata generării, a compactării
și a pașilor de unelte și se oprește inclusiv după anulare/eroare. Butonul folderului deschide folderul
din câmpul Folder de lucru și își schimbă imediat numele; calea completă apare la trecerea mouse-ului.

Procentul de context este o estimare: se recalculează la editarea ciornei, la fiecare fragment de răspuns
și la actualizarea periodică de 200ms. Un procent întreg poate rămâne neschimbat pentru răspunsuri scurte.
Citirea automată de metadate face doar GET, nu inferență și nu încărcarea modelului. Schimbarea modelului
are debounce de minimum 750ms; încercările automate sunt limitate la una pe minut per server/model,
inclusiv când serverul nu răspunde. Rezultatele întârziate de la alt model nu se aplică profilului curent.
Serverele care nu raportează contextul încărcat rămân necunoscute până la configurarea manuală;
maximul teoretic nu este prezentat ca limită efectivă a instanței.

Unelte → Salvează setările și modelul implicit memorează și profilul de răspuns, promptul de sistem,
streaming, unelte, stilul de aprobare și opțiunile/pragul autocompactării. Se restaurează la pornire și
în chat-uri noi. Un chat reluat își păstrează propriile mesaje, ciornă, model, folder și compactare.

Fișierele create cu succes de unelte și rapoartele generate de PDFControl sunt deja scrise pe disc în
destinația operației. Salvează o copie este opțional. Un răspuns text nu devine fișier fără o cerere de
creare a fișierului, iar confirmările operațiilor rămân cele stabilite prin modul de aprobare ales.


## PDFCONTROL complet (1.6.0)

Selectează folderul proiectului, apoi apasă **PDFCONTROL** lângă editor sau
**Unelte → PDFCONTROL: operații directe…**. Panoul folosește motorul original,
fără o cerere către model, și este tradus în toate cele șase limbi. Selectează:

- Verifică desenele: controlul original, desene marcate și RAPPORT.pdf.
- Extrage desenele: separarea planșelor dintr-un PDF și identificarea V/W.
- Verifică/completează perechile V/W: copiază numai armarea confirmată de referința reală.
- Compară revizii: selectează întâi PDF-urile vechi, apoi cele noi; primești PDF-urile
  Vergelijking_OUD_NIEUW și Verschillen_OUD_NIEUW, cu marcajele motorului original.
- Flux complet: extragere, perechi, control și raport PDF cu anexele disponibile.
- Regenerează raportul: indică ID-ul unei lucrări terminate pentru un raport nou.

Fișierele trebuie să fie sub proiectul de șase cifre din rădăcina PRODUCTIE configurată
în conector. Sursele sunt copiate într-o lucrare izolată și verificate prin SHA256.
Rezultatele se salvează automat în proiect/Controle/Octop-PDFCONTROL/<job_id>/;
butonul Deschide raportul și lista de rapoarte din chat deschid fișierele existente.
Nu este necesară salvarea manuală a rezultatelor. Panoul oferă progres și anulare.

În chat poți cere, de exemplu:

> Pentru proiectul 507XXX, execută fluxul complet PDFCONTROL pentru PDF-urile din
> subfolderul Desene. Inventariază întâi fișierele. Urmărește jobul până la final
> și oferă RAPPORT.pdf și desenele marcate; fără raport text în locul PDF-ului.

> Compară cu PDFCONTROL proiectul 507XXX: vechile PDF-uri din Revizia_A cu cele
> noi din Revizia_B. Oferă PDF-urile comparației și diferențelor cu marcaje vizibile.

Înlocuiește 507XXX cu numărul real. Modelul trebuie să suporte apeluri de unelte;
panoul direct rămâne disponibil independent de capabilitățile acestuia.
Joburile se urmăresc prin pdfcontrol_job_status, iar artefactele sunt paginate.
Istoricul conversației păstrează legăturile către rezultatele operațiilor directe.

Perechile neconfirmate și identitățile ambigue sunt semnalate pentru verificare,
nu completate prin presupuneri. Un job terminat confirmă execuția, nu aprobarea
tehnică sau transferul pe server. Raportul original păstrează filtrarea anexelor
și plafonul motorului (150 de planșe); include implicit planșele cu probleme.
Sursele nu sunt modificate, iar configurația internă și regulile motorului nu
sunt reconstruite de model. Anularea păstrează rezultatele deja produse.

### Build cu motorul privat

Sursele integrării sunt în integration/. Motorul și runtime-ul existente trebuie
restaurate separat în pdfcontrol/ (excluse din Git). integration/sync_engine.py
sincronizează modulele originale din C:/PDFCONTROL și verifică manifestul SHA256;
poate primi --original pentru altă instalare. Nicio regulă sau desen privat nu
este publicat în repository. Build.ps1 execută testele Java și testele reale MCP
pe PDF-uri sintetice înainte de instalare. Regresiile originale suplimentare se
execută local folosind testele din instalarea PDFCONTROL, fără a le publica.


## Furnizori cu nume și context API (1.7.0)

Alege protocolul din Furnizor (API custom pentru NVIDIA/OpenAI-compatible sau API
Anthropic pentru un server care acceptă acel protocol), completează URL-ul, cheia
și modelul, apoi apasă **Salvează furnizorul…** și scrie numele dorit. Noul nume
apare direct în lista Furnizor. Poți salva mai multe conturi ale aceluiași serviciu.
Același nume actualizează profilul existent; un nume nou creează un profil separat.
Fiecare păstrează cheia, URL-ul, modelul și lista de modele. **Încarcă modele**
citește catalogul profilului selectat. Setările/modelul implicit se salvează în
continuare prin Unelte; conversațiile reluate păstrează profilul propriu.

Exemple de URL-uri: https://api.gonkarouter.io/v1 și
https://integrate.api.nvidia.com/v1 . Cheile rămân numai în setari.properties,
nu în conversații, exporturi sau Git. Profilul fără cheie nu împrumută cheia altui
profil. Un răspuns HTTP 401 indică autentificare nereușită; un catalog HTTP 200
fără câmpuri de context indică metadate absente, nu o limită zero.

Inspecția contextului folosește numai GET pentru toate protocoalele. Pe servere
locale păstrează citirea nativă LM Studio, cu fallback la catalogul standard;
serverele externe sunt citite prin <base>/models. Nu face inferență și nu încarcă
modelul. Recunoaște câmpurile numerice context_length, max_context_length,
context_window, max_model_len și limitele de intrare explicite. Un maxim declarat
sau o limită de ieșire nu devine automat context încărcat. Nu deduce limite din ID.

GonkaRouter și NVIDIA nu comunicau contextul în catalogul autentificat verificat
la 20 octombrie 2026. Pentru GonkaRouter, **Aplică buget recomandat** configurează
explicit un buget local conservator de 180000 tokeni, sub limita de intrare de
aproximativ 200K descrisă la https://gonkarouter.io/pricing . Nu este context
confirmat de API. Pentru alte servicii poți configura manual un buget verificat
pentru modelul/ruta folosită. Procentajul estimat și autocompactarea se raportează
la bugetul local; alegerea lui nu modifică serverul. Valorile introduse manual
nu sunt șterse când catalogul nu comunică limite.


## Catalog, disponibilitate și capabilități (1.8.0)

**Încarcă modele** citește catalogul API și deschide lista detaliată. Poți reveni
la ea prin **Unelte → Catalog modele**, fără a declanșa automat teste. Bifa
**Testează la încărcarea listei**, memorată per furnizor, pornește teste pentru
modelele netestate/expirate când soliciți explicit încărcarea listei. Pornirea
aplicației, reluarea chaturilor și actualizările GET nu pornesc teste de inferență.

Testele trimit doar un mesaj sintetic scurt (maximum 128 tokeni de ieșire), fără
conversație, documente sau uneltele aplicației. Consumă tokeni API și sunt afișate
separat de consumul chatului. Sunt maximum două cereri simultane, pornite la cel
puțin 1,5 secunde distanță; HTTP 429 introduce o pauză de 30 secunde. Poți opri
lotul. Un timeout ori o cerere de test incompatibilă nu declară modelul inexistent.
HTTP 403/404 indică acces indisponibil pentru cont la acel test; HTTP 401 oprește
lotul. Un HTTP 200 fără răspuns final este afișat distinct. Un identificator de
model diferit în răspuns este semnalat, iar GonkaRouter primește No-Fallback la
probe. Testul nu garantează calitatea răspunsurilor sau funcționarea pe orice input.

Modelele care au răspuns apar primele în catalog și în lista de selecție după
închiderea acestuia. Filtrul **Doar modele care au răspuns** ascunde restul.
Accesul este memorat separat per profil, URL, protocol și amprentă de cheie,
în model-tests/, cu expirare după 24 de ore. Cheia și răspunsurile de eroare cu
identificatori de cont nu se scriu în aceste rezultate. Un model inaccesibil
rămâne vizibil pentru diagnostic/retestare; catalogul nu îl selectează în locul
tău și nu schimbă automat modelul unui chat reluat.

Pictogramele sunt: ochi = imagini, ciocan = apeluri de unelte, bec = raționament.
Culorile arată capabilități declarate de API/documentație; ? înseamnă informație
absentă, iar bara oblică înseamnă false explicit din API. Un test de răspuns simplu
nu verifică imaginile sau raționamentul. **Testează uneltele** solicită o funcție
fictivă și verifică dacă modelul o returnează; funcția nu se execută. Capabilitatea
vizuală a modelului nu adaugă un flux de atașare de imagini în LocalChat.

Pentru deepseek-ai/deepseek-v4.1-flash pe NVIDIA, documentația modelului precizează
1048576 tokeni și imagini/unelte/raționament:
https://docs.api.nvidia.com/nim/reference/nvidia-deepseek-v4_1-flash . Acest maxim
publicat este separat de contextul rutei, absent din catalogul contului. Butonul
**Aplică buget recomandat** poate seta explicit un buget local conservator de
131072 tokeni; nu configurează serverul și nu dovedește o limită a contului.
Modelele fără informații rămân necunoscute până la o sursă sau configurare manuală.

În stânga, consumul API cumulativ și ultimul tur încheiat sunt distincte de
ocuparea estimată a contextului. Un „~?” în cerc indică buget local necunoscut,
chiar dacă API-ul a raportat tokenii consumați. Estimările nu înlocuiesc usage
raportat, iar usage absent este afișat ca necunoscut, inclusiv după EOF/anulare.

Capabilitățile documentate pentru Muse Glimmer, Gemma 4, Nemotron, Laguna și Step 5 sunt incluse cu sursele lor oficiale. Cifrele publicate ca 1M/256K/131072+ își păstrează eticheta originală; nu sunt prezentate drept limite exacte ale API-ului. Filtrul Doar cu unelte cere o capabilitate declarată sau un apel de test verificat. Testele de unelte sunt păstrate separat: un test de unelte nereușit nu șterge disponibilitatea confirmată pentru chat.

## Procent de context live (1.8.1)

Când bugetul local este zero și există o recomandare documentată sau metadate de
context, LocalChat inițializează automat un buget conservator, etichetat „buget
recomandat automat”. Pentru NVIDIA Muse Glimmer este 131072 tokeni. Această regulă
înlocuiește necesitatea de a apăsa manual „Aplică buget recomandat” din 1.8.0.
Bugetul nu configurează serverul și nu confirmă contextul real al rutei API.
Valorile manuale pozitive rămân neschimbate, separat per model/furnizor; modelele
fără informații rămân cu „~?” până la configurare. Limitele cunoscute ale API-ului
restrâng bugetul automat. Încărcarea unui chat nu generează răspunsuri sau compactări.

Procentul estimat are două zecimale și se actualizează în timpul streamingului și
după fiecare rezultat de unealtă. Cu streaming oprit, textul devine disponibil
numai la primirea răspunsului complet. Tokenii interni de reasoning și usage
facturat devin cunoscuți numai când API-ul îi raportează; nu sunt inventați din
animația de activitate. Consumul API cumulativ rămâne separat de procent.

## Atașamente și drag-and-drop (1.9.0)

Apasă agrafa de lângă „Conversație nouă” pentru selecția multiplă de fișiere și
foldere sau trage-le din Explorer peste zona mesajului ori conversației. Selecția
apare deasupra casetei de text; „×” elimină referința, fără a șterge fișierul.
Poți atașa maximum 20 de referințe per mesaj. Calea completă apare în tooltip.

Exemplu: selectează desenele PDF și lista XLSX, apoi scrie „Verifică desenele cu
PDFCONTROL și compară articolele cu lista atașată”. Uneltele trebuie activate.
Fără text, Trimite cere analiza fișierelor/folderelor selectate. Referințele sunt
locale: selectarea nu citește întregul conținut, nu încarcă recursiv un folder și
nu apelează modelul. După trimitere, modelul poate cere citirea cu uneltele reale
pentru PDF, DOCX, XLSX/XLSM, MSG și text. Conținutul citit de unelte poate ajunge
la API-ul modelului selectat ca rezultat de unealtă. Pentru imagini/binare, simpla
selectare nu adaugă trimitere vizuală multimodală sau un cititor nou: se aplică
formatele acceptate de uneltele existente.

Fișierele selectate explicit, chiar în afara folderului de lucru, sunt autorizate
pentru citire în conversația curentă; un folder selectat autorizează citirea
conținutului său. Aceasta nu schimbă aprobările pentru scrieri sau comenzi. Un
fișier atașat nu oferă acces automat la vecinii săi. Setările cu cheile API rămân
blocate inclusiv când sunt într-un folder selectat. Referințele trimise rămân
valabile la continuarea chatului; ciorna cu selecțiile nestrimise se salvează
separat per conversație și este refăcută după un eșec fără răspuns util.
Exportul unui chat include referințele locale, nu copiile documentelor.

## Interfata romana fara diacritice (1.9.1)

Meniurile, butoanele, dialogurile, mesajele de stare/eroare si etichetele generate
de uneltele LocalChat folosesc litere normale in romana. Limba se numeste „Romana”
in selector; codul ro si preferinta salvata raman aceleasi. Celelalte limbi isi
pastreaza traducerile si caracterele proprii. Textele noi fara traducere sunt
normalizate ca sabloane in romana, inainte de inserarea valorilor dinamice.

Fisierele si continutul utilizatorului nu se convertesc: numele, caile, textele
citate, documentele si conversatiile existente isi pastreaza exact caracterele.
Aceasta schimbare priveste textele proprii interfetei, nu rescrierea istoricului
sau modificarea raspunsurilor libere generate de model.

## Context protejat inaintea fiecarei cereri (1.10.0)

Cu autocompactarea activa, LocalChat verifica atat intrebarea noua, cat si fiecare
pas care urmeaza unui rezultat de unealta. Rezerva loc pentru raspuns si o marja
de siguranta. Daca este necesar, rezuma mai intai memoria in fragmente, apoi
verifica din nou incadrarea si continua aceeasi intrebare. Intrebarea curenta este
pastrata exact, iar istoricul original, inclusiv rezultatele complete ale
uneltelor, ramane in arhiva. Vederea trimisa modelului este separata.

Numarul real de tokeni de intrare raportat de API calibreaza estimarile pentru
profilul/modelul selectat. Ultima valoare cunoscuta este pastrata per chat;
cresterea prin rezultate noi de unelte este planificata conservator, separat de
consumul facturat. Ancora este valabila numai pentru acelasi model, sistem,
definitii de unelte si memorie. Procentele sunt in continuare estimari ~, nu o
promisiune ca API-ul ofera un tokenizer sau o limita verificata pentru cont.

Compactarea foloseste modelul curent si consuma tokeni; consumul este inclus in
statistici. Nu executa unelte operationale si nu reia scrierile/comenzile deja
terminate. Stop, esecul rezumarii sau modificarea ciornei inainte de prima
trimitere opresc continuarea automata. O intrebare prea mare pentru context nu
este trunchiata in tacere. Cand limita este necunoscuta, configureaza un buget
verificat; cand autocompactarea este oprita, o cerere care nu incape este blocata.

## Lista completa de comenzi

Tasteaza `/` in caseta mesajului: lista se deruleaza cu rotita mouse-ului si cu
sagetile Sus/Jos. Enter alege comanda; Esc inchide lista. Comenzile care cer
parametri se insereaza in caseta pentru completare. Celelalte se executa la
selectie. Shift+Enter continua sa insereze un rand nou. `/help` afiseaza lista
integrala in chat. Dark si Light aplica fundal, text si selectie cu contrast.

| Comanda | Functie |
| --- | --- |
| `/status` | Starea, modelul si folderul curent |
| `/model provider:model` | Model; fara argument afiseaza selectia curenta |
| `/new [titlu]` | Conversatie noua |
| `/stop` | Oprire |
| `/token` | Context estimat si consum API |
| `/history` | Statistici |
| `/compact` | Compactare manuala |
| `/list [filtru]` | Lista conversatiilor si ID-uri pentru navigare |
| `/switch ID` | Deschide conversatia |
| `/resume [ID]` | Reia conversatia anterioara sau cea indicata |
| `/title [titlu]` | Redenumire |
| `/pin` | Fixare/desprindere |
| `/skills` | Skill-uri instalate |
| `/connectors` | Starea conectorilor MCP |
| `/context numar` | Bugetul local de context |
| `/archive` | Arhivare/restaurare |
| `/export` | Export JSON |
| `/delete` | Stergere recuperabila, cu confirmare |
| `/attach` | Selectie de fisiere/foldere |
| `/folder` | Deschide folderul de lucru |
| `/pdfcontrol` | Panoul operatiilor PDFCONTROL |
| `/settings` | Salveaza explicit setarile si modelul implicit |
| `/autocompact on`, `off` sau `70` | Activeaza, dezactiveaza sau seteaza pragul 30-95% per chat |
| `/help` | Lista completa |

Lista contine functii implementate in LocalChat. Functii specifice altor
aplicatii, precum animale de companie sau obiective programate, nu sunt simulate.

## Chat fara diacritice si buget pentru modele noi (1.10.1)

In limba Romana, inclusiv raspunsurile modelelor si conversatiile vechi se
afiseaza fara diacritice. Arborii de directoare folosesc caractere obisnuite,
de exemplu +-- in loc de colturi si linii Unicode care pot aparea ca patratele.
Originalul este pastrat: JSON-ul conversatiei si mesajele trimise modelului nu
sunt convertite, iar fisierele si caile folosite de unelte raman intacte.
Click dreapta in chat permite copierea afisarii simplificate sau a textului
original al selectiei. Pentru o cale de fisier cu diacritice, foloseste
Copiaza textul original. Schimbarea limbii reface afisarea din original, fara API.

Cand un model nou nu are metadate sau context documentat, aplicatia foloseste
un buget local initial de lucru de 32768 pentru procent si protectia contextului.
Interfata arata explicit ca limita reala API este necunoscuta. Bugetul local nu
configureaza serverul si nu garanteaza limita acceptata de cont; il poti ajusta.
Datele cunoscute ulterior inlocuiesc bugetul de lucru, iar bugetele manuale
pozitive raman separate per model. /context 0 dezactiveaza explicit folosirea
unei limite locale cunoscute, caz in care procentul ramane necunoscut.
Aceasta regula inlocuieste mentinerea automata la zero pentru modelele fara date.

Favorite modele: apasa steluta de langa selector sau foloseste butonul din catalog. Modelele favorite apar primele; filtrul Doar favorite restrange catalogul. Favoritele sunt locale per furnizor si URL si nu modifica modelul implicit (Unelte > Salveaza setarile si modelul implicit).

Motor propriu PDFCONTROL: sursa autoritara este LocalChat_Build/pdfcontrol. Buildul normal verifica manifestul acestei copii si o distribuie in LocalChat/pdfcontrol; nu citeste si nu suprascrie din C:/PDFCONTROL. Importul originalului este o migrare explicita prin integration/sync_engine.py --package <folder> --import-original <sursa>, niciodata un pas automat. Motorul privat si cataloagele nu se publica in Git.


## Markdown, copiere si rapoarte in chat (1.10.3)

Raspunsurile asistentului afiseaza titluri, text ingrosat/cursiv/taiat, citate,
liste, tabele si cod. Formatarea se actualizeaza in streaming si la reluarea
conversatiei; continutul original din JSON si cererile API ramane intact.
Butonul Copiaza raspunsul copiaza ultimul raspuns ca Markdown original.
Click dreapta > Copiaza textul afisat copiaza selectia fara marcajele ascunse;
Copiaza textul original pastreaza inclusiv caile reale cu diacritice.
Fara selectie, cele doua actiuni copiaza conversatia afisata integral.

PDF-urile existente din rezultatele uneltelor apar in conversatie cu actiuni
Deschide raportul, Salveaza o copie, Copiaza fisierul si Copiaza calea.
Copiaza fisierul permite lipirea PDF-ului in Explorer. Nu sterge originalul.
Actiunile reapar la reluarea chatului, fara inferenta. Accesul verifica din nou
existenta fisierului si folderul de lucru curent. Nu afisa PDF-uri din lucrari
PDFCONTROL pending/running/failed/cancelled sau fara livrare verificata.

Controlul desenelor livreaza RAPPORT.pdf original si desenele marcate disponibile.
Compararea versiunilor vechi/noi livreaza Vergelijking_OUD_NIEUW.pdf si PDF-urile
cu diferente disponibile. V/W sunt tipuri de desen, nu versiuni vechi/noi.
Exemplu de cerere: Verifica toate PDF-urile sursa ale proiectului 505417 din
Controle/Atasamente_nou_2026-10-19 cu PDFCONTROL. Genereaza raportul PDF original,
cu anexele disponibile, salveaza-l in proiect si pune-l in chat. Explica limitarile.
Pentru comparare: Compara desenele vechi din <folder vechi> cu cele noi din
<folder nou>, proiectul 505417. Genereaza PDF-ul vizual OUD/NIEUW cu marcaje.

Compactarea manuala si automata lucreaza pe fragmente. Daca rezumatul este prea
lung, incearca de maximum trei ori cu limite mai stricte. Un raspuns gol poate
primi o rezerva de iesire mai mare, pastrand rezumatul vizibil limitat.
Istoricul integral, intrebarea exacta si ciorna sunt protejate la esec sau Stop.
Un istoric care depaseste bugetul poate ajunge la protectia automata inaintea
primei inferente normale, daca autocompactarea este activa. Limita locala nu
este o confirmare a limitei acceptate de furnizor.


## Status si raport complet PDFCONTROL (1.10.4)

VORM DEFINITIEF si DEFINITIEF sunt echivalente in predicatul comun de status,
inclusiv la celulele obisnuite, nu numai la cartusul MBS Specials. Sunt acceptate
variante de spatiere/majuscule si VORMDEFINITIEF; NIET DEFINITIEF, CONCEPT,
VOORLOPIG si TER CONTROLE raman nefinale. Se citeste celula de status curenta,
nu o mentiune din legenda sau istoricul reviziilor.

Controlul si regenerarea prin conector folosesc raportul complet al motorului:
tabele pentru toate desenele selectate, controale executate inclusiv OK si anexele
disponibile, cu limita de siguranta originala a numarului de anexe. Numarul
neconformitatilor se calculeaza independent de numarul desenelor incluse.
Raportul pastreaza limitarile de catalog/fabrica si nu reprezinta o aprobare de
productie. Optiunea originala a motorului pentru un raport doar cu probleme
ramane disponibila pentru utilizarea separata a motorului.


## Card PDF si previzualizare in dreapta (1.11.0)

Fiecare PDF existent descoperit in rezultatele uneltelor are un card in conversatie,
cu nume, iconita PDF, dimensiune, butonul Previzualizeaza si meniul de actiuni.
PDF-urile livrate si verificate de PDFCONTROL deschid automat panoul din dreapta
la terminarea operatiei. Un click pe card permite redeschiderea ulterioara.
Cardurile reapar la reluarea conversatiei, fara apel API si fara citirea automata
a rapoartelor vechi. Panoul se goleste cand schimbi conversatia sau folderul.

Panoul afiseaza o singura pagina la un moment dat, cu navigare, numar de pagina,
potrivire la latime si zoom 50-200%. Bara dintre chat si PDF poate fi redimensionata.
Butoanele cu iconite permit salvarea unei copii, deschiderea externa si copierea
fisierului pentru Explorer. Inchiderea panoului reda spatiul conversatiei.

Previzualizarea foloseste Python/PyMuPDF din copia proprie PDFCONTROL, intr-un
proces local separat, cu limite de timp si rezolutie. Nu trimite PDF-ul modelului,
nu modifica originalul si nu consuma context sau tokeni. Rapoartele invalide,
criptate ori inaccesibile au mesaj de eroare si optiune de deschidere externa.
Interfata este tradusa in toate cele sase limbi si adaptata ambelor teme.


Iconitele din antet sunt functionale: meniul cu trei puncte permite redenumire,
fixare, arhivare, export JSON, compactare si copierea raspunsului. Iconita de
reglaje ofera modelul, profilul de raspuns, aprobarile, uneltele si autocompactarea.

Panoul PDF pastreaza mai multe taburi, fiecare cu inchidere separata. Plus deschide
selectorul pentru alt PDF din folderul de lucru. Iconita de marire foloseste tot
spatiul chatului, apoi restabileste impartirea. Iconita de panouri si Ctrl+Shift+B
ascund/reafiseaza taburile fara sa le inchida. Schimbarea conversatiei sau a
folderului goleste toate taburile. Pozitia paginii si zoomul sunt separate per tab.

### Clipboard in caseta de mesaj

Ctrl+V, Shift+Insert si click dreapta > Lipeste accepta text, fisiere copiate din Explorer si capturi din clipboard. Textul inlocuieste selectia curenta. Capturile sunt salvate ca PNG in `atasamente_clipboard/` si atasate la ciorna; eliminarea atasamentului nu sterge fisierul. Selectia nu apeleaza modelul si nu trimite automat pixelii ca intrare vizuala. Sunt pastrate limita de 20 de atasamente si blocarea atasarii in timpul generarii. Imaginile au limita de 16 milioane de pixeli.

La erori explicite HTTP 502/503/504, LocalChat reincearca acelasi apel API de maximum trei ori, cu asteptare scurta si Stop activ. Compactarea reincearca fragmentul curent, fara unelte operationale; cererea normala pastreaza rezultatele uneltelor deja executate. Un raspuns partial intrerupt nu este reluat automat. Dupa esec, memoria completa precedenta, istoricul si ciorna sunt pastrate.

Click pe indicatorul de context din bara de jos deschide un panou live cu procent, bara segmentata si categoriile Sistem, Unelte si Conversatie. Valorile ~ sunt estimari ale contextului activ pentru cererea urmatoare, inclusiv ciorna. Consumul API este disponibil separat prin extindere; o limita necunoscuta nu este afisata ca 0%. Panoul se inchide cu Escape, click in afara sau inca un click pe indicator.

Panoul de context este un card compact, de 360 px latime, cu colturi rotunjite si suprafata explicita Dark/Light, inclusiv la prima deschidere dupa pornire. Sectiunea API se extinde printr-o iconita vectoriala.

Caseta de mesaj este mai inalta implicit. Trage separatorul dintre raspunsuri si mesaj pentru redimensionare: minimum 96 px cand spatiul permite, maximum egala cu zona vizibila a raspunsurilor. Dimensiunea aleasa este memorata; o fereastra mai mica o limiteaza temporar fara a pierde preferinta. Butoanele si bara de stare raman vizibile.

### Memorii locale

Unelte > Memorii locale permite activarea memoriei, controlul preferintelor automate din conversatii cu unelte, inspectarea si editarea intrarilor, stergerea unei intrari sau a tuturor memoriilor. In instalari noi din surse, memoria este implicit oprita. Memoriile sunt in `memorie/memorii.json`, separat de rezumatul pentru compactarea unui chat si de regulile manuale `reguli.md`.

In modul MBS, cand memoria este activa, mesaje precum `Tine minte ca toate conversatiile le facem in limba romana` sunt salvate imediat, local, fara apel API suplimentar. Declaratii de preferinte precum `Prefer rapoarte PDF` se pot retine automat; optiunea pentru conversatii cu unelte controleaza acest caz. Modul doar citire blocheaza salvarea din chat. Interfata de administrare permite adaugari si modificari explicite.

Exemplu de proiect: `Pentru proiectul 505417, retine ca livrarea asteapta confirmare.` Nota se aplica numai proiectului identificat explicit in mesaj, folderul de lucru sau mesajele utilizatorului din chatul curent. Numerele din raspunsuri sau documente nu selecteaza proiectul. Daca proiectul este ambiguu, foloseste administrarea memoriilor pentru a-l alege explicit. `PROJECT_INFO.md` ramane documentul de verificat pentru starea proiectului; memoria nu autorizeaza operatii si nu modifica acest document automat.

Preferintele globale si notele proiectului curent sunt incluse in sistem, in limita bugetului de context, si functioneaza inclusiv cu Tool-uri dezactivate. Conteaza in estimarea contextului si sunt trimise furnizorului API selectat odata cu cererea. Cererile curente explicite au prioritate. Reguli sau preferinte noi nu se deduc din documente ori rezultate de unelte, iar cheile API/parolele sunt respinse. Nu exista procesare retroactiva automata a tuturor chaturilor si nici inferenta ascunsa pentru extragere. Dupa stergerea memoriilor, istoricul nu le recreeaza automat. Regulile manuale, conversatiile si `PROJECT_INFO.md` se gestioneaza separat.

Referinta de produs pentru controalele de memorie: https://developers.openai.com/codex/memories . LocalChat foloseste propriul depozit si propriile setari; memoria Codex este administrata separat de Codex.

## International chat (1.13.0)

`Interface language` controls menus only. `Response language` fixes the language of the model's chat prose, even if the input and older saved preferences use another language. Select `Automatic (by mode)` to follow the current explicit language request, then saved language preferences in MBS mode or the input language in clean Normal mode. Fixed mode intentionally requires changing the selector to change chat language; requested report/file languages, literal paths and project IDs stay separate.

`Translate my message` asks the same inference to prefix its final answer with `> "translated message"`. This is rendered italic and blue, with different readable colors for Dark and Light. Translation is enabled initially in fixed mode and can be switched off. Automatic mode disables the translation control. The model generates this translation, so quality and instruction compliance depend on the selected model; no separate translation service or inference is added. Long input can be labelled as a translated summary; attached file lists and document contents are not echoed as a question translation. Original messages remain unchanged in the archive/API. Selectors and translation are stored per chat; explicit Save settings and default model sets the choices for new chats. Loading a chat does not translate its history or call inference.

The response-length profile defaults to Automatic for new installations/chats without an existing explicit default. It uses explicit `max_output_tokens` / `max_completion_tokens` API metadata when available (including route caps), never context length or a guess from the model name. Unknown output limits use a labelled local working cap of 16000 tokens. Every request remains bounded by free context and safety margin; automatic is not unlimited and does not guarantee a minimum answer length. Existing per-chat fixed profiles retain their indices 0/1/2. The profile tooltip reports the active cap and its provenance.

The conversation mode selector offers **MBS** (tools enabled, project skills, saved rules, relevant persistent memories and PDFCONTROL) and **Normal** (clean general conversation). Normal sends no tool definitions, skill catalog, working-folder identity, saved rules or persistent memories, and captures no new memories. Its editable system prompt is separate from MBS. Fixed response-language selection still applies; Automatic follows the current message language.

Changing mode starts a **fresh active context**. Earlier questions, answers, tool results and compacted memory from the previous mode are excluded from inference, but remain in the visible archive with bookmarks and the unsent draft. Switching back also starts a fresh segment; it does not resume an earlier mode segment automatically. Context counters, manual/automatic compaction and API anchors use only the active segment. The segment and current mode persist per chat: reopening or loading a chat never generates an API answer. Existing Normal chats from older versions receive a fresh segment on first load to remove their inherited MBS persona. Older answers are retained for reading/copying.

Separate model/context metadata GET requests and explicit local UI functions such as opening an existing PDF remain available without inference. The selected mode is stored per chat through toolsEnabled; activeContextStart and contextMode are local archive metadata and never sent to providers.

Fixed response language (1.13.1) omits recognized global conversation-language preferences from the active memory/rules context and puts a short instruction in the selected language first. This does not delete preferences or alter chat history; Automatic in MBS reuses them. Project notes and report/file language requests remain available. This removes conflicting instructions rather than relying only on a final override in a long prompt. Compliance is still a model behavior, not a translation guarantee.

For fixed mode, the wire request also carries a temporary application language control after the current user message (an additional text block in Anthropic format). Original user content remains exact; this control is not written to the conversation archive or used as the retained compaction question. It is counted in context estimates and reattached after compaction, including tool continuations. No additional inference, tool invocation or automatic retry is introduced.

Leading provider `<think>...</think>` content is hidden in ordinary chat rendering and Copy response, including split streaming tags. Raw text remains in JSON and explicit Copy original selections; literal code examples and non-leading tags stay visible. A completed response containing only an explicitly closed technical block produces a visible missing-final-answer message. The selected language applies to the displayed final response; no second model call is used to hide technical text.

In 1.13.2, a blank-line-separated translated quote outside fenced code also ends an otherwise unclosed leading think block. This handles compatible streaming routes that omit the closing tag. The same boundary is used for rendering, ordinary copying and visible-final detection; reloading a saved chat restores its existing answer without inference.

In 1.13.3, both automatic and fixed response modes request the final marker [[LOCALCHAT_FINAL]] through the same temporary control. Automatic includes enabled global language preferences and relevant language-only manual preferences, honoring an explicit current language request first and ignoring old assistant language. Rendering hides an incomplete technical prefix during streaming, then uses the final marker, a known close tag or the legacy quoted boundary. If a completed reply has none of those boundaries, its available text remains visible rather than being discarded as an empty final. Original messages stay intact, and changing themes/loading history preserves the completed state without inference.

### Navigare in conversatii (1.14.0)

Bara subtire din stanga raspunsurilor are un reper pentru fiecare intrebare. Clicul sare la intrebarea respectiva si raspunsurile ei. Hover afiseaza titlul si actiunea de adaugare/eliminare a unui semn de carte. Butonul de navigare din antet listeaza toate intrebarile; cele marcate au o stea. Pe bara focalizata, sagetile navigheaza si B sau Space schimba semnul de carte. Semnele se salveaza numai in conversatia curenta, inclusiv in export, fara a fi trimise modelului. In timpul navigarii manuale, streaming-ul nu muta automat caretul la sfarsit.

Blocul tehnic initial al furnizorului are controlul `Detalii tehnice`, pliat implicit. Clicul deschide/inchide continutul primit. Raspunsul final ramane vizibil, indiferent de limba sau de modul Automat. Continutul ambiguu fara delimitare nu este ascuns ca detaliu tehnic. Deschiderea nu genereaza informatii noi si nu apeleaza API-ul. Originalele, offseturile selectiei si copierea raspunsului final sunt pastrate; detaliile sunt din nou pliate la redeschiderea chatului.

Din 1.14.1, hover pe reper afiseaza un card compact de 320 x 110 px: titlul intrebarii, un extras scurt din raspuns si iconita de semn de carte in coltul dreapta sus. Iconita comuta marcarea fara salt; titlul/extrasul deschid pozitia discutiei. Pana exista raspuns, extrasul provine din intrebare. Aspectul este adaptat explicit temelor Dark si Light.

Din 1.14.1, zona de raspunsuri, editorul de mesaj si panourile de derulare folosesc borduri FlatLaf rotunjite de 14 px. Campurile si butoanele au colturi de 12 px, inclusiv in dialogurile aplicatiei; schimbarea temei pastreaza stilul si limitele de redimensionare.

### Editarea mesajului (1.14.2)

In caseta de mesaj, Ctrl+A selecteaza tot, Ctrl+C copiaza selectia, Ctrl+X o decupeaza, Ctrl+V o inlocuieste cu textul din clipboard. Ctrl+Z anuleaza editarea; Ctrl+Y sau Ctrl+Shift+Z o refac. Sunt disponibile si Ctrl+Insert (copiere), Shift+Insert (lipire), Shift+Delete (decupare). Meniul contextual include Undo/Redo traduse. Lipirea capturilor/fisierelor ramane o atasare locala, conform regulilor existente.

Undo/Redo privesc numai textul ciornei, fara a anula operatii cu fisiere sau executii. Istoricul editarilor este local sesiunii editorului, limitat si resetat la schimbarea chatului ori trimiterea/restaurarea mesajului; nu se serializeaza si nu amesteca textele conversatiilor. Clipboard-ul temporar blocat are reincercari limitate; textul nu este decupat daca nu a putut fi copiat.

### Recuperarea caii rezultatului PDFCONTROL (1.15.1)

Cand modelul omite `engine` din `<run>/engine/checked/resultaten.json`, citirea recupereaza calea numai daca manifestul `job.json` al aceluiasi job done/partial confirma exact fisierul existent, iar manifestul si rezultatul raman in folderul de lucru autorizat. Raspunsul uneltei arata calea ceruta si calea folosita si contine datele citite efectiv, cu acelasi offset/limit. Lipsa dovezilor, o tinta protejata sau o eroare reala de citire raman erori. Nu sunt create copii/aliasuri si nu se reface controlul PDF. Mesajele rosii din istoricul existent raman dovezi ale apelurilor vechi; actualizarea nu le rescrie.

## Modele dupa profil (1.16.0)

Lista de selectie aplica implicit filtrul de profil tuturor furnizorilor, indiferent de pret sau nume. **MBS** afiseaza modele cu suport pentru unelte declarat de API/documentatie sau verificat prin proba. **Normal** permite si modele de chat fara unelte. Modelele de audio, embeddings sau moderare sunt specializate, nu modele de conversatie generala.

In **Unelte > Catalog modele**, debifeaza **Doar modele potrivite profilului** pentru toate intrarile si motivele clasificarii. Modelele necunoscute pot fi testate explicit; lipsa metadatelor nu declara lipsa suportului. HTTP 403/404 este acces blocat per cont, cu expirare/retestare; 429, timeout si erorile temporare nu modifica aptitudinea modelului. Filtrarea nu genereaza inferente, nu sterge favoritele sau modelele si pastreaza selectia curenta, chiar daca este nerecomandata. O evaluare MBS nefavorabila nu blocheaza folosirea modelului in Normal.

## Agenti la cerere si reluarea lucrului (1.16.0)

In **MBS**, poti scrie: `Porneste doi agenti: unul verifica raportul PDF deja existent, celalalt verifica perechile V/W. Nu relansa controlul si nu modifica sursele. Reuneste constatarile.` Sau foloseste `/agents <sarcina>`. Modelul principal imparte cererea in sarcini independente prin `run_agents`; fiecare foloseste furnizorul si modelul selectat, pentru orice API de chat compatibil cu unelte. Nu necesita MiniMax/Qwen sau o lista fixa de furnizori. In Normal nu sunt expuse unelte.

Limite: maximum 3 sarcini intr-un lot, 2 simultan, 2 loturi distincte pe tura, 16 pasi/copil, maximum 4096 tokeni de iesire pentru fiecare cerere a copilului, 180 secunde/lot. Bugetul/contextul si permisiunile chatului raman aplicate, iar consumul copiilor se adauga consumului real al conversatiei. Rate limit-ul furnizorului poate limita paralelismul util. Agentii nu se pornesc pentru simpla discutie despre agenti sau pentru o cerere negativa si nu deleaga recursiv. Stop opreste cererile si uneltele cunoscute; arhivele private sunt in `agent-runs/`, fara credentialele furnizorului.

La «continua», indexul factual local al actiunilor si caile returnate raman disponibile separat de rezumat. Aplicatia opreste repetarea fara progres a citirilor. Pentru fisiere deja copiate, copy_path verifica hashul si omite identicele; diferentele nu autorizeaza suprascrierea. O operatie SMB blocata in Files.copy nu poate fi intrerupta instantaneu, dar anularea nu este declarata succes.

Pentru un build intr-o sesiune fara desktop interactiv se poate folosi `Build.ps1 -SkipDeploy -SkipNativeQA`. Aceasta omite explicit testele cu tastatura/mouse/captura native; nu reprezinta verificare a lor. Buildul implicit pastreaza testele native obligatorii.
