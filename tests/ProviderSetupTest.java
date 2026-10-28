package localchat;
import java.nio.file.*;import java.util.*;import java.io.*;import java.net.*;import java.awt.*;import javax.swing.*;import java.util.List;
public final class ProviderSetupTest{
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static void invoke(Object app,String method)throws Exception{FeatureTest.call(app,method,new Class<?>[]{});}
 static void facts()throws Exception{
  Properties p=new Properties();p.setProperty("a.url","https://old.invalid/v1");p.setProperty("a.model","old-model");p.setProperty("a.models","[\"old-model\"]");p.setProperty("a.catalogUrl","https://old.invalid/v1");
  ProviderModels.rememberRoute(p,"a","https://new.invalid/v1","old-model");p.setProperty("a.url","https://new.invalid/v1");
  check(ProviderModels.ids(p,"a","https://new.invalid/v1").isEmpty(),"old endpoint list is never shown on new route");
  check(ProviderModels.model(p,"a","https://new.invalid/v1").isBlank(),"inherited selection does not become a model for new route");
  check(ProviderModels.rawIds(p,"a").equals(List.of("old-model")),"source list preserved without destructive filtering");
  ProviderModels.rememberRoute(p,"a","https://new.invalid/v1","manual-new");p.setProperty("a.model","manual-new");check(ProviderModels.model(p,"a","https://new.invalid/v1").equals("manual-new"),"explicit typed model bound to new route");
  check(ProviderModels.matches("https://example.test/v1/","https://example.test/v1"),"trailing slash does not invalidate same endpoint");
  for(String host:List.of("api.x.ai","api.x.ai.evil.invalid","api.gonkarouter.io")){
   HttpURLConnection c=new HttpURLConnection(new URL("https://"+host+"/v1/models")){final Map<String,String> sent=new HashMap<>();public void setRequestProperty(String k,String v){sent.put(k,v);}public String getRequestProperty(String k){return sent.get(k);}public boolean usingProxy(){return false;}public void disconnect(){}public void connect(){}};ProviderHeaders.authenticate(c,Provider.ANTHROPIC,"synthetic-key");
   check(host.equals("api.x.ai")?"Bearer synthetic-key".equals(c.getRequestProperty("Authorization"))&&c.getRequestProperty("x-api-key")==null:"synthetic-key".equals(c.getRequestProperty("x-api-key"))&&c.getRequestProperty("Authorization")==null,"auth scoped to exact xAI host: "+host);
  }
  for(String key:List.of("grok_synthetic","crsr_synthetic")){
   try{ModelCatalog.fetch(Provider.CUSTOM,"https://api.x.ai/v1",key);throw new AssertionError("wrong service key accepted");}catch(IOException expected){check(!expected.getMessage().contains(key)&&expected.getMessage().contains("Agents"),"wrong service key is diagnosed locally without sending it");}
   ProviderHeaders.validateKey("https://api.cursor.com/v1",key);
  }
  ProviderHeaders.validateKey("https://api.x.ai/v1","new-format-not-guessed");
  for(String language:I18n.CODES){I18n.setLanguage(language);HttpURLConnection bad=new HttpURLConnection(new URL("http://127.0.0.1/")){public boolean usingProxy(){return false;}public void disconnect(){}public void connect(){}public InputStream getErrorStream(){return new ByteArrayInputStream("{\"error\":\"Incorrect API key provided: synthetic-secret\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}};
   String message=ProviderHeaders.metadataError(bad,400).getMessage();check(message.contains(I18n.text("Cheia API nu este acceptata de furnizor. Verifica cheia in configuratia acestui API."))&&!message.contains("synthetic-secret"),"localized API error never echoes credentials");
  }I18n.setLanguage("ro");
 }
 static void setup(Path home)throws Exception{
  LocalChat[] app={null};try(ModelCatalogTest.Server server=new ModelCatalogTest.Server()){
   FeatureTest.edt(()->{
    app[0]=AttachmentTest.window(home,server.url());ProviderTest.set(app[0],Provider.ANTHROPIC,server.url(),"old-router-model","old-router-key");FeatureTest.call(app[0],"saveNamedProvider",new Class<?>[]{String.class},"Router");
    Properties p=FeatureTest.field(app[0],"config");SavedProviders.Entry old=FeatureTest.field(app[0],"shownSelection");Map<String,String> original=ProviderManagementTest.subtree(p,old.prefix());
    AttachmentTest.input(app[0]).setText("keep this draft");ProviderEditor editor=new ProviderEditor();
    check(editor.url.getText().isEmpty()&&editor.key.getPassword().length==0&&editor.name.getText().isEmpty()&&editor.protocol.getSelectedItem()==Provider.CUSTOM,"new provider form does not inherit URL, key, name or Anthropic protocol");
    for(String lang:I18n.CODES)for(int theme:new int[]{0,1}){
     I18n.setLanguage(lang);if(theme==1)com.formdev.flatlaf.FlatDarkLaf.setup();else com.formdev.flatlaf.FlatLightLaf.setup();ProviderEditor panel=new ProviderEditor();check(panel.key.getEchoChar()!=0&&panel.protocol.getSelectedItem()==Provider.CUSTOM,"new provider defaults and masked key in "+lang+theme);
    }I18n.setLanguage("ro");
    FeatureTest.call(app[0],"createNamedProvider",new Class<?>[]{String.class,Provider.class,String.class,String.class},"Fresh",Provider.CUSTOM,server.url(),"new-fixture-key");
    SavedProviders.Entry fresh=FeatureTest.field(app[0],"shownSelection");
    check(!fresh.id().equals(old.id())&&fresh.protocol()==Provider.CUSTOM,"independent provider identity and protocol");
    check(ProviderManagementTest.subtree(p,old.prefix()).equals(original),"new provider leaves original configuration intact");
    check(FeatureTest.<JComboBox<?>>field(app[0],"modelBox").getSelectedItem().toString().isEmpty()&&ProviderModels.rawIds(p,fresh.prefix()).isEmpty(),"new provider starts without router models");
    check(AttachmentTest.input(app[0]).getText().equals("keep this draft")&&server.headers.isEmpty(),"creation preserves draft and does not query API");
    p.setProperty(fresh.prefix()+".probeOnLoad","false");p.setProperty(fresh.prefix()+".models","[\"old-router-model\"]");p.setProperty(fresh.prefix()+".model","old-router-model");FeatureTest.<JComboBox<String>>field(app[0],"modelBox").setSelectedItem("old-router-model");
    invoke(app[0],"saveConfig");invoke(app[0],"loadModels");
   });
   javax.swing.Timer[] closer={null};FeatureTest.edt(()->{closer[0]=new javax.swing.Timer(40,e->{for(Window w:Window.getWindows())if(w instanceof ModelCatalogDialog)w.dispose();});closer[0].start();});
   try{
    boolean[] loaded={false};for(int n=0;n<250&&!loaded[0];n++){FeatureTest.edt(()->loaded[0]=FeatureTest.<JComboBox<?>>field(app[0],"modelBox").getSelectedItem().equals("good"));if(!loaded[0])Thread.sleep(20);}check(loaded[0],"successful catalog replaces inherited model instead of preserving absent router ID");
    FeatureTest.metadataIdle(app[0]);FeatureTest.edt(()->{
     check(server.headers.stream().allMatch(h->h.startsWith("GET ")),"manual model loading with probes disabled performs GET only");
     Properties p=FeatureTest.field(app[0],"config");SavedProviders.Entry entry=FeatureTest.field(app[0],"shownSelection");
     check(p.getProperty(entry.prefix()+".modelsUrl").equals(server.url())&&p.getProperty(entry.prefix()+".modelUrl").equals(server.url()),"catalog and selected model carry endpoint provenance");
     var unknown=new ModelCatalog.Entry("unknown-chat",null,null,null,0,0,"fixture");p.setProperty(entry.prefix()+".models","[\"unknown-chat\"]");p.setProperty(entry.prefix()+".catalog",Json.write(List.of(unknown.json())));p.setProperty(entry.prefix()+".catalogUrl",server.url());FeatureTest.<JComboBox<String>>field(app[0],"modelBox").setSelectedItem("unknown-chat");invoke(app[0],"refreshDashboard");
     check(ModelSuitabilityTest.ids(FeatureTest.field(app[0],"modelBox")).contains("unknown-chat"),"MBS with no declared candidates keeps unknown chat model visible");
     var cache=new ModelCatalog.Cache(home,entry.prefix(),Provider.CUSTOM,server.url(),"new-fixture-key");
     var decision=ModelSuitability.assess(unknown,cache.get(unknown.id()),server.url(),true);check(decision.allowed()&&!decision.suggested(),"visible unknown model remains MBS unconfirmed");
     ModelCatalogDialog dialog=new ModelCatalogDialog(app[0],"fixture",List.of(unknown),cache,Provider.CUSTOM,server.url(),"",false,e->{},e->{},()->{});
     dialog.configureProfile(true,true,e->{});check(dialog.visible.size()==1&&dialog.profileOnly.isSelected(),"1.28.9: an unconfirmed model is listed under the MBS filter instead of being hidden");dialog.dispose();var declaredNo=new ModelCatalog.Entry("chat-only",null,false,null,0,0,"fixture");ModelCatalogDialog none=new ModelCatalogDialog(app[0],"fixture",List.of(declaredNo),cache,Provider.CUSTOM,server.url(),"",false,e->{},e->{},()->{});none.configureProfile(true,true,e->{});check(none.visible.size()==1&&!none.profileOnly.isSelected(),"declared tools=false only: catalog falls back to inspectable rows rather than an empty filtered screen");none.dispose();
    });
   }finally{FeatureTest.edt(()->closer[0].stop());}
  }finally{FeatureTest.edt(()->{if(app[0]!=null)app[0].dispose();});}
 }
 public static void main(String[] args)throws Exception{try{facts();setup(Path.of(System.getProperty("localchat.home")));System.out.println("PASS: "+checks+" provider creation/endpoint isolation/xAI authentication/unknown-capability checks");}finally{FeatureTest.edt(()->{for(Window w:Window.getWindows())w.dispose();});}System.exit(0);}
}
