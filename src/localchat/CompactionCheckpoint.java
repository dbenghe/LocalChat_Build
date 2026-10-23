package localchat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Private, exact-input checkpoints. A checkpoint is never active conversation memory. */
final class CompactionCheckpoint {
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static Path directory(Path home,Path conversation,String profile){return home.resolve("compaction-cache").resolve(hash(conversation.toAbsolutePath().normalize()+"\n"+profile));}
    static String key(Provider provider,String url,String key,String model,String system,List<ChatClient.Message> request,int target,int window,double scale){
        return hash("checkpoint-v2-complete\n"+provider.name()+"\n"+url+"\n"+hash(key)+"\n"+model+"\n"+system+"\n"+Json.write(request.stream().map(ChatClient.Message::toJson).toList())+"\n"+target+"/"+window+"/"+scale);
    }
    static String read(Path directory,String key,int target){
        if(directory==null)return null;
        try{Path file=directory.resolve(key+".json");if(!Files.isRegularFile(file)||Files.size(file)>131072||Files.getLastModifiedTime(file).toMillis()<System.currentTimeMillis()-30L*86400000)return null;
            Object data=Json.parse(Files.readString(file));String summary=Json.str(data,"summary");
            if(!"complete-v2".equals(Json.str(data,"policy"))||!key.equals(Json.str(data,"key"))||summary==null||summary.isBlank()||ContextMemory.estimate(summary)>target||!hash(summary).equals(Json.str(data,"digest")))return null;
            return summary;
        }catch(IOException|RuntimeException ignored){return null;}
    }
    static void save(Path directory,String key,String summary){
        if(directory==null)return;
        Path temp=null;
        try{Files.createDirectories(directory);temp=Files.createTempFile(directory,"checkpoint-",".tmp");
            Files.writeString(temp,Json.write(Json.obj("policy","complete-v2","key",key,"summary",summary,"digest",hash(summary))));
            try{Files.move(temp,directory.resolve(key+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,directory.resolve(key+".json"),StandardCopyOption.REPLACE_EXISTING);}
            // Bound private disk use. Old checkpoints are optional and can always be regenerated.
            try(var files=Files.list(directory)){var ordered=files.filter(f->f.getFileName().toString().endsWith(".json")).sorted(Comparator.comparingLong(CompactionCheckpoint::modified).reversed()).toList();
                for(int i=0;i<ordered.size();i++)if(i>=128||modified(ordered.get(i))<System.currentTimeMillis()-30L*86400000)Files.deleteIfExists(ordered.get(i));}
        }catch(IOException|RuntimeException ignored){/* A cache failure must not invalidate a valid model summary. */}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    private static long modified(Path path){try{return Files.getLastModifiedTime(path).toMillis();}catch(IOException e){return 0;}}
}
