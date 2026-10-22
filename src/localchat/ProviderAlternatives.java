package localchat;
import java.nio.file.Path;
import java.util.*;
/** Suggestions from private cached metadata only. Choosing never resends work. */
final class ProviderAlternatives {
 record Choice(Object selection,String model,String label,int rank){}
 static List<Choice> choices(Properties config,Path home,Object current,String model,boolean mbs){
  List<Object> providers=new ArrayList<>(SavedProviders.list(config));for(Provider p:Provider.values())if(config.containsKey(SavedProviders.prefix(p)+".url"))providers.add(p);
  List<Choice> result=new ArrayList<>();
  String currentBase=config.getProperty(SavedProviders.prefix(current)+".url","");
  var reference=ModelCatalog.saved(config,SavedProviders.prefix(current),currentBase,List.of(model)).get(0);
  for(Object selection:providers){
   String prefix=SavedProviders.prefix(selection),base=config.getProperty(prefix+".url",""),key=config.getProperty(prefix+".key","");Provider protocol=SavedProviders.protocol(selection);
   if(base.isBlank()||key.isBlank()&&protocol!=Provider.CUSTOM)continue;
   Set<String> ids=new LinkedHashSet<>();String selected=ProviderModels.model(config,prefix,base);if(!selected.isBlank())ids.add(selected);
   ids.addAll(ProviderModels.ids(config,prefix,base));
   var cache=new ModelCatalog.Cache(home,prefix,protocol,base,key);
   for(var entry:ModelCatalog.saved(config,prefix,base,new ArrayList<>(ids))){
    if(selection.equals(current)&&entry.id().equals(model))continue;
    var probe=cache.get(entry.id());if(probe.fresh()&&probe.state().equals("auth"))continue;var decision=ModelSuitability.assess(entry,probe,base,mbs);if(!decision.allowed()||mbs&&!decision.suggested())continue;
    String label=SavedProviders.label(selection,base)+" · "+entry.id()+" — "+ModelCatalog.costLabel(entry)+" — "+ModelCatalog.concurrency(entry.parallel());
    int rank=(selection.equals(current)?20:0)+(probe.fresh()&&probe.state().equals("rate")?40:0)+(Boolean.TRUE.equals(reference.vision())&&!Boolean.TRUE.equals(entry.vision())?4:0)+(Boolean.TRUE.equals(reference.reasoning())&&!Boolean.TRUE.equals(entry.reasoning())?3:0)+(reference.maximum()>0&&entry.maximum()>0&&entry.maximum()<reference.maximum()?3:0)+("zero".equals(entry.cost())?0:5)+(cache.get(entry.id()).fresh()&&cache.get(entry.id()).state().equals("responded")?0:2);
    result.add(new Choice(selection,entry.id(),label,rank));
   }
  }return result.stream().sorted(Comparator.comparingInt(Choice::rank).thenComparing(Choice::label)).limit(4).toList();
 }
}
