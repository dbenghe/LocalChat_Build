package localchat;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Local file references discovered in tool results; never auto-open a path from model prose. */
final class ReportFiles {
    private static final Pattern PATH=Pattern.compile("(?im)(?:[a-z]:[\\\\/]|/|\\\\\\\\)[^\\r\\n\"<>|]*?\\.(?:pdf|xlsx|csv|docx|txt)(?=$|[\\s\",}\\]])");
    static List<Path> discover(String text, Path root) {
        if(root==null)return List.of(); Set<Path> found=new LinkedHashSet<>();
        boolean parsed=false;
        try {
            Object value=Json.parse(text);parsed=true;
            collect(value,root.toRealPath(),found);
        } catch(Exception ignored) { }
        if(parsed)return new ArrayList<>(found);
        Matcher matcher=PATH.matcher(text);
        while(matcher.find())validate(matcher.group(),root,found);
        return new ArrayList<>(found);
    }
    private static void collect(Object value,Path root,Set<Path> found) {
        if(value instanceof Map<?,?> map){
            // A nested job receipt is subject to the same proof requirements as a top-level receipt.
            if(map.containsKey("job_id")||map.containsKey("delivery_verified")){
                Object id=map.get("job_id");String state=Objects.toString(map.get("status"),"");
                if(!(id instanceof String text)||text.isBlank()||!Set.of("done","partial").contains(state)||!Boolean.TRUE.equals(map.get("delivery_verified")))return;
                for(String field:List.of("report","differences_report"))if(map.get(field) instanceof String path)validate(path,root,found);
                collect(map.get("artifacts"),root,found);return;
            }
            // Child prose, task input and opaque tool strings cannot promote a path to a verified deliverable.
            if(map.containsKey("group_id")&&map.get("tasks") instanceof List<?> tasks){for(Object task:tasks)collectDeliveries(Json.get(task,"deliveries"),root,found);return;}
            for(var entry:map.entrySet()){
                String key=entry.getKey().toString().toLowerCase(Locale.ROOT);
                if(Set.of("task","answer","messages","source_files","prompt","description","content").contains(key))continue;
                if(key.equals("deliveries")){collectDeliveries(entry.getValue(),root,found);continue;}
                if(Set.of("report","path","file","output","report_path","differences_report").contains(key)&&entry.getValue() instanceof String path)validate(path,root,found);
                else if(entry.getValue() instanceof Map<?,?>||entry.getValue() instanceof List<?>)collect(entry.getValue(),root,found);
            }
        }else if(value instanceof List<?> list)for(Object item:list)collect(item,root,found);
    }
    private static void collectDeliveries(Object rows,Path root,Set<Path> found){if(rows instanceof List<?> list)for(Object row:list)if(row instanceof Map<?,?> map&&map.containsKey("job_id"))collect(row,root,found);}
    static boolean allowed(Path path,Path root) {
        try {
            if(root==null || !path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))return false;
            Path p=path.toRealPath();return p.startsWith(root.toRealPath()) && Files.isRegularFile(p)
                && p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".+\\.(pdf|xlsx|csv|docx|txt)")
                && !p.getFileName().toString().equalsIgnoreCase("reguli.md"); }
        catch(IOException|RuntimeException e){return false;}
    }
    private static void validate(String raw,Path root,Set<Path> found) {
        try { Path p=Path.of(raw.trim());if(!p.isAbsolute())p=root.resolve(p);
            if(allowed(p,root))found.add(p.toRealPath()); }catch(IOException|RuntimeException ignored){}
    }
}
