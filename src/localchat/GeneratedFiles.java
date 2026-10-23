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
    static Map<Path,String> snapshot(Path cwd)throws IOException{
        Map<Path,String> out=new LinkedHashMap<>();if(!Files.isDirectory(cwd))return out;Path root=cwd.toRealPath();int[] count={0};
        Files.walkFileTree(root,EnumSet.noneOf(FileVisitOption.class),12,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a)throws IOException{if(++count[0]>10000)throw new IOException("Output inventory limit");if(!dir.equals(root)&&(Hotfolder.EXCLUDED.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))||!dir.toRealPath().startsWith(root)))return FileVisitResult.SKIP_SUBTREE;return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path file,BasicFileAttributes a)throws IOException{if(++count[0]>10000)throw new IOException("Output inventory limit");if(a.isRegularFile()&&!a.isSymbolicLink()&&Hotfolder.source(root.relativize(file)))out.put(file,a.size()+":"+a.lastModifiedTime().toMillis());return FileVisitResult.CONTINUE;}
        });return out;
    }
    static Scope command(Path registry,Path cwd,String command)throws IOException{
        String intent=command;Map<String,String> owned=load(registry);
        // Only inspect a script explicitly invoked and previously written by LocalChat, never arbitrary neighbors.
        for(var row:owned.entrySet()){Path p=Path.of(row.getKey());if(p.toString().endsWith(".py")&&Files.isRegularFile(p)&&Files.size(p)<1024*1024&&row.getValue().equals(fingerprint(p))&&mentions(command,p.getFileName().toString()))intent+="\n"+Files.readString(p);}
        return new Scope(registry,cwd,intent);
    }
    static boolean mentions(String text,String name){return Pattern.compile("(?i)(?<![a-z0-9_.-])"+Pattern.quote(name)+"(?![a-z0-9_.-])").matcher(text).find();}
    static final class Scope implements AutoCloseable{
        final Path registry,root;final Set<Path> outputs=new HashSet<>();final Map<Path,String> before;
        Scope(Path registry,Path cwd,String intent)throws IOException{this.registry=registry;root=cwd.toRealPath();before=snapshot(root);
            // Restrict provenance to declared write destinations; merely mentioning a read source is insufficient.
            var writes=Pattern.compile("(?i)\\b(?:save|to_excel|to_csv)\\s*\\(\\s*[rub]*[\"']([^\"']+)[\"']|open\\s*\\(\\s*[rub]*[\"']([^\"']+)[\"']\\s*,\\s*[\"'][wax][^\"']*[\"']|>\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s;&|]+))").matcher(intent);
            while(writes.find())for(int i=1;i<=writes.groupCount();i++)if(writes.group(i)!=null)try{String name=writes.group(i);if(name.contains("{")||name.contains("%")||name.equals("&1")||name.equalsIgnoreCase("NUL"))continue;Path output=Path.of(name);outputs.add((output.isAbsolute()?output:root.resolve(output)).toAbsolutePath().normalize());}catch(InvalidPathException ignored){}
            ACTIVE.add(this);}
        boolean matches(Path file){try{return outputs.contains(file.toRealPath());}catch(IOException e){return false;}}
        public void close()throws IOException{try{Map<Path,String> after=snapshot(root);List<Path> outputs=new ArrayList<>();for(var e:after.entrySet())if(!Objects.equals(before.get(e.getKey()),e.getValue())&&matches(e.getKey()))outputs.add(e.getKey());mark(registry,outputs);}finally{ACTIVE.remove(this);}}
    }
}
