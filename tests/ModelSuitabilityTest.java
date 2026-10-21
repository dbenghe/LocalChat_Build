package localchat;
import java.nio.file.*;import java.util.*;import javax.swing.*;
/** Offline profile policy and actual selector/catalog integration; no external inference. */
public final class ModelSuitabilityTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static ModelCatalog.Probe probe(String state,Boolean tools,long age){return new ModelCatalog.Probe(state,0,0,System.currentTimeMillis()-age,TokenUsage.UNKNOWN,tools);}
 static ModelCatalog.Entry entry(String id,Boolean tools){return new ModelCatalog.Entry(id,null,tools,null,0,0,"fixture");}
 static void policy(){
  String base="https://new-free-provider.invalid/v1";var unknown=probe("untested",null,Long.MAX_VALUE/4);
  for(String name:List.of("meta-llama/llama-prompt-guard-2-86m","openai/gpt-oss-safeguard-20b","whisper-large-v3","canopylabs/orpheus-v1-english","nvidia/nv-embedqa-mistral-7b-v2","nvidia/nemotron-parse-2.0","nvidia/riva-translate-4b-instruct-v2"))for(boolean mbs:new boolean[]{true,false})check(!ModelSuitability.assess(entry(name,true),unknown,base,mbs).allowed(),"specialized family excluded from general chat "+name);
  for(String task:List.of("embedding","text-to-speech","automatic-speech-recognition","moderation","reranking")){var e=ModelCatalog.entry(Json.obj("id","opaque-server-id","task",task),base);check(!ModelSuitability.assess(e,unknown,base,false).allowed(),"opaque specialized API metadata "+task);check(ModelCatalog.Entry.from(e.json()).purpose().equals(task),"purpose round trip");}
  var explicitChat=ModelCatalog.entry(Json.obj("id","private/whisper-chat","supports_chat",true,"supports_tool_calling",true),base);
  check(ModelSuitability.assess(explicitChat,unknown,base,true).suggested(),"explicit generic chat metadata overrides ambiguous family name");
  check(Boolean.TRUE.equals(ModelCatalog.Entry.from(explicitChat.json()).chat()),"explicit chat support survives persistence");
  var cachedChat=new Properties();cachedChat.setProperty("future.catalogUrl",base);cachedChat.setProperty("future.catalog",Json.write(List.of(explicitChat.json())));
  check(Boolean.TRUE.equals(ModelCatalog.saved(cachedChat,"future",base,List.of(explicitChat.id())).get(0).chat()),"explicit chat support survives cached catalog merge");
  var explicitAudio=ModelCatalog.entry(Json.obj("id","private/whisper-chat","supports_chat",true,"supports_tool_calling",true,"task","text-to-speech"),base);
  check(!ModelSuitability.assess(explicitAudio,unknown,base,false).allowed(),"explicit specialized task wins over generic chat flag");
  var explicitNoChat=ModelCatalog.entry(Json.obj("id","opaque","capabilities",Json.obj("chat",false,"tools",true)),base);
  check(!ModelSuitability.assess(explicitNoChat,unknown,base,false).allowed(),"nested explicit chat false blocks nonchat model");
  var u=entry("unrecognized-chat",null);check(!ModelSuitability.assess(u,unknown,base,true).suggested()&&ModelSuitability.assess(u,unknown,base,false).suggested(),"unknown tools are not guessed; Normal available");
  var no=entry("chat-only",false);check(!ModelSuitability.assess(no,unknown,base,true).allowed()&&ModelSuitability.assess(no,unknown,base,false).allowed(),"declared tools false delimits MBS and Normal");
  check(ModelSuitability.assess(u,probe("responded",true,0),base,true).suggested(),"verified tools enable opaque future model");
  for(String status:List.of("rate","transport","request","temporary","pending"))check(ModelSuitability.assess(entry("tools-model",true),probe(status,null,0),base,true).suggested(),"temporary failures never mark model weak "+status);
  for(boolean mbs:new boolean[]{true,false}){check(!ModelSuitability.assess(u,probe("unavailable",null,0),base,mbs).allowed(),"account availability separate");check(ModelSuitability.assess(u,probe("unavailable",null,25*3600000L),base,mbs).allowed(),"expired unavailability does not block");}
  var muse=entry("meta/muse-glimmer-30b",true);check(!ModelSuitability.assess(muse,unknown,"https://integrate.api.nvidia.com/v1",true).suggested()&&ModelSuitability.assess(muse,unknown,"https://integrate.api.nvidia.com/v1",false).suggested(),"review advisory is MBS-only");check(ModelSuitability.assess(muse,unknown,"https://integrate.api.nvidia.com.evil/v1",true).suggested(),"review exact host boundary");
  for(String host:List.of("https://api.groq.com/openai/v1","https://api.tileward.com/v1")){String id=host.contains("groq")?"qwen/qwen3.8-27b":"Tileward-Qwen3.8-27b";check(Boolean.TRUE.equals(ModelCatalog.entry(Json.obj("id",id),host).tools()),"documented provider tools");check(Boolean.FALSE.equals(ModelCatalog.entry(Json.obj("id",id,"supports_tool_calling",false),host).tools()),"API false preserved");check(ModelCatalog.entry(Json.obj("id",id),base).tools()==null,"host isolation for documentation");}
 }
 static void probeSelection(Path home)throws Exception{
  var cache=new ModelCatalog.Cache(home,"probe-selection",Provider.CUSTOM,"http://127.0.0.1:1/v1","");var e=entry("opaque-chat",null);long now=System.currentTimeMillis();
  cache.put(e.id(),probe("responded",null,0));
  check(ModelCatalogDialog.needsProbe(e,cache,true,now),"prior successful text response does not replace MBS tool probe");
  check(!ModelCatalogDialog.needsProbe(e,cache,false,now),"Normal keeps successful plain response cached");
  cache.putTool(e.id(),probe("responded",true,0));check(!ModelCatalogDialog.needsProbe(e,cache,true,now),"fresh successful tool probe not repeated");
  cache.putTool(e.id(),probe("responded",true,25*3600000L));check(ModelCatalogDialog.needsProbe(e,cache,true,now),"expired tool probe selected again");
  cache.put(e.id(),probe("unavailable",null,0));check(!ModelCatalogDialog.needsProbe(e,cache,true,now),"fresh account block prevents automatic tool probe");
  cache.put(e.id(),probe("unavailable",null,25*3600000L));check(ModelCatalogDialog.needsProbe(e,cache,true,now),"expired account block may be retested");
  for(String state:List.of("rate","transport","temporary","request","pending")){cache.put(e.id(),probe(state,null,0));check(!ModelCatalogDialog.needsProbe(e,cache,true,now),"recent plain failure enforces tool cooldown "+state);cache.put(e.id(),probe(state,null,61000));check(ModelCatalogDialog.needsProbe(e,cache,true,now),"plain cooldown expires "+state);cache.put(e.id(),probe("responded",null,0));cache.putTool(e.id(),probe(state,null,0));check(!ModelCatalogDialog.needsProbe(e,cache,true,now),"recent tool failure enforces cooldown "+state);cache.putTool(e.id(),probe(state,null,61000));check(ModelCatalogDialog.needsProbe(e,cache,true,now),"tool cooldown expires "+state);}
  check(!ModelCatalogDialog.needsProbe(entry("chat-only",false),cache,true,now),"automatic MBS tests skip explicitly absent tools");
  check(!ModelCatalogDialog.needsProbe(entry("whisper-large-v3",null),cache,true,now),"specialized family not automatically tool-tested");
 }
 @SuppressWarnings("unchecked") static List<String> ids(JComboBox<String> box){var out=new ArrayList<String>();for(int i=0;i<box.getItemCount();i++)out.add(box.getItemAt(i));return out;}
 static void ui(Path home)throws Exception{
  try(var server=new ModelCatalogTest.Server()){
   FeatureTest.edt(()->{
    LocalChat app=AttachmentTest.window(home,server.url());Properties cfg=FeatureTest.field(app,"config");JComboBox<String> box=FeatureTest.field(app,"modelBox");JCheckBox tools=FeatureTest.field(app,"toolsBox");
    var entries=List.of(entry("tools-good",true),entry("chat-only",false),entry("unknown",null),entry("whisper-large-v3",false));
    cfg.setProperty("custom.models",Json.write(entries.stream().map(ModelCatalog.Entry::id).toList()));cfg.setProperty("custom.catalogUrl",server.url());cfg.setProperty("custom.catalog",Json.write(entries.stream().map(ModelCatalog.Entry::json).toList()));box.setSelectedItem("tools-good");AttachmentTest.input(app).setText("unsent private draft");String all=cfg.getProperty("custom.models");
    ModelFavorites.toggle(cfg,"custom",server.url(),"chat-only");
    for(String lang:I18n.CODES)for(int theme:new int[]{0,1}){
     ((JComboBox<?>)FeatureTest.field(app,"languageBox")).setSelectedIndex(Arrays.asList(I18n.CODES).indexOf(lang));((JComboBox<?>)FeatureTest.field(app,"themeBox")).setSelectedIndex(theme);
     tools.setSelected(true);FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});check(ids(box).equals(List.of("tools-good")),"MBS filtered selector "+lang+theme);
     tools.setSelected(false);FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});check(ids(box).containsAll(List.of("tools-good","chat-only","unknown"))&&!ids(box).contains("whisper-large-v3"),"Normal general-chat selector "+lang+theme);
     check(box.getSelectedItem().equals("tools-good")&&AttachmentTest.input(app).getText().equals("unsent private draft"),"selection/draft preserved "+lang+theme);
     var label=(JLabel)box.getRenderer().getListCellRendererComponent(new JList<>(),"chat-only",1,false,false);check(label.getText().contains(I18n.text("Disponibil in Normal")),"localized renderer "+lang+theme);
    }
    check(cfg.getProperty("custom.models").equals(all)&&ModelFavorites.contains(cfg,"custom",server.url(),"chat-only"),"filter never prunes saved catalog/favorites");check(server.bodies.isEmpty(),"profile/theme/language switches never infer");
    tools.setSelected(true);box.setSelectedItem("chat-only");FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});check(ids(box).contains("chat-only")&&box.getSelectedItem().equals("chat-only"),"unsuitable current selection kept honestly");AttachmentTest.input(app).setText("draft must survive blocked send");FeatureTest.call(app,"send",new Class<?>[]{});check(server.bodies.isEmpty()&&AttachmentTest.input(app).getText().equals("draft must survive blocked send"),"MBS blocks no-tools model before API and preserves draft");
    var cache=new ModelCatalog.Cache(home,"profile-fixture",Provider.CUSTOM,server.url(),"");var dialog=new ModelCatalogDialog(app,"fixture",entries,cache,Provider.CUSTOM,server.url(),"",false,e->{},v->{},()->{});dialog.configureProfile(true,true,v->{});check(dialog.visible.size()==1&&dialog.selected().id().equals("tools-good"),"MBS catalog matches selector");dialog.profileOnly.doClick();check(dialog.visible.size()==4,"all entries inspectable");
    int row=0;for(int i=0;i<dialog.visible.size();i++)if(dialog.visible.get(i).id().equals("whisper-large-v3"))row=i;dialog.table.setRowSelectionInterval(row,row);check(!dialog.choose.isEnabled()&&!dialog.toolsTest.isEnabled()&&!dialog.selectedTest.isEnabled(),"specialist cannot be used/tested as general chat");
    cache.putTool("unknown",probe("responded",true,0));dialog.profileOnly.doClick();check(dialog.visible.size()==2,"explicit verified tool result promotes new model");dialog.dispose();app.dispose();
   });
  }
 }
 public static void main(String[] args)throws Exception{try{policy();probeSelection(Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"probes-"));ui(Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"run-"));System.out.println("PASS: "+checks+" profile model checks; metadata, account isolation, selectors, six languages/themes, no inference or data loss");}finally{FeatureTest.edt(()->{for(var w:java.awt.Window.getWindows())w.dispose();});}System.exit(0);}
}
