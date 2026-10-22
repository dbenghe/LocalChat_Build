package localchat;
import java.nio.file.*;import java.util.*;
/** Exact local source folder identity; a project number alone never joins conversations. */
final class HotfolderChats {
 static String path(String value){try{return value==null||value.isBlank()?"":Path.of(value).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);}catch(RuntimeException e){return "";}}
 static boolean matches(Conversations.Saved chat,Hotfolder.Change change){
  var state=chat.state();if("normal".equals(state.contextMode)||Boolean.FALSE.equals(state.toolsEnabled))return false;
  String folder=path(change.folder().toString());if(folder.equals(path(state.hotfolderProject))||folder.equals(path(chat.workDir())))return true;
  if(!state.hotfolderProject.isBlank())return false;
  boolean project=change.project().equals(state.factoryProject)||chat.messages().stream().filter(m->m.role.equals("user")).anyMatch(m->change.project().equals(ProjectLocations.project(m.content,null,"")));
  if(!project)return false;
  try{Path base=Path.of(chat.workDir()).toAbsolutePath().normalize(),target=change.folder().toAbsolutePath().normalize();if(folder.equals(path(base.resolve(change.project()).toString())))return true;if(!target.startsWith(base))return false;String relative=base.relativize(target).toString().replace('\\','/').toLowerCase(Locale.ROOT);return chat.messages().stream().filter(m->m.role.equals("user")).anyMatch(m->m.content.replace('\\','/').replaceAll("/+","/").toLowerCase(Locale.ROOT).contains(relative));}catch(RuntimeException e){return false;}
 }
 static Conversations.Saved find(List<Conversations.Saved> chats,Hotfolder.Change change){return chats.stream().filter(c->matches(c,change)).sorted(Comparator.<Conversations.Saved>comparingInt(c->c.messages().isEmpty()?1:0).thenComparing(c->c.file().getFileName().toString())).findFirst().orElse(null);}
 static String append(String original,String addition){return addition.isBlank()||original.contains(addition)?original:original.isBlank()?addition:original+"\n\n"+addition;}
 static String event(Hotfolder.Change change){return ModelCatalog.hash(path(change.folder().toString())+change.signature());}
 static void mark(Conversations.State state,Hotfolder.Change change,boolean existing){state.projectStatus=existing&&(!state.projectStatus.equals("draft")||!state.hotfolderPendingDraft.isBlank())?"restarted":"draft";state.archived=false;state.unread=true;state.queuePaused=true;state.hotfolderProject=change.folder().toString();state.hotfolderEvent=event(change);}
}
