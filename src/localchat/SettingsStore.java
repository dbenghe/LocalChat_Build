package localchat;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Replace complete settings only; transient Windows sharing locks must not lose preferences. */
final class SettingsStore {
    static final int ATTEMPTS=21;
    interface Move { void run(Path source,Path target)throws IOException; }
    static void save(Path target,Properties values)throws IOException {save(target,values,SettingsStore::replace);}
    static void save(Path target,Properties values,Move move)throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp=Files.createTempFile(target.toAbsolutePath().getParent(),"settings-",".tmp");
        try {
            try(OutputStream out=Files.newOutputStream(temp)){values.store(out,"Setari LocalChat (contin cheile API - nu le distribui)");}
            for(int attempt=0;;attempt++) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Settings save interrupted");
                try{move.run(temp,target);return;}
                catch(AccessDeniedException locked){
                    if(attempt>=ATTEMPTS-1)throw locked;
                    try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("Settings save interrupted");}
                }
            }
        }finally{try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    private static void replace(Path source,Path target)throws IOException {
        try{Files.move(source,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
    }
}
