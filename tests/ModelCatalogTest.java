package localchat;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import javax.swing.*;

/** Synthetic catalogs and HTTP inference fixtures. No user files or external API calls. */
public final class ModelCatalogTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static final class Server implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        final List<Object> bodies=Collections.synchronizedList(new ArrayList<>());final List<String> headers=Collections.synchronizedList(new ArrayList<>());volatile Throwable error;
        final ExecutorService pool=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
        Server()throws Exception{pool.submit(()->{try{while(!socket.isClosed()){Socket c=socket.accept();pool.submit(()->serve(c));}}catch(SocketException e){if(!socket.isClosed())error=e;}catch(Exception e){error=e;}});}
        void serve(Socket c){try(c){c.setSoTimeout(3000);var input=c.getInputStream();var header=new ByteArrayOutputStream();int b;while((b=input.read())>=0){header.write(b);if(header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;}
            String request=header.toString(StandardCharsets.US_ASCII);headers.add(request);int length=0;for(String line:request.split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).strip());Object root=length==0?Json.obj():Json.parse(new String(input.readNBytes(length),StandardCharsets.UTF_8));bodies.add(root);String model=Objects.toString(Json.str(root,"model"),"good");
            if(model.equals("slow"))Thread.sleep(200);
            int status=switch(model){case "bad"->404;case "auth"->401;case "rate"->429;case "invalid"->400;case "server-error"->500;default->200;};
            Object body;if(request.startsWith("GET "))body=Json.obj("data",List.of(Json.obj("id","good","context_length",131072,"capabilities",Json.obj("vision",true,"tools",true,"reasoning",false)),Json.obj("id","bad")));
            else if(model.equals("error-body"))body=Json.obj("error","invalid response envelope");
            else if(status!=200)body=Json.obj("error","account-sensitive-text-must-not-be-stored");
            else if(request.startsWith("POST /v1/messages"))body=Json.obj("model",model,"content",List.of(Json.obj("type","text","text","OK")),"usage",Json.obj("input_tokens",18,"output_tokens",2));
            else{Object message=Json.obj("content",model.equals("empty")?"":"OK");if(Json.get(root,"tools") instanceof List)message=Json.obj("tool_calls",List.of(Json.obj("id","call_fixture","type","function","function",Json.obj("name","localchat_probe","arguments","{\"value\":\"OK\"}"))));body=Json.obj("model",model.equals("alias")?"other-model":model,"choices",List.of(Json.obj("message",message)),"usage",Json.obj("prompt_tokens",18,"completion_tokens",2));}
            byte[] data=Json.write(body).getBytes(StandardCharsets.UTF_8);boolean broken=model.equals("broken");var out=c.getOutputStream();out.write(("HTTP/1.1 "+status+" Fixture\r\nContent-Type: application/json\r\nContent-Length: "+(broken?data.length+100:data.length)+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(broken?"{".getBytes(StandardCharsets.UTF_8):data);
        }catch(IOException e){/* cancellation can close a socket */}catch(Throwable e){error=e;}}
        String url(){return "http://127.0.0.1:"+socket.getLocalPort()+"/v1";}
        public void close()throws Exception{socket.close();pool.shutdownNow();if(error!=null)throw new Exception(error);}
    }
    static void facts(){
        var documented=ModelCatalog.entry(Json.obj("id","deepseek-ai/deepseek-v4.1-flash"),"https://integrate.api.nvidia.com/v1");check(documented.maximum()==0&&documented.documentedMaximum()==1048576,"published model context never becomes API route context");check(documented.vision()&&documented.tools()&&documented.reasoning(),"documented badges are sourced");check(documented.suggestedBudget("https://integrate.api.nvidia.com/v1")==131072,"NVIDIA budget is a conservative local choice");
        var muse=ModelCatalog.entry(Json.obj("id","meta/muse-glimmer-30b"),"https://integrate.api.nvidia.com/v1");check(muse.vision()&&muse.tools()&&muse.reasoning()&&muse.documentedLabel().equals("131072+"),"Muse badges and documentary lower-bound label preserved");
        Properties prior=new Properties();prior.setProperty("nvidia.catalogUrl","https://integrate.api.nvidia.com/v1");prior.setProperty("nvidia.catalog",Json.write(List.of(new ModelCatalog.Entry("meta/muse-glimmer-30b",null,null,null,0,0,"old cache").json())));var refreshed=ModelCatalog.saved(prior,"nvidia","https://integrate.api.nvidia.com/v1",List.of("meta/muse-glimmer-30b")).get(0);check(refreshed.vision()&&refreshed.tools()&&refreshed.documentedLabel().equals("131072+"),"new reviewed facts enrich older cached catalogs");
        var unknown=ModelCatalog.entry(Json.obj("id","vision-tools-thinking"),"https://example.test/v1");check(unknown.vision()==null&&unknown.tools()==null&&unknown.reasoning()==null,"no capability guessing from model names");
        check(ModelCatalog.entry(Json.obj("id","deepseek-ai/deepseek-v4.1-flash"),"https://integrate.api.nvidia.com.evil/v1").documentedMaximum()==0,"source facts obey exact host boundary");
        var explicit=ModelCatalog.entry(Json.obj("id","deepseek-ai/deepseek-v4.1-flash","capabilities",Json.obj("tools",false)),"https://integrate.api.nvidia.com/v1");check(Boolean.FALSE.equals(explicit.tools()),"API capability false is not overwritten by documentation");
        var api=ModelCatalog.entry(Json.obj("id","a","architecture",Json.obj("input_modalities",List.of("text","image")),"supported_parameters",List.of("tools","reasoning_effort"),"context_length",90000),"https://api.test/v1");check(api.vision()&&api.tools()&&api.reasoning()&&api.maximum()==90000,"catalog capability structures parsed");
        check(ModelCatalog.parse(Json.obj("data",List.of(Json.obj("id","a"),Json.obj("id","a"))),"https://api.test/v1").size()==1,"duplicate catalog IDs eliminated");
        var usage=TokenUsage.parse(Json.obj("usage",Json.obj("input_tokens",23,"output_tokens",4)),false,TokenUsage.UNKNOWN,true);check(usage.input()==23&&usage.output()==4&&usage.complete(),"compatible API usage field aliases read without estimates");
    }
    static void budget(Path home)throws Exception{FeatureTest.edt(()->{
        I18n.setLanguage("ro");var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);var app=ctor.newInstance();
        ProviderTest.set(app,Provider.CUSTOM,"https://integrate.api.nvidia.com/v1","deepseek-ai/deepseek-v4.1-flash","fixture-not-real");
        FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});
        WorkspacePanel workspace=FeatureTest.field(app,"workspace");ContextRing ring=FeatureTest.field(app,"contextRing");
        check(((JLabel)FeatureTest.field(app,"modelMetadata")).getText().contains("1048576"),"NVIDIA model context visible as documentation");
        check(workspace.limit.getValue().equals(131072),"documented model initializes conservative LOCAL working budget");
        check(((JLabel)FeatureTest.field(app,"modelMetadata")).getText().contains("buget recomandat automat"),"automatic recommendation clearly labeled");
        Properties config=FeatureTest.field(app,"config");String profile=(String)FeatureTest.call(app,"contextProfile",new Class<?>[]{});
        check(config.getProperty(profile+".limitSource").equals("automatic"),"local recommendation has separate provenance");
        config.setProperty(profile+".metadata",Json.write(new ModelInfo.Info(0,0,"").json()));
        FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});
        check(ring.maximum==131072&&!ring.getText().equals("~?"),"unknown API context does not disable local percentage");
        check(ModelInfo.Info.from(Json.parse(config.getProperty(profile+".metadata"))).loaded()==0,"local budget never promoted to confirmed API window");
        workspace.limit.setValue(42000);FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});
        check(ring.maximum==42000,"manual positive budget preserved");
        ProviderTest.set(app,Provider.CUSTOM,"https://integrate.api.nvidia.com/v1","meta/muse-glimmer-30b","fixture-not-real");
        FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});
        check(ring.maximum==131072,"Muse Glimmer screenshot scenario auto enables percentage");
        String muse=(String)FeatureTest.call(app,"contextProfile",new Class<?>[]{});
        config.setProperty(muse+".metadata",Json.write(new ModelInfo.Info(0,0,64000,"fixture API input cap").json()));
        FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});
        check(ring.maximum==64000,"automatic budget respects subsequently discovered API input cap");
        ProviderTest.set(app,Provider.CUSTOM,"https://integrate.api.nvidia.com/v1","deepseek-ai/deepseek-v4.1-flash","fixture-not-real");
        FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});check(ring.maximum==42000,"switching models restores independent manual budget");
        ProviderTest.set(app,Provider.CUSTOM,"https://example.test/v1","unknown-model","fixture-not-real");
        FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});check(ring.maximum==0&&ring.getText().equals("~?"),"unknown model context not invented");
        for(String lang:I18n.CODES){I18n.setLanguage(lang);ring.update(0,0,1000,131072);String before=ring.getText();ring.update(0,0,1014,131072);check(!ring.getText().equals(before),"small sub-percent changes visible in "+lang);workspace.meter(0,0,1014,131072,"");check(workspace.context.getString().contains(ring.getText().substring(1)),"sidebar and footer percentage agree in "+lang);}
        app.dispose();
    });}
    static void probes(Path home)throws Exception{
        try(Server server=new Server()){
            var catalog=ModelCatalog.fetch(Provider.CUSTOM,server.url(),"fixture-key");check(catalog.size()==2&&catalog.get(0).tools(),"real GET retains metadata and IDs");
            Map<String,String> expected=Map.of("good","responded","bad","unavailable","auth","auth","rate","rate","invalid","request","empty","reachable","alias","substituted","broken","transport","server-error","temporary","error-body","request");
            for(var item:expected.entrySet()){var result=ModelCatalog.probe(Provider.CUSTOM,server.url(),"fixture-key",item.getKey(),false,new ModelCatalog.Cancellation());check(result.state().equals(item.getValue()),"HTTP/result classification "+item.getKey()+": "+result.state());if(item.getKey().equals("good"))check(result.usage().input()==18&&result.usage().output()==2,"test tokens are actual usage");}
            var tools=ModelCatalog.probe(Provider.CUSTOM,server.url(),"fixture-key","good",true,new ModelCatalog.Cancellation());check(Boolean.TRUE.equals(tools.toolVerified()),"tool call verified without execution");
            var anthropic=ModelCatalog.probe(Provider.ANTHROPIC,server.url(),"fixture-anthropic","good",false,new ModelCatalog.Cancellation());check(anthropic.state().equals("responded")&&anthropic.usage().input()==18,"Anthropic-format response tests supported");check(server.headers.stream().anyMatch(h->h.contains("x-api-key: fixture-anthropic")),"Anthropic authentication headers");
            for(Object body:server.bodies)if(Json.get(body,"messages") instanceof List<?> messages){check(messages.size()==1,"probe never includes conversation history");String text=Json.str(messages.get(0),"content");check(text.equals("Reply with exactly OK.")||text.startsWith("Call localchat_probe"),"probe prompt is synthetic only");}
            var cancelled=new ModelCatalog.Cancellation();cancelled.cancel();int count=server.bodies.size();check(ModelCatalog.probe(Provider.CUSTOM,server.url(),"fixture","good",false,cancelled).state().equals("cancelled")&&server.bodies.size()==count,"cancelled tests make no request");
            var cache=new ModelCatalog.Cache(home,"profile-a",Provider.CUSTOM,server.url(),"fixture-key");cache.put("good",tools);var reload=new ModelCatalog.Cache(home,"profile-a",Provider.CUSTOM,server.url(),"fixture-key");check(reload.get("good").fresh()&&Boolean.TRUE.equals(reload.get("good").toolVerified()),"test results and tool verification persist");cache.putTool("good",new ModelCatalog.Probe("request",400,10,System.currentTimeMillis(),TokenUsage.UNKNOWN,null));check(cache.get("good").state().equals("responded"),"failed tool test does not erase working chat response");cache.putTool("good",tools);check(Boolean.TRUE.equals(cache.getTool("good").toolVerified()),"tool test retained separately");check(!new ModelCatalog.Cache(home,"profile-a",Provider.CUSTOM,server.url(),"different-key").get("good").fresh(),"account key changes invalidate accessibility results");check(!new ModelCatalog.Cache(home,"profile-b",Provider.CUSTOM,server.url(),"fixture-key").get("good").fresh(),"provider profiles keep tests separate");String saved=Files.readString(cache.file);check(!saved.contains("fixture-key")&&!saved.contains("account-sensitive"),"cache excludes credentials and account error bodies");
            check(!new ModelCatalog.Probe("responded",200,1,System.currentTimeMillis()-TimeUnit.HOURS.toMillis(25),TokenUsage.UNKNOWN,null).fresh(),"old responses expire");
            ModelCatalogDialog[] dialog={null};
            FeatureTest.edt(()->{dialog[0]=new ModelCatalogDialog(null,"Fixture",catalog,cache,Provider.CUSTOM,server.url(),"fixture-key",true,e->{},e->{},()->{});dialog[0].start(catalog,false);});
            for(int i=0;i<250&&dialog[0].running;i++)Thread.sleep(20);FeatureTest.edt(()->{check(!dialog[0].running,"batch UI testing completes");check(cache.get("good").state().equals("responded")&&cache.get("bad").state().equals("unavailable"),"batch separates responding from inaccessible model");dialog[0].toolOnly.doClick();check(dialog[0].visible.size()==1,"tool-capable model filter works");dialog[0].toolOnly.doClick();dialog[0].only.doClick();check(dialog[0].visible.size()==1&&dialog[0].visible.get(0).id().equals("good"),"responded-only filter works");dialog[0].only.doClick();int bad=-1;for(int i=0;i<dialog[0].visible.size();i++)if(dialog[0].visible.get(i).id().equals("bad"))bad=i;dialog[0].table.setRowSelectionInterval(bad,bad);check(!dialog[0].choose.isEnabled(),"inaccessible account model cannot be selected in catalog");dialog[0].dispose();});
            var cancelCache=new ModelCatalog.Cache(home,"cancel-profile",Provider.CUSTOM,server.url(),"fixture");ModelCatalogDialog[] aborted={null};
            FeatureTest.edt(()->{List<ModelCatalog.Entry> items=List.of(ModelCatalog.entry(Json.obj("id","slow"),server.url()),catalog.get(0),catalog.get(1));aborted[0]=new ModelCatalogDialog(null,"Cancel fixture",items,cancelCache,Provider.CUSTOM,server.url(),"fixture",true,e->{},e->{},()->{});aborted[0].start(items,false);aborted[0].stop.doClick();});
            for(int i=0;i<250&&aborted[0].running;i++)Thread.sleep(20);FeatureTest.edt(()->{check(!aborted[0].running,"cancelled batch completes");check(cancelCache.values.size()<3,"cancel never marks all queued models tested");aborted[0].dispose();});
            Path pictures=home.resolve("screenshots");Files.createDirectories(pictures);
            for(String language:I18n.CODES)for(boolean dark:new boolean[]{false,true})FeatureTest.edt(()->{I18n.setLanguage(language);if(dark)com.formdev.flatlaf.FlatDarkLaf.setup();else com.formdev.flatlaf.FlatLightLaf.setup();var ui=new ModelCatalogDialog(null,"Fixture",catalog,cache,Provider.CUSTOM,server.url(),"fixture-key",true,e->{},e->{},()->{});ui.addNotify();ui.validate();check(ui.choose.getWidth()>=ui.choose.getPreferredSize().width,"translated catalog action fits "+language);var image=new java.awt.image.BufferedImage(ui.getWidth(),ui.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();ui.getRootPane().printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",pictures.resolve(language+(dark?"-dark":"-light")+".png").toFile());ui.dispose();});
        }
    }
    public static void main(String[] args)throws Exception{Path home=Path.of(System.getProperty("localchat.home"));Files.createDirectories(home);try{facts();probes(home);budget(home);System.out.println("PASS: "+checks+" catalog/probe/capability/token checks; six languages x two themes; synthetic HTTP only");}finally{FeatureTest.edt(()->{for(var window:java.awt.Window.getWindows())window.dispose();});}System.exit(0);}
}
