package localchat;
import java.util.*;
/** Limits exact repeated discovery calls; job-status polling and changed work are not duplicates. */
final class ToolLoopGuard {
 private static final Set<String> READS=Set.of("pdfcontrol_compare_import","read_skill","list_dir","find_files","read_file","search_text","run_agents",ComposerAssist.TOOL);
 private final Map<String,Integer> counts=new HashMap<>();private final Map<String,Tools.Result> skills=new HashMap<>();
 private final Set<String> evidence=new HashSet<>();
 private boolean read(String name){return READS.contains(name)||name.endsWith("pdfcontrol_project_files")||name.endsWith("pdfcontrol_read_document")||name.endsWith("pdfcontrol_list_projects")||name.endsWith("pdfcontrol_job_summary")||name.endsWith("pdfcontrol_job_findings")||name.endsWith("pdfcontrol_catalogue_status");}
 private String signature(ChatClient.ToolCall c){return c.name+"|"+Json.write(new TreeMap<>(c.args));}
 Tools.Result before(ChatClient.ToolCall call){
  if(!read(call.name))return null;
  String id=signature(call);int count=counts.merge(id,1,Integer::sum);
  if(count>=5)return Tools.Result.error(I18n.text("Bucla fara progres: aceeasi citire a fost ceruta de cinci ori. Operatiile efectuate sunt pastrate; verifica blocajul concret inainte de continuare."));
  if(call.name.equals("read_skill")&&skills.containsKey(id))return Tools.Result.ok(I18n.text("Skill deja citit in aceasta tura: {0}. Continua etapa ceruta; pentru un detaliu lipsa citeste sectiunea necesara din fisier, fara reluarea intregii proceduri.",Objects.toString(Json.get(call.args,"name"),""))+"\n"+Skills.resourceIndex(skills.get(id).text));
  return null;
 }
 void completed(ChatClient.ToolCall call,Tools.Result result){
  if(call.name.equals("read_skill")&&!result.isError)skills.putIfAbsent(signature(call),result);
  boolean changed=Set.of("write_file","append_file","edit_file","move_path","extract_attachments").contains(call.name)||call.name.contains("pdfcontrol_start_")||call.name.endsWith("pdfcontrol_regenerate_report");
  if(changed&&!result.isError){counts.clear();skills.clear();}
  // A previously unseen successful document/page is progress. Repeated reads, errors,
  // directory inventories and cached reminders do not reset a stalled cycle.
  boolean document=call.name.equals("read_file")||call.name.endsWith("pdfcontrol_read_document");
  if(document&&!result.isError&&evidence.add(signature(call)+"|"+ModelCatalog.hash(result.text)))counts.clear();
 }
 boolean repeating(ChatClient.ToolCall call){return read(call.name)&&!Set.of("run_agents",ComposerAssist.TOOL).contains(call.name)&&counts.getOrDefault(signature(call),0)>=3;}
 boolean stalled(ChatClient.ToolCall call){return read(call.name)&&counts.getOrDefault(signature(call),0)>=5;}
}
