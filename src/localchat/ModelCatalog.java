package localchat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Catalog facts and account-specific response tests. No private prompts or tool execution. */
final class ModelCatalog {
    record Entry(String id,Boolean vision,Boolean tools,Boolean reasoning,long maximum,long documentedMaximum,String source,String documentedLabel) {
        Entry(String id,Boolean vision,Boolean tools,Boolean reasoning,long maximum,long documentedMaximum,String source){this(id,vision,tools,reasoning,maximum,documentedMaximum,source,documentedMaximum>0?Long.toString(documentedMaximum):"");}
        Object json(){return Json.obj("id",id,"vision",vision,"tools",tools,"reasoning",reasoning,"maximum",maximum,"documentedMaximum",documentedMaximum,"source",source,"documentedLabel",documentedLabel);}
        static Entry from(Object o){return new Entry(Json.str(o,"id"),bool(Json.get(o,"vision")),bool(Json.get(o,"tools")),bool(Json.get(o,"reasoning")),ModelInfo.first(o,"maximum"),ModelInfo.first(o,"documentedMaximum"),Objects.toString(Json.str(o,"source"),""),Objects.toString(Json.str(o,"documentedLabel"),ModelInfo.first(o,"documentedMaximum")>0?Long.toString(ModelInfo.first(o,"documentedMaximum")):""));}
        long suggestedBudget(String base){return ModelInfo.recommendedBudget(base)>0?ModelInfo.recommendedBudget(base):Math.min(131072,maximum>0?maximum:documentedMaximum);}
    }
    record Doc(Boolean vision,Boolean tools,Boolean reasoning,long context,String label,String source){}
    // Reviewed 2026-10-20. Model-card figures are documentary facts, never route limits.
    static final Map<String,Doc> NVIDIA_DOCS=Map.ofEntries(
        Map.entry("deepseek-ai/deepseek-v4.1-flash",new Doc(true,true,true,1048576,"1048576","https://docs.api.nvidia.com/nim/reference/nvidia-deepseek-v4_1-flash")),
        Map.entry("meta/muse-glimmer-30b",new Doc(true,true,true,131072,"131072+","https://build.nvidia.com/meta/muse-glimmer-30b/modelcard")),
        Map.entry("google/gemma-4-31b-it",new Doc(true,true,true,256000,"256K","https://docs.api.nvidia.com/nim/reference/google-gemma-4-31b-it")),
        Map.entry("poolside/laguna-xs-2.1",new Doc(false,true,true,262144,"262144","https://build.nvidia.com/poolside/laguna-xs-2.1/modelcard")),
        Map.entry("stepfun-ai/step-5-preview",new Doc(true,true,true,1000000,"1M","https://build.nvidia.com/stepfun-ai/step-5-preview/modelcard")),
        Map.entry("nvidia/nemotron-3-super-120b-a12b",new Doc(false,true,true,1000000,"1M","https://docs.api.nvidia.com/nim/reference/nvidia-nemotron-3-super-120b-a12b")),
        Map.entry("nvidia/nemotron-3-ultra-550b-a55b",new Doc(null,true,true,0,"","https://docs.api.nvidia.com/nim/re/reference/nvidia-nemotron-3-ultra-550b-a55b")),
        Map.entry("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",new Doc(true,null,true,0,"","https://build.nvidia.com/nvidia/nemotron-3-nano-omni-30b-a3b-reasoning/modelcard")),
        Map.entry("nvidia/nemotron-3.5-content-safety",new Doc(true,null,null,0,"","https://docs.nvidia.com/nim/vision-language-models/latest/vision-content-safety.html")),
        Map.entry("openai/gpt-oss-20b",new Doc(null,null,null,128000,"128000","https://docs.api.nvidia.com/nim/reference/openai-gpt-oss-20b"))
    );
    static Boolean bool(Object value){return value instanceof Boolean b?b:null;}
    static Boolean capability(Object row,String... names){for(String name:names){Boolean value=bool(Json.get(row,name));if(value!=null)return value;value=bool(Json.get(row,"capabilities",name));if(value!=null)return value;}return null;}
    static Entry entry(Object row,String base){
        String id=Json.str(row,"id");if(id==null)id=Json.str(row,"key");
        Boolean vision=capability(row,"vision","supports_vision","image_input"),tools=capability(row,"tools","function_calling","supports_tool_calling"),reasoning=capability(row,"reasoning","supports_reasoning");
        Object modalities=Json.get(row,"architecture","input_modalities");if(vision==null&&modalities instanceof List<?> list)vision=list.contains("image");
        Object parameters=Json.get(row,"supported_parameters");if(parameters instanceof List<?> list){if(tools==null&&list.contains("tools"))tools=true;if(reasoning==null&&(list.contains("reasoning")||list.contains("reasoning_effort")))reasoning=true;}
        long maximum=ModelInfo.parse(Json.obj("data",List.of(row)),id,"catalog").maximum(),documented=0;String source=base+"/models";String host=null;try{host=URI.create(base).getHost();}catch(RuntimeException ignored){}
        String docLabel="";Doc doc="integrate.api.nvidia.com".equalsIgnoreCase(host)?NVIDIA_DOCS.get(id):null;
        if(doc!=null){if(vision==null)vision=doc.vision();if(tools==null)tools=doc.tools();if(reasoning==null)reasoning=doc.reasoning();documented=doc.context();docLabel=doc.label();source+=" | "+doc.source();}
        if("api.gonkarouter.io".equalsIgnoreCase(host)&&Set.of("zai-org/GLM-5.3-Flash","MiniMaxAI/MiniMax-M2.7","deepseek-ai/DeepSeek-V4-Flash-0731").contains(id)){
            if(tools==null)tools=true;if(reasoning==null&&!id.equals("deepseek-ai/DeepSeek-V4-Flash-0731"))reasoning=true;source+=" | https://gonkarouter.io/models";
        }
        return new Entry(id,vision,tools,reasoning,maximum,documented,source,docLabel);
    }
    static List<Entry> parse(Object root,String base){Object rows=Json.get(root,"data");if(!(rows instanceof List))rows=Json.get(root,"models");List<Entry> entries=new ArrayList<>();Set<String> seen=new HashSet<>();if(rows instanceof List<?> list)for(Object row:list){String id=Json.str(row,"id");if(id==null)id=Json.str(row,"key");if(id!=null&&!id.isBlank()&&seen.add(id))entries.add(entry(row,base));}return entries;}
    static List<Entry> fetch(Provider protocol,String base,String key)throws IOException{
        HttpURLConnection c=open(protocol,base+"/models",key,"GET",10000);try{int status=c.getResponseCode();if(status!=200)throw new IOException("HTTP "+status);return parse(Json.parse(read(c.getInputStream(),4*1024*1024,System.nanoTime()+TimeUnit.SECONDS.toNanos(15))),base);}finally{c.disconnect();}
    }
    static HttpURLConnection open(Provider protocol,String address,String key,String method,int timeout)throws IOException{
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setRequestMethod(method);c.setConnectTimeout(5000);c.setReadTimeout(timeout);c.setInstanceFollowRedirects(false);c.setRequestProperty("Content-Type","application/json");if("api.gonkarouter.io".equalsIgnoreCase(URI.create(address).getHost()))c.setRequestProperty("X-Gonka-No-Fallback","true");
        if(!key.isBlank())c.setRequestProperty(protocol.format==Provider.Format.ANTHROPIC?"x-api-key":"Authorization",protocol.format==Provider.Format.ANTHROPIC?key:"Bearer "+key);
        if(protocol.format==Provider.Format.ANTHROPIC)c.setRequestProperty("anthropic-version",ChatClient.ANTHROPIC_VERSION);return c;
    }
    static String read(InputStream stream,int maximum,long deadline)throws IOException{try(stream){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=stream.read(buffer))>=0){if(System.nanoTime()>deadline)throw new java.net.SocketTimeoutException("Test deadline");bytes.write(buffer,0,count);if(bytes.size()>maximum)throw new IOException("Response too large");}return bytes.toString(StandardCharsets.UTF_8);}}
    record Probe(String state,int http,long millis,long checked,TokenUsage usage,Boolean toolVerified,String servedModel) {
        Probe(String state,int http,long millis,long checked,TokenUsage usage,Boolean toolVerified){this(state,http,millis,checked,usage,toolVerified,"");}
        Object json(){return Json.obj("state",state,"http",http,"millis",millis,"checked",checked,"input",usage.input(),"output",usage.output(),"complete",usage.complete(),"toolVerified",toolVerified,"servedModel",servedModel);}
        static Probe from(Object row){Object in=Json.get(row,"input"),out=Json.get(row,"output");return new Probe(Objects.toString(Json.str(row,"state"),"untested"),(int)TokenUsage.number(row,"http",0),TokenUsage.number(row,"millis",0),TokenUsage.number(row,"checked",0),new TokenUsage(in instanceof Number?((Number)in).longValue():-1,out instanceof Number?((Number)out).longValue():-1,0,0,Boolean.TRUE.equals(Json.get(row,"complete"))),bool(Json.get(row,"toolVerified")),Objects.toString(Json.str(row,"servedModel"),""));}
        boolean fresh(){return checked>0&&System.currentTimeMillis()-checked<TimeUnit.HOURS.toMillis(24);}
        String display(){if(fresh()&&http>=500)return I18n.text("Eroare temporara a serverului");return I18n.text(!fresh()?"Netestat sau test expirat":switch(state){case "responded"->"Raspunde";case "reachable"->"API accesibil, fara raspuns final";case "unavailable"->"Inaccesibil acestui cont";case "auth"->"Cheie neacceptata";case "rate"->"Limita temporara de cereri";case "request"->"Cerere de test incompatibila";case "pending"->"Cerere acceptata, raspuns in asteptare";case "temporary"->"Eroare temporara a serverului";case "substituted"->"Raspuns cu alt identificator";case "cancelled"->"Test anulat";case "transport"->"Conexiune intrerupta sau timeout";default->"Netestat sau test expirat";});}
        int rank(){return !fresh()?2:state.equals("responded")?0:state.equals("reachable")?1:state.equals("unavailable")?4:3;}
    }
    static final class Cancellation {
        final AtomicBoolean cancelled=new AtomicBoolean();final Set<HttpURLConnection> active=ConcurrentHashMap.newKeySet();
        void cancel(){cancelled.set(true);for(var c:active){Thread thread=new Thread(c::disconnect,"model-probe-cancel");thread.setDaemon(true);thread.start();}}
    }
    static Probe probe(Provider protocol,String base,String key,String model,boolean tool,Cancellation cancel){
        long start=System.nanoTime();int status=0;TokenUsage usage=TokenUsage.UNKNOWN;Boolean verified=null;String state="transport",served="";HttpURLConnection c=null;
        try {
            if(cancel.cancelled.get())return new Probe("cancelled",0,0,System.currentTimeMillis(),usage,null);
            boolean anthropic=protocol.format==Provider.Format.ANTHROPIC;Map<String,Object> body=Json.obj("model",model,"messages",List.of(Json.obj("role","user","content",tool?"Call localchat_probe with value OK. Do not perform other actions.":"Reply with exactly OK.")),"stream",false);
            body.put(protocol==Provider.OPENAI?"max_completion_tokens":"max_tokens",128);
            if(tool){Object schema=Json.obj("type","object","properties",Json.obj("value",Json.obj("type","string")),"required",List.of("value"));Object definition=anthropic?Json.obj("name","localchat_probe","description","Harmless capability test; no execution.","input_schema",schema):Json.obj("type","function","function",Json.obj("name","localchat_probe","description","Harmless capability test; no execution.","parameters",schema));body.put("tools",List.of(definition));body.put("tool_choice",anthropic?Json.obj("type","tool","name","localchat_probe"):Json.obj("type","function","function",Json.obj("name","localchat_probe")));}
            c=open(protocol,base+(anthropic?"/messages":"/chat/completions"),key,"POST",15000);cancel.active.add(c);if(cancel.cancelled.get())throw new InterruptedIOException();c.setDoOutput(true);
            try(var out=c.getOutputStream()){out.write(Json.write(body).getBytes(StandardCharsets.UTF_8));}
            status=c.getResponseCode();state=switch(status){case 401->"auth";case 403,404->"unavailable";case 429->"rate";case 400,422->"request";case 202->"pending";case 500,502,503,504->"temporary";default->"transport";};
            if(status==200){Object root=Json.parse(read(c.getInputStream(),256*1024,start+TimeUnit.SECONDS.toNanos(30)));usage=TokenUsage.parse(root,anthropic,TokenUsage.UNKNOWN,true);String content=Json.str(root,"choices",0,"message","content");boolean hasContent=content!=null&&!content.isBlank();Object calls=Json.get(root,"choices",0,"message","tool_calls");
                if(calls instanceof List<?> list)for(Object call:list)if("localchat_probe".equals(Json.str(call,"function","name")))verified=true;
                Object blocks=Json.get(root,"content");if(blocks instanceof List<?> list)for(Object block:list){if("text".equals(Json.str(block,"type"))&&!Objects.toString(Json.str(block,"text"),"").isBlank())hasContent=true;if("tool_use".equals(Json.str(block,"type"))&&"localchat_probe".equals(Json.str(block,"name")))verified=true;}
                served=Objects.toString(Json.str(root,"model"),"");state=hasContent||Boolean.TRUE.equals(verified)?"responded":"reachable";if(Json.get(root,"error")!=null||!(anthropic?blocks instanceof List:calls instanceof List||Json.get(root,"choices") instanceof List))state="request";if(!served.isBlank()&&!served.equals(model)){state="substituted";verified=null;}
            }
        }catch(Exception error){state=cancel.cancelled.get()?"cancelled":"transport";}
        finally{if(c!=null){cancel.active.remove(c);c.disconnect();}}
        return new Probe(cancel.cancelled.get()?"cancelled":state,status,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),System.currentTimeMillis(),usage,verified,served);
    }
    static String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static final class Cache {
        final Path file;final Map<String,Probe> values=new ConcurrentHashMap<>(),toolValues=new ConcurrentHashMap<>();
        Cache(Path home,String prefix,Provider protocol,String base,String key){file=home.resolve("model-tests").resolve(hash(prefix+"|"+protocol+"|"+base+"|"+hash(key))+".json");try{Object root=Json.parse(Files.readString(file));Object rows=Json.get(root,"models");if(rows instanceof Map<?,?> map)for(var entry:map.entrySet())values.put(entry.getKey().toString(),Probe.from(entry.getValue()));Object tools=Json.get(root,"tools");if(tools instanceof Map<?,?> map)for(var entry:map.entrySet())toolValues.put(entry.getKey().toString(),Probe.from(entry.getValue()));}catch(IOException|RuntimeException ignored){}}
        Probe get(String model){Probe raw=values.getOrDefault(model,new Probe("untested",0,0,0,TokenUsage.UNKNOWN,null));Probe tool=getTool(model);return tool.fresh()&&Boolean.TRUE.equals(tool.toolVerified())?new Probe(raw.state(),raw.http(),raw.millis(),raw.checked(),raw.usage(),true,raw.servedModel()):raw;}
        Probe getTool(String model){return toolValues.getOrDefault(model,new Probe("untested",0,0,0,TokenUsage.UNKNOWN,null));}
        synchronized void putTool(String model,Probe probe)throws IOException{toolValues.put(model,probe);if(probe.state().equals("responded")&&(!get(model).fresh()||!Set.of("responded","reachable").contains(get(model).state())))values.put(model,probe);save();}
        synchronized void put(String model,Probe probe)throws IOException{values.put(model,probe);save();}
        private void save()throws IOException{Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"tests-",".tmp");try{Map<String,Object> rows=new TreeMap<>();values.forEach((id,result)->rows.put(id,result.json()));Map<String,Object> tools=new TreeMap<>();toolValues.forEach((id,result)->tools.put(id,result.json()));Files.writeString(temp,Json.write(Json.obj("models",rows,"tools",tools)));for(int attempt=0;;attempt++)try{try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}break;}catch(java.nio.file.AccessDeniedException error){if(attempt>=20)throw error;try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}}}finally{Files.deleteIfExists(temp);}}
    }
    static final class CapabilityIcon implements Icon {
        final Entry entry;final Probe probe;CapabilityIcon(Entry entry,Probe probe){this.entry=entry;this.probe=probe;}
        public int getIconWidth(){return 70;}public int getIconHeight(){return 18;}
        public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Boolean[] flags={entry.vision(),Boolean.TRUE.equals(probe.toolVerified())?Boolean.TRUE:entry.tools(),entry.reasoning()};Color[] colors={new Color(0xD7AB18),new Color(0x559BEB),new Color(0x25B78E)};g.setStroke(new BasicStroke(1.4f));
            for(int i=0;i<3;i++){int left=i*23;g.setColor(Boolean.TRUE.equals(flags[i])?colors[i]:Color.GRAY);g.drawOval(left+1,1,16,16);if(flags[i]==null){g.drawString("?",left+6,14);continue;}if(Boolean.FALSE.equals(flags[i])){g.drawLine(left+5,13,left+13,5);continue;}switch(i){case 0->{g.drawOval(left+4,6,10,6);g.drawOval(left+8,8,2,2);}case 1->{g.drawLine(left+5,13,left+12,6);g.drawLine(left+9,4,left+14,9);}case 2->{g.drawOval(left+6,4,7,8);g.drawLine(left+8,14,left+11,14);}}}g.dispose();}
    }
    static String facts(Entry entry,Probe probe){return I18n.text("Imagini: {0} · Unelte: {1} · Rationament: {2}",flag(entry.vision()),flag(Boolean.TRUE.equals(probe.toolVerified())?Boolean.TRUE:entry.tools()),flag(entry.reasoning()))+" | "+probe.display()+" | "+probe.servedModel()+" | "+entry.source();}
    static String flag(Boolean value){return I18n.text(value==null?"necunoscut":value?"Da":"Nu");}
    static List<Entry> saved(Properties config,String prefix,String base,List<String> ids){Map<String,Entry> indexed=new HashMap<>();try{Object rows=Json.parse(base.equals(config.getProperty(prefix+".catalogUrl",""))?config.getProperty(prefix+".catalog","[]"):"[]");if(rows instanceof List<?> list)for(Object row:list){Entry entry=Entry.from(row);indexed.put(entry.id(),entry);}}catch(RuntimeException ignored){}List<Entry> rows=new ArrayList<>();for(String id:ids){Entry documented=entry(Json.obj("id",id),base),cached=indexed.get(id);rows.add(cached==null?documented:new Entry(id,cached.vision()!=null?cached.vision():documented.vision(),cached.tools()!=null?cached.tools():documented.tools(),cached.reasoning()!=null?cached.reasoning():documented.reasoning(),cached.maximum(),documented.documentedMaximum(),documented.source(),documented.documentedLabel()));}return rows;}
}
