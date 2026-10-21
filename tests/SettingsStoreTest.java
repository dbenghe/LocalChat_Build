package localchat;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class SettingsStoreTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static Properties read(Path p)throws IOException{Properties v=new Properties();try(var in=Files.newInputStream(p)){v.load(in);}return v;}
    static void clean(Path dir)throws IOException{try(var paths=Files.list(dir)){check(paths.noneMatch(p->p.getFileName().toString().startsWith("settings-")),"temporary settings cleaned");}}
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("localchat-settings-test-"),target=dir.resolve("setari.properties");
        Properties values=new Properties();values.setProperty("model","fixture-model");values.setProperty("api.key","synthetic-test-value");values.setProperty("ui.language","ro");
        SettingsStore.save(target,values);check(read(target).equals(values),"all settings round trip");clean(dir);
        byte[] old=Files.readAllBytes(target);values.setProperty("model","new-model");AtomicInteger calls=new AtomicInteger();
        SettingsStore.save(target,values,(s,t)->{check(Arrays.equals(Files.readAllBytes(t),old),"old settings intact until successful replacement");if(calls.incrementAndGet()<4)throw new AccessDeniedException(s.toString(),t.toString(),"synthetic sharing lock");Files.move(s,t,StandardCopyOption.REPLACE_EXISTING);});
        check(calls.get()==4&&read(target).equals(values),"temporary sharing lock recovers");clean(dir);
        byte[] stable=Files.readAllBytes(target);calls.set(0);
        try{SettingsStore.save(target,values,(s,t)->{calls.incrementAndGet();throw new AccessDeniedException(s.toString());});throw new AssertionError("permanent lock accepted");}catch(AccessDeniedException expected){}
        check(calls.get()==SettingsStore.ATTEMPTS,"permanent denial bounded");check(Arrays.equals(stable,Files.readAllBytes(target)),"failure preserves old settings");clean(dir);
        calls.set(0);try{SettingsStore.save(target,values,(s,t)->{calls.incrementAndGet();throw new IOException("disk failure");});throw new AssertionError("IO failure accepted");}catch(IOException expected){}
        check(calls.get()==1,"unrelated IO errors not retried");clean(dir);
        calls.set(0);Thread.currentThread().interrupt();try{SettingsStore.save(target,values,(s,t)->calls.incrementAndGet());throw new AssertionError("interruption ignored");}catch(InterruptedIOException expected){check(Thread.currentThread().isInterrupted(),"interrupt retained");}finally{Thread.interrupted();}
        check(calls.get()==0&&Arrays.equals(stable,Files.readAllBytes(target)),"interruption preserves settings");clean(dir);
        System.out.println("PASS: "+checks+" settings persistence checks");
    }
}
