package localchat;
import java.util.*;
/** Limits exact repeated discovery calls; job-status polling and changed work are not duplicates. */
final class ToolLoopGuard {
 private static final Set<String> READS=Set.of("read_skill","list_dir","find_files","read_file","search_text","run_agents");
 private final Map<String,Integer> counts=new HashMap<>();private final Map<String,Tools.Result> skills=new HashMap<>();
 private boolean read(String name){return READS.contains(name)||name.endsWith("pdfcontrol_project_files")||name.endsWith("pdfcontrol_read_document")||name.endsWith("pdfcontrol_list_projects");}
 private String signature(ChatClient.ToolCall c){return c.name+"|"+Json.write(new TreeMap<>(c.args));}
 Tools.Result before(ChatClient.ToolCall call){
  if(!read(call.name))return null;
  String id=signature(call);int count=counts.merge(id,1,Integer::sum);
  if(count>=5)return Tools.Result.error(I18n.text("Bucla fara progres: aceeasi citire a fost ceruta de cinci ori. Operatiile efectuate sunt pastrate; verifica blocajul concret inainte de continuare."));
  if(call.name.equals("read_skill")&&skills.containsKey(id))return Tools.Result.ok(I18n.text("Skill deja citit in aceasta tura: {0}. Continua etapa ceruta; pentru un detaliu lipsa citeste sectiunea necesara din fisier, fara reluarea intregii proceduri.",Objects.toString(Json.get(call.args,"name"),"")));
  return null;
 }
 void completed(ChatClient.ToolCall call,Tools.Result result){
  if(call.name.equals("read_skill")&&!result.isError)skills.putIfAbsent(signature(call),result);
  boolean changed=Set.of("write_file","append_file","edit_file","move_path","extract_attachments").contains(call.name)||call.name.contains("pdfcontrol_start_")||call.name.endsWith("pdfcontrol_regenerate_report");
  if(changed&&!result.isError){counts.clear();skills.clear();}
 }
 boolean stalled(ChatClient.ToolCall call){return read(call.name)&&counts.getOrDefault(signature(call),0)>=5;}
}
