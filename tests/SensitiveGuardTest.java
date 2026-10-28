package localchat;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 1.28.9: sensitive command guard (network drives, credentials, browser cookie/login stores). No real credential or drive is touched. */
public class SensitiveGuardTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static boolean hit(String cmd,Path cwd){return !SensitiveGuard.scan(cmd,cwd).isEmpty();}
 static boolean has(String cmd,Path cwd,String category,String targetPart){for(var h:SensitiveGuard.scan(cmd,cwd))if(h.category().equals(category)&&h.target().contains(targetPart))return true;return false;}
 static String b64(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_16LE));}

 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory("sensitive-guard");
  I18n.setLanguage("ro");SensitiveGuard.reset();
  matrix(root);scripts(root);flow(root);i18n();
  System.out.println("PASS: "+checks+" sensitive guard checks");
 }

 static void matrix(Path root){
  for(String c:List.of("net use Z: \\\\srv\\share /persistent:yes","NET USE * /delete","net.exe use \\\\srv\\share","powershell New-PSDrive -Name Z -PSProvider FileSystem -Root \\\\srv\\share -Persist",
    "Remove-PSDrive -Name Z -Persist","new-psdrive -name y -psprovider filesystem -root c:\\x -credential $cred","Get-Credential","GET-CREDENTIAL -Message x","cmdkey /add:srv /user:u /pass:p","cmdkey /list","runas /user:admin cmd",
    "mountvol Z: \\\\srv\\share","subst Y: \\\\srv\\share","$s=ConvertTo-SecureString 'x' -AsPlainText -Force","New-Object System.Management.Automation.PSCredential('u',$s)",
    "copy \"%LOCALAPPDATA%\\Google\\Chrome\\User Data\\Default\\Login Data\" x","type \"C:\\Users\\a\\AppData\\Local\\Microsoft\\Edge\\User Data\\Default\\Network\\Cookies\"","dir %APPDATA%\\Mozilla\\Firefox\\Profiles\\x\\cookies.sqlite",
    "python -c \"import browser_cookie3\"","copy \"C:\\Users\\a\\AppData\\Local\\Google\\Chrome\\User Data\\Local State\" x","[Security.Cryptography.ProtectedData]::Unprotect($b,$null,'CurrentUser')","python -c \"ctypes.windll.crypt32.CryptUnprotectData\"",
    "New-SmbMapping -LocalPath Z: -RemotePath \\\\srv\\share","VaultCmd /list","type logins.json","echo a && net use Z: \\\\srv\\share"))
   check(hit(c,root),"positive: "+c);
  for(String c:List.of("net user","net users","net view","net share","dir","echo hello","git status","Select-String -Path mail.msg -Pattern password","type cookies.txt","subst X: C:\\temp","mountvol","New-PSDrive -Name X -PSProvider FileSystem -Root C:\\temp",
    "Remove-PSDrive -Name X","python script.py","echo knowledge edge","echo credentials are important","dir C:\\Users\\a\\Documents\\Cookies","powershell -Command Get-Date","where java","echo cmdkeyboard"))
   check(!hit(c,root),"negative: "+c+" -> "+SensitiveGuard.scan(c,root));
  check(has("net use Z: \\\\SRV\\Share\\sub\\dir",root,SensitiveGuard.NETDRIVE,"\\\\srv\\share")&&has("net use Z: \\\\SRV\\Share",root,SensitiveGuard.NETDRIVE,"z:"),"net use target = normalized share + drive letter");
  check(!has("net use Z: \\\\srv\\share",root,SensitiveGuard.NETDRIVE,"c:"),"drive letter only from the mapping verb");
  check(has("cmdkey /add:Server1 /user:u",root,SensitiveGuard.CREDENTIALS,"cmdkey:server1")&&has("runas /user:Admin x",root,SensitiveGuard.CREDENTIALS,"runas:admin"),"credential targets: cmdkey target and runas user");
  check(has("copy \"C:\\Users\\a\\AppData\\Local\\Google\\Chrome\\User Data\\Profile 2\\Login Data\" x",root,SensitiveGuard.BROWSER,"chrome:profile 2"),"browser store target = browser + profile");
  check(has("dir \"%LOCALAPPDATA%\\Microsoft\\Edge\\User Data\\Default\\Network\\Cookies\"",root,SensitiveGuard.BROWSER,"edge:default"),"edge profile target");
  check(!has("dir \"%LOCALAPPDATA%\\Microsoft\\Edge\\User Data\\Default\\Network\\Cookies\"",root,SensitiveGuard.BROWSER,"chrome"),"only the browser named is a target");
  check(SensitiveGuard.scan("net use Z: \\\\a\\b & cmdkey /list",root).size()==2,"two categories in one command = two hits");
  check(SensitiveGuard.scan(null,root).isEmpty()&&SensitiveGuard.scan("",root).isEmpty(),"empty command is clean");
  // encoded commands
  check(has("powershell -NoProfile -EncodedCommand "+b64("net use Z: \\\\srv\\share"),root,SensitiveGuard.NETDRIVE,"\\\\srv\\share"),"-EncodedCommand decoded");
  check(hit("powershell -enc "+b64("Get-Credential"),root)&&hit("pwsh -ec "+b64("cmdkey /list"),root)&&hit("POWERSHELL -ENCODEDCOMMAND "+b64("Get-Credential"),root),"-enc / -ec / upper case decoded");
  check(!hit("powershell -EncodedCommand "+b64("Get-Date"),root)&&!hit("powershell -EncodedCommand ####notbase64####",root),"benign or malformed encoded command is clean");
  check(hit("powershell -EncodedCommand "+b64("powershell -enc "+b64("Get-Credential")),root),"nested encoding decoded");
 }

 static void scripts(Path root)throws Exception{
  Path dir=Files.createDirectories(root.resolve("scripts"));
  Files.writeString(dir.resolve("map.ps1"),"$c = Get-Credential\nNew-PSDrive -Name Z -Root \\\\srv\\share -Persist\n");
  Files.writeString(dir.resolve("ok.ps1"),"Get-Date\n");
  Files.writeString(dir.resolve("grab.py"),"import browser_cookie3\nprint(1)\n");
  Files.writeString(dir.resolve("mount.bat"),"@echo off\nnet use Y: \\\\files\\data\n");
  Files.writeString(dir.resolve("outer.cmd"),"@echo off\ncall mount.bat\n");
  Files.writeString(dir.resolve("with space.ps1"),"cmdkey /list\n");
  byte[] body="Get-Credential\r\n".getBytes(StandardCharsets.UTF_16LE);byte[] bom=new byte[body.length+2];bom[0]=(byte)0xFF;bom[1]=(byte)0xFE;System.arraycopy(body,0,bom,2,body.length);Files.write(dir.resolve("utf16.ps1"),bom);
  check(hit("powershell -File map.ps1",dir)&&hit("powershell -ExecutionPolicy Bypass -File .\\map.ps1",dir),"script by relative path scanned");
  check(hit("powershell -File "+dir.resolve("map.ps1"),root),"script by absolute path scanned");
  check(!hit("powershell -File ok.ps1",dir)&&!hit("powershell -File missing.ps1",dir),"clean or missing script is not flagged");
  check(has("python grab.py",dir,SensitiveGuard.BROWSER,"any"),"python script reading a cookie library flagged");
  check(hit("mount.bat",dir)&&hit("cmd /c mount.bat",dir),"bare .bat by name scanned");
  check(has("outer.cmd",dir,SensitiveGuard.NETDRIVE,"\\\\files\\data"),"nested script (call mount.bat) scanned");
  check(hit("powershell -File \"with space.ps1\"",dir),"quoted path with spaces scanned");
  check(hit("powershell -File utf16.ps1",dir),"UTF-16 script with BOM scanned");
  check(!hit("powershell -File map.ps1",root.resolve("nowhere")),"relative path resolves against the working folder");
  Files.writeString(dir.resolve("loop.cmd"),"call loop.cmd\n");
  check(!hit("loop.cmd",dir),"self referencing script terminates");
  // a script the model "just wrote" (write_file) is scanned at run time
  var asked=new AtomicInteger();Tools t=tools(root,asked,Tools.Decision.YES,true);
  var w=t.execute(new ChatClient.ToolCall("w1","write_file",Json.obj("path","scripts/fresh.ps1","content","cmdkey /list\n")));
  check(!w.isError&&asked.get()==0,"writing the script itself is not the sensitive step");
  var r=run(t,"echo fresh.ps1",dir.toString());
  check(!r.isError&&asked.get()==1,"a script the model just wrote is scanned when it is run: "+r.text);
 }

 static Tools tools(Path root,AtomicInteger asked,Tools.Decision answer,boolean auto){
  return tools(root,asked,new ArrayList<>(),answer,auto);
 }
 static Tools tools(Path root,AtomicInteger asked,List<String[]> cards,Tools.Decision answer,boolean auto){
  return new Tools(new Tools.Context(root,root.resolve("skills"),root.resolve("rules.md"),root.resolve("settings.properties"),Map.of(),null,new Mcp(),PathMap.none(),auto,auto?EnumSet.allOf(Tools.Access.class):EnumSet.noneOf(Tools.Access.class),(title,d)->{asked.incrementAndGet();cards.add(new String[]{title,d});return answer;}),List.of());
 }
 static Tools.Result run(Tools t,String command,String cwd){return t.execute(new ChatClient.ToolCall("r"+System.nanoTime(),"run_command",Json.obj("command",command,"cwd",cwd)));}

 static void flow(Path root)throws Exception{
  Path work=Files.createDirectories(root.resolve("flow"));
  String cwd=work.toString();
  // plain commands never ask, in any mode
  SensitiveGuard.reset();var asked=new AtomicInteger();List<String[]> cards=new ArrayList<>();
  Tools auto=tools(work,asked,cards,Tools.Decision.YES,true);
  var plain=run(auto,"echo hello & echo Select-String mail.msg password",cwd);
  check(!plain.isError&&plain.text.contains("hello")&&asked.get()==0,"plain command and a Select-String on a .msg never ask: "+plain.text);
  // auto approval mode + every access remembered: a sensitive command still asks, once per call, and "yes" is not remembered
  var first=run(auto,"echo net use Z: \\\\srv\\share",cwd);
  check(!first.isError&&asked.get()==1,"auto/always approvals still ask for a sensitive command: asked "+asked.get()+" "+first.text);
  check(cards.get(0)[0].equals(SensitiveGuard.TITLE)&&cards.get(0)[1].contains("\\\\srv\\share")&&cards.get(0)[1].contains(I18n.text("Tinta: {0}","x").substring(0,6)),"card carries title, reason and target: "+cards.get(0)[1]);
  check(cards.get(0)[1].contains("unitate de retea")&&cards.get(0)[1].contains("net use Z:"),"card says what the command does and why it is sensitive");
  run(auto,"echo net use Z: \\\\srv\\share",cwd);
  check(asked.get()==2,"a one-time yes is not remembered");
  // always: remembered per category + target
  asked.set(0);Tools always=tools(work,asked,cards,Tools.Decision.ALWAYS,true);
  var persisted=new ArrayList<String>();SensitiveGuard.configure(true,"",()->persisted.add(SensitiveGuard.saved()));
  run(always,"echo net use Z: \\\\srv\\share",cwd);
  check(asked.get()==1&&SensitiveGuard.count()==1&&persisted.size()==1,"always is remembered and persisted: "+persisted);
  run(always,"echo net use Z: \\\\SRV\\share\\sub",cwd);
  check(asked.get()==1,"same share and drive letter (case and sub folder ignored) is already allowed");
  run(always,"echo net use Y: \\\\srv\\share",cwd);
  check(asked.get()==2,"another drive letter on the same share asks again (target = share + drive letter)");
  asked.set(0);run(always,"echo net use Z: \\\\other\\share",cwd);
  check(asked.get()==1,"a different server asks again");
  asked.set(0);run(always,"echo cmdkey /add:srv",cwd);
  check(asked.get()==1,"a different category asks again");
  run(always,"echo cmdkey /add:srv",cwd);
  check(asked.get()==1,"credential target remembered");
  run(always,"echo cmdkey /add:another",cwd);
  check(asked.get()==2,"another credential target asks again");
  Path profile=Files.createDirectories(work.resolve("AppData/Local/Google/Chrome/User Data/Default"));
  String cookie="echo \""+profile.resolve("Login Data")+"\"";
  asked.set(0);run(always,cookie,cwd);run(always,cookie,cwd);
  check(asked.get()==1,"cookie store remembered per browser profile");
  run(always,"echo \"C:\\x\\Microsoft\\Edge\\User Data\\Default\\Network\\Cookies\"",cwd);
  check(asked.get()==2,"another browser asks again");
  // persisted text has no secrets, only category|target
  String saved=SensitiveGuard.saved();
  check(saved.lines().allMatch(l->l.matches("(netdrive|credentials|browser-store)\\|[^|]+"))&&!saved.contains("pass"),"saved allowances are labels only: "+saved);
  // reload from the config text
  SensitiveGuard.configure(true,saved,null);
  asked.set(0);run(always,"echo net use Z: \\\\srv\\share",cwd);
  check(asked.get()==0&&SensitiveGuard.count()==saved.lines().count(),"allowances survive a restart (config round trip)");
  // "ask every time" ignores remembered allowances and does not remember
  SensitiveGuard.remembering(false);asked.set(0);
  run(always,"echo net use Z: \\\\srv\\share",cwd);run(always,"echo net use Z: \\\\srv\\share",cwd);
  check(asked.get()==2&&SensitiveGuard.remembering()==false,"ask-always mode ignores remembered allowances");
  var before=SensitiveGuard.count();run(always,"echo cmdkey /add:never",cwd);
  check(SensitiveGuard.count()==before,"ask-always mode does not remember new choices");
  check(cards.get(cards.size()-1)[1].contains("intreaba mereu"),"card says the choice is not remembered");
  SensitiveGuard.remembering(true);asked.set(0);
  run(always,"echo net use Z: \\\\srv\\share",cwd);
  check(asked.get()==0,"switching back to remembering uses the saved allowances again");
  // clear action
  SensitiveGuard.clear();
  check(SensitiveGuard.count()==0&&SensitiveGuard.saved().isEmpty(),"clear removes every allowance");
  run(always,"echo net use Z: \\\\srv\\share",cwd);
  check(asked.get()==1,"after clearing, the same target asks again");
  // denial: clear result, command not executed
  SensitiveGuard.reset();asked.set(0);Tools deny=tools(work,asked,cards,Tools.Decision.NO,true);
  Path marker=work.resolve("marker.txt");
  var denied=run(deny,"echo x> marker.txt & echo net use Z: \\\\srv\\share",cwd);
  check(denied.isError&&!Files.exists(marker)&&asked.get()==1,"denied command is not executed");
  check(denied.text.contains("Nu o repeta")&&denied.text.contains("nu ocoli")&&denied.text.contains("NU a fost executata"),"denial tells the model not to retry or work around: "+denied.text);
  run(deny,"echo x> marker.txt & echo net use Z: \\\\srv\\share",cwd);
  check(asked.get()==2&&SensitiveGuard.count()==0,"a denial is not remembered");
  // a script run by path asks through the same card
  Files.writeString(work.resolve("m.bat"),"@echo off\r\necho net use Q: \\\\files\\data\r\n");
  asked.set(0);Tools yes=tools(work,asked,cards,Tools.Decision.YES,true);
  var viaScript=run(yes,"m.bat",cwd);
  check(!viaScript.isError&&asked.get()==1,"script by path asks before running: "+viaScript.text);
  // read-only mode blocks without asking
  asked.set(0);Tools ro=tools(work,asked,cards,Tools.Decision.YES,true).readOnly(true);
  var blocked=run(ro,"echo net use Z: \\\\srv\\share",cwd);
  check(blocked.isError&&asked.get()==0,"read-only mode blocks it without a card");
  // read_file on a browser store asks
  Files.writeString(profile.resolve("Login Data"),"not a database");
  asked.set(0);Tools reader=tools(work,asked,cards,Tools.Decision.NO,true);
  var read=reader.execute(new ChatClient.ToolCall("rf","read_file",Json.obj("path",profile.resolve("Login Data").toString())));
  check(read.isError&&asked.get()==1,"read_file on a browser login store asks and honours a refusal: "+read.text);
  var okRead=reader.execute(new ChatClient.ToolCall("rf2","read_file",Json.obj("path",work.resolve("m.bat").toString())));
  check(!okRead.isError&&asked.get()==1,"read_file on a normal file does not ask");
  // not-auto mode: the sensitive card comes first, then the ordinary approval
  SensitiveGuard.reset();asked.set(0);cards.clear();Tools manual=tools(work,asked,cards,Tools.Decision.YES,false);
  run(manual,"echo cmdkey /list",cwd);
  check(cards.size()==2&&cards.get(0)[0].equals(SensitiveGuard.TITLE)&&!cards.get(1)[0].equals(SensitiveGuard.TITLE),"manual mode: sensitive card, then the normal run card");
  SensitiveGuard.reset();
 }

 static void i18n(){
  String[] keys={SensitiveGuard.TITLE,"Monteaza, demonteaza sau modifica o unitate de retea (net use, New-PSDrive, subst). Poate folosi credentiale si da acces la un server extern.",
   "Citeste, creeaza sau foloseste credentiale (Get-Credential, cmdkey, runas, SecureString, managerul de credentiale, DPAPI). Parolele si cheile sunt date sensibile.",
   "Acceseaza cookie-uri sau parole salvate ale unui browser (Cookies, Login Data, Local State, cookies.sqlite). Poate expune sesiuni autentificate si parole.",
   "Tinta: {0}","Citire fisier: {0}","Permite mereu pentru aceasta tinta","Comenzi sensibile: retine alegerile","Sterge aprobarile sensibile retinute ({0})","Aprobarile sensibile retinute au fost sterse.",
   "\"Permite mereu pentru aceasta tinta\" se retine numai pentru aceasta categorie si tinta; o alta tinta intreaba din nou. Se poate sterge din setari rapide.",
   "Alegerea nu se retine (setarea Comenzi sensibile este pe \"intreaba mereu\").",
   "Unitati de retea, credentiale si cookie-uri de browser cer mereu confirmare, chiar si la aprobare automata. Bifat: \"Permite mereu\" se retine per categorie si tinta. Debifat: intreaba la fiecare comanda sensibila."};
  for(String code:I18n.CODES){
   I18n.setLanguage(code);
   for(String k:keys)check(I18n.contains(k)&&(code.equals("ro")||!I18n.text(k).equals(k)),"translated "+code+": "+k.substring(0,Math.min(40,k.length())));
   if(!code.equals("ro")){
    String d=SensitiveGuard.details(List.of(new SensitiveGuard.Hit(SensitiveGuard.NETDRIVE,"\\\\srv\\share")),"cmd");
    check(!d.contains("unitate de retea")&&d.contains("\\\\srv\\share"),"card text localized for "+code);
   }
  }
  I18n.setLanguage("en");
  check(SensitiveGuard.details(List.of(new SensitiveGuard.Hit(SensitiveGuard.CREDENTIALS,"cmdkey:srv")),"cmd").contains("Target: cmdkey:srv"),"english card");
  I18n.setLanguage("ro");
 }
}