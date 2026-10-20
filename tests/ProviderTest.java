package localchat;
import java.util.*;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.swing.*;

/** Real HTTP and UI tests; all credentials are synthetic and no inference is performed. */
public final class ProviderTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void set(LocalChat app,Provider protocol,String url,String model,String key)throws Exception {
        ((JComboBox<Object>)FeatureTest.field(app,"providerBox")).setSelectedItem(protocol);
        ((JTextField)FeatureTest.field(app,"urlField")).setText(url);((JComboBox<String>)FeatureTest.field(app,"modelBox")).setSelectedItem(model);((JPasswordField)FeatureTest.field(app,"keyField")).setText(key);
    }
    static final class Catalog implements AutoCloseable {
        final java.net.ServerSocket socket=new java.net.ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
        final List<String> requests=Collections.synchronizedList(new ArrayList<>());final List<String> headers=Collections.synchronizedList(new ArrayList<>());
        volatile Object body=Json.obj("data",List.of(Json.obj("id","model-a","context_length",131072,"max_input_tokens",120000)));
        volatile int status=200;volatile Throwable error;
        Catalog()throws Exception{Thread worker=new Thread(()->{try{while(!socket.isClosed())try(Socket c=socket.accept()){
            var reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.US_ASCII));String request=reader.readLine();requests.add(request);String line;while((line=reader.readLine())!=null&&!line.isEmpty())headers.add(line);
            int code=request.startsWith("GET /v1/models ")?status:404;byte[] data=Json.write(body).getBytes(StandardCharsets.UTF_8);var out=c.getOutputStream();out.write(("HTTP/1.1 "+code+" Fixture\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);
        }}catch(SocketException e){if(!socket.isClosed())error=e;}catch(Throwable e){error=e;}},"provider-catalog-fixture");worker.setDaemon(true);worker.start();}
        String url(){return "http://127.0.0.1:"+socket.getLocalPort()+"/v1";}
        public void close()throws Exception{socket.close();if(error!=null)throw new Exception(error);}
    }
    static void metadata()throws Exception {
        for(String field:List.of("context_length","context_window","max_context_length","max_model_len","context_window_tokens")){
            var info=ModelInfo.parse(Json.obj("data",List.of(Json.obj("id","test",field,100000,"max_output_tokens",8192))),"test","fixture");check(info.maximum()==100000&&info.loaded()==0,"declared maximum stays separate: "+field);
        }
        check(ModelInfo.parse(Json.obj("data",List.of(Json.obj("id","test","max_output_tokens",8192))),"test","fixture").maximum()==0,"output budget is never context");
        check(ModelInfo.parse(Json.obj("data",List.of(Json.obj("id","test","context_length",-1))),"test","fixture").maximum()==0,"invalid negative limit ignored");
        check(ModelInfo.parse(Json.obj("data",List.of(Json.obj("id","test","context_length","1M"))),"test","fixture").maximum()==0,"ambiguous string limits ignored");
        check(ModelInfo.recommendedBudget("https://api.gonkarouter.io/v1")==180000,"documented Gonka working budget");check(ModelInfo.recommendedBudget("https://api.gonkarouter.io.evil/v1")==0,"recommendation host boundary");check(ModelInfo.recommendedBudget("https://integrate.api.nvidia.com/v1")==0,"no invented NVIDIA context");
        try(Catalog catalog=new Catalog()){
            var info=ModelInfo.fetch(catalog.url(),"fixture-bearer","model-a",Provider.CUSTOM);check(info.maximum()==131072&&info.loaded()==0&&info.inputLimit()==120000,"external catalog fields preserved separately");
            check(catalog.headers.contains("Authorization: Bearer fixture-bearer"),"OpenAI-compatible authentication");
            catalog.body=Json.obj("data",List.of(Json.obj("id","model-a")));var unknown=ModelInfo.fetch(catalog.url(),"fixture-anthropic","model-a",Provider.ANTHROPIC);check(unknown.maximum()==0&&unknown.loaded()==0&&!unknown.source().isBlank(),"successful catalog without limits is explicitly unknown");
            check(catalog.headers.contains("x-api-key: fixture-anthropic")&&catalog.headers.contains("anthropic-version: 2023-06-01"),"Anthropic-compatible catalog headers");
            check(catalog.requests.stream().allMatch(q->q.startsWith("GET ")),"metadata never infers or loads a model");catalog.status=401;try{ModelInfo.fetch(catalog.url(),"fixture","model-a",Provider.CUSTOM);throw new AssertionError("401 accepted");}catch(IOException expected){check(expected.getMessage().contains("401"),"authentication error retained");}
        }
    }
    static void profiles(Path home)throws Exception {
        Properties values=new Properties();var a=SavedProviders.save(values,"Personal A",Provider.CUSTOM,"https://a.example/v1","fixture-a","model-a");var b=SavedProviders.save(values,"Personal B",Provider.ANTHROPIC,"https://b.example/v1","fixture-b","model-b");
        check(SavedProviders.list(values).size()==2,"saved profile enumeration");check(!a.id().equals(b.id()),"separate stable identities");check(SavedProviders.resolve(values,a.id(),Provider.OPENAI).equals(a),"saved identity resolution");check(SavedProviders.save(values,"personal a",Provider.CUSTOM,"https://a.example/v1","fixture-a2","model-a2").id().equals(a.id()),"same name updates stable identity");
        for(String url:List.of("https://key@a.example/v1","https://a.example/v1?api_key=x","file:///tmp/test"))try{SavedProviders.save(values,"Bad",Provider.CUSTOM,url,"secret","");throw new AssertionError("Bad URL accepted");}catch(IllegalArgumentException expected){checks++;}
        for(String name:List.of(" ","<html>oops"))try{SavedProviders.save(values,name,Provider.CUSTOM,"https://a.example/v1","secret","");throw new AssertionError("Bad name accepted");}catch(IllegalArgumentException expected){checks++;}
        check(SavedProviders.label(Provider.ANTHROPIC,"https://api.gonkarouter.io/v1").equals("api.gonkarouter.io"),"gateway is not mislabeled Claude");
        LocalChat[] app={null};SavedProviders.Entry[] entries=new SavedProviders.Entry[2];Path[] chats=new Path[2];
        FeatureTest.edt(()->{
            var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);app[0]=ctor.newInstance();
            set(app[0],Provider.CUSTOM,"http://127.0.0.1:1/v1","model-a","fixture-a");FeatureTest.call(app[0],"saveNamedProvider",new Class<?>[]{String.class},"My server A");entries[0]=FeatureTest.field(app[0],"shownSelection");
            FeatureTest.command(app[0],"/new Chat A");FeatureTest.call(app[0],"ensureSession",new Class<?>[]{});FeatureTest.call(app[0],"saveConversation",new Class<?>[]{});chats[0]=FeatureTest.field(app[0],"conversationFile");
            FeatureTest.command(app[0],"/new Chat B");((JTextField)FeatureTest.field(app[0],"urlField")).setText("http://127.0.0.1:2/v1");((JComboBox<String>)FeatureTest.field(app[0],"modelBox")).setSelectedItem("model-b");((JPasswordField)FeatureTest.field(app[0],"keyField")).setText("fixture-b");FeatureTest.call(app[0],"saveNamedProvider",new Class<?>[]{String.class},"My server B");entries[1]=FeatureTest.field(app[0],"shownSelection");
            Properties config=FeatureTest.field(app[0],"config");check(config.getProperty(entries[0].prefix()+".key").equals("fixture-a"),"Save As preserves previous provider key");check(config.getProperty(entries[0].prefix()+".url").endsWith(":1/v1"),"Save As preserves previous endpoint");check(config.getProperty(entries[1].prefix()+".models").equals("[\"model-b\"]"),"Save As does not inherit another endpoint model list");
            FeatureTest.call(app[0],"saveDefaults",new Class<?>[]{});FeatureTest.call(app[0],"ensureSession",new Class<?>[]{});FeatureTest.call(app[0],"saveConversation",new Class<?>[]{});chats[1]=FeatureTest.field(app[0],"conversationFile");
            FeatureTest.command(app[0],"/switch "+Conversations.shortId(chats[0]));check(entries[0].equals(FeatureTest.field(app[0],"shownSelection")),"resumed conversation restores named provider");check(new String(((JPasswordField)FeatureTest.field(app[0],"keyField")).getPassword()).equals("fixture-a"),"resumed conversation restores matching credential");
            FeatureTest.command(app[0],"/switch "+Conversations.shortId(chats[1]));check(entries[1].equals(FeatureTest.field(app[0],"shownSelection")),"second chat provider remains isolated; actual="+FeatureTest.field(app[0],"shownSelection")+"; status="+((JLabel)FeatureTest.field(app[0],"status")).getText());
            String saved=Files.readString(chats[1]);check(!saved.contains("fixture-b"),"chat JSON excludes API keys");FeatureTest.call(app[0],"saveConfig",new Class<?>[]{});app[0].dispose();app[0]=ctor.newInstance();
            check(entries[1].equals(FeatureTest.field(app[0],"shownSelection")),"restart restores explicit named default");check(new String(((JPasswordField)FeatureTest.field(app[0],"keyField")).getPassword()).equals("fixture-b"),"restart restores matching key");
            set(app[0],Provider.ANTHROPIC,"https://api.gonkarouter.io/v1","glm-fixture","fixture-gonka");FeatureTest.call(app[0],"refreshDashboard",new Class<?>[]{});check(((JButton)FeatureTest.field(app[0],"applyBudgetButton")).isVisible(),"Gonka budget action visible under Anthropic protocol");FeatureTest.call(app[0],"applyRecommendedBudget",new Class<?>[]{});check(((WorkspacePanel)FeatureTest.field(app[0],"workspace")).limit.getValue().equals(180000),"explicit budget drives context meter");check(((ContextRing)FeatureTest.field(app[0],"contextRing")).maximum==180000,"footer uses selected working budget");
            Properties live=FeatureTest.field(app[0],"config");String profile=(String)FeatureTest.call(app[0],"contextProfile",new Class<?>[]{});live.setProperty(profile+".metadata",Json.write(new ModelInfo.Info(0,0,"fixture catalog").json()));
            Path pictures=home.resolve("screenshots");Files.createDirectories(pictures);
            for(int locale=0;locale<I18n.CODES.length;locale++)for(int theme=0;theme<2;theme++){
                ((JComboBox<?>)FeatureTest.field(app[0],"languageBox")).setSelectedIndex(locale);((JComboBox<?>)FeatureTest.field(app[0],"themeBox")).setSelectedIndex(theme);FeatureTest.call(app[0],"refreshDashboard",new Class<?>[]{});app[0].addNotify();app[0].validate();
                JButton suggested=FeatureTest.field(app[0],"applyBudgetButton");JLabel description=FeatureTest.field(app[0],"modelMetadata");check(suggested.getWidth()>=suggested.getPreferredSize().width,"recommended budget fits "+I18n.CODES[locale]);check(description.getWidth()>=description.getPreferredSize().width,"context explanation fits "+I18n.CODES[locale]);
                var picture=new java.awt.image.BufferedImage(app[0].getWidth(),app[0].getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var g=picture.createGraphics();app[0].getRootPane().printAll(g);g.dispose();javax.imageio.ImageIO.write(picture,"png",pictures.resolve(I18n.CODES[locale]+(theme==1?"-dark":"-light")+".png").toFile());
            }

            app[0].dispose();
        });
        try(Catalog catalog=new Catalog()){
            catalog.body=Json.obj("data",List.of(Json.obj("id","model-a")));
            FeatureTest.edt(()->{var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);app[0]=ctor.newInstance();set(app[0],Provider.ANTHROPIC,catalog.url(),"model-a","fixture-anthropic");((WorkspacePanel)FeatureTest.field(app[0],"workspace")).limit.setValue(42000);FeatureTest.call(app[0],"inspectModel",new Class<?>[]{});});FeatureTest.metadataIdle(app[0]);
            FeatureTest.edt(()->{check(((JLabel)FeatureTest.field(app[0],"modelMetadata")).getText().equals(I18n.text("API-ul nu comunică limita de context · Buget local: {0}",42000)),"missing API context is explained in current language");check(((WorkspacePanel)FeatureTest.field(app[0],"workspace")).limit.getValue().equals(42000),"unknown metadata preserves manual working budget");app[0].dispose();});
        }
    }
    public static void main(String[] args)throws Exception{Path home=Path.of(System.getProperty("localchat.home"));Files.createDirectories(home);try{metadata();profiles(home);System.out.println("PASS: "+checks+" provider/profile and external metadata checks; no inference requests");}finally{FeatureTest.edt(()->{for(var window:java.awt.Window.getWindows())window.dispose();});}System.exit(0);}
}
