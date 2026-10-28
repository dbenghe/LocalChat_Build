package localchat;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** 1.28.9: context limits, reducer scaling, tool exposure, MBS list, Mistral, download_url, malformed-call safety. No real WeTransfer or workers.dev call. */
public final class Release1289Test {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static void reply(com.sun.net.httpserver.HttpExchange x,int status,String type,byte[] body,String... headers)throws IOException{
  x.getResponseHeaders().add("Content-Type",type);for(int i=0;i+1<headers.length;i+=2)x.getResponseHeaders().add(headers[i],headers[i+1]);
  if(body==null){x.sendResponseHeaders(status,-1);}else{x.sendResponseHeaders(status,body.length);try(OutputStream o=x.getResponseBody()){o.write(body);}}x.close();
 }

 public static void main(String[] args)throws Exception{
  Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"r1289-");
  contextLimits();reducer();relevance(home);suitability();mistral();download(home);hotfolder(home);protocol(home);capacity();i18n();
  System.out.println("PASS: "+checks+" 1.28.9 checks");
 }

 // ---- item 1
 static void contextLimits()throws Exception{
  check(ModelCatalog.Entry.nameContext("Tileward-Qwen3.6-35B")==262144&&ModelCatalog.Entry.nameContext("qwen3.5-9b")==262144&&ModelCatalog.Entry.nameContext("llama-3.1-8b")==0&&ModelCatalog.Entry.nameContext("qwen2.5-7b")==0&&ModelCatalog.Entry.nameContext(null)==0,"name rule: qwen3.5-3.9 only");
  check(new ModelCatalog.Entry("Tileward-Qwen3.6",null,null,null,0,0,"fixture").suggestedBudget("https://example.invalid/v1")==131072,"qwen3.6 without metadata: 262144 native, budget capped at 131072");
  check(new ModelCatalog.Entry("Tileward-Qwen3.6",null,null,null,32768,0,"fixture").suggestedBudget("https://example.invalid/v1")==32768,"declared maximum wins over the name rule");
  check(LocalChat.cappedLimit(200000,65536)==65536&&LocalChat.cappedLimit(32768,65536)==32768&&LocalChat.cappedLimit(200000,0)==200000&&LocalChat.cappedLimit(0,65536)==0,"manual limit is capped by the loaded context, never raised");
  String models="{\"data\":[{\"id\":\"m\"}]}",nativeBody="{\"models\":[{\"key\":\"m\",\"max_context_length\":262144,\"loaded_instances\":[{\"id\":\"m\",\"config\":{\"context_length\":65536}}]}]}";
  AtomicInteger nativeHits=new AtomicInteger(),catalogHits=new AtomicInteger();
  HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
  server.createContext("/v1/models",x->{catalogHits.incrementAndGet();reply(x,200,"application/json",bytes(models));});
  server.createContext("/api/v1/models",x->{nativeHits.incrementAndGet();reply(x,200,"application/json",bytes(nativeBody));});
  server.start();
  try{
   String wide="http://[::ffff:127.0.0.1]:"+server.getAddress().getPort()+"/v1";
   check(!ModelInfo.local(wide),"fixture host counts as non-LAN");
   ModelInfo.resetNativeMissing();
   var info=ModelInfo.fetch(wide,"","m",Provider.CUSTOM);
   check(info.loaded()==65536&&info.maximum()==262144,"non-LAN host: /v1/models without context falls through to native /api/v1/models: "+info);
   check(catalogHits.get()==1&&nativeHits.get()==1,"catalog asked first, native second");
  }finally{server.stop(0);}
  AtomicInteger missing=new AtomicInteger();
  HttpServer none=HttpServer.create(new InetSocketAddress(0),0);
  none.createContext("/v1/models",x->reply(x,200,"application/json",bytes(models)));
  none.createContext("/api/v1/models",x->{missing.incrementAndGet();reply(x,404,"text/plain",bytes("no"));});
  none.start();
  try{
   String wide="http://[::ffff:127.0.0.1]:"+none.getAddress().getPort()+"/v1";ModelInfo.resetNativeMissing();
   var a=ModelInfo.fetch(wide,"","m",Provider.CUSTOM);var b=ModelInfo.fetch(wide,"","m",Provider.CUSTOM);
   check(a.loaded()==0&&b.loaded()==0,"404 on the native endpoint is ignored");check(missing.get()==1,"native 404 is remembered: asked "+missing.get()+" time(s) for two fetches");
  }finally{none.stop(0);ModelInfo.resetNativeMissing();}
 }

 // ---- item 2
 static void reducer()throws Exception{
  var history=ContextReductionTest.loop(120);long used=ContextMemory.messages(history);int def=ToolResultReducer.apply(history).count();
  check(used>60000&&def>40,"fixture is large enough: used="+used+" default reduced="+def);
  check(ToolResultReducer.apply(history,65536).count()==def&&ToolResultReducer.apply(history,8192).count()==def&&ToolResultReducer.apply(history,0).count()==def,"limits up to 65536 (and unknown) behave exactly as before");
  int low=(int)Math.max(65537,used*10);var idle=ToolResultReducer.apply(history,low);
  check(idle.count()==0&&idle.messages().equals(history),"large context under 40% use: nothing is reduced");
  int mid=(int)Math.max(65537,used*2);var some=ToolResultReducer.apply(history,mid);
  check(some.count()>0&&some.count()<def,"large context at ~50%: reduced, but more recent results are kept ("+some.count()+" < "+def+")");
  check(ToolResultReducer.keepRecent(mid,used)==Math.min(ToolResultReducer.MAX_KEEP,6+mid/16000)&&ToolResultReducer.keepRecent(mid,used)>6,"keep grows with the context");
  long hot=used*100/(long)(used*100/85+1);int tight=(int)Math.max(65537,used*100/85);
  check(ToolResultReducer.apply(history,tight).count()==def||used*10>=tight*7L&&ToolResultReducer.apply(history,tight).count()==def,"above 70% use the small-context keep applies again ("+ToolResultReducer.apply(history,tight).count()+" vs "+def+")");
  check(ToolResultReducer.keepRecent(1_000_000,10)==ToolResultReducer.MAX_KEEP&&ToolResultReducer.keepRecent(1_000_000,800_000)==ToolResultReducer.KEEP_RECENT&&ToolResultReducer.keepRecent(32768,1)==ToolResultReducer.KEEP_RECENT,"keep: capped at 40, 6 under pressure, 6 for small contexts");
  var again=ToolResultReducer.apply(history,mid);boolean same=again.messages().size()==some.messages().size();for(int i=0;same&&i<again.messages().size();i++)same=Json.write(again.messages().get(i).toJson()).equals(Json.write(some.messages().get(i).toJson()));
  check(same,"deterministic: the same input gives the same request prefix");
 }

 // ---- item 3
 static void relevance(Path home)throws Exception{
  check(ToolRelevance.FILES.equals(ToolRelevance.group("download_url"))&&ToolRelevance.names(ToolRelevance.FILES).contains("download_url"),"download_url is a gated file tool");
  for(String text:List.of("descarca fisierul din link","download the file from this link","download de bijlage","descarga el archivo","pobierz plik","dosyayi indirin","open this safelink"))check(ToolRelevance.unlocked(List.of(),text).contains(ToolRelevance.FILES),"download wording unlocks the file tools: "+text);
  check(!ToolRelevance.unlocked(List.of(),"hello, how are you").contains(ToolRelevance.FILES),"unrelated text unlocks nothing");
  try(var server=new ModelCatalogTest.Server()){
   FeatureTest.edt(()->{
    try{
     LocalChat app=AttachmentTest.window(home,server.url());Properties cfg=FeatureTest.field(app,"config");JComboBox<String> box=FeatureTest.field(app,"modelBox");
     var entries=List.of(ModelSuitabilityTest.entry("verified",true),ModelSuitabilityTest.entry("unknown",null),ModelSuitabilityTest.entry("weak",false));
     cfg.setProperty("custom.models",Json.write(entries.stream().map(ModelCatalog.Entry::id).toList()));cfg.setProperty("custom.catalogUrl",server.url());cfg.setProperty("custom.catalog",Json.write(entries.stream().map(ModelCatalog.Entry::json).toList()));
     cfg.setProperty("tools.all","false");
     box.setSelectedItem("verified");FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});
     check(!(boolean)FeatureTest.call(app,"selectiveTools",new Class<?>[]{}),"verified-tools model: write/run tools exposed by default (no selection)");
     box.setSelectedItem("unknown");FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});
     check((boolean)FeatureTest.call(app,"selectiveTools",new Class<?>[]{}),"unknown model keeps the relevance gating");
     box.setSelectedItem("weak");FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});
     check((boolean)FeatureTest.call(app,"selectiveTools",new Class<?>[]{}),"model without tools keeps the gating");
     cfg.setProperty("tools.all","true");box.setSelectedItem("unknown");FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});
     check(!(boolean)FeatureTest.call(app,"selectiveTools",new Class<?>[]{}),"tools.all sends every tool to an unknown model");
    }catch(Exception e){throw new RuntimeException(e);}
   });
  }
 }

 // ---- item 4
 static void suitability()throws Exception{
  var unknown=ModelSuitabilityTest.entry("unknown",null);var no=ModelSuitabilityTest.entry("chat-only",false);var good=ModelSuitabilityTest.entry("good",true);
  var specific=new ModelCatalog.Entry("whisper-large-v3",null,false,null,0,0,"fixture");
  String base="https://new-free-provider.invalid/v1";var none=ModelSuitabilityTest.probe("untested",null,Long.MAX_VALUE/4);var blocked=ModelSuitabilityTest.probe("unavailable",null,0);
  check(ModelSuitability.listed(unknown,none,base,true,true)&&ModelSuitability.listed(good,none,base,true,true),"MBS filter lists unknown and tools models");
  check(!ModelSuitability.listed(no,none,base,true,true)&&!ModelSuitability.listed(specific,none,base,true,true)&&!ModelSuitability.listed(good,blocked,base,true,true),"MBS filter hides tools=false, task-specific and blocked models");
  check(ModelSuitability.listed(no,none,base,true,false),"filter off lists everything inspectable");
  check(ModelSuitability.assess(unknown,none,base,true).label().equals("MBS neconfirmat")&&!ModelSuitability.assess(unknown,none,base,true).suggested(),"unknown tools are labelled neconfirmat and not suggested");
  for(String lang:I18n.CODES)check(I18n.contains("MBS neconfirmat")&&I18n.keys(lang).contains("MBS neconfirmat"),"label translated in "+lang);
 }

 // ---- item 5
 static void mistral()throws Exception{
  var off=ThinkingKnowledge.fromRow(Json.parse("{\"id\":\"mistral-medium-latest\",\"capabilities\":{\"reasoning\":false}}"));
  var on=ThinkingKnowledge.fromRow(Json.parse("{\"id\":\"magistral-medium\",\"capabilities\":{\"reasoning\":true}}"));
  check(Boolean.FALSE.equals(off.thinks()),"Mistral capabilities.reasoning=false: does not think");
  check(on.thinks()==null||Boolean.TRUE.equals(on.thinks()),"capabilities.reasoning=true: can think (off-switch unknown)");check(on.off()==null,"no off-switch is invented for true");
  var a=ThinkingKnowledge.rule("https://api.mistral.ai/v1","mistral-medium-latest");var b=ThinkingKnowledge.rule("https://api.mistral.ai/v1","mistral-medium-3-5");
  check(a.thinks()==b.thinks()&&Objects.equals(a.off(),b.off()),"mistral-medium* shares the medium rule");
  check(a!=null&&b!=null&&ThinkingKnowledge.rule("https://example.invalid/v1","mistral-medium-latest")==null,"rule does not misfire on a foreign host");
 }

 // ---- item 6
 static Tools tools(Path root,AtomicInteger asked,Tools.Decision answer,boolean auto){
  return new Tools(new Tools.Context(root,root.resolve("skills"),root.resolve("rules.md"),root.resolve("settings.properties"),Map.of(),null,new Mcp(),PathMap.none(),auto,auto?EnumSet.allOf(Tools.Access.class):EnumSet.noneOf(Tools.Access.class),(t,d)->{asked.incrementAndGet();return answer;}),List.of());
 }
 static Tools.Result call(Tools t,Object... kv){return t.execute(new ChatClient.ToolCall("d"+System.nanoTime(),"download_url",Json.obj(kv)));}

 static void download(Path home)throws Exception{
  String link="https://eur01.safelinks.protection.outlook.com/?url=https%3A%2F%2Fwe.tl%2Ft-abc123&data=05%7C02%7Cuser%40example.com%7Cabc&sdata=xyz&reserved=0";
  check(UrlDownload.unwrapSafelink(link).equals("https://we.tl/t-abc123"),"safelink unwrapped: "+UrlDownload.unwrapSafelink(link));
  check(UrlDownload.unwrapSafelink("https://example.com/a.pdf").equals("https://example.com/a.pdf"),"normal links stay as they are");
  check(UrlDownload.unwrapSafelink("https://eur01.safelinks.protection.outlook.com/?url="+java.net.URLEncoder.encode(link,StandardCharsets.UTF_8)).equals("https://we.tl/t-abc123"),"nested safelinks unwrap");
  check(UrlDownload.browserOnly("we.tl")&&UrlDownload.browserOnly("wetransfer.com")&&UrlDownload.browserOnly("download.wetransfer.com")&&!UrlDownload.browserOnly("notwetransfer.com")&&!UrlDownload.browserOnly("we.tl.example.com"),"browser-only hosts");
  check(UrlDownload.sanitize("..\\..\\evil.txt").indexOf('\\')<0&&UrlDownload.sanitize("../x.txt").indexOf('/')<0&&!UrlDownload.sanitize("CON.txt").equalsIgnoreCase("CON.txt")&&!UrlDownload.sanitize("a:b?.txt").matches(".*[:?].*"),"file names are sanitized");

  AtomicInteger landing=new AtomicInteger(),others=new AtomicInteger();
  HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  byte[] payload=new byte[3000];new Random(7).nextBytes(payload);
  s.createContext("/file.bin",x->{others.incrementAndGet();reply(x,200,"application/octet-stream",payload,"Content-Disposition","attachment; filename=\"report.bin\"");});
  s.createContext("/redir",x->{others.incrementAndGet();reply(x,302,"text/plain",null,"Location","/file.bin");});
  s.createContext("/evil",x->reply(x,200,"application/octet-stream",payload,"Content-Disposition","attachment; filename=\"..\\..\\outside.bin\""));
  s.createContext("/landing",x->{landing.incrementAndGet();reply(x,200,"text/html; charset=utf-8",bytes("<html><a href=\"/file.bin\">download</a> password?</html>"));});
  s.createContext("/loop",x->reply(x,302,"text/plain",null,"Location","/loop"));
  s.createContext("/big",x->reply(x,200,"application/octet-stream",new byte[3*1024*1024],"Content-Disposition","attachment; filename=\"big.bin\""));
  s.createContext("/notype",x->{others.incrementAndGet();reply(x,200,"text/plain",bytes("plain text file"),"Content-Disposition","attachment; filename=\"note.txt\"");});
  s.start();String base="http://127.0.0.1:"+s.getAddress().getPort();
  try{
   Path work=Files.createDirectories(home.resolve("work"));AtomicInteger asked=new AtomicInteger();
   var t=tools(work,asked,Tools.Decision.YES,true);
   UrlDownload.allowPrivateForTests=false;
   var refused=call(t,"url",base+"/file.bin");
   check(refused.isError&&!Files.exists(work.resolve("downloads"))||refused.isError&&Optional.ofNullable(work.resolve("downloads").toFile().list()).orElse(new String[0]).length==0,"loopback/private address refused without the test switch: "+refused.text);
   check(others.get()==0,"no request reached the private address");
   UrlDownload.allowPrivateForTests=true;
   var ok=call(t,"url",base+"/redir");
   check(!ok.isError&&ok.text.contains("report.bin")&&ok.text.contains("3000")&&ok.text.contains("SHA-256"),"redirect followed and file saved: "+ok.text);
   Path saved=work.resolve("downloads").resolve("report.bin");
   var md=java.security.MessageDigest.getInstance("SHA-256");StringBuilder hex=new StringBuilder();for(byte b:md.digest(Files.readAllBytes(saved)))hex.append(String.format("%02x",b));
   check(Files.size(saved)==3000&&ok.text.toLowerCase(Locale.ROOT).contains(hex.toString()),"saved size and SHA-256 match");
   check(Optional.ofNullable(work.resolve("downloads").toFile().list((d,n)->n.endsWith(".part"))).orElse(new String[0]).length==0,"no .part left behind");
   var second=call(t,"url",base+"/file.bin");check(!second.isError&&second.text.contains("report (1).bin")&&Files.exists(work.resolve("downloads").resolve("report (1).bin")),"second download is numbered, never overwritten: "+second.text);
   var over=call(t,"url",base+"/file.bin","overwrite",true);check(!over.isError&&Files.size(saved)==3000&&!Files.exists(work.resolve("downloads").resolve("report (2).bin")),"overwrite=true replaces the file");
   String registry=Files.readString(work.resolve("hotfolder-generated.json"));check(registry.contains("report.bin"),"download registered as a generated file");
   var traversal=call(t,"url",base+"/evil");check(!traversal.isError&&!Files.exists(work.resolve("outside.bin"))&&!Files.exists(work.getParent().resolve("outside.bin")),"traversal in the file name stays inside the folder: "+traversal.text);
   var text=call(t,"url",base+"/notype");check(!text.isError&&text.text.contains("note.txt"),"attachment with a text type is a file");
   int before=others.get();var page=call(t,"url",base+"/landing");
   check(page.isError&&landing.get()==1&&others.get()==before&&page.text.contains("Nothing was downloaded")&&page.text.contains("not read or scraped"),"HTML landing page is not scraped, no further request: "+page.text);
   check(!Files.exists(work.resolve("downloads").resolve("landing")),"nothing saved for the landing page");
   var loop=call(t,"url",base+"/loop");check(loop.isError&&!loop.text.contains("IOException"),"redirect loop stops: "+loop.text);
   var big=call(t,"url",base+"/big","max_mb",1);check(big.isError&&!Files.exists(work.resolve("downloads").resolve("big.bin"))&&Optional.ofNullable(work.resolve("downloads").toFile().list((d,n)->n.startsWith("big"))).orElse(new String[0]).length==0,"size limit stops the download and leaves nothing: "+big.text);
   var outside=call(t,"url",base+"/file.bin","directory",Files.createTempDirectory("outside-").toString());check(outside.isError,"target folder outside the working folder is refused: "+outside.text);
   // permission: gated, serialized prompt, NO downloads nothing
   AtomicInteger prompts=new AtomicInteger();Path work2=Files.createDirectories(home.resolve("work2"));
   var denied=call(tools(work2,prompts,Tools.Decision.NO,false),"url",base+"/file.bin");check(denied.isError&&prompts.get()==1&&!Files.exists(work2.resolve("downloads").resolve("report.bin")),"permission refused: nothing downloaded, one prompt");
   var yes=call(tools(work2,prompts,Tools.Decision.YES,false),"url",base+"/file.bin");check(!yes.isError&&prompts.get()==2&&Files.exists(work2.resolve("downloads").resolve("report.bin")),"permission granted: downloaded");
   var ro=tools(work2,new AtomicInteger(),Tools.Decision.YES,true).readOnly(true);var blocked=call(ro,"url",base+"/file.bin","overwrite",true);check(blocked.isError,"read-only mode blocks the download: "+blocked.text);
   // WeTransfer: neutral, before any network call or prompt
   for(String wt:List.of("https://we.tl/t-abc123","https://wetransfer.com/downloads/abc/def","https://eur01.safelinks.protection.outlook.com/?url=https%3A%2F%2Fwe.tl%2Ft-abc123&data=x")){
    AtomicInteger p=new AtomicInteger();var r=call(tools(Files.createDirectories(home.resolve("work3")),p,Tools.Decision.YES,false),"url",wt);String low=r.text.toLowerCase(Locale.ROOT);
    check(r.isError&&low.contains("needs a browser")&&p.get()==0,"WeTransfer: neutral browser message, no prompt: "+wt);
    check(!low.contains("password is required")&&!low.contains("has expired")&&!low.contains("is processing")&&!low.contains("requires a password")&&!low.contains("link expired"),"WeTransfer: never claims password/expiry/processing as facts: "+r.text);
    check(low.contains("open_in_browser")&&low.contains("no private api"),"WeTransfer: offers the browser and states no private API was used");
   }
   check(!Files.exists(home.resolve("work3").resolve("downloads")),"WeTransfer: nothing created");
   // open_in_browser through the test hook
   List<String> opened=Collections.synchronizedList(new ArrayList<>());var oldOpener=UrlDownload.opener;UrlDownload.opener=u->opened.add(u.toString());
   try{
    AtomicInteger p=new AtomicInteger();var openYes=call(tools(work3(home),p,Tools.Decision.YES,false),"url","https://we.tl/t-abc123","open_in_browser",true);
    check(!openYes.isError&&opened.equals(List.of("https://we.tl/t-abc123"))&&p.get()==1&&openYes.text.contains("did not download"),"open_in_browser asks first, then opens the address: "+openYes.text);
    var openNo=call(tools(work3(home),new AtomicInteger(),Tools.Decision.NO,false),"url","https://we.tl/t-zzz","open_in_browser",true);check(openNo.isError&&opened.size()==1,"refused permission does not open the browser");
   }finally{UrlDownload.opener=oldOpener;}
   // mutation + spec
   var m=Agent.class.getDeclaredMethod("isMutation",String.class);m.setAccessible(true);check((boolean)m.invoke(null,"download_url")&&!(boolean)m.invoke(null,"read_file"),"download_url is serialized as a mutation");
   var spec=tools(work,new AtomicInteger(),Tools.Decision.YES,true).specs().stream().filter(x->x.name.equals("download_url")).findFirst().orElseThrow();String d=spec.description.toLowerCase(Locale.ROOT);
   check(d.contains("wetransfer")&&d.contains("never")&&d.contains("private")&&d.contains("password"),"tool description carries the skill-independent WeTransfer/private-API rule");
   check(d.length()<1500,"tool description stays compact: "+d.length());
  }finally{UrlDownload.allowPrivateForTests=false;s.stop(0);}
 }
 static Path work3(Path home)throws IOException{return Files.createDirectories(home.resolve("work3"));}

 // ---- run_command hotfolder note
 static void hotfolder(Path home)throws Exception{
  for(String c:List.of("dir","type a.txt","echo hello","git status","git log --oneline -5","ipconfig /all","dir | findstr x","where java","python --version"))check(GeneratedFiles.readOnlyCommand(c),"read-only: "+c);
  for(String c:List.of("echo x > a.txt","copy a b","del a","mkdir x","git commit -m x","git checkout .","python script.py","dir & del a","type a | more > b","echo %PATH%","powershell -c x","git config user.name x"))check(!GeneratedFiles.readOnlyCommand(c),"not read-only: "+c);
   String reason=Tools.inventoryNote(new IOException("Invalid output registry"));
  check(reason.contains("Invalid output registry")&&!reason.contains("IOException"),"a real registry failure keeps a readable one-line reason: "+reason);
  check(!Tools.inventoryNote(new IOException()).isBlank(),"empty message still readable");
  Path big=Files.createDirectories(home.resolve("big"));for(int i=0;i<10005;i++)Files.writeString(big.resolve("f"+i+".txt"),"x");
  var t=tools(big,new AtomicInteger(),Tools.Decision.YES,true);
  var read=t.execute(new ChatClient.ToolCall("c1","run_command",Json.obj("command","dir /b a.none","cwd",big.toString())));
  check(!read.text.contains("Nu pot inregistra")&&!read.text.contains("10000"),"read-only command in a huge folder: no hotfolder note: "+read.text);
  long started=System.currentTimeMillis();
  var write=t.execute(new ChatClient.ToolCall("c2","run_command",Json.obj("command","mkdir newdir","cwd",big.toString())));
  check(!write.text.contains("Nu pot inregistra")&&!write.text.contains("IOException")&&!write.text.contains("10000")&&Files.isDirectory(big.resolve("newdir")),"mutating command in a huge folder runs with no hotfolder note: "+write.text);
  var redirect=t.execute(new ChatClient.ToolCall("c3","run_command",Json.obj("command","echo hi>huge-out.csv","cwd",big.toString())));
  check(!redirect.text.contains("Nu pot inregistra")&&Files.exists(big.resolve("huge-out.csv")),"declared output in a huge folder is still registered without a walk: "+redirect.text);
  check(System.currentTimeMillis()-started<20000,"no folder inventory per command");
  // helper/temp names never notify, real sources still do
  for(String n:List.of("_fix_j_drive.csv","_tmp.xlsx","temp.csv","tmp-data.csv","Bronnen_wetl.csv","x.pdf.tmp","Bronnen_a/doc.pdf","_work/doc.pdf","tmp/doc.pdf","a/__pycache__/x.pdf"))check(!Hotfolder.source(Path.of(n)),"helper name is not a source: "+n);
  for(String n:List.of("Plan.pdf","mail.msg","Bronnen/doc.pdf","temporary.pdf","template.xlsx","Importlijst/test.xlsx"))check(Hotfolder.source(Path.of(n)),"real source stays visible: "+n);
  Path sources=Files.createDirectories(home.resolve("hf-sources")),project=Files.createDirectories(sources.resolve("123456"));
  Files.writeString(project.resolve("old.eml"),"old");Hotfolder watcher=new Hotfolder(home.resolve("hf-state.json"),30);check(watcher.scan(sources,0).isEmpty(),"baseline");
  Files.writeString(project.resolve("_download_wetl_v2.csv"),"helper");Files.writeString(project.resolve("temp.csv"),"helper");Files.createDirectories(project.resolve("Bronnen_x"));Files.writeString(project.resolve("Bronnen_x/a.pdf"),"helper");Files.writeString(project.resolve("_fix_j_drive.ps1"),"helper");
  watcher.scan(sources,100);check(watcher.scan(sources,300).isEmpty(),"agent helper files never notify");
  Files.writeString(project.resolve("customer.pdf"),"real");watcher.scan(sources,400);var seen=watcher.scan(sources,600);check(seen.size()==1&&seen.get(0).files().equals(List.of("customer.pdf")),"a real source still notifies, alone: "+seen);
  // write detection beyond redirection and open(): exact declared destinations only, no folder walk
  Path reg=home.resolve("gen-registry.json"),work=Files.createDirectories(home.resolve("gen-work"));
  String[][] cases={{"echo hi >> o1.txt","o1.txt"},{"powershell -c \"Set-Content -Path o2.txt -Value hi\"","o2.txt"},{"powershell -c \"'x' | Out-File -FilePath o3.txt\"","o3.txt"},{"powershell -c \"Set-Content o4.txt hi\"","o4.txt"},{"powershell -c \"Invoke-WebRequest https://example.invalid/y -OutFile o5.zip\"","o5.zip"},{"curl -L -o o6.pdf https://example.invalid/y","o6.pdf"},{"powershell -c \"[IO.File]::WriteAllText('o7.csv','x')\"","o7.csv"},{"python -c \"from pathlib import Path; Path('o8.csv').write_text('x')\"","o8.csv"},{"powershell -c \"Export-Csv -Path o10.csv\"","o10.csv"}};
  for(String[] c:cases){try(var scope=GeneratedFiles.command(reg,work,c[0])){Files.writeString(work.resolve(c[1]),"x");}check(GeneratedFiles.load(reg).containsKey(GeneratedFiles.key(work.resolve(c[1]))),"declared output registered: "+c[0]);}
  Path script=work.resolve("_job.ps1");Files.writeString(script,"Set-Content -Path o9.csv -Value x");GeneratedFiles.mark(reg,List.of(script));
  try(var scope=GeneratedFiles.command(reg,work,"powershell -File _job.ps1")){Files.writeString(work.resolve("o9.csv"),"x");}
  check(GeneratedFiles.load(reg).containsKey(GeneratedFiles.key(work.resolve("o9.csv"))),"output declared inside a script LocalChat wrote is registered");
  Files.writeString(work.resolve("in1.csv"),"before");
  try(var scope=GeneratedFiles.command(reg,work,"powershell -c \"Get-Content -Path in1.csv\"")){Files.writeString(work.resolve("in1.csv"),"changed by the user");}
  check(!GeneratedFiles.load(reg).containsKey(GeneratedFiles.key(work.resolve("in1.csv"))),"a read source is never registered as generated");
 }

 // ---- steering: malformed text tool calls
 static Agent.Ui ui(AtomicInteger executed,AtomicInteger malformed,AtomicInteger unverified){return new Agent.Ui(){public void text(String t){}public void step(){}public void toolStarted(ChatClient.ToolCall c){executed.incrementAndGet();}public void toolFinished(ChatClient.ToolCall c,Tools.Result r){}public void malformedNotice(int n){malformed.incrementAndGet();}public void unverifiedReply(){unverified.incrementAndGet();}};}
 @SuppressWarnings("unchecked") static List<ChatClient.Message> added(Agent a)throws Exception{return (List<ChatClient.Message>)FeatureTest.field(a,"added");}
 static void protocol(Path home)throws Exception{
  Files.writeString(home.resolve("fixture.txt"),"verified fixture");
  String bad="<think><minimax:tool_call><invoke name=\"run_command\"><parameter name=\"command\">echo not executed</parameter></invoke></minimax:tool_call> The transfer requires a password and has expired.";
  String call=Json.write(Json.obj("choices",List.of(Json.obj("message",Json.obj("content","","tool_calls",List.of(Json.obj("id","native","type","function","function",Json.obj("name","read_file","arguments",Json.write(Json.obj("path","fixture.txt"))))))))));
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer(bad,5,3)),FeatureTest.json(call),FeatureTest.json(FeatureTest.answer("Final checked",8,3)))){
   var a=new Agent(ToolProtocolTest.tools(home));AtomicInteger ex=new AtomicInteger(),mal=new AtomicInteger(),unv=new AtomicInteger();
   a.run(Provider.CUSTOM,server.url(),"","arbitrary-model","fixture","system",List.of(ChatClient.Message.user("Download it")),false,ui(ex,mal,unv));
   String body=Json.write(server.requests.get(1));
   check(body.contains("NO tool ran")&&body.contains("No text/XML request was executed"),"repair instruction says no tool ran");
   check(body.contains("NEVERIFICAT")&&!body.contains("requires a password")&&!body.contains("has expired"),"the stored malformed reply keeps a visible mark and drops the invented 'results'");
   check(mal.get()==1&&unv.get()==0&&ex.get()==1,"one user notice; a real tool ran afterwards so the final answer is not marked");
   check(added(a).get(added(a).size()-1).content.equals("Final checked"),"final answer after a real tool is unmarked");
  }
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer(bad,5,3)),FeatureTest.json(FeatureTest.answer("The transfer needs a password.",8,3)))){
   var a=new Agent(ToolProtocolTest.tools(home));AtomicInteger ex=new AtomicInteger(),mal=new AtomicInteger(),unv=new AtomicInteger();
   a.run(Provider.CUSTOM,server.url(),"","arbitrary-model","fixture","system",List.of(ChatClient.Message.user("Download it")),false,ui(ex,mal,unv));
   var last=added(a).get(added(a).size()-1);
   check(ex.get()==0&&mal.get()==1&&unv.get()==1,"final text after a malformed call without any tool run: notice + unverified callback");
   check(last.content.startsWith("[NEVERIFICAT")&&last.content.contains("The transfer needs a password."),"final text is prefixed as unverified, content kept for the user");
  }
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("Plain answer without markup",5,3)))){
   var a=new Agent(ToolProtocolTest.tools(home));AtomicInteger ex=new AtomicInteger(),mal=new AtomicInteger(),unv=new AtomicInteger();
   a.run(Provider.CUSTOM,server.url(),"","arbitrary-model","fixture","system",List.of(ChatClient.Message.user("Hi")),false,ui(ex,mal,unv));
   check(mal.get()==0&&unv.get()==0&&added(a).get(0).content.equals("Plain answer without markup"),"normal replies are untouched");
  }
  check(ToolProtocol.markUnverified("x").startsWith("[NEVERIFICAT")&&ToolProtocol.markMalformed(ToolProtocolTest.BAD).contains("NEVERIFICAT")&&ToolProtocol.beforeMarkup("Hello <minimax:tool_call>x").equals("Hello"),"marking helpers");
 }

 // ---- six languages
 // ---- 429/503 vs context (t56u)
 static void capacity()throws Exception{
  // the provider-saturation hint never blames or mentions the context threshold as a cause and leads with retry
  String ro=LocalChat.CAPACITY_HINT;
  check(ro.contains("saturat")&&ro.contains("nu contextul")&&ro.indexOf("Reincearca")<ro.indexOf("alege alt model"),"429/503 hint: provider saturated, not context, retry first, model switch last");
  check(!ro.contains("70%")&&!ro.contains("prag de context sau"),"429/503 hint is not tied to the context threshold");
  check(LocalChat.capacityStatus("http 429: x")==429&&LocalChat.capacityStatus("http 503: x")==503&&LocalChat.capacityStatus("http 500: x")==0,"capacity status detection");
  for(String k:List.of("Furnizorul este saturat (HTTP {0}); contextul nu este plin. Reincearca peste putin timp sau alege optional alt furnizor sau model. Selectia nu trimite mesajul; limitele contului pot fi comune.",
    "Conversatia curenta ramane ({0} din context): reincarcarea sau reselectarea modelului nu o goleste. Foloseste /compact sau Conversatie noua pentru a reduce procentul."))
   for(String lang:I18n.CODES){check(I18n.keys(lang).contains(k),"capacity key in "+lang);}
  check(InferenceRetry.retryable(429)&&InferenceRetry.retryable(503)&&!InferenceRetry.retryable(401)&&!InferenceRetry.retryable(400),"retry set: 429 and 503 yes, auth and request errors no");
  // at >= the 70% threshold ContextGuard compacts silently (a summary request) and never fails with a model-switch message
  try(var server=new ContextGuardTest.Server(null)){
   var ui=new ContextGuardTest.Ui();var guard=new ContextGuard(8192,1024,true,70,1.25);
   var active=guard.prepare(Provider.CUSTOM,server.url(),"","fixture","system",ContextGuardTest.material(),List.of(),1,ui);
   check(ui.summaries>0&&active.size()<ContextGuardTest.material().size()+1,"at >= 70% the guard compacts instead of asking for another model");
  }
 }
 static void i18n()throws Exception{
  List<String> keys=List.of("[NEVERIFICAT: apelurile de unelte din acest raspuns au fost scrise ca text si NU au fost executate; rezultatele descrise dupa ele nu sunt reale.]",
   "[NEVERIFICAT: raspunsul urmeaza unui apel de unealta scris ca text si neexecutat, iar dupa aceea nicio unealta nu a rulat; faptele de mai jos nu sunt confirmate.]",
   "Deschidere in browserul implicit","Fisierul nu este descarcat de LocalChat; il descarci tu in browser.","Folderul tinta trebuie sa fie in folderul de lucru: {0}","Descarcare din URL","link Outlook dezambalat","In folderul: {0}","MBS neconfirmat");
  String old=I18n.language();
  try{for(String lang:I18n.CODES){
   for(String k:keys)check(I18n.keys(lang).contains(k),"key present in "+lang+": "+k.substring(0,Math.min(30,k.length())));
   check(I18n.keys(lang).size()==I18n.keys("ro").size(),"same key count in "+lang);
   I18n.setLanguage(lang);String mark=I18n.text(keys.get(0));check(!mark.isBlank()&&mark.startsWith("[")&&(lang.equals("ro")||!mark.equals(keys.get(0))),"malformed mark translated in "+lang+": "+mark);
   check(lang.equals("ro")||!I18n.text(keys.get(1)).equals(keys.get(1)),"unverified mark translated in "+lang);
  }}finally{I18n.setLanguage(old);}
 }
}