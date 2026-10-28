package localchat;
import java.awt.*;import java.util.*;import java.util.List;import java.util.function.Consumer;import javax.swing.*;import javax.swing.event.*;

/** Explicit choices and optional draft suggestions. No inference, execution or memory writes. */
final class ComposerAssist extends JPanel {
 static final String TOOL="localchat_interaction";
 record Question(String id,String text,List<String> options,boolean multiple){Question(String id,String text,List<String> options){this(id,text,options,false);}}
 record Request(String message,List<Question> questions,List<String> suggestions){}
 static ChatClient.ToolSpec spec(){
   var question=Json.obj("type","object","properties",Json.obj("multiple",Json.obj("type","boolean","description","true only for independent actions selectable together"),"id",Json.obj("type","string"),"question",Json.obj("type","string"),"options",Json.obj("type","array","items",Json.obj("type","string"))),"required",List.of("id","question","options"),"additionalProperties",false);
   return new ChatClient.ToolSpec(TOOL,"Only for a concrete missing fact or permission blocking the next authorised action. Max 6 short questions, plain-text options, max 3 suggestions. Shows UI and pauses; nothing is approved. Findings go in your text.",Json.obj("type","object","properties",Json.obj("message",Json.obj("type","string"),"questions",Json.obj("type","array","items",question),"suggestions",Json.obj("type","array","items",Json.obj("type","string"))),"required",List.of("message","questions","suggestions"),"additionalProperties",false));
 }
 static Request parse(Object raw){
  String message=Objects.toString(Json.str(raw,"message"),"");if(message.length()>1200)throw new IllegalArgumentException("interaction_message");var questions=new ArrayList<Question>();var ids=new HashSet<String>();Object q=Json.get(raw,"questions");if(!(q instanceof List<?> qs)||qs.size()>6)throw new IllegalArgumentException("interaction_questions");
  for(Object item:qs){String id=Json.str(item,"id"),text=Json.str(item,"question");Object choices=Json.get(item,"options");if(id==null||id.isBlank()||id.length()>40||!ids.add(id)||text==null||text.isBlank()||text.length()>700||!(choices instanceof List<?> options)||options.size()>8)throw new IllegalArgumentException("interaction_question");var values=new ArrayList<String>();for(Object option:options){if(!(option instanceof String value)||value.isBlank()||value.length()>160||values.contains(value))throw new IllegalArgumentException("interaction_option");values.add(value);}Object multiple=Json.get(item,"multiple");if(multiple!=null&&!(multiple instanceof Boolean))throw new IllegalArgumentException("interaction_multiple");questions.add(new Question(id,text,List.copyOf(values),Boolean.TRUE.equals(multiple)));}
  Object s=Json.get(raw,"suggestions");if(!(s instanceof List<?> ss)||ss.size()>3)throw new IllegalArgumentException("interaction_suggestions");var suggestions=new ArrayList<String>();for(Object value:ss){if(!(value instanceof String text)||text.isBlank()||text.length()>1000||text.stripLeading().startsWith("/"))throw new IllegalArgumentException("interaction_suggestion");suggestions.add(text);}
  if(questions.isEmpty()&&suggestions.isEmpty())throw new IllegalArgumentException("interaction_empty");return new Request(message,List.copyOf(questions),List.copyOf(suggestions));
 }
 static Request detect(String answer){return InteractionFallback.detect(answer);}
 static final class GhostInput extends JTextArea {
  String ghost="";GhostInput(){super(3,40);}void ghost(String value){ghost=value;repaint();}
  protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(!getText().isEmpty()||ghost.isBlank())return;Graphics2D g=(Graphics2D)graphics.create();try{g.setColor(UIManager.getColor("Label.disabledForeground"));g.setFont(getFont().deriveFont(Font.ITALIC));Insets in=getInsets();int x=in.left+3,y=in.top+g.getFontMetrics().getAscent(),width=Math.max(50,getWidth()-x-in.right-6);String text=I18n.text("Tab pentru propunere")+": "+ghost.replaceAll("\\s+"," ");if(text.length()>140)text=text.substring(0,137)+"\u2026";StringBuilder line=new StringBuilder();for(String word:text.split(" ")){if(g.getFontMetrics().stringWidth(line+word)>width&&line.length()>0){g.drawString(line.toString(),x,y);y+=g.getFontMetrics().getHeight();line.setLength(0);break;}line.append(word).append(' ');}if(y<=in.top+g.getFontMetrics().getHeight()+2)g.drawString(line.toString(),x,y);}finally{g.dispose();}}
 }
 private final GhostInput input;private final Consumer<String> submit;private Request request;private String key="";private boolean dismissed,busy,building;
 private final Map<String,Set<String>> selected=new LinkedHashMap<>();
 private final Map<String,String> answers=new LinkedHashMap<>(),custom=new LinkedHashMap<>();
 Runnable onClose=()->{},onPresentation=()->{};
 final JButton send=new JButton(),close=new JButton(new UiIcon("close",13));
 ComposerAssist(GhostInput input,Consumer<String> submit){super(new BorderLayout(4,4));this.input=input;this.submit=submit;setOpaque(false);setAlignmentX(Component.LEFT_ALIGNMENT);setBorder(new javax.swing.border.AbstractBorder(){public Insets getBorderInsets(Component c){return new Insets(9,10,9,10);}public Insets getBorderInsets(Component c,Insets in){in.set(9,10,9,10);return in;}public void paintBorder(Component c,Graphics g,int x,int y,int w,int h){g.setColor(UIManager.getColor("Component.borderColor"));g.drawRoundRect(x,y,w-1,h-1,20,20);}});close.addActionListener(e->{dismiss();onClose.run();});send.addActionListener(e->{if(ready()&&!busy){String reply=answer();dismiss();submit.accept(reply);}});setVisible(false);}
 void clear(){request=null;key="";answers.clear();custom.clear();selected.clear();dismissed=false;input.ghost("");removeAll();setVisible(false);revalidate();}
 boolean waiting(){return request!=null&&!dismissed&&!request.questions.isEmpty();}
 void busy(boolean value){busy=value;send.setEnabled(!value&&ready());for(Component c:components(this))if(c instanceof AbstractButton||c instanceof JTextField)c.setEnabled(!value);send.setEnabled(!value&&ready());}
 private static java.util.List<Component> components(Container parent){java.util.List<Component> all=new ArrayList<>();for(Component c:parent.getComponents()){all.add(c);if(c instanceof Container box)all.addAll(components(box));}return all;}
 void retry(String message){dismissed=false;render();JTextArea feedback=wrapped(message);feedback.setFont(UIManager.getFont("Label.font").deriveFont(12f));JPanel footer=clearPanel(new BorderLayout(6,0));footer.add(feedback);footer.add(send,BorderLayout.EAST);add(footer,BorderLayout.SOUTH);revalidate();repaint();}
 void dismiss(){dismissed=true;input.ghost("");setVisible(false);revalidate();}
 void present(Object raw,String identity){presentLocal(parse(raw),identity);}
 void presentLocal(Request next,String identity){if(!key.equals(identity)){answers.clear();custom.clear();selected.clear();dismissed=false;key=identity;}request=next;render();}
 void restore(List<ChatClient.Message> history,String saved){
  String identity="";Request next=null;int boundary=-1;for(int i=history.size()-1;i>=0;i--)if(history.get(i).role.equals("user")){boundary=i;break;}
  Set<String> confirmed=new HashSet<>();for(var message:history)if(message.role.equals("tool")&&!message.isError)try{if("waiting_user".equals(Json.str(Json.parse(message.content),"status")))confirmed.add(message.toolCallId);}catch(RuntimeException ignored){}
  for(int i=history.size()-1;i>boundary;i--){var m=history.get(i);if(!m.role.equals("assistant"))continue;for(var call:m.toolCalls)if(call.name.equals(TOOL)&&confirmed.contains(call.id))try{next=parse(call.args);identity=call.id;}catch(RuntimeException ignored){}if(next!=null)break;}
  restoreLocal(next,identity,saved);
 }
 void restoreLocal(Request next,String identity,String saved){if(next==null){clear();return;}boolean same=key.equals(identity);request=next;if(!same){key=identity;answers.clear();custom.clear();selected.clear();dismissed=false;try{Object state=Json.parse(saved);if(key.equals(Json.str(state,"key"))){dismissed=Boolean.TRUE.equals(Json.get(state,"dismissed"));for(Question q:displayQuestions()){Object values=Json.get(state,"selected",q.id);if(values instanceof List<?> list){Set<String> valid=new LinkedHashSet<>();for(Object v:list)if(v instanceof String text&&q.options.contains(text))valid.add(text);selected.put(q.id,valid);}answers.put(q.id,Objects.toString(Json.str(state,"answers",q.id),""));custom.put(q.id,Objects.toString(Json.str(state,"custom",q.id),""));}}}catch(RuntimeException ignored){}}render();
 }
 String value(String id){String value=custom.getOrDefault(id,"").strip();Question q=displayQuestions().stream().filter(item->item.id.equals(id)).findFirst().orElse(null);if(q!=null&&q.multiple){String choices=String.join("; ",q.options.stream().filter(v->selected.getOrDefault(id,Set.of()).contains(v)).toList());return choices+(value.isEmpty()?"":(choices.isEmpty()?"":"; ")+value);}return value.isEmpty()?answers.getOrDefault(id,""):value;}
 String snapshot(){Map<String,Object> saved=new LinkedHashMap<>();selected.forEach((id,values)->saved.put(id,new ArrayList<>(values)));return Json.write(Json.obj("key",key,"dismissed",dismissed,"answers",answers,"custom",custom,"selected",saved));}
 boolean acceptFirst(){if(busy||request==null||dismissed||!request.questions.isEmpty()||request.suggestions.isEmpty()||!input.getText().isEmpty())return false;accept(request.suggestions.get(0));return true;}
 private void accept(String suggestion){if(busy)return;String separator=input.getText().isEmpty()?"":"\n\n";ComposerEditing.edit(input,()->{input.setCaretPosition(input.getDocument().getLength());input.replaceSelection(separator+suggestion);});dismiss();input.requestFocusInWindow();}
 private List<Question> displayQuestions(){if(request==null)return List.of();return request.questions.isEmpty()?List.of(new Question("__next_action",I18n.text("Pasul urmator"),request.suggestions)):request.questions;}
 private boolean ready(){return request!=null&&displayQuestions().stream().allMatch(q->!custom.getOrDefault(q.id,"").isBlank()||(q.multiple?!selected.getOrDefault(q.id,Set.of()).isEmpty():q.options.contains(answers.getOrDefault(q.id,""))));}
 private String answer(){StringBuilder b=new StringBuilder();for(Question q:displayQuestions()){if(!b.isEmpty())b.append('\n');if(request.questions.isEmpty()){b.append(value(q.id));continue;}b.append(q.text).append(": ");if(q.multiple){for(String option:q.options)b.append("\n").append(selected.getOrDefault(q.id,Set.of()).contains(option)?"[x] ":"[ ] ").append(option);String extra=custom.getOrDefault(q.id,"").strip();if(!extra.isEmpty())b.append("\n").append(I18n.text("Alt raspuns…")).append(": ").append(extra);}else b.append(value(q.id));}return b.toString();}
 @Override public Dimension getMaximumSize(){return new Dimension(720,getPreferredSize().height);}
 @Override public Dimension getPreferredSize(){Dimension size=super.getPreferredSize();return new Dimension(Math.min(720,size.width),size.height);}
 protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(getBackground());g.fillRoundRect(0,0,getWidth(),getHeight(),20,20);g.dispose();}
 private static JTextArea wrapped(String text){
  JTextArea area=new JTextArea(text){public Dimension getPreferredSize(){int height=textHeight(this,getWidth()>0?getWidth():620);Object max=getClientProperty("interaction.maxLines");if(max instanceof Number lines)height=Math.min(height,lines.intValue()*getFontMetrics(getFont()).getHeight()+2);return new Dimension(620,height);}};
  area.setFont(UIManager.getFont("Label.font"));area.setEditable(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setOpaque(false);area.setBorder(null);return area;
 }
 private static int textHeight(JTextArea area,int width){
  int lines=0;for(String paragraph:area.getText().split("\\R",-1)){
   if(paragraph.isEmpty()){lines++;continue;}var attributed=new java.text.AttributedString(paragraph);attributed.addAttribute(java.awt.font.TextAttribute.FONT,area.getFont());
   var measure=new java.awt.font.LineBreakMeasurer(attributed.getIterator(),area.getFontMetrics(area.getFont()).getFontRenderContext());
   while(measure.getPosition()<paragraph.length()){measure.nextLayout(Math.max(30,width));lines++;}
  }return Math.max(1,lines)*area.getFontMetrics(area.getFont()).getHeight()+2;
 }
 private static void invalidateChildren(Container box){for(Component child:box.getComponents()){if(child instanceof Container container)invalidateChildren(container);child.invalidate();}box.invalidate();}
 private static final class CardRows extends JPanel implements Scrollable {
  CardRows(){setOpaque(false);setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));}
  public void doLayout(){invalidateChildren(this);super.doLayout();}
  public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
  public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 24;}
  public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(24,r.height-24);}
  public boolean getScrollableTracksViewportWidth(){return true;}
  public boolean getScrollableTracksViewportHeight(){return false;}
 }
 private static JPanel clearPanel(LayoutManager layout){JPanel panel=new JPanel(layout);panel.setOpaque(false);return panel;}
 static String optionLabel(String value){return value.replaceFirst("^\\s*\\d+[.)]\\s+","");}

 private static String safe(String value){return value.replace("<","‹").replace(">","›");}
 void translate(){render();}
 private void render(){
  removeAll();input.ghost("");if(request==null||dismissed){setVisible(false);revalidate();return;}
  building=true;setBackground(UIManager.getColor("Panel.background"));
  JPanel header=clearPanel(new BorderLayout(6,0));JTextArea title=wrapped(request.questions.isEmpty()?I18n.text("Pasul urmator"):request.message);
  title.setFont(title.getFont().deriveFont(12f));title.putClientProperty("interaction.maxLines",2);title.setToolTipText(safe(request.message));header.add(title);
  close.setToolTipText(I18n.text("Inchide propunerile"));close.setMargin(new Insets(0,2,0,2));close.putClientProperty("JButton.buttonType","toolBarButton");close.setPreferredSize(new Dimension(20,20));header.add(close,BorderLayout.EAST);add(header,BorderLayout.NORTH);
  JPanel rows=new CardRows();
  if(request.questions.isEmpty()){
   // Optional suggestions are text links: click edits the draft; nothing is sent or executed.
   for(String suggestion:request.suggestions.stream().limit(2).toList()){
    JButton link=new JButton(clipLabel(suggestion,100));link.setFont(UIManager.getFont("Label.font").deriveFont(12f));link.setHorizontalAlignment(SwingConstants.LEFT);link.setBorder(BorderFactory.createEmptyBorder(4,2,4,2));link.setContentAreaFilled(false);link.setToolTipText(safe(suggestion));link.putClientProperty("interaction.suggestion",suggestion);link.putClientProperty("html.disable",true);link.addActionListener(e->accept(suggestion));link.setAlignmentX(Component.LEFT_ALIGNMENT);rows.add(link);
   }
   if(!request.suggestions.isEmpty())input.ghost(request.suggestions.get(0));add(rows);send.setEnabled(false);
  }else{
   for(Question q:request.questions){
    JPanel row=clearPanel(new BorderLayout(3,3));row.setAlignmentX(Component.LEFT_ALIGNMENT);row.setBorder(BorderFactory.createEmptyBorder(2,0,5,0));
    JTextArea label=wrapped(q.text);label.setFont(UIManager.getFont("Label.font").deriveFont(12f));label.putClientProperty("interaction.maxLines",2);label.setToolTipText(safe(q.text));JPanel questionHeader=clearPanel(new BorderLayout());questionHeader.add(label);
    if(q.text.length()>160){JToggleButton details=new JToggleButton(I18n.text("Detalii"));details.setFont(UIManager.getFont("Label.font").deriveFont(11f));details.putClientProperty("JButton.buttonType","toolBarButton");details.addActionListener(e->{label.putClientProperty("interaction.maxLines",details.isSelected()?1000:2);label.revalidate();rows.revalidate();});questionHeader.add(details,BorderLayout.EAST);}row.add(questionHeader,BorderLayout.NORTH);
    JPanel choices=clearPanel(null);choices.setLayout(new BoxLayout(choices,BoxLayout.Y_AXIS));ButtonGroup group=new ButtonGroup();
    JTextField other=new JTextField(custom.getOrDefault(q.id,""),16);other.setFont(UIManager.getFont("Label.font").deriveFont(12f));other.putClientProperty("JTextField.placeholderText",I18n.text("Alt raspuns…"));other.getAccessibleContext().setAccessibleName(q.text+" — "+I18n.text("Alt raspuns…"));
    for(String value:q.options){
     JToggleButton option=new JToggleButton(clipLabel(optionLabel(value),115));option.setFont(UIManager.getFont("Label.font").deriveFont(12f));option.setHorizontalAlignment(SwingConstants.LEFT);option.setMargin(new Insets(3,7,3,7));option.putClientProperty("html.disable",true);option.putClientProperty("JButton.buttonType","toolBarButton");option.putClientProperty("interaction.question",q.id);option.putClientProperty("interaction.option",value);option.setToolTipText(safe(value));option.setAlignmentX(Component.LEFT_ALIGNMENT);option.setMaximumSize(new Dimension(Integer.MAX_VALUE,28));option.setSelected(q.multiple?selected.getOrDefault(q.id,Set.of()).contains(value):value.equals(answers.get(q.id)));
     if(!q.multiple)group.add(option);
     option.addActionListener(e->{if(building||busy)return;if(q.multiple){Set<String> values=selected.computeIfAbsent(q.id,k->new LinkedHashSet<>());if(option.isSelected())values.add(value);else values.remove(value);}else{answers.put(q.id,value);other.setText("");}send.setEnabled(ready());});choices.add(option);
    }
    other.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}void changed(){custom.put(q.id,other.getText());if(!q.multiple&&!other.getText().isBlank()){group.clearSelection();answers.remove(q.id);}send.setEnabled(!busy&&ready());}});
    other.setAlignmentX(Component.LEFT_ALIGNMENT);other.setMaximumSize(new Dimension(Integer.MAX_VALUE,26));choices.add(Box.createVerticalStrut(3));choices.add(other);row.add(choices);rows.add(row);
   }
   JScrollPane scroll=new JScrollPane(rows);scroll.setPreferredSize(new Dimension(560,Math.min(164,request.questions.stream().mapToInt(q->48+q.options.size()*26).sum())));scroll.setOpaque(false);scroll.getViewport().setOpaque(false);scroll.setBorder(null);scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.getVerticalScrollBar().setUnitIncrement(24);add(scroll);
   send.setText(I18n.text("Trimite raspunsurile"));send.setFont(UIManager.getFont("Label.font").deriveFont(12f));send.setMargin(new Insets(2,8,2,8));send.putClientProperty("JButton.buttonType","roundRect");JPanel footer=clearPanel(new FlowLayout(FlowLayout.RIGHT,0,0));footer.add(send);add(footer,BorderLayout.SOUTH);
  }
  building=false;busy(busy);if(request.questions.isEmpty())send.setEnabled(false);setVisible(true);revalidate();repaint();onPresentation.run();
 }
 private static String clipLabel(String text,int limit){String value=text.replaceAll("\\s+"," ");return value.length()>limit?value.substring(0,limit-1)+"\u2026":value;}
}
