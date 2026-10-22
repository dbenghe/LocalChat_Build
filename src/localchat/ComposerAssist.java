package localchat;
import java.awt.*;import java.util.*;import java.util.List;import java.util.function.Consumer;import javax.swing.*;import javax.swing.event.*;

/** Explicit choices and optional draft suggestions. No inference, execution or memory writes. */
final class ComposerAssist extends JPanel {
 static final String TOOL="localchat_interaction";
 record Question(String id,String text,List<String> options,boolean multiple){Question(String id,String text,List<String> options){this(id,text,options,false);}}
 record Request(String message,List<Question> questions,List<String> suggestions){}
 static ChatClient.ToolSpec spec(){
  var question=Json.obj("type","object","properties",Json.obj("multiple",Json.obj("type","boolean","description","true for independent actions that may be selected together; false for mutually exclusive answers"),"id",Json.obj("type","string","maxLength",40),"question",Json.obj("type","string","maxLength",700),"options",Json.obj("type","array","maxItems",8,"items",Json.obj("type","string","maxLength",160))),"required",List.of("id","question","options"),"additionalProperties",false);
  return new ChatClient.ToolSpec(TOOL,"REQUIRED when waiting for an answer or confirmation: display interactive cards, not prose-only questions. Present up to six concrete clarification questions with choices and free-text answers. Set multiple=true for independent actions to approve together; use false for yes/no or mutually exclusive alternatives. Also offer up to three useful next-message suggestions grounded in the CURRENT task, tool evidence, skills and permitted memories. Do not repeat an already granted approval. Include useful findings in your accompanying assistant text. This only displays UI and pauses this turn: no action, approval, message or memory is created until the user explicitly submits. Suggestions are optional drafts accepted with Tab, never automatic execution. Do not use for trivial or invented next steps.",Json.obj("type","object","properties",Json.obj("message",Json.obj("type","string","maxLength",1200),"questions",Json.obj("type","array","maxItems",6,"items",question),"suggestions",Json.obj("type","array","maxItems",3,"items",Json.obj("type","string","maxLength",1000))),"required",List.of("message","questions","suggestions"),"additionalProperties",false));
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
  protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(!getText().isEmpty()||ghost.isBlank())return;Graphics2D g=(Graphics2D)graphics.create();try{g.setColor(UIManager.getColor("Label.disabledForeground"));g.setFont(getFont().deriveFont(Font.ITALIC));Insets in=getInsets();int x=in.left+3,y=in.top+g.getFontMetrics().getAscent(),width=Math.max(50,getWidth()-x-in.right-6);String text=I18n.text("Tab pentru propunere")+": "+ghost;StringBuilder line=new StringBuilder();for(String word:text.split(" ")){if(g.getFontMetrics().stringWidth(line+word)>width&&line.length()>0){g.drawString(line.toString(),x,y);y+=g.getFontMetrics().getHeight();line.setLength(0);if(y>getHeight()-in.bottom)break;}line.append(word).append(' ');}if(y<=getHeight()-in.bottom)g.drawString(line.toString(),x,y);}finally{g.dispose();}}
 }
 private final GhostInput input;private final Consumer<String> submit;private Request request;private String key="";private boolean dismissed,busy,building;
 private final Map<String,Set<String>> selected=new LinkedHashMap<>();
 private final Map<String,String> answers=new LinkedHashMap<>(),custom=new LinkedHashMap<>();
 final JButton send=new JButton(),close=new JButton(new UiIcon("close",13));
 ComposerAssist(GhostInput input,Consumer<String> submit){super(new BorderLayout(4,4));this.input=input;this.submit=submit;setOpaque(false);setAlignmentX(Component.LEFT_ALIGNMENT);setBorder(new javax.swing.border.AbstractBorder(){public Insets getBorderInsets(Component c){return new Insets(9,10,9,10);}public Insets getBorderInsets(Component c,Insets in){in.set(9,10,9,10);return in;}public void paintBorder(Component c,Graphics g,int x,int y,int w,int h){g.setColor(UIManager.getColor("Component.borderColor"));g.drawRoundRect(x,y,w-1,h-1,20,20);}});close.addActionListener(e->dismiss());send.addActionListener(e->{if(ready()&&!busy){String reply=answer();dismiss();submit.accept(reply);}});setVisible(false);}
 void clear(){request=null;key="";answers.clear();custom.clear();selected.clear();dismissed=false;input.ghost("");removeAll();setVisible(false);revalidate();}
 boolean waiting(){return request!=null&&!dismissed&&!request.questions.isEmpty();}
 void busy(boolean value){busy=value;send.setEnabled(!value&&ready());for(Component c:components(this))if(c instanceof JRadioButton||c instanceof JCheckBox||c instanceof JTextField)c.setEnabled(!value);}
 private static java.util.List<Component> components(Container parent){java.util.List<Component> all=new ArrayList<>();for(Component c:parent.getComponents()){all.add(c);if(c instanceof Container box)all.addAll(components(box));}return all;}
 void dismiss(){dismissed=true;input.ghost("");setVisible(false);revalidate();}
 void present(Object raw,String identity){presentLocal(parse(raw),identity);}
 void presentLocal(Request next,String identity){if(!key.equals(identity)){answers.clear();custom.clear();selected.clear();dismissed=false;key=identity;}request=next;render();}
 void restore(List<ChatClient.Message> history,String saved){
  String identity="";Request next=null;int boundary=-1;for(int i=history.size()-1;i>=0;i--)if(history.get(i).role.equals("user")){boundary=i;break;}
  Set<String> confirmed=new HashSet<>();for(var message:history)if(message.role.equals("tool")&&!message.isError)try{if("waiting_user".equals(Json.str(Json.parse(message.content),"status")))confirmed.add(message.toolCallId);}catch(RuntimeException ignored){}
  for(int i=history.size()-1;i>boundary;i--){var m=history.get(i);if(!m.role.equals("assistant"))continue;for(var call:m.toolCalls)if(call.name.equals(TOOL)&&confirmed.contains(call.id))try{next=parse(call.args);identity=call.id;}catch(RuntimeException ignored){}if(next!=null)break;if(m.toolCalls.isEmpty()&&!m.content.isBlank()){next=detect(ReasoningDisplay.answer(m.content));if(next!=null){identity="legacy-"+ModelCatalog.hash(m.content+boundary);break;}}}
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
  removeAll();input.ghost("");if(request==null||dismissed){setVisible(false);revalidate();return;}building=true;setBackground(UIManager.getColor("Panel.background"));
  JTextArea title=wrapped(request.message.isBlank()?I18n.text("Pasul urmator"):request.message);title.putClientProperty("html.disable",true);title.putClientProperty("interaction.maxLines",3);title.setToolTipText(safe(title.getText()));JPanel header=clearPanel(new BorderLayout(5,0));header.add(title);header.add(close,BorderLayout.EAST);close.setToolTipText(I18n.text("Inchide propunerile"));close.setMargin(new Insets(2,3,2,3));close.putClientProperty("JButton.buttonType","toolBarButton");close.setPreferredSize(new Dimension(23,23));add(header,BorderLayout.NORTH);
  if(!displayQuestions().isEmpty()){
   JPanel rows=new CardRows();
   for(Question q:displayQuestions()){
    JPanel row=clearPanel(new BorderLayout(4,4));row.setAlignmentX(Component.LEFT_ALIGNMENT);row.setBorder(BorderFactory.createEmptyBorder(3,0,6,0));JTextArea label=wrapped(q.text+(q.multiple?" — "+I18n.text("Poti bifa mai multe optiuni."):""));label.putClientProperty("html.disable",true);label.setToolTipText(safe(q.text));row.add(label,BorderLayout.NORTH);
    JPanel choices=clearPanel(null);choices.setLayout(new BoxLayout(choices,BoxLayout.Y_AXIS));ButtonGroup group=new ButtonGroup();java.util.List<AbstractButton> buttons=new ArrayList<>();JTextField other=new JTextField(custom.getOrDefault(q.id,""),16);other.putClientProperty("FlatLaf.style","arc: 12");other.putClientProperty("JTextField.placeholderText",I18n.text("Alt raspuns…"));other.getAccessibleContext().setAccessibleName(q.text+" — "+I18n.text("Alt raspuns…"));
    for(int i=0;i<q.options.size();i++){String value=q.options.get(i);AbstractButton option=q.multiple?new JCheckBox(Integer.toString(i+1)):new JRadioButton(Integer.toString(i+1));option.setAlignmentX(Component.LEFT_ALIGNMENT);option.setOpaque(false);option.setVerticalAlignment(SwingConstants.TOP);option.setMargin(new Insets(0,1,0,3));option.setPreferredSize(new Dimension(38,22));option.putClientProperty("html.disable",true);option.putClientProperty("interaction.question",q.id);option.putClientProperty("interaction.option",value);option.setSelected(q.multiple?selected.getOrDefault(q.id,Set.of()).contains(value):value.equals(answers.get(q.id)));option.setToolTipText(safe(value));option.setMaximumSize(new Dimension(Integer.MAX_VALUE,30));option.getAccessibleContext().setAccessibleName(q.text+" — "+value);option.addActionListener(e->{if(!building&&!busy){if(q.multiple){Set<String> values=selected.computeIfAbsent(q.id,k->new LinkedHashSet<>());if(option.isSelected())values.add(value);else values.remove(value);}else{answers.put(q.id,value);other.setText("");}send.setEnabled(ready());}});if(!q.multiple)group.add(option);buttons.add(option);JTextArea optionText=wrapped(optionLabel(value));JPanel choice=new JPanel(new BorderLayout(5,0)){
     public Dimension getPreferredSize(){return new Dimension(660,Math.max(22,textHeight(optionText,Math.max(100,ComposerAssist.this.getWidth()-85)))+6);}
     public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
    };choice.setBorder(BorderFactory.createEmptyBorder(3,0,3,0));choice.setAlignmentX(Component.LEFT_ALIGNMENT);choice.setOpaque(false);choice.add(option,BorderLayout.WEST);optionText.putClientProperty("interaction.label",true);optionText.setToolTipText(safe(value));optionText.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseReleased(java.awt.event.MouseEvent e){if(SwingUtilities.isLeftMouseButton(e)&&option.isEnabled())option.doClick();}});choice.add(optionText);choices.add(choice);}
    other.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}void changed(){custom.put(q.id,other.getText());if(!q.multiple&&!other.getText().isBlank()){group.clearSelection();answers.remove(q.id);}send.setEnabled(!busy&&ready());}});
    other.setAlignmentX(Component.LEFT_ALIGNMENT);other.setMaximumSize(new Dimension(Integer.MAX_VALUE,28));choices.add(Box.createVerticalStrut(4));choices.add(other);row.add(choices);rows.add(row);
   }
   JScrollPane scroll=new JScrollPane(rows);int height=displayQuestions().stream().mapToInt(q->60+q.options.size()*28).sum();scroll.setPreferredSize(new Dimension(660,Math.min(155,height)));scroll.setOpaque(false);scroll.getViewport().setOpaque(false);scroll.setBorder(null);scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.getVerticalScrollBar().setUnitIncrement(24);add(scroll);send.setText(I18n.text("Trimite raspunsurile"));send.setMargin(new Insets(3,9,3,9));send.putClientProperty("JButton.buttonType","roundRect");JPanel footer=clearPanel(new FlowLayout(FlowLayout.RIGHT,0,0));footer.add(send);add(footer,BorderLayout.SOUTH);putClientProperty("FlatLaf.style","arc: 18");if(request.questions.isEmpty())input.ghost(request.suggestions.get(0));

  }
  building=false;busy(busy);setVisible(true);revalidate();repaint();
 }
}
