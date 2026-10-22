package localchat;
import java.util.*;import java.util.List;
/** Explicitly submitted pending messages. Never part of API context before dispatch. */
final class MessageQueue {
 static final int MAX=10;
 record Entry(String id,String text,List<String> attachments,String mode,String scope){
  Entry {attachments=List.copyOf(attachments);}
  Object json(){return Json.obj("id",id,"text",text,"attachments",attachments,"mode",mode,"scope",scope);}
 }
 static Entry entry(String text,List<String> attachments,boolean mbs){return new Entry(UUID.randomUUID().toString(),text,List.copyOf(attachments),mbs?"mbs":"normal","");}
 static List<Entry> parse(Object raw){List<Entry> result=new ArrayList<>();if(raw instanceof List<?> list)for(Object o:list){String text=Objects.toString(Json.str(o,"text"),""),id=Objects.toString(Json.str(o,"id"),"");List<String> paths=new ArrayList<>();if(Json.get(o,"attachments") instanceof List<?> values)for(Object v:values)if(v instanceof String p&&!p.isBlank()&&paths.size()<AttachmentSupport.MAX_PENDING)paths.add(p);if(!id.isBlank()&&(!text.isBlank()||!paths.isEmpty())&&result.size()<MAX)result.add(new Entry(id,text,paths,"mbs".equals(Json.str(o,"mode"))?"mbs":"normal",Objects.toString(Json.str(o,"scope"),"")));}return result;}
}
