package localchat;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

/** Explicit image attachments only. Immutable local snapshots; bytes never enter chat JSON. */
final class ImageInput {
    static final long MAX_BYTES=5*1024*1024;
    static final int MAX_IMAGES=4;
    /** Verified encodings of the current question's images, reused by the steps of a tool loop; a changed file (size or time) misses. */
    private static final Map<String,String> ENCODED=new LinkedHashMap<>(8,0.75f,true){protected boolean removeEldestEntry(Map.Entry<String,String> eldest){return size()>MAX_IMAGES;}};
    record Ref(String path,String sha256,String mime) {
        Object json(){return Json.obj("path",path,"sha256",sha256,"mime",mime);}
        static Ref from(Object o){return new Ref(Json.str(o,"path"),Json.str(o,"sha256"),Json.str(o,"mime"));}
        String data(){try{
            Path file=Path.of(path);long size=Files.size(file),time=Files.getLastModifiedTime(file).toMillis();if(size>MAX_BYTES)throw new IOException("Image too large");
            String key=path+"|"+sha256+"|"+mime+"|"+size+"|"+time;
            synchronized(ENCODED){String known=ENCODED.get(key);if(known!=null)return known;}
            byte[] bytes=Files.readAllBytes(file);if(!hash(bytes).equals(sha256)||!Set.of("image/png","image/jpeg","image/gif").contains(mime))throw new IOException("Image changed");
            String encoded=Base64.getEncoder().encodeToString(bytes);
            synchronized(ENCODED){ENCODED.put(key,encoded);}
            return encoded;
        }catch(IOException|RuntimeException error){throw new IllegalArgumentException(I18n.text("Imaginea salvata nu mai este disponibila. Reataseaza imaginea."),error);}}
    }
    static boolean candidate(String path){return path.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif)$");}
    static long estimate(List<String> paths){return paths.stream().filter(ImageInput::candidate).count()*4096;}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static List<Ref> snapshot(List<String> paths,Path directory,Path secret)throws IOException {
        List<Ref> result=new ArrayList<>();
        for(String name:paths){if(!candidate(name)||Files.isDirectory(Path.of(name)))continue;
            if(result.size()>=MAX_IMAGES)throw new IOException(I18n.text("Maximum 4 images per message"));
            Path source=AttachmentSupport.validate(Path.of(name),secret);
            if(Files.size(source)>MAX_BYTES)throw new IOException(I18n.text("Maximum 5 MiB per image"));
            byte[] bytes=Files.readAllBytes(source);if(bytes.length>MAX_BYTES)throw new IOException(I18n.text("Maximum 5 MiB per image"));
            String format;
            try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
                Iterator<ImageReader> readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException(I18n.text("Invalid image"));
                ImageReader reader=readers.next();try{reader.setInput(input,false,false);format=reader.getFormatName().toLowerCase(Locale.ROOT);
                    if((long)reader.getWidth(0)*reader.getHeight(0)>16_000_000L)throw new IOException(I18n.text("Maximum 16 megapixels"));
                    if(reader.getNumImages(true)>1)throw new IOException(I18n.text("Animated images are not supported"));
                    if(reader.read(0)==null)throw new IOException(I18n.text("Invalid image"));
                }finally{reader.dispose();}
            }
            String mime=switch(format){case "png"->"image/png";case "jpeg","jpg"->"image/jpeg";case "gif"->"image/gif";default->throw new IOException(I18n.text("Unsupported image format"));};
            Files.createDirectories(directory);String digest=hash(bytes);Path target=directory.resolve(digest+"."+format);
            try{Files.write(target,bytes,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException exists){if(!hash(Files.readAllBytes(target)).equals(digest))throw new IOException(I18n.text("Invalid image snapshot"));}
            result.add(new Ref(target.toAbsolutePath().toString(),digest,mime));
        }
        return List.copyOf(result);
    }
}
