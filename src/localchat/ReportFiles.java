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
        try { collect(Json.parse(text),root.toRealPath(),found); } catch(Exception ignored) { }
        Matcher matcher=PATH.matcher(text);
        while(matcher.find())validate(matcher.group(),root,found);
        return new ArrayList<>(found);
    }
    private static void collect(Object value,Path root,Set<Path> found) {
        if(value instanceof Map<?,?>) for(var entry:((Map<?,?>)value).entrySet()) {
            String k=entry.getKey().toString().toLowerCase(Locale.ROOT);
            if(Set.of("report","path","file","output","report_path").contains(k) && entry.getValue() instanceof String)
                validate(entry.getValue().toString(),root,found);
            else if(entry.getValue() instanceof Map<?,?> || entry.getValue() instanceof List<?>)collect(entry.getValue(),root,found);
        }
        else if(value instanceof List<?>)for(Object item:(List<?>)value)collect(item,root,found);
    }
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
