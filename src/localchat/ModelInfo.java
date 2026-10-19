package localchat;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Read-only metadata. No model loading, inference or model-name heuristics. */
final class ModelInfo {
    record Info(long maximum,long loaded,String source) {
        Object json(){return Json.obj("maximum",maximum,"loaded",loaded,"source",source,"checked",java.time.Instant.now().toString());}
        static Info from(Object o){return new Info(TokenUsage.number(o,"maximum",0),TokenUsage.number(o,"loaded",0),Objects.toString(Json.str(o,"source"),""));}
    }
    static Info parse(Object root,String selected,String source) {
        Object rows=Json.get(root,"models");if(!(rows instanceof List))rows=Json.get(root,"data");
        if(rows instanceof List)for(Object row:(List<?>)rows) {
            String key=Json.str(row,"key"),id=Json.str(row,"id");long loaded=0;boolean match=selected.equals(key)||selected.equals(id);
            Object instances=Json.get(row,"loaded_instances");
            if(instances instanceof List) {
                long minimum=Long.MAX_VALUE;boolean exact=false;
                for(Object instance:(List<?>)instances){long size=TokenUsage.number(Json.get(instance,"config"),"context_length",0);
                    if(selected.equals(Json.str(instance,"id"))){match=true;exact=true;loaded=size;break;}
                    if(size>0)minimum=Math.min(minimum,size);}
                if(!exact && minimum<Long.MAX_VALUE)loaded=minimum;
            }
            if(match)return new Info(Math.max(0,TokenUsage.number(row,"max_context_length",0)),Math.max(0,loaded),source);
        }
        return new Info(0,0,source);
    }
    static Info fetch(String base,String key,String selected) throws IOException {
        URI uri=URI.create(base);String path=Objects.toString(uri.getPath(),"").replaceFirst("/v1/?$","").replaceFirst("/$","");
        String prefix=uri.getScheme()+"://"+uri.getRawAuthority()+path;
        IOException failure=null;
        for(String endpoint:List.of("/api/v1/models","/api/v0/models")) {
            HttpURLConnection c=(HttpURLConnection)new URL(prefix+endpoint).openConnection();
            c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);
            if(!key.isBlank())c.setRequestProperty("Authorization","Bearer "+key);
            try {int status=c.getResponseCode();if(status!=200)throw new IOException("HTTP "+status);
                byte[] bytes=c.getInputStream().readNBytes(4*1024*1024+1);if(bytes.length>4*1024*1024)throw new IOException("Metadata too large");
                Info info=parse(Json.parse(new String(bytes,StandardCharsets.UTF_8)),selected,endpoint);
                if(info.maximum()>0 || info.loaded()>0)return info;
            }catch(IOException e){failure=e;}catch(RuntimeException e){failure=new IOException("Invalid metadata",e);}finally{c.disconnect();}
        }
        if(failure!=null)throw failure;return new Info(0,0,"");
    }
}
