package localchat;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Visible per-chat task monitor; reads archived tool evidence without inference. EDT only. */
final class AgentPanel extends JPanel {
    static final int MAX_ROWS=18;
    private final LinkedHashMap<String,Row> rows=new LinkedHashMap<>();
    private final JPanel cards=new JPanel(),header=new JPanel(new BorderLayout(4,0));
    private final JLabel heading=new JLabel(),empty=new JLabel();
    final JButton close=new JButton(new UiIcon("close",14));
    final JButton stop=new JButton();
    private boolean dark,busy;private Runnable changed=()->{};
    void onChange(Runnable listener){changed=listener;changed.run();}
    int runningCount(){return busy?(int)rows.values().stream().filter(row->row.state.equals("running")||row.state.startsWith("tool:")).count():0;}
    int waitingCount(){return busy?(int)rows.values().stream().filter(row->row.state.equals("queued")||row.state.equals("pending")).count():0;}
    String summaryState(){if(runningCount()>0)return "running";if(waitingCount()>0)return "pending";if(rows.isEmpty())return "draft";for(String state:List.of("failed","partial","unconfirmed","cancelled"))if(rows.values().stream().anyMatch(row->row.state.equals(state)||state.equals("unconfirmed")&&active(row.state)))return state;return rows.values().stream().allMatch(row->row.state.equals("done"))?"done":"unconfirmed";}
    private static final class Row {
        String id,title,task,model,state="queued",answer="",error="";boolean pending,expanded;
        Row(String id,String title,String task,String model){this.id=id;this.title=title;this.task=task;this.model=model;}
    }
    AgentPanel(Runnable hide,Runnable cancel){
        super(new BorderLayout(6,6));setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        setPreferredSize(new Dimension(310,300));setMinimumSize(new Dimension(230,100));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD,13f));header.add(heading);header.add(close,BorderLayout.EAST);add(header,BorderLayout.NORTH);
        addComponentListener(new java.awt.event.ComponentAdapter(){public void componentResized(java.awt.event.ComponentEvent e){cards.revalidate();}});
        close.setMargin(new Insets(3,3,3,3));close.addActionListener(e->hide.run());
        cards.setLayout(new BoxLayout(cards,BoxLayout.Y_AXIS));JScrollPane scroll=new JScrollPane(cards);scroll.setBorder(null);scroll.getVerticalScrollBar().setUnitIncrement(18);add(scroll);
        stop.addActionListener(e->cancel.run());add(stop,BorderLayout.SOUTH);translate();
    }
    void queued(String id,String title,String task,String model){rows.putIfAbsent(id,new Row(id,title,task,model));trim();rebuild();}
    void progress(String id,String state){Row row=rows.get(id);if(row!=null){row.state=state;rebuild();}}
    void busy(boolean value){busy=value;stop.setEnabled(value&&rows.values().stream().anyMatch(row->active(row.state)));changed.run();}
    int count(){return rows.size();}
    String state(String id){return rows.containsKey(id)?rows.get(id).state:"";}
    String task(String id){return rows.containsKey(id)?rows.get(id).task:"";}
    void result(String text){try{Object data=Json.parse(text);String group=Json.str(data,"group_id"),model=Objects.toString(Json.str(data,"model"),"");if(group==null||group.isBlank()||!(Json.get(data,"tasks") instanceof List<?> tasks))return;int i=0;
        for(Object item:tasks){String id=group+"-"+(++i),title=Objects.toString(Json.str(item,"title"),""),task=Objects.toString(Json.str(item,"task"),"");Row row=rows.get(id);if(row==null){row=new Row(id,title,task,model);rows.put(id,row);}else if(row.task.isBlank())row.task=task;
            row.state=Objects.toString(Json.str(item,"status"),"unconfirmed");row.answer=Objects.toString(Json.str(item,"answer"),"");row.error=Objects.toString(Json.str(item,"error"),"");row.pending=Boolean.TRUE.equals(Json.get(item,"completion_pending"));
        }trim();rebuild();}catch(RuntimeException ignored){}}
    void restore(List<ChatClient.Message> history){
        Map<String,Boolean> expanded=new HashMap<>();rows.forEach((id,row)->expanded.put(id,row.expanded));rows.clear();
        Map<String,ChatClient.Message> results=new HashMap<>();for(var m:history)if(m.role.equals("tool"))results.put(m.toolCallId,m);
        for(var m:history)for(var c:m.toolCalls)if(c.name.equals("run_agents")){var result=results.get(c.id);boolean structured=false;if(result!=null)try{Object value=Json.parse(result.content);structured=Json.str(value,"group_id")!=null&&!Json.str(value,"group_id").isBlank()&&Json.get(value,"tasks") instanceof List<?>;if(structured)result(result.content);}catch(RuntimeException ignored){}
            if(!structured&&Json.get(c.args,"tasks") instanceof List<?> tasks){int i=0;for(Object task:tasks){String id=c.id+"-"+(++i);Row row=new Row(id,Objects.toString(Json.str(task,"title"),""),Objects.toString(Json.str(task,"task"),""),"");row.state="unconfirmed";rows.put(id,row);}}
        }
        rows.forEach((id,row)->{row.expanded=expanded.getOrDefault(id,false);if(active(row.state))row.state="unconfirmed";});trim();rebuild();
    }
    void translate(){heading.setText(I18n.text("Agenti"));close.setToolTipText(I18n.text("Ascunde panoul agentilor"));close.getAccessibleContext().setAccessibleName(close.getToolTipText());stop.setText(I18n.text("Opreste tura"));empty.setText(I18n.text("Niciun agent pornit in aceasta conversatie."));getAccessibleContext().setAccessibleName(I18n.text("Panou agenti"));rebuild();}
    void theme(boolean value){dark=value;rebuild();}
    private void trim(){while(rows.size()>MAX_ROWS)rows.remove(rows.keySet().iterator().next());}
    private static boolean active(String state){return Set.of("queued","running","pending").contains(state)||state.startsWith("tool:");}
    private String label(Row row){if(row.error.equals("group_deadline"))return I18n.text("Termen depasit");return row.state.startsWith("tool:")?I18n.text("Rulez {0}…",row.state.substring(5)):I18n.text(switch(row.state){case "queued"->"In asteptare";case "running"->"In curs";case "done"->"Gata.";case "partial"->"Rezultat partial";case "cancelled"->"Anulat";case "failed"->"Eroare";default->"Stare neconfirmata";});}
    private Color statusColor(Row row){return StatusColors.color(row.state,dark);}

    private static JLabel plain(String text){JLabel label=new JLabel(text);label.putClientProperty("html.disable",true);return label;}
    private JTextArea text(String value,int font){JTextArea text=new JTextArea(value){public Dimension getPreferredSize(){int width=Math.max(155,AgentPanel.this.getWidth()>0?AgentPanel.this.getWidth()-54:255);setSize(width,Short.MAX_VALUE);Dimension size=super.getPreferredSize();size.width=width;return size;}};text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setOpaque(false);text.setFont(getFont().deriveFont((float)font));text.setForeground(new Color(dark?0xEDF1F7:0x202733));text.setBorder(null);return text;}
    private void rebuild(){
        if(cards==null)return;heading.setText(I18n.text("Agenti")+" ("+rows.size()+")");cards.removeAll();Color background=new Color(dark?0x1B1F26:0xFFFFFF);setBackground(background);header.setBackground(background);cards.setBackground(background);close.setBackground(background);close.setForeground(new Color(dark?0xEDF1F7:0x202733));stop.setBackground(new Color(dark?0x282E38:0xEDF1F7));stop.setForeground(new Color(dark?0xEDF1F7:0x202733));heading.setForeground(new Color(dark?0xEDF1F7:0x202733));empty.setForeground(new Color(dark?0xABB8CC:0x596477));
        if(rows.isEmpty()){cards.add(empty);cards.add(Box.createVerticalStrut(8));cards.add(text(I18n.text("Porneste agentii din prompt sau prin /agents, in profilul MBS."),12));}
        for(Row row:rows.values()){
            JPanel card=new JPanel(new BorderLayout(4,5)){
                public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
                protected void paintComponent(Graphics g){Graphics2D a=(Graphics2D)g.create();a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);a.setColor(new Color(dark?0x282E38:0xEDF1F7));a.fillRoundRect(0,0,getWidth(),getHeight(),14,14);a.dispose();}
            };card.setOpaque(false);card.setBorder(BorderFactory.createEmptyBorder(9,10,9,10));card.setAlignmentX(LEFT_ALIGNMENT);
            JPanel top=new JPanel(new BorderLayout(4,3));top.setOpaque(false);JLabel title=plain(row.title);title.setFont(getFont().deriveFont(Font.BOLD,12f));title.setForeground(new Color(dark?0xEDF1F7:0x202733));top.add(title,BorderLayout.NORTH);JLabel state=plain(label(row));state.setIcon(StatusColors.icon(row.state,14));state.setBackground(new Color(dark?0x282E38:0xEDF1F7));state.setFont(getFont().deriveFont(11f));state.setForeground(statusColor(row));top.add(state);card.add(top,BorderLayout.NORTH);
            JPanel body=new JPanel();body.setOpaque(false);body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS));if(!row.model.isBlank())body.add(text(row.model,11));body.add(text(shorten(row.task,155),12));
            if(row.pending)body.add(text(I18n.text("Rezultatul operatiilor inca in curs nu este confirmat."),11));
            JToggleButton details=new JToggleButton(I18n.text("Detalii agent"),row.expanded);details.setMargin(new Insets(2,5,2,5));details.setBackground(new Color(dark?0x343D4B:0xFFFFFF));details.setForeground(new Color(dark?0xEDF1F7:0x202733));details.setFont(getFont().deriveFont(11f));details.setAlignmentX(LEFT_ALIGNMENT);details.addActionListener(e->{row.expanded=details.isSelected();rebuild();});body.add(Box.createVerticalStrut(5));body.add(details);
            if(row.expanded){String content=I18n.text("Sarcina")+":\n"+row.task;if(!row.answer.isBlank())content+="\n\n"+I18n.text("Rezultat")+":\n"+row.answer;if(!row.error.isBlank())content+="\n\n"+I18n.text("Diagnostic")+": "+row.error;body.add(Box.createVerticalStrut(5));body.add(text(content,12));}
            card.add(body);cards.add(card);cards.add(Box.createVerticalStrut(8));
        }
        busy(busy);cards.revalidate();cards.repaint();
    }
    private static String shorten(String text,int max){return text.length()<=max?text:text.substring(0,max)+"…";}
}
