package localchat;
import java.net.URI;
import java.util.*;

/** Local settings only: credentials never enter conversation state or exports. */
final class SavedProviders {
    record Entry(String id,String name,Provider protocol) {
        String prefix(){return "provider.saved."+id;}
        public String toString(){return name;}
    }
    static List<Entry> list(Properties config){
        List<Entry> entries=new ArrayList<>();
        for(String key:config.stringPropertyNames())if(key.matches("provider[.]saved[.][a-f0-9-]{36}[.]name")){
            String id=key.substring(15,key.length()-5),prefix="provider.saved."+id;
            try{entries.add(new Entry(id,config.getProperty(key),Provider.valueOf(config.getProperty(prefix+".protocol"))));}catch(RuntimeException ignored){}
        }
        entries.sort(Comparator.comparing(Entry::name,String.CASE_INSENSITIVE_ORDER));return entries;
    }
    static Object resolve(Properties config,String id,Provider fallback){return list(config).stream().filter(e->e.id().equals(id)).map(e->(Object)e).findFirst().orElse(fallback);}
    static Entry save(Properties config,String name,Provider protocol,String base,String key,String model){
        name=name.strip();if(name.isBlank()||name.length()>80||name.chars().anyMatch(c->Character.isISOControl(c)||c=='<'||c=='>'))throw new IllegalArgumentException(I18n.text("Numele furnizorului trebuie sa aiba 1–80 de caractere."));
        URI uri=URI.create(base);if(!Set.of("https","http").contains(Objects.toString(uri.getScheme(),""))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException(I18n.text("Adresa API trebuie sa fie HTTP/HTTPS, fara cheie in URL."));
        String id=UUID.randomUUID().toString();for(Entry entry:list(config))if(entry.name().equalsIgnoreCase(name)){id=entry.id();break;}
        Entry entry=new Entry(id,name,protocol);String prefix=entry.prefix();config.setProperty(prefix+".name",name);config.setProperty(prefix+".protocol",protocol.name());config.setProperty(prefix+".url",base);config.setProperty(prefix+".key",key);config.setProperty(prefix+".model",model);return entry;
    }
    static Provider protocol(Object selection){return selection instanceof Entry entry?entry.protocol():(Provider)selection;}
    static String prefix(Object selection){return selection instanceof Entry entry?entry.prefix():((Provider)selection).id();}
    static String id(Object selection){return selection instanceof Entry entry?entry.id():"";}
    static String label(Object selection,String base){
        if(selection instanceof Entry entry)return entry.name();Provider p=(Provider)selection;
        try{String host=URI.create(base).getHost();return Objects.equals(host,URI.create(p.defaultUrl).getHost())?p.shortName:host==null?"API":host;}catch(RuntimeException error){return "API";}
    }
}
