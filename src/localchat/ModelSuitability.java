package localchat;
import java.net.URI;
import java.util.*;

/** Profile suitability is separate from price, model size and temporary API failures. */
final class ModelSuitability {
    record Decision(String label,String reason,boolean allowed,boolean suggested,int rank) {}
    static String host(String base){try{return Objects.toString(URI.create(base).getHost(),"").toLowerCase(Locale.ROOT);}catch(RuntimeException e){return "";}}
    static String purpose(Object row){
        if(Boolean.FALSE.equals(ModelCatalog.capability(row,"chat","supports_chat")))return "nonchat";
        for(String key:List.of("task","pipeline_tag","type")){String value=Objects.toString(Json.str(row,key),"").toLowerCase(Locale.ROOT);if(Set.of("embedding","embeddings","feature-extraction","text-to-speech","automatic-speech-recognition","speech-to-text","audio","moderation","rerank","reranking").contains(value))return value;}return "";
    }
    static boolean specialized(ModelCatalog.Entry e){return !e.purpose().isBlank()||(!Boolean.TRUE.equals(e.chat())&&specialized(e.id()));}
    static boolean specialized(String id){
        String name=id.toLowerCase(Locale.ROOT);
        // Recognized task-specific families, not a guess at tools/vision/context from a model name.
        return name.matches(".*(?:^|[/_.-])(?:whisper|orpheus|embed|embedqa|embedding|embeddings|rerank|nvclip|deplot)(?:$|[/_.-]).*")
            || name.contains("prompt-guard-")||name.contains("safety-guard-")||name.contains("content-safety")||name.contains("topic-control")
            ||name.contains("/llama-guard-")||name.contains("/gpt-oss-safeguard-")||name.contains("/nemotron-parse")||name.contains("/riva-translate-")
            ||name.contains("video-detector")||name.endsWith("-reward")||name.contains("/ising-calibration-");
    }
    static Decision assess(ModelCatalog.Entry entry,ModelCatalog.Probe probe,String base,boolean mbs){
        if(specialized(entry))return new Decision("Model specializat","Nu este model de chat general: audio, embeddings, moderare sau procesare specializata.",false,false,5);
        if(probe.fresh()&&probe.state().equals("unavailable"))return new Decision("Acces blocat pentru cont","API-ul a returnat 403 sau 404. Retesteaza dupa schimbarea accesului; aceasta nu este o evaluare a inteligentei.",false,false,4);
        if(!mbs)return new Decision("Disponibil in Normal","Chat fara unelte sau reguli MBS. Capabilitatile necunoscute raman neconfirmate.",true,true,0);
        boolean tools=Boolean.TRUE.equals(probe.toolVerified())||Boolean.TRUE.equals(entry.tools());
        if(!tools&&Boolean.FALSE.equals(entry.tools()))return new Decision("Doar Normal","Profilul MBS necesita apeluri de unelte; API-ul declara lipsa suportului.",false,false,3);
        if(!tools)return new Decision("MBS neconfirmat","Testeaza uneltele din catalog. Lipsa metadatelor nu inseamna ca modelul este slab.",true,false,2);
        if(host(base).equals("integrate.api.nvidia.com")&&entry.id().equals("meta/muse-glimmer-30b"))return new Decision("Nerecomandat pentru MBS","Evaluare locala 2026-10-21: identificatorul jobului nu a fost pastrat in proba. Disponibil in Normal; suportul pentru unelte ramane declarat.",true,false,1);
        return new Decision("Compatibil cu MBS","Suport pentru unelte declarat sau verificat. Nu garanteaza corectitudinea regulilor de proiect.",true,true,0);
    }
    static boolean listed(ModelCatalog.Entry e,ModelCatalog.Probe p,String base,boolean mbs,boolean filtered){var d=assess(e,p,base,mbs);return !filtered||d.allowed();}
    static String text(Decision d){return I18n.text(d.label())+" · "+I18n.text(d.reason());}
}
