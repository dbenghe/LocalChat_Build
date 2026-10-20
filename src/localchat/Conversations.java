package localchat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Conversations saved as JSON files in the app's "conversatii" folder. */
final class Conversations {
    private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    record Saved(Path file, String title, String workDir, List<ChatClient.Message> messages, State state) {
        @Override
        public String toString() {
            return file.getFileName().toString().replace(".json", "").replace('_', ' ') + "   " + title;
        }
    }

    static final class State {
        String title="";
        boolean pinned, archived;
        String draft="",provider="",providerPreset="",model="",baseUrl="";
        final List<String> draftAttachments=new ArrayList<>(),attachedPaths=new ArrayList<>();
        long contextAnchorInput=-1;int contextAnchorCount=-1,contextAnchorThrough=-1;String contextAnchorProfile="",contextAnchorSignature="";
        int contextLimit=-1;
        int responseProfile=1;
        boolean autoCompact;
        int autoThreshold=70;
        String systemPrompt;
        Boolean toolsEnabled,streamEnabled;
        ContextMemory memory=new ContextMemory();
        TokenUsage.Ledger usage=new TokenUsage.Ledger();
        TokenUsage.Ledger last=new TokenUsage.Ledger();
        Object toJson() { return Json.obj("customTitle",title,"pinned",pinned,"archived",archived,"draft",draft,"draftAttachments",draftAttachments,"attachedPaths",attachedPaths,"provider",provider,"providerPreset",providerPreset,"model",model,"baseUrl",baseUrl,"contextLimit",contextLimit,"autoCompact",autoCompact,"autoThreshold",autoThreshold,"responseProfile",responseProfile,"systemPrompt",systemPrompt,"toolsEnabled",toolsEnabled,"streamEnabled",streamEnabled,"summary",memory.summary,
                "contextAnchorInput",contextAnchorInput,"contextAnchorCount",contextAnchorCount,"contextAnchorThrough",contextAnchorThrough,"contextAnchorProfile",contextAnchorProfile,"contextAnchorSignature",contextAnchorSignature,"compactedThrough",memory.through,"retainedQuestion",memory.question,"usage",usage.toJson(),"lastTurn",last.toJson()); }
        static State fromJson(Object o, int count) {
            State s=new State();
            for(String key:List.of("draftAttachments","attachedPaths")){Object values=Json.get(o,key);if(values instanceof List<?> list)for(Object v:list)if(v instanceof String value && !value.isBlank() && !(key.equals("draftAttachments")?s.draftAttachments:s.attachedPaths).contains(value))(key.equals("draftAttachments")?s.draftAttachments:s.attachedPaths).add(value);}
            String t=Json.str(o,"customTitle");if(t!=null)s.title=t;
            s.contextAnchorInput=(Json.get(o,"contextAnchorInput") instanceof Number value?value.longValue():-1);s.contextAnchorCount=(int)(Json.get(o,"contextAnchorCount") instanceof Number value?value.longValue():-1);s.contextAnchorThrough=(int)(Json.get(o,"contextAnchorThrough") instanceof Number value?value.longValue():-1);s.contextAnchorProfile=java.util.Objects.toString(Json.str(o,"contextAnchorProfile"),"");
            s.contextAnchorSignature=java.util.Objects.toString(Json.str(o,"contextAnchorSignature"),"");
            s.providerPreset=java.util.Objects.toString(Json.str(o,"providerPreset"),"");
            s.pinned=Boolean.TRUE.equals(Json.get(o,"pinned"));s.archived=Boolean.TRUE.equals(Json.get(o,"archived"));
            String draft=Json.str(o,"draft"),provider=Json.str(o,"provider"),model=Json.str(o,"model"),url=Json.str(o,"baseUrl");
            s.responseProfile=(int)Math.max(0,Math.min(2,TokenUsage.number(o,"responseProfile",1)));
            s.autoCompact=Boolean.TRUE.equals(Json.get(o,"autoCompact"));
            s.autoThreshold=(int)Math.max(30,Math.min(95,TokenUsage.number(o,"autoThreshold",70)));
            s.systemPrompt=Json.str(o,"systemPrompt");
            Object tools=Json.get(o,"toolsEnabled"),stream=Json.get(o,"streamEnabled");
            if(tools instanceof Boolean)s.toolsEnabled=(Boolean)tools;if(stream instanceof Boolean)s.streamEnabled=(Boolean)stream;
            if(draft!=null)s.draft=draft;if(provider!=null)s.provider=provider;if(model!=null)s.model=model;if(url!=null)s.baseUrl=url;
            Object limit=Json.get(o,"contextLimit");if(limit instanceof Number)s.contextLimit=Math.max(-1,Math.min(2000000,((Number)limit).intValue()));String sum=Json.str(o,"summary");
            int through=(int)TokenUsage.number(o,"compactedThrough",0);
            if(sum!=null && through>0 && through<=count){s.memory.summary=sum;s.memory.through=through;s.memory.question=java.util.Objects.toString(Json.str(o,"retainedQuestion"),"");}
            s.usage=TokenUsage.Ledger.fromJson(Json.get(o,"usage"));
            s.last=TokenUsage.Ledger.fromJson(Json.get(o,"lastTurn"));
            if(Json.get(o,"usage")==null && count>0)s.usage.historicalUnknown=true;
            if(Json.get(o,"lastTurn")==null && count>0)s.last.historicalUnknown=true;
            return s;
        }
    }
    static String shortId(Path path) {
        try { byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0,8); }
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    private Conversations() {
    }

    static Path newFile(Path dir) {
        return dir.resolve(LocalDateTime.now().format(FILE_NAME) + "_" + java.util.UUID.randomUUID().toString().substring(0,8) + ".json");
    }

    static void save(Path file, String workDir, List<ChatClient.Message> messages) throws IOException {
        State state=Files.isRegularFile(file) ? load(file).state() : new State();
        save(file,workDir,messages,state);
    }

    static void save(Path file,String workDir,List<ChatClient.Message> messages,State state) throws IOException {
        List<Object> list = new ArrayList<>();
        for (ChatClient.Message m : messages) list.add(m.toJson());
        Files.createDirectories(file.getParent());
        Path temp=Files.createTempFile(file.getParent(),"conversation-",".tmp");
        try {
            Files.writeString(temp,Json.write(Json.obj("title",state.title.isBlank()?title(messages):state.title,
                    "workDir",workDir,"messages",list,"state",state.toJson())),StandardCharsets.UTF_8);
            try { Files.move(temp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(temp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temp);}
    }

    /** Reversible deletion. Removed chats are excluded from all normal lists and exports. */
    static Path trash(Path file) throws IOException {
        Path folder=file.getParent().resolve("deleted");Files.createDirectories(folder);
        return Files.move(file,folder.resolve(java.util.UUID.randomUUID()+"_"+file.getFileName()));
    }
    static void export(List<Saved> chats, Path destination) throws IOException {
        // Serialize only known conversation fields: settings/API keys are never included.
        Path temp=Files.createTempFile(destination.toAbsolutePath().getParent(),"chats-",".tmp");
        try {
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(temp))) {
            for(Saved saved:chats) {
                Saved fresh=load(saved.file());
                List<Object> messages=new ArrayList<>();for(var m:fresh.messages())messages.add(m.toJson());
                zip.putNextEntry(new java.util.zip.ZipEntry(fresh.file().getFileName().toString()));
                zip.write(Json.write(Json.obj("title",fresh.title(),"workDir",fresh.workDir(),"messages",messages,"state",fresh.state().toJson())).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
        try{Files.move(temp,destination,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
        catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(temp,destination,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }

    static Saved load(Path file) throws IOException {
        Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        List<ChatClient.Message> messages = new ArrayList<>();
        Object list = Json.get(root, "messages");
        if (list instanceof List) for (Object m : (List<?>) list) messages.add(ChatClient.Message.fromJson(m));
        String title = Json.str(root, "title");
        return new Saved(file, title == null ? "" : title, Json.str(root, "workDir"), messages, State.fromJson(Json.get(root,"state"),messages.size()));
    }

    /** Newest first. Unreadable files are skipped. */
    static List<Saved> list(Path dir) {
        List<Saved> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json")).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    out.add(load(p));
                } catch (IOException | RuntimeException ignored) {
                    // damaged file: leave it out of the list
                }
            });
        } catch (IOException ignored) {
            // folder vanished
        }
        out.sort(Comparator.comparing((Saved v)->!v.state().pinned));
        return out;
    }

    static String title(List<ChatClient.Message> messages) {
        for (ChatClient.Message m : messages) {
            if (m.role.equals("user")) {
                String t = m.content.replaceAll("\\s+", " ").trim();
                return t.length() > 80 ? t.substring(0, 80) + "…" : t;
            }
        }
        return "";
    }
}
