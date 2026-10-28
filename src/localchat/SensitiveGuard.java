package localchat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * 1.28.9: detection of commands and scripts that mount network drives, handle credentials or read browser cookie/login stores,
 * plus the per category + target allowances the user may remember. A hit always asks (even in auto/always approval mode);
 * only its own remembered allowance skips the card. Nothing secret is stored: only "category|target" labels.
 */
final class SensitiveGuard {
    private SensitiveGuard(){}
    static final String TITLE="Comanda sensibila: confirmare necesara";
    static final String NETDRIVE="netdrive",CREDENTIALS="credentials",BROWSER="browser-store";
    record Hit(String category,String target){String key(){return category+"|"+target;}}

    // ---- remembered allowances (process wide, persisted by LocalChat in the config) ----
    private static final Set<String> ALLOWED=new LinkedHashSet<>();
    private static volatile boolean remember=true;
    private static volatile Runnable persist=()->{};
    static synchronized void configure(boolean rememberChoices,String saved,Runnable onChange){
        remember=rememberChoices;persist=onChange==null?()->{}:onChange;ALLOWED.clear();
        if(saved!=null)for(String line:saved.split("\\R"))if(line.contains("|")&&!line.isBlank())ALLOWED.add(line.strip());
    }
    static boolean remembering(){return remember;}
    static synchronized void remembering(boolean value){remember=value;persist.run();}
    static synchronized boolean allowed(Hit hit){return remember&&ALLOWED.contains(hit.key());}
    static synchronized void remember(Hit hit){if(remember&&ALLOWED.add(hit.key()))persist.run();}
    static synchronized void clear(){if(!ALLOWED.isEmpty()){ALLOWED.clear();persist.run();}}
    static synchronized int count(){return ALLOWED.size();}
    static synchronized String saved(){return String.join("\n",ALLOWED);}
    /** Test helper: forget everything and return to the defaults. */
    static synchronized void reset(){ALLOWED.clear();remember=true;persist=()->{};}

    // ---- detection ----
    private static final int MAX_SCRIPT=2_000_000,MAX_DEPTH=3;
    private static final String UNC="[\\\\]{2}[A-Za-z0-9_.$-]+[\\\\][^\\s\"'|&;<>,]+";
    private static final Pattern NET_USE=Pattern.compile("(?i)(?<![\\w.-])net(?:\\.exe|1)?\\s+use(?![\\w-])");
    private static final Pattern PSDRIVE=Pattern.compile("(?i)\\b(?:new|remove)-psdrive\\b");
    private static final Pattern PSDRIVE_FLAGS=Pattern.compile("(?i)\\s-(?:persist|credential)\\b|-root\\s+[\"']?[\\\\]{2}");
    private static final Pattern SMB=Pattern.compile("(?i)\\b(?:new|remove)-smbmapping\\b");
    private static final Pattern MOUNT_UNC=Pattern.compile("(?i)(?<![\\w.-])(?:mountvol|subst)(?:\\.exe)?\\b[^\\r\\n]*"+UNC);
    private static final Pattern UNC_P=Pattern.compile(UNC);
    private static final Pattern CRED=Pattern.compile("(?i)\\b(?:get-credential|convertto-securestring|convertfrom-securestring|pscredential|get-storedcredential|new-storedcredential|credentialmanager|passwordvault|vaultcmd|credwiz|credread|credenumerate|cryptunprotectdata|mimikatz|sekurlsa)\\b|(?<![\\w.-])(?:cmdkey|runas)(?:\\.exe)?\\b|\\bprotecteddata\\W+unprotect\\b|\\blsass\\b|keymgr\\.dll");
    private static final Pattern CMDKEY=Pattern.compile("(?i)cmdkey(?:\\.exe)?[^\\r\\n]*?/(?:add|delete|generic|list|pass|smartcard)[:=]\\s*[\"']?([^\\s\"']+)");
    private static final Pattern RUNAS=Pattern.compile("(?i)runas(?:\\.exe)?[^\\r\\n]*?/user:\\s*[\"']?([^\\s\"']+)");
    private static final Pattern STORE_ALWAYS=Pattern.compile("(?i)cookies\\.sqlite|logins\\.json|key4\\.db|login data|browser[_-]?cookie3|pycookiecheat|rookiepy|chrome_cookies");
    private static final Pattern STORE_SPECIAL=Pattern.compile("(?i)local state|web data|network[\\\\/]cookies|[\\\\/]cookies(?![\\w.-])|\\bcookies(?:-journal)?\\b");
    private static final Pattern BROWSER_DIR=Pattern.compile("(?i)google[\\\\/]chrome|microsoft[\\\\/]edge|bravesoftware|brave-browser|opera software|vivaldi|mozilla[\\\\/]firefox|firefox[\\\\/]profiles|user data|msedge|chromium");
    private static final Pattern PROFILE=Pattern.compile("(?i)[\\\\/](default|profile \\d+|guest profile)(?=[\\\\/\\s\"']|$)");
    private static final Pattern ENCODED=Pattern.compile("(?i)\\s-(?:e|ec|enc|encodedcommand)\\s+[\"']?([A-Za-z0-9+/]{16,}={0,2})");
    private static final String EXT="(?:ps1|psm1|py|pyw|bat|cmd|vbs|js|wsf|hta|sh)";
    private static final Pattern SCRIPT_REF=Pattern.compile("(?i)\"([^\"\\r\\n]+\\."+EXT+")\"|'([^'\\r\\n]+\\."+EXT+")'|([^\\s\"'<>|&;()]+\\."+EXT+")(?=$|[\\s\"'<>|&;)])");

    /** Everything sensitive in a command line: its text, decoded -EncodedCommand payloads and scripts it runs by path. */
    static List<Hit> scan(String command,Path cwd){
        LinkedHashMap<String,Hit> out=new LinkedHashMap<>();
        scan(command==null?"":command,cwd,0,new HashSet<>(),out);
        return List.copyOf(out.values());
    }
    /** A file the model wants to read directly (read_file): only browser cookie/login stores count. */
    static List<Hit> scanPath(Path file){
        LinkedHashMap<String,Hit> out=new LinkedHashMap<>();
        if(file!=null)browser(file.toString(),out);
        return List.copyOf(out.values());
    }
    private static void scan(String text,Path cwd,int depth,Set<Path> seen,Map<String,Hit> out){
        if(text.isBlank())return;
        textHits(text,out);
        if(depth>=MAX_DEPTH)return;
        Matcher enc=ENCODED.matcher(text);
        while(enc.find()){
            try{String decoded=new String(Base64.getDecoder().decode(enc.group(1)),StandardCharsets.UTF_16LE);if(printable(decoded))scan(decoded,cwd,depth+1,seen,out);}catch(IllegalArgumentException ignored){}
        }
        Matcher ref=SCRIPT_REF.matcher(text);
        while(ref.find()){
            String token=ref.group(1)!=null?ref.group(1):ref.group(2)!=null?ref.group(2):ref.group(3);
            String body=readScript(token,cwd,seen);
            if(body!=null)scan(body,cwd,depth+1,seen,out);
        }
    }
    private static boolean printable(String s){
        if(s.isEmpty())return false;int bad=0;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c<9||(c>13&&c<32)||c==0xFFFD)bad++;}return bad*20<=s.length();
    }
    private static String readScript(String token,Path cwd,Set<Path> seen){
        try{
            Path p=Path.of(token.strip());if(!p.isAbsolute())p=(cwd==null?Path.of("."):cwd).resolve(p);
            p=p.normalize();
            if(!Files.isRegularFile(p)||!seen.add(p))return null;
            if(Files.size(p)>MAX_SCRIPT)return null;
            byte[] data=Files.readAllBytes(p);
            if(data.length>=2&&(data[0]&255)==0xFF&&(data[1]&255)==0xFE)return new String(data,2,data.length-2,StandardCharsets.UTF_16LE);
            if(data.length>=2&&(data[0]&255)==0xFE&&(data[1]&255)==0xFF)return new String(data,2,data.length-2,StandardCharsets.UTF_16BE);
            return new String(data,StandardCharsets.UTF_8);
        }catch(InvalidPathException|IOException|SecurityException e){return null;}
    }
    private static void add(Map<String,Hit> out,String category,String target){Hit h=new Hit(category,target);out.putIfAbsent(h.key(),h);}
    static String norm(String s){
        String t=s.strip().replace('/','\\').toLowerCase(Locale.ROOT);
        while(t.length()>2&&t.endsWith("\\"))t=t.substring(0,t.length()-1);
        return t;
    }
    private static void textHits(String text,Map<String,Hit> out){
        netdrive(text,out);
        if(CRED.matcher(text).find()){
            Set<String> targets=new LinkedHashSet<>();
            Matcher k=CMDKEY.matcher(text);while(k.find())targets.add("cmdkey:"+norm(k.group(1)));
            Matcher r=RUNAS.matcher(text);while(r.find())targets.add("runas:"+norm(r.group(1)));
            if(targets.isEmpty())targets.add("*");
            for(String t:targets)add(out,CREDENTIALS,t);
        }
        browser(text,out);
    }
    private static void netdrive(String text,Map<String,Hit> out){
        boolean hit=NET_USE.matcher(text).find()||SMB.matcher(text).find()||MOUNT_UNC.matcher(text).find();
        Matcher d=PSDRIVE.matcher(text);
        if(!hit&&d.find()&&PSDRIVE_FLAGS.matcher(text).find())hit=true;
        if(!hit)return;
        TreeSet<String> parts=new TreeSet<>();
        Matcher u=UNC_P.matcher(text);while(u.find())parts.add(shareOf(u.group()));
        // drive letters only count next to a mapping verb, so "C:\\temp" in the same script does not widen the target
        Matcher near=Pattern.compile("(?i)(?:net(?:\\.exe|1)?\\s+use|subst|new-psdrive[^\\r\\n]*?-name|remove-psdrive[^\\r\\n]*?-name|new-smbmapping[^\\r\\n]*?-localpath|remove-smbmapping[^\\r\\n]*?-localpath)\\s+[\"']?([a-z]:?)(?=[\\s\"']|$)").matcher(text);
        while(near.find()){String letter=near.group(1).toLowerCase(Locale.ROOT);parts.add(letter.endsWith(":")?letter:letter+":");}
        add(out,NETDRIVE,parts.isEmpty()?"*":String.join(" ",parts));
    }
    private static String shareOf(String unc){
        String[] seg=norm(unc).split("\\\\+");
        // \\server\share (first two segments); deeper folders share the allowance of their share
        return seg.length>=3?"\\\\"+seg[1]+"\\"+seg[2]:norm(unc);
    }
    private static void browser(String text,Map<String,Hit> out){
        boolean store=STORE_ALWAYS.matcher(text).find()||(BROWSER_DIR.matcher(text).find()&&STORE_SPECIAL.matcher(text).find());
        if(!store)return;
        String low=text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> browsers=new LinkedHashSet<>();
        if(low.contains("google\\chrome")||low.contains("google/chrome")||low.contains("chrome_cookies")||low.contains("chrome"))browsers.add("chrome");
        if(low.contains("microsoft\\edge")||low.contains("microsoft/edge")||low.contains("msedge")||Pattern.compile("\\bedge\\b").matcher(low).find())browsers.add("edge");
        if(low.contains("firefox")||low.contains("cookies.sqlite")||low.contains("logins.json")||low.contains("key4.db"))browsers.add("firefox");
        if(low.contains("brave"))browsers.add("brave");
        if(Pattern.compile("\\bopera\\b").matcher(low).find())browsers.add("opera");
        if(low.contains("vivaldi"))browsers.add("vivaldi");
        if(browsers.isEmpty())browsers.add("any");
        Matcher p=PROFILE.matcher(text);String profile=p.find()?p.group(1).toLowerCase(Locale.ROOT):"*";
        for(String b:browsers)add(out,BROWSER,b+":"+profile);
    }

    // ---- what the card says ----
    static String reason(String category){
        return switch(category){
            case NETDRIVE->I18n.text("Monteaza, demonteaza sau modifica o unitate de retea (net use, New-PSDrive, subst). Poate folosi credentiale si da acces la un server extern.");
            case CREDENTIALS->I18n.text("Citeste, creeaza sau foloseste credentiale (Get-Credential, cmdkey, runas, SecureString, managerul de credentiale, DPAPI). Parolele si cheile sunt date sensibile.");
            default->I18n.text("Acceseaza cookie-uri sau parole salvate ale unui browser (Cookies, Login Data, Local State, cookies.sqlite). Poate expune sesiuni autentificate si parole.");
        };
    }
    static String details(List<Hit> hits,String what){
        StringBuilder b=new StringBuilder();
        for(Hit h:hits)b.append("- ").append(reason(h.category())).append(' ').append(I18n.text("Tinta: {0}",h.target())).append('\n');
        b.append('\n').append(what);
        b.append("\n\n").append(remember
            ?I18n.text("\"Permite mereu pentru aceasta tinta\" se retine numai pentru aceasta categorie si tinta; o alta tinta intreaba din nou. Se poate sterge din setari rapide.")
            :I18n.text("Alegerea nu se retine (setarea Comenzi sensibile este pe \"intreaba mereu\")."));
        return b.toString();
    }
    static String refusal(List<Hit> hits){
        StringBuilder cats=new StringBuilder();for(Hit h:hits){if(cats.length()>0)cats.append(", ");cats.append(h.category()).append(':').append(h.target());}
        return "Utilizatorul a refuzat comanda sensibila ("+cats+"). Comanda NU a fost executata. Nu o repeta, nu o reface pe alta cale (alt script, alta unealta, alt shell, alta codare) si nu ocoli refuzul; spune-i utilizatorului ce nu ai putut face si intreaba-l ce doreste.";
    }
}