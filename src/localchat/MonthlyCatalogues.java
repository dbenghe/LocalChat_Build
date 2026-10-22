package localchat;
import java.nio.file.*;import java.time.*;import java.io.*;import java.util.*;
/** One local unread draft each month; no API, filesystem watcher or account automation. */
final class MonthlyCatalogues {
 private final Path marker;
 MonthlyCatalogues(Path marker){this.marker=marker;}
 boolean due(YearMonth month){try{return !month.toString().equals(Json.str(Json.parse(Files.readString(marker)),"month"));}catch(IOException|RuntimeException e){return true;}}
 boolean ensure(YearMonth month,Path chats,String workDir,Conversations.State state)throws IOException{
  if(!due(month))return false;String token="catalogues:"+month;
  boolean exists=Conversations.list(chats).stream().anyMatch(c->token.equals(c.state().catalogueMonth));
  if(!exists){state.catalogueMonth=token;state.unread=true;state.projectStatus="draft";state.title=I18n.text("Actualizare cataloage fabrici — {0}",month);state.draft=I18n.text("Descarca listele actuale de articole pentru fabricile folosite. Compara separat Soest, Alphen, Veenoord si Lelystad cu snapshoturile precedente. Citeste productie-projecten si references/cataloage-fabrici.md. Arata articolele noi, codurile/descrierile/unitatile/statusurile modificate si articolele absente; pastreaza originalele si arhiva locala. Cere prin card listele lipsa. Actualizeaza cataloagele proprii LocalChat numai dupa verificarea sursei si fabricii. Nu modifica proiectele de productie.");Conversations.save(Conversations.newFile(chats),workDir,List.of(),state);}
  Files.createDirectories(marker.getParent());Path temp=Files.createTempFile(marker.getParent(),"catalogues-",".tmp");try{Files.writeString(temp,Json.write(Json.obj("month",month.toString())));try{Files.move(temp,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,marker,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}return !exists;
 }
}
