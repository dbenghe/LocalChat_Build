package localchat;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/** Evidence for continuing a long turn; inventories, polling, failures and repeated operations are not progress. */
final class AgentProgress {
    private final Set<String> completed=new HashSet<>();
    private int checkpoint;
    void completed(ChatClient.ToolCall call,Tools.Result result){
        if(result.isError)return;
        String name=call.name;
        if(Set.of("read_skill","list_dir","find_files","search_text","list_stray_files",ComposerAssist.TOOL).contains(name)
                ||name.endsWith("pdfcontrol_status")||name.endsWith("pdfcontrol_job_status")
                ||name.endsWith("pdfcontrol_project_files")||name.endsWith("pdfcontrol_list_projects")
                ||name.endsWith("pdfcontrol_catalogue_status"))return;
        // Signature alone: timestamps or changed stdout cannot turn the same operation into new work.
        completed.add(name+"|"+Json.write(new TreeMap<>(call.args)));
    }
    boolean advance(){boolean changed=completed.size()>checkpoint;checkpoint=completed.size();return changed;}
}
