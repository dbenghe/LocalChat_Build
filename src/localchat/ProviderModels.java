package localchat;
import java.util.*;
/** Cached model identities belong to the endpoint that produced them, not merely a UI row. */
final class ProviderModels {
 static String route(String value){return Objects.toString(value,"").strip().replaceFirst("/+$","");}
 static boolean matches(String a,String b){return route(a).equals(route(b));}
 static List<String> ids(Properties p,String prefix,String base){
  String origin=p.getProperty(prefix+".modelsUrl",p.getProperty(prefix+".catalogUrl",p.getProperty(prefix+".url","")));
  if(!matches(origin,base))return List.of();return rawIds(p,prefix);
 }
 static List<String> rawIds(Properties p,String prefix){List<String> ids=new ArrayList<>();try{Object raw=Json.parse(p.getProperty(prefix+".models","[]"));if(raw instanceof List<?> list)for(Object value:list)if(value instanceof String id&&!id.isBlank()&&!ids.contains(id))ids.add(id);}catch(RuntimeException ignored){}return ids;}
 static String model(Properties p,String prefix,String base){
  String model=p.getProperty(prefix+".model",""),origin=p.getProperty(prefix+".modelUrl",p.getProperty(prefix+".url",""));
  return matches(origin,base)?model:"";
 }
 static void rememberRoute(Properties p,String prefix,String base,String model){
  String old=p.getProperty(prefix+".url",""),previous=p.getProperty(prefix+".model","");
  if(p.containsKey(prefix+".models")&&!p.containsKey(prefix+".modelsUrl"))p.setProperty(prefix+".modelsUrl",p.getProperty(prefix+".catalogUrl",old));
  String origin=p.getProperty(prefix+".modelUrl",old);
  if(model.isBlank()||!model.equals(previous))origin=base;
  p.setProperty(prefix+".modelUrl",origin);
 }
}
