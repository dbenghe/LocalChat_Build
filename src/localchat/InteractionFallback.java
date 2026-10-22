package localchat;
import java.text.Normalizer;import java.util.*;import java.util.List;import java.util.regex.*;
/** Local compatibility UI for explicit unanswered requests. Never invents or executes a next step. */
final class InteractionFallback {
 private static String fold(String s){return Normalizer.normalize(s,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);}
 private static boolean waiting(String s){return fold(s).matches("(?s).*(astept.{0,25}(confirm|raspuns)|am nevoie de un raspuns|confirma|confirm|await.{0,25}(answer|approval)|waiting for|bevestig|wacht op|potwierdz|czekam|onay|yanitini|esper.{0,15}(confirm|respuesta)).*");}
 private static boolean question(String s){String f=fold(s);return s.contains("?")&&f.matches("(?s).*(intrebarea pentru tine|o intrebare|trebuie|doresti|vrei|care |acelasi|ramane|merge |should |would you|do you |which |question for you|same |shall |wil je|moet |welke |vraag|czy |ktory|pregunta|quieres|debe |hangi|ister misin).*");}
 static ComposerAssist.Request detect(String answer){
  List<String> lines=new ArrayList<>();boolean fence=false;for(String raw:answer.split("\\R")){String t=raw.strip();if(t.startsWith("```")||t.startsWith("~~~")){fence=!fence;continue;}if(fence||t.startsWith(">"))continue;lines.add(t.replace("**","").replaceAll("^#{1,6}\\s+","").strip());}
  String visible=String.join("\n",lines);if(!waiting(visible))return nextSteps(lines);
  List<ComposerAssist.Question> qs=new ArrayList<>();List<String> actions=new ArrayList<>();boolean actionSection=false,overflow=false;Set<String> used=new HashSet<>();
  for(String line:lines){String f=fold(line);if(!line.matches("^\\d+[.)]\\s+.*")&&(waiting(line)||f.matches(".*(pasi pregatiti|dupa raspunsul tau|prepared steps|after your answer|voorgestelde stappen).*")))actionSection=true;
   Matcher numbered=Pattern.compile("^\\d+[.)]\\s+(.+)$").matcher(line);if(!numbered.matches())continue;String text=numbered.group(1);
   if(text.matches("(?is).*(da\\s*/\\s*nu|yes\\s*/\\s*no|ja\\s*/\\s*nee|si\\s*/\\s*no|tak\\s*/\\s*nie|evet\\s*/\\s*hayir).*")){qs.add(new ComposerAssist.Question("legacy-"+qs.size(),text,List.of(I18n.text("Da"),I18n.text("Nu"))));used.add(line);}
   else if(actionSection&&action(text)&&!text.contains("?")){if(text.length()>160)overflow=true;else{actions.add(text);used.add(line);}}
  }
  if(overflow||actions.size()>8)return null;
  if(!actions.isEmpty())qs.add(new ComposerAssist.Question("legacy-actions",I18n.text("Ce actiuni alegi?"),List.copyOf(actions),true));
  for(String line:lines){if(used.contains(line)||!question(line)||line.length()>700)continue;if(qs.stream().noneMatch(q->q.text().equals(line)))qs.add(new ComposerAssist.Question("legacy-"+qs.size(),line,choices(line)));}
  // Too much text stays prose rather than silently dropping an unanswered request.
  if(qs.isEmpty()||qs.size()>6)return null;
  return new ComposerAssist.Request(I18n.text("Alege un raspuns sau scrie altceva."),List.copyOf(qs),List.of());
 }
 private static boolean action(String text){return fold(text).matches("^(copiez|copiaza|copiere|mut|muta|mutare|verific|verifica|oprit|opreste|creeaza|genereaza|actualizeaza|copy|move|check|verify|stop|create|generate|update|kopieer|verplaats|controleer|maak|copia|mueve|comprueba|crea|skopiuj|przenies|sprawdz|utworz|kopyala|tasi|kontrol).*");}
 private static List<String> choices(String line){String text=line.substring(0,line.indexOf('?')).replaceFirst("^[^:]{1,40}:\\s*","").strip();String[] parts=text.split("(?iu)\\s+(?:sau|or|of|lub|veya)\\s+",2);if(parts.length==2&&Arrays.stream(parts).allMatch(p->!p.isBlank()&&p.length()<=160))return List.of(parts[0],parts[1]);if(fold(text).matches("(?s).*(trebuie|doresti|vrei|pot sa|should|would you|do you|shall|wil je|moet|czy|quieres|debe|ister misin).*"))return List.of(I18n.text("Da"),I18n.text("Nu"));return List.of();}
 private static ComposerAssist.Request nextSteps(List<String> lines){List<String> proposals=new ArrayList<>();boolean active=false;for(String line:lines){String f=fold(line);if(f.matches("^(pasul urmator|urmatorii pasi|propunere|next steps?|suggested next steps?|volgende stap|proximos pasos|nastepny krok|sonraki adim)\\s*[:—-].*")){active=true;String inline=line.replaceFirst("^[^:—-]+[:—-]\\s*","");if(!inline.isBlank()&&!inline.contains("?"))proposals.add(inline);continue;}if(!active||line.isBlank())continue;if(line.matches("^[-*0-9].*")){String text=line.replaceFirst("^(?:[-*]|\\d+[.)])\\s+","");if(!text.contains("?")&&text.length()<=1000)proposals.add(text);}else break;if(proposals.size()==3)break;}if(proposals.isEmpty()||proposals.size()>3||proposals.stream().anyMatch(p->p.length()>1000||p.stripLeading().startsWith("/")))return null;return new ComposerAssist.Request(I18n.text("Pasul urmator"),List.of(),List.copyOf(proposals));}

}
