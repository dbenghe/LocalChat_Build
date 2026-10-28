package localchat;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Read-only API metadata. Declared maxima, loaded context and input caps are distinct. */
final class ModelInfo {
    record Info(long maximum,long loaded,long inputLimit,long outputLimit,String source,long parallel) {
        Info(long maximum,long loaded,long inputLimit,long outputLimit,String source){this(maximum,loaded,inputLimit,outputLimit,source,0);}
        Info(long maximum,long loaded,long inputLimit,String source){this(maximum,loaded,inputLimit,0,source);}
        Info(long maximum,long loaded,String source){this(maximum,loaded,0,source);}
        Object json(){return Json.obj("maximum",maximum,"loaded",loaded,"inputLimit",inputLimit,"outputLimit",outputLimit,"source",source,"parallel",parallel,"checked",java.time.Instant.now().toString());}
        static Info from(Object o){return new Info(TokenUsage.number(o,"maximum",0),TokenUsage.number(o,"loaded",0),TokenUsage.number(o,"inputLimit",0),TokenUsage.number(o,"outputLimit",0),Objects.toString(Json.str(o,"source"),""),first(o,"parallel"));}
    }
    static long first(Object row,String... fields){for(String field:fields){Object value=Json.get(row,field);if(value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue()>0 && n.doubleValue()==n.longValue())return n.longValue();}return 0;}
    static Info parse(Object root,String selected,String source) {
        Object rows=Json.get(root,"models");if(!(rows instanceof List))rows=Json.get(root,"data");
        if(rows instanceof List)for(Object row:(List<?>)rows) {
            String key=Json.str(row,"key"),id=Json.str(row,"id");long loaded=0,parallel=0;boolean match=selected.equals(key)||selected.equals(id);
            Object instances=Json.get(row,"loaded_instances");
            if(instances instanceof List) {
                long minimum=Long.MAX_VALUE,minParallel=Long.MAX_VALUE;boolean exact=false,unknownParallel=false;
                for(Object instance:(List<?>)instances){long size=first(Json.get(instance,"config"),"context_length");
                    if(selected.equals(Json.str(instance,"id"))){match=true;exact=true;loaded=size;parallel=first(Json.get(instance,"config"),"parallel");break;}
                    if(size>0)minimum=Math.min(minimum,size);long count=first(Json.get(instance,"config"),"parallel");if(count>0)minParallel=Math.min(minParallel,count);else unknownParallel=true;}
                if(!exact && minimum<Long.MAX_VALUE)loaded=minimum;
                if(!exact&&!unknownParallel&&minParallel<Long.MAX_VALUE)parallel=minParallel;
            }
            if(match){
                long maximum=first(row,"max_context_length","context_length","context_window","max_model_len","context_window_tokens");
                if(maximum==0)maximum=first(Json.get(row,"top_provider"),"context_length");
                if(loaded==0)loaded=first(row,"loaded_context_length","loaded_context_window");
                long input=first(row,"max_input_tokens");if(input==0)input=first(Json.get(row,"limits"),"max_input_tokens");
                long output=0;for(Object limits:new Object[]{row,Json.get(row,"limits"),Json.get(row,"top_provider")}){long cap=first(limits,"max_output_tokens","max_completion_tokens");if(cap>0)output=output>0?Math.min(output,cap):cap;}
                if(!(instances instanceof List)){parallel=first(row,"max_concurrent_requests","max_concurrent_predictions");if(parallel==0)parallel=first(Json.get(row,"limits"),"max_concurrent_requests","max_concurrent_predictions");}
                return new Info(maximum,loaded,input,output,source,parallel);
            }
        }
        return new Info(0,0,0,source);
    }
    static boolean local(String base){String host=URI.create(base).getHost();return host!=null&&(host.equalsIgnoreCase("localhost")||host.equals("[::1]")||host.startsWith("127.")||host.startsWith("10.")||host.startsWith("192.168.")||host.matches("172[.](1[6-9]|2[0-9]|3[01])[.].*"));}
    static long recommendedBudget(String base){try{return "api.gonkarouter.io".equalsIgnoreCase(URI.create(base).getHost())?180000:0;}catch(RuntimeException e){return 0;}}
    private static final Map<String,Long> NATIVE_MISSING=new java.util.concurrent.ConcurrentHashMap<>();
    static boolean nativeMissing(String url){Long at=NATIVE_MISSING.get(url);return at!=null&&System.nanoTime()-at<java.util.concurrent.TimeUnit.HOURS.toNanos(1);}
    static void markNativeMissing(String url){NATIVE_MISSING.put(url,System.nanoTime());}
    static void resetNativeMissing(){NATIVE_MISSING.clear();}
    static Info fetch(String base,String key,String selected)throws IOException{return fetch(base,key,selected,Provider.CUSTOM);}
    static Info fetch(String base,String key,String selected,Provider protocol)throws IOException {
        ProviderHeaders.validateKey(base,key);URI uri=URI.create(base);String path=Objects.toString(uri.getPath(),"").replaceFirst("/v1/?$","").replaceFirst("/$","");
        String prefix=uri.getScheme()+"://"+uri.getRawAuthority()+path;String catalog=base.replaceFirst("/+$","")+"/models";
        boolean lan=local(base);String nativeUrl=prefix+"/api/v1/models";
        /* 1.28.9: a non-LAN LM Studio behind a tunnel answers /v1/models without context; its native endpoint is asked second (once per hour per host when it does not exist, e.g. a relay that proxies only /v1/*). */
        List<String> urls=lan?List.of(nativeUrl,prefix+"/api/v0/models",catalog):protocol==Provider.CUSTOM&&!nativeMissing(nativeUrl)?List.of(catalog,nativeUrl):List.of(catalog);
        IOException failure=null;Info valid=null;
        for(String address:urls) {
            HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
            c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);
            ProviderHeaders.authenticate(c,protocol,key);
            try {int status=c.getResponseCode();if(status!=200)throw ProviderHeaders.metadataError(c,status);
                byte[] bytes=c.getInputStream().readNBytes(4*1024*1024+1);if(bytes.length>4*1024*1024)throw new IOException("Metadata too large");
                Object data=Json.parse(new String(bytes,StandardCharsets.UTF_8));if(!(Json.get(data,"models") instanceof List)&&!(Json.get(data,"data") instanceof List))throw new IOException("Invalid model catalog");
                Info info=parse(data,selected,address);if(valid==null||address.equals(catalog))valid=info;
                boolean useful=info.maximum()>0||info.loaded()>0||info.inputLimit()>0||info.outputLimit()>0||info.parallel()>0;
                if(useful||(address.equals(catalog)&&(lan||urls.size()==1)))return info;
            }catch(IOException e){if(!lan&&address.equals(nativeUrl))markNativeMissing(nativeUrl);else failure=e;}catch(RuntimeException e){if(!lan&&address.equals(nativeUrl))markNativeMissing(nativeUrl);else failure=new IOException("Invalid metadata",e);}finally{c.disconnect();}
        }
        if(valid!=null)return valid;if(failure!=null)throw failure;return new Info(0,0,"");
    }
}
