package localchat;
import java.nio.file.Path;import java.util.*;import java.util.regex.Pattern;
/** Model-independent scheduling of independent evidence checks, never parallel production writes. */
final class AutoDelegation {
    static String plain(String text){StringBuilder out=new StringBuilder();boolean code=false;for(String line:text.split("\\R")){if(line.stripLeading().startsWith("```")){code=!code;continue;}if(!code&&!line.stripLeading().startsWith(">"))out.append(line).append('\n');}return java.text.Normalizer.normalize(out.toString(),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replace('ł','l').replace('ı','i');}
    static boolean forbidden(String text){String value=plain(text);return Pattern.compile("(?iu)\\b(?:fara|without|geen|sin|bez|nu|no|don't|do not|niet|nie)\\b[^\n.!?]{0,70}\\b(?:agenti|agents|agenten|agentes|agentow|ajan|ajanlar)\\b").matcher(value).find();}
    static List<Map<String,Object>> plan(String text,Path folder,String project){
        String value=plain(text);if(value.matches("(?s).*\\b(?:ajansiz|ajan(?:lar)?\\s+(?:kullanma|baslatma|calistirma))\\b.*")||forbidden(text)||value.strip().matches("(?s)^(?:ce |cum |what |how |wat |hoe |que |como |jak |nasil ).*"))return List.of();
        String scope=value.replaceAll("(?iu)\\b(?:fara|without|geen|sin|bez|nu|no|don't|do not|niet|nie)\\b[^.!?\n;]*", " ");
        if(!Pattern.compile("(?iu)\\b(?:verifica|compara|proceseaza|continua|check|compare|process|verify|controleer|verwerk|revisa|revise|sprawdz|porownaj|incele|karsilastir|dogrula|isle|kontrol)\\b").matcher(scope).find())return List.of();
        List<String[]> domains=new ArrayList<>();
        if(scope.matches("(?s).*(?:email|e-mail|e-posta|wiadomosc|zalacz|\\.msg\\b|\\.eml\\b|atasament|bijlagen|sources|bronnen|histori|gecmis).*"))domains.add(new String[]{"Surse si istoric","Inspect the existing project summary and source inventory. Identify new email/attachments versus documented history and report only evidenced changes or ambiguity. Do not restart completed work, extract files, or modify documentation."});
        if(scope.matches("(?s).*(?:import|catalog|katalog|articol|artikel|artykul|urun|ice\\s*aktar|spreadsheet|xlsx).*"))domains.add(new String[]{"Liste si articole","Inspect the selected project's import files and the CONFIRMED factory catalogue. Prefer pdfcontrol_compare_import for local bounded comparisons; if factory or exact sources are missing, report what is missing. Never dump entire catalogues/spreadsheets into context. Do not edit XLSX or copy files."});
        if(scope.matches("(?s).*(?:pdfcontrol|desen|drawing|tekening|tekeningen|rysunk|dibuj|planos|cizim|revizi|revision).*"))domains.add(new String[]{"Desene si rapoarte","Inspect existing drawing inventories, PDFCONTROL job status and final report evidence for the requested project/stage. Report coverage, unresolved checks and evidence paths. Do not start new control jobs, change drawings, or repeat completed checks."});
        if(domains.size()<2)return List.of();
        String shared="Current user request (scope, not permission to expand work):\n"+WorkProgress.clip(text,3200)+"\nLocal working folder: "+folder+"\nProject: "+project+"\n";
        List<Map<String,Object>> tasks=new ArrayList<>();for(String[] domain:domains)tasks.add(Json.obj("title",I18n.text(domain[0]),"task",shared+"Assigned independent evidence check: "+domain[1]+" Return concise findings, exact source paths, limitations and the next coordinator action. Other agents check different evidence. No mutation or recursive delegation."));return List.copyOf(tasks);
    }
}
