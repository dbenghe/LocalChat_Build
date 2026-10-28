package localchat;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/** Private provenance of exact tool outputs, never an exclusion of an entire source folder. */
final class GeneratedFiles {
    private static final List<Scope> ACTIVE=new CopyOnWriteArrayList<>();
    static String key(Path file)throws IOException{return file.toRealPath().toString().toLowerCase(Locale.ROOT);}
    static String fingerprint(Path file)throws IOException{var a=Files.readAttributes(file,BasicFileAttributes.class);return a.size()+":"+a.lastModifiedTime().toMillis();}
    static Map<String,String> load(Path registry)throws IOException{
        Map<String,String> out=new TreeMap<>();if(!Files.exists(registry))return out;
        try{Object o=Json.get(Json.parse(Files.readString(registry)),"files");if(!(o instanceof Map<?,?> rows))throw new IOException("Invalid output registry");rows.forEach((k,v)->out.put(k.toString(),v.toString()));return out;}catch(RuntimeException e){throw new IOException("Invalid output registry",e);}
    }
    static boolean ignored(Path file,Map<String,String> records)throws IOException{
        String saved=records.get(key(file));if(saved!=null&&saved.equals(fingerprint(file)))return true;
        return ACTIVE.stream().anyMatch(scope->scope.matches(file));
    }
    static void mark(Path registry,Collection<Path> files)throws IOException{
        Map<String,String> additions=new TreeMap<>();for(Path p:files)if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))additions.put(key(p),fingerprint(p));
        if(additions.isEmpty())return;Files.createDirectories(registry.getParent());
        synchronized(GeneratedFiles.class){try(var channel=FileChannel.open(registry.resolveSibling(registry.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
            Map<String,String> records=load(registry);records.putAll(additions);if(records.size()>20000)throw new IOException("Output registry limit");
            Path tmp=Files.createTempFile(registry.getParent(),"generated-",".tmp");try{Files.writeString(tmp,Json.write(Json.obj("files",records)));try{Files.move(tmp,registry,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,registry,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tmp);}
        }}
    }
    private static final Set<String> READ_ONLY=Set.of("dir","type","echo","where","findstr","find","tree","more","hostname","whoami","ver","vol","tasklist","ipconfig","ping","nslookup","sort","cd","chdir","date","time");
    private static final Set<String> GIT_READ=Set.of("status","log","diff","show","branch","remote","rev-parse","ls-files","describe","tag","blame","config");
    /** True only when every part of the command is a known read-only command without redirection; anything doubtful is inventoried as before. */
    static boolean readOnlyCommand(String command){
        if(command==null||command.isBlank()||command.indexOf('>')>=0||command.contains("%")||command.contains("^")||command.contains("`"))return false;
        for(String part:command.split("\\R|&&|\\|\\||&|\\|")){
            String p=part.strip();while(p.startsWith("@"))p=p.substring(1).strip();if(p.isEmpty())continue;
            String[] words=p.split("\\s+");String first=words[0].toLowerCase(Locale.ROOT);
            boolean ok=READ_ONLY.contains(first)&&!((first.equals("date")||first.equals("time"))&&words.length>1&&!words[1].equalsIgnoreCase("/t"));
            if(first.equals("git")&&words.length>1&&GIT_READ.contains(words[1].toLowerCase(Locale.ROOT))&&!p.contains(" --output")&&!(words[1].equals("config")&&!(words.length>2&&List.of("--get","--get-all","--list","-l").contains(words[2].toLowerCase(Locale.ROOT))))&&!(words[1].equals("branch")&&words.length>2&&!words[2].startsWith("-"))&&!(words[1].equals("tag")&&words.length>2&&!words[2].startsWith("-")))ok=true;
            if(words.length==2&&(first.equals("python")||first.equals("py")||first.equals("pip")||first.equals("java")||first.equals("node"))&&(words[1].equals("--version")||words[1].equals("-V")||words[1].equals("-version")))ok=true;
            if(!ok)return false;
        }
        return true;
    }
    private static final Pattern SCRIPT=Pattern.compile("(?i)\\.(py|ps1|bat|cmd|js|vbs)$");
    /** Declared write destinations (exact paths only); merely mentioning a read source never counts. Group 1..n of each pattern are candidate paths. */
    private static final List<Pattern> WRITES=List.of(
        Pattern.compile("(?i)\\b(?:save|saveas|to_excel|to_csv|to_json|to_parquet|savefig|writeFile(?:Sync)?)\\s*\\(\\s*[rub]*[\"']([^\"']+)[\"']|open\\s*\\(\\s*[rub]*[\"']([^\"']+)[\"']\\s*,\\s*[\"'][wax][^\"']*[\"']|>>?\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s;&|>]+))"),
        Pattern.compile("(?i)(?:Path|pathlib\\.Path)\\s*\\(\\s*[rub]*[\"']([^\"']+)[\"']\\s*\\)\\s*\\.\\s*write_(?:text|bytes)\\b|urlretrieve\\s*\\([^,]+,\\s*[rub]*[\"']([^\"']+)[\"']"),
        Pattern.compile("(?i)\\b(?:set-content|add-content|out-file|export-csv|export-clixml|new-item)\\s+(?:-(?:path|literalpath|filepath)\\s+)?(?:\"([^\"]+)\"|'([^']+)'|([^\\s;&|\\-\"'][^\\s;&|\"']*))"),
        Pattern.compile("(?i)\\b(?:set-content|add-content|out-file|export-csv|export-clixml|new-item)\\b[^\\r\\n|;]*?\\s-(?:path|literalpath|filepath)\\s+(?:\"([^\"]+)\"|'([^']+)'|([^\\s;&|\"']+))"),
        Pattern.compile("(?i)\\s-(?:outfile|filepath|destination|destinationpath)\\s+(?:\"([^\"]+)\"|'([^']+)'|([^\\s;&|\"']+))"),
        Pattern.compile("(?i)\\b(?:curl|wget)(?:\\.exe)?\\b[^\\r\\n|;]*?\\s(?:-o|--output)\\s+(?:\"([^\"]+)\"|'([^']+)'|([^\\s;&|\"']+))"),
        Pattern.compile("(?i)\\[(?:System\\.)?IO\\.File\\]::(?:WriteAll(?:Text|Bytes|Lines)|AppendAllText|Create)\\s*\\(\\s*[\"']([^\"']+)[\"']"));
    static String text(Path p)throws IOException{byte[] b=Files.readAllBytes(p);if(b.length>=2&&(b[0]&255)==0xFF&&(b[1]&255)==0xFE)return new String(b,2,b.length-2,java.nio.charset.StandardCharsets.UTF_16LE);return new String(b,java.nio.charset.StandardCharsets.UTF_8);}
    /** 1.28.9: no folder walk. Only the exact files a command declares as write destinations are fingerprinted before and after, so a huge or slow work folder can never fail or slow the command. */
    static Scope command(Path registry,Path cwd,String command)throws IOException{
        String intent=command;Map<String,String> owned=load(registry);
        // Only inspect a script explicitly invoked and previously written by LocalChat, never arbitrary neighbors.
        for(var row:owned.entrySet()){Path p=Path.of(row.getKey());if(SCRIPT.matcher(p.toString()).find()&&mentions(command,p.getFileName().toString())&&Files.isRegularFile(p)&&Files.size(p)<1024*1024&&row.getValue().equals(fingerprint(p)))intent+="\n"+text(p);}
        return new Scope(registry,cwd,intent);
    }
    static boolean mentions(String text,String name){return Pattern.compile("(?i)(?<![a-z0-9_.-])"+Pattern.quote(name)+"(?![a-z0-9_.-])").matcher(text).find();}
    static final class Scope implements AutoCloseable{
        final Path registry,root;final Set<Path> outputs=new HashSet<>();final Map<Path,String> before=new HashMap<>();
        Scope(Path registry,Path cwd,String intent)throws IOException{this.registry=registry;root=Files.isDirectory(cwd)?cwd.toRealPath():cwd.toAbsolutePath().normalize();
            for(Pattern pattern:WRITES){var m=pattern.matcher(intent);while(m.find())for(int i=1;i<=m.groupCount();i++)if(m.group(i)!=null)declare(m.group(i));}
            for(Path output:outputs)before.put(output,stamp(output));
            ACTIVE.add(this);}
        private void declare(String name){
            if(name.isBlank()||name.contains("{")||name.contains("%")||name.contains("$")||name.equals("&1")||name.equalsIgnoreCase("NUL"))return;
            try{Path output=Path.of(name);outputs.add((output.isAbsolute()?output:root.resolve(output)).toAbsolutePath().normalize());}catch(InvalidPathException ignored){}
        }
        private static String stamp(Path file){try{return Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)?fingerprint(file):"";}catch(IOException|RuntimeException e){return "";}}
        boolean matches(Path file){if(outputs.contains(file.toAbsolutePath().normalize()))return true;try{return outputs.contains(file.toRealPath());}catch(IOException e){return false;}}
        public void close()throws IOException{try{List<Path> changed=new ArrayList<>();for(Path output:outputs){String now=stamp(output);if(!now.isEmpty()&&!now.equals(before.get(output)))changed.add(output);}mark(registry,changed);}finally{ACTIVE.remove(this);}}
    }
}
