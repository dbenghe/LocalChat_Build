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
