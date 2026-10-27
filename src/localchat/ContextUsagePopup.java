package localchat;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;

/** Live context breakdown; estimates and API billing remain distinct. No inference or timers. */
final class ContextUsagePopup extends JPopupMenu {
    static final Color[] COLORS={new Color(0x9CA5B2),new Color(0xAC93EB),new Color(0x21BBCB),new Color(0xE3A33B)};
    final JLabel title=new JLabel(),occupancy=new JLabel(),budget=new JLabel();
    final JTextArea explanation=new JTextArea(),usage=new JTextArea();
    final JLabel[] names={new JLabel(),new JLabel(),new JLabel(),new JLabel()},values={new JLabel(),new JLabel(),new JLabel(),new JLabel()};
    final Segments segments=new Segments();
    final JToggleButton api=new JToggleButton();
    private long system,tools,conversation,results;private int maximum;private String apiText="";
    private String renderedLanguage="";private boolean darkTheme;private Color outline=new Color(0xCFD3DA);
    private final JPanel rows=new JPanel(new GridLayout(4,1,0,5));
    ContextUsagePopup(){
        putClientProperty("Popup.borderCornerRadius",18);putClientProperty("Popup.roundedBorderWidth",1);putClientProperty("Popup.dropShadowPainted",true);putClientProperty("Popup.forceHeavyWeight",true);setOpaque(false);setLayout(new BorderLayout());JPanel content=new JPanel();content.setOpaque(false);content.setLayout(new BoxLayout(content,BoxLayout.Y_AXIS));content.setPreferredSize(null);
        for(JComponent c:new JComponent[]{title,occupancy,explanation,segments,rows,budget,api,usage})c.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD,18f));occupancy.setFont(occupancy.getFont().deriveFont(14f));budget.setFont(budget.getFont().deriveFont(11f));
        for(JTextArea area:new JTextArea[]{explanation,usage}){area.setEditable(false);area.setOpaque(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setFocusable(false);area.setFont(title.getFont().deriveFont(Font.PLAIN,12f));}
        explanation.setRows(3);explanation.setColumns(29);explanation.setMaximumSize(new Dimension(Integer.MAX_VALUE,48));
        content.add(title);content.add(Box.createVerticalStrut(8));content.add(occupancy);content.add(Box.createVerticalStrut(9));content.add(explanation);content.add(Box.createVerticalStrut(8));content.add(segments);content.add(Box.createVerticalStrut(9));
        rows.setOpaque(false);
        for(int i=0;i<4;i++){JPanel row=new JPanel(new BorderLayout(12,0));row.setOpaque(false);JLabel dot=new JLabel(new Dot(COLORS[i]));JPanel label=new JPanel(new BorderLayout(11,0));label.setOpaque(false);label.add(dot,BorderLayout.WEST);label.add(names[i]);row.add(label);row.add(values[i],BorderLayout.EAST);names[i].setFont(title.getFont().deriveFont(Font.PLAIN,14f));values[i].setFont(names[i].getFont());rows.add(row);}
        rows.setMaximumSize(new Dimension(Integer.MAX_VALUE,104));content.add(rows);content.add(Box.createVerticalStrut(8));content.add(budget);content.add(Box.createVerticalStrut(9));
        api.setBorderPainted(false);api.setContentAreaFilled(false);api.setMargin(new Insets(2,0,2,0));api.setFont(title.getFont().deriveFont(Font.PLAIN,12f));api.setHorizontalAlignment(SwingConstants.LEFT);api.addActionListener(e->{usage.setVisible(api.isSelected());refresh();if(isVisible())packPopup();});
        usage.setRows(4);usage.setColumns(29);usage.setVisible(false);usage.setMaximumSize(new Dimension(Integer.MAX_VALUE,95));content.add(api);content.add(usage);add(content);styleFonts();refresh();
    }
    void update(long s,long t,long c,int max,String billing){update(s,t,c,0,max,billing);}
    /** Per-component estimate: system prompt, tool definitions, conversation messages and tool results (all ~ estimates). */
    void update(long s,long t,long c,long r,int max,String billing){if(system==Math.max(0,s)&&tools==Math.max(0,t)&&conversation==Math.max(0,c)&&results==Math.max(0,r)&&maximum==max&&java.util.Objects.equals(apiText,billing)&&renderedLanguage.equals(I18n.language()))return;system=Math.max(0,s);tools=Math.max(0,t);conversation=Math.max(0,c);results=Math.max(0,r);maximum=max;apiText=billing;refresh();}
    void refresh(){
        renderedLanguage=I18n.language();boolean dark=darkTheme;Color bg=new Color(dark?0x1B1F26:0xFFFFFF),fg=new Color(dark?0xEDF1F7:0x202733);
        Color muted=new Color(dark?0xABB8CC:0x596477);outline=new Color(dark?0x414B5D:0xCDD4DF);
        setBackground(bg);setForeground(fg);setOpaque(false);setBorder(new EmptyBorder(16,18,13,18));
        title.setText(I18n.text("Utilizare context"));title.setForeground(fg);occupancy.setText(maximum>0?I18n.text("~{0}% ocupat",ContextRing.percentage(system+tools+conversation+results,maximum)):I18n.text("Limita de context necunoscuta"));occupancy.setForeground(muted);
        text(explanation,I18n.text("Estimarea contextului activ pentru cererea urmatoare, inclusiv ciorna. Nu reprezinta consumul API facturat."));explanation.setForeground(muted);
        String[] keys={"Prompt de sistem","Definitii unelte","Conversatie","Rezultate unelte"};long[] tokens={system,tools,conversation,results};
        for(int i=0;i<4;i++){names[i].setText(I18n.text(keys[i]));names[i].setForeground(fg);values[i].setText(shortTokens(tokens[i]));values[i].setForeground(muted);values[i].setToolTipText("~"+tokens[i]+" "+I18n.text("tokeni"));}
        budget.setText(maximum>0?I18n.text("Buget local: {0} / {1} tokeni","~"+(system+tools+conversation+results),maximum):I18n.text("Configureaza bugetul local pentru a calcula procentul."));budget.setForeground(muted);
        api.setText(I18n.text("Consum API separat"));api.setIcon(new Chevron(api.isSelected(),muted));api.setForeground(muted);usage.setForeground(muted);text(usage,apiText);
        segments.update(tokens,maximum,dark);getAccessibleContext().setAccessibleName(title.getText());revalidate();repaint();
    }
    private static void text(JTextArea area,String value){if(!area.getText().equals(value)){area.setText(value);area.setCaretPosition(0);}}
    private void styleFonts(){Font base=UIManager.getFont("Label.font");String family=base==null?"Dialog":base.getFamily();title.setFont(new Font(family,Font.BOLD,18));occupancy.setFont(new Font(family,Font.PLAIN,14));for(JLabel label:names)label.setFont(new Font(family,Font.PLAIN,14));for(JLabel label:values)label.setFont(new Font(family,Font.PLAIN,14));for(JTextArea area:new JTextArea[]{explanation,usage})area.setFont(new Font(family,Font.PLAIN,12));budget.setFont(new Font(family,Font.PLAIN,11));api.setFont(new Font(family,Font.PLAIN,11));}
    void theme(boolean dark){darkTheme=dark;SwingUtilities.updateComponentTreeUI(this);styleFonts();refresh();}
    @Override public void show(Component anchor,int x,int y){theme(darkTheme);super.show(anchor,x,y);}
    @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(getBackground());g.fillRoundRect(0,0,getWidth(),getHeight(),18,18);g.setColor(outline);g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,18,18);g.dispose();}
    private void packPopup(){setPreferredSize(null);setPopupSize(getPreferredSize());Component anchor=getInvoker();if(anchor!=null&&anchor.isShowing())show(anchor,0,-getPreferredSize().height-8);}
    public Dimension getPreferredSize(){Dimension size=super.getPreferredSize();return new Dimension(360,size.height);}
    static String shortTokens(long value){String suffix=value>=1000000?"M":value>=1000?"k":"";double scale=value>=1000000?1000000:value>=1000?1000:1;return "~"+(suffix.isEmpty()?Long.toString(value):String.format(java.util.Locale.forLanguageTag(I18n.language()),"%.1f",value/scale))+suffix;}
    static final class Chevron implements Icon{final boolean expanded;final Color color;Chevron(boolean expanded,Color color){this.expanded=expanded;this.color=color;}public int getIconWidth(){return 9;}public int getIconHeight(){return 9;}public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(color);g.setStroke(new BasicStroke(1.2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));java.awt.geom.Path2D path=new java.awt.geom.Path2D.Double();if(expanded){path.moveTo(x+2,y+3);path.lineTo(x+4.5,y+6);path.lineTo(x+7,y+3);}else{path.moveTo(x+3,y+2);path.lineTo(x+6,y+4.5);path.lineTo(x+3,y+7);}g.draw(path);g.dispose();}}
    static final class Dot implements Icon{final Color color;Dot(Color c){color=c;}public int getIconWidth(){return 13;}public int getIconHeight(){return 13;}public void paintIcon(Component c,Graphics g,int x,int y){Graphics2D a=(Graphics2D)g.create();a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);a.setColor(color);a.fillRoundRect(x,y,13,13,4,4);a.dispose();}}
    static final class Segments extends JComponent {
        long[] values={0,0,0,0};int maximum;boolean dark;
        Segments(){setPreferredSize(new Dimension(320,10));setMaximumSize(new Dimension(Integer.MAX_VALUE,10));}
        void update(long[] values,int maximum,boolean dark){this.values=values.clone();this.maximum=maximum;this.dark=dark;repaint();}
        protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);var outline=new java.awt.geom.RoundRectangle2D.Double(0,0,getWidth(),getHeight(),getHeight(),getHeight());g.setColor(dark?new Color(0x34373D):new Color(0xE1E4E9));g.fill(outline);g.clip(outline);double x=0;if(maximum>0)for(int i=0;i<values.length;i++){double width=Math.min(getWidth()-x,values[i]*(double)getWidth()/maximum);if(width<=0)continue;g.setColor(COLORS[i]);g.fill(new java.awt.geom.Rectangle2D.Double(x,0,width,getHeight()));x+=width;}g.dispose();}
    }
}
