package localchat;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
/** Public native model metadata only. Never reads auth.json or conversation rollouts. */
final class CodexMetadata {
 static final String SOURCE="Codex native models_cache.json | "+CodexConnection.SOURCE;
 record Context(long maximum,long effective,String stamp){}
 record Observed(long window,long at,String stamp){}
 private static final Map<String,Observed> OBSERVED=new ConcurrentHashMap<>();
 static ModelCatalog.Entry enrich(ModelCatalog.Entry entry){ModelInfo.Info info=info(entry.id());return new ModelCatalog.Entry(entry.id(),entry.vision(),entry.tools(),entry.reasoning(),info.loaded(),info.maximum(),info.source(),info.maximum()>0?Long.toString(info.maximum()):"",entry.purpose().equals("chat")?"":entry.purpose(),entry.chat(),entry.parallel(),entry.cost());}
 static Path cache(){String home=System.getenv("CODEX_HOME");return (home==null||home.isBlank()?Path.of(System.getProperty("user.home"),".codex"):Path.of(home)).resolve("models_cache.json");}
 static Context catalogue(String model){return read(cache(),model);}
 static Context read(Path file,String model){
  try{if(!Files.isRegularFile(file)||Files.size(file)>16*1024*1024)return new Context(0,0,"");
   Object root=Json.parse(Files.readString(file));String fetched=Json.str(root,"fetched_at");
   if(fetched==null||Instant.parse(fetched).isBefore(Instant.now().minus(Duration.ofDays(7)))||Instant.parse(fetched).isAfter(Instant.now().plus(Duration.ofMinutes(10))))return new Context(0,0,"");
   String stamp=ModelCatalog.hash(Objects.toString(Json.get(root,"identity"),"")+"|"+fetched);
   if(Json.get(root,"models") instanceof List<?> rows)for(Object row:rows)if(model.equals(Json.str(row,"slug"))){long max=ModelInfo.first(row,"context_window"),percent=ModelInfo.first(row,"effective_context_window_percent");if(max>2000000)return new Context(0,0,stamp);return new Context(max,percent>0&&percent<=100?max*percent/100:0,stamp);}
  }catch(IOException|RuntimeException ignored){}
  return new Context(0,0,"");
 }
 static void observe(String model,long window){if(window>0&&window<=2000000)OBSERVED.put(model,new Observed(window,System.currentTimeMillis(),catalogue(model).stamp()));}
 static ModelInfo.Info info(String model){Context c=catalogue(model);Observed o=OBSERVED.get(model);long loaded=o!=null&&o.stamp().equals(c.stamp())&&System.currentTimeMillis()-o.at()<15*60_000?o.window():0;
  return new ModelInfo.Info(c.maximum(),loaded,0,0,SOURCE+(loaded>0?" | thread/tokenUsage/updated":""));
 }
}
