package localchat;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;

/** Live context breakdown; estimates and API billing remain distinct. No inference or timers. */
final class ContextUsagePopup extends JPopupMenu {
    static final Color[] COLORS={new Color(0x9CA5B2),new Color(0xAC93EB),new Color(0x21BBCB)};
    final JLabel title=new JLabel(),occupancy=new JLabel(),budget=new JLabel();
    final JTextArea explanation=new JTextArea(),usage=new JTextArea();
    final JLabel[] names={new JLabel(),new JLabel(),new JLabel()},values={new JLabel(),new JLabel(),new JLabel()};
    final Segments segments=new Segments();
    final JToggleButton api=new JToggleButton();
    private long system,tools,conversation;private int maximum;private String apiText="";
    private final JPanel rows=new JPanel(new GridLayout(3,1,0,7));
    ContextUsagePopup(){
        setLayout(new BorderLayout());JPanel content=new JPanel();content.setOpaque(false);content.setLayout(new BoxLayout(content,BoxLayout.Y_AXIS));content.setPreferredSize(null);
        for(JComponent c:new JComponent[]{title,occupancy,explanation,segments,rows,budget,api,usage})c.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD,20f));occupancy.setFont(occupancy.getFont().deriveFont(16f));budget.setFont(budget.getFont().deriveFont(12f));
        for(JTextArea area:new JTextArea[]{explanation,usage}){area.setEditable(false);area.setOpaque(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setFocusable(false);area.setFont(title.getFont().deriveFont(Font.PLAIN,13f));}
        explanation.setRows(3);explanation.setColumns(38);explanation.setMaximumSize(new Dimension(Integer.MAX_VALUE,57));
        content.add(title);content.add(Box.createVerticalStrut(10));content.add(occupancy);content.add(Box.createVerticalStrut(9));content.add(explanation);content.add(Box.createVerticalStrut(15));content.add(segments);content.add(Box.createVerticalStrut(19));
        rows.setOpaque(false);
        for(int i=0;i<3;i++){JPanel row=new JPanel(new BorderLayout(12,0));row.setOpaque(false);JLabel dot=new JLabel(new Dot(COLORS[i]));JPanel label=new JPanel(new BorderLayout(11,0));label.setOpaque(false);label.add(dot,BorderLayout.WEST);label.add(names[i]);row.add(label);row.add(values[i],BorderLayout.EAST);names[i].setFont(title.getFont().deriveFont(Font.PLAIN,15f));values[i].setFont(names[i].getFont());rows.add(row);}
        rows.setMaximumSize(new Dimension(Integer.MAX_VALUE,99));content.add(rows);content.add(Box.createVerticalStrut(15));content.add(budget);content.add(Box.createVerticalStrut(12));
        api.setBorderPainted(false);api.setContentAreaFilled(false);api.setMargin(new Insets(2,0,2,0));api.setFont(title.getFont().deriveFont(Font.PLAIN,12f));api.setHorizontalAlignment(SwingConstants.LEFT);api.addActionListener(e->{usage.setVisible(api.isSelected());refresh();if(isVisible())packPopup();});
        usage.setRows(4);usage.setColumns(38);usage.setVisible(false);usage.setMaximumSize(new Dimension(Integer.MAX_VALUE,95));content.add(api);content.add(usage);add(content);refresh();
    }
    void update(long s,long t,long c,int max,String billing){system=Math.max(0,s);tools=Math.max(0,t);conversation=Math.max(0,c);maximum=max;apiText=billing;refresh();}
    void refresh(){
        Color bg=UIManager.getColor("Panel.background"),fg=UIManager.getColor("Label.foreground");if(bg==null)bg=new Color(0x202020);if(fg==null)fg=Color.WHITE;
        boolean dark=bg.getRed()+bg.getGreen()+bg.getBlue()<384;Color muted=dark?new Color(0xA4A8AF):new Color(0x626771);Color edge=dark?new Color(0x41454D):new Color(0xCFD3DA);
        setBackground(bg);setForeground(fg);setBorder(new CompoundBorder(new LineBorder(edge,1,true),new EmptyBorder(20,22,17,22)));
        title.setText(I18n.text("Utilizare context"));title.setForeground(fg);occupancy.setText(maximum>0?I18n.text("~{0}% ocupat",ContextRing.percentage(system+tools+conversation,maximum)):I18n.text("Limita de context necunoscuta"));occupancy.setForeground(muted);
        explanation.setText(I18n.text("Estimarea contextului activ pentru cererea urmatoare, inclusiv ciorna. Nu reprezinta consumul API facturat."));explanation.setForeground(muted);
        String[] keys={"Prompt de sistem","Definitii unelte","Conversatie"};long[] tokens={system,tools,conversation};
        for(int i=0;i<3;i++){names[i].setText(I18n.text(keys[i]));names[i].setForeground(fg);values[i].setText(shortTokens(tokens[i]));values[i].setForeground(muted);values[i].setToolTipText("~"+tokens[i]+" "+I18n.text("tokeni"));}
        budget.setText(maximum>0?I18n.text("Buget local: {0} / {1} tokeni","~"+(system+tools+conversation),maximum):I18n.text("Configureaza bugetul local pentru a calcula procentul."));budget.setForeground(muted);
        api.setText((api.isSelected()?"▾ ":"▸ ")+I18n.text("Consum API separat"));api.setForeground(muted);usage.setForeground(muted);usage.setText(apiText);usage.setCaretPosition(0);
        segments.update(tokens,maximum,dark);getAccessibleContext().setAccessibleName(title.getText());revalidate();repaint();
    }
    private void packPopup(){setPreferredSize(null);setPopupSize(getPreferredSize());Component anchor=getInvoker();if(anchor!=null&&anchor.isShowing())show(anchor,0,-getPreferredSize().height-8);}
    public Dimension getPreferredSize(){Dimension size=super.getPreferredSize();return new Dimension(450,size.height);}
    static String shortTokens(long value){String suffix=value>=1000000?"M":value>=1000?"k":"";double scale=value>=1000000?1000000:value>=1000?1000:1;return "~"+(suffix.isEmpty()?Long.toString(value):String.format(java.util.Locale.forLanguageTag(I18n.language()),"%.1f",value/scale))+suffix;}
    static final class Dot implements Icon{final Color color;Dot(Color c){color=c;}public int getIconWidth(){return 13;}public int getIconHeight(){return 13;}public void paintIcon(Component c,Graphics g,int x,int y){Graphics2D a=(Graphics2D)g.create();a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);a.setColor(color);a.fillRoundRect(x,y,13,13,4,4);a.dispose();}}
    static final class Segments extends JComponent {
        long[] values={0,0,0};int maximum;boolean dark;
        Segments(){setPreferredSize(new Dimension(400,12));setMaximumSize(new Dimension(Integer.MAX_VALUE,12));}
        void update(long[] values,int maximum,boolean dark){this.values=values.clone();this.maximum=maximum;this.dark=dark;repaint();}
        protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);var outline=new java.awt.geom.RoundRectangle2D.Double(0,0,getWidth(),getHeight(),getHeight(),getHeight());g.setColor(dark?new Color(0x34373D):new Color(0xE1E4E9));g.fill(outline);g.clip(outline);double x=0;if(maximum>0)for(int i=0;i<values.length;i++){double width=Math.min(getWidth()-x,values[i]*(double)getWidth()/maximum);if(width<=0)continue;g.setColor(COLORS[i]);g.fill(new java.awt.geom.Rectangle2D.Double(x,0,width,getHeight()));x+=width;}g.dispose();}
    }
}
