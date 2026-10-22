package localchat;
import javax.swing.*;import java.awt.*;import java.util.List;
/** Local workflow labels are user-owned, never inferred completion claims. */
final class ProjectStatus {
 static final List<String> VALUES=List.of("draft","in_progress","partial","completed","restarted");
 static String valid(String value){return value!=null&&VALUES.contains(value)?value:"draft";}
 static String label(String value){return I18n.text(switch(valid(value)){case "in_progress"->"In lucru";case "partial"->"Partial";case "completed"->"Finalizat";case "restarted"->"Repornit";default->"Draft";});}
 static Icon icon(String value){return StatusColors.icon(valid(value),14);}
}
