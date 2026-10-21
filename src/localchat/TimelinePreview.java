package localchat;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.function.BooleanSupplier;

/** Compact, inert local excerpt with an independent icon-only bookmark action. */
final class TimelinePreview extends JPanel {
    final JLabel title=new JLabel();
    final JTextArea excerpt=new JTextArea();
    final JToggleButton bookmark=new JToggleButton();
    private final Color outline;
    private JWindow window;
    private Timer dismissal;
    TimelinePreview(String heading,String text,boolean marked,boolean dark,Runnable navigate,BooleanSupplier toggle){
        Color bg=new Color(dark?0x292B30:0xFFFFFF),fg=new Color(dark?0xEDF1F7:0x202733),muted=new Color(dark?0xB3BAC5:0x596477);
        outline=new Color(dark?0x41464F:0xCDD4DF);setBackground(bg);setForeground(fg);setOpaque(false);setBorder(new EmptyBorder(8,10,9,8));setLayout(new BorderLayout(0,5));
        putClientProperty("Popup.borderCornerRadius",14);putClientProperty("Popup.roundedBorderWidth",1);putClientProperty("Popup.dropShadowPainted",true);putClientProperty("Popup.forceHeavyWeight",true);
        String family=UIManager.getFont("Label.font").getFamily();title.setFont(new Font(family,Font.BOLD,12));title.setForeground(fg);title.putClientProperty("html.disable",true);title.setText(heading);title.setMinimumSize(new Dimension(0,22));
        bookmark.setText("");bookmark.setPreferredSize(new Dimension(24,24));bookmark.setMargin(new Insets(2,2,2,2));bookmark.setBorderPainted(false);bookmark.setContentAreaFilled(false);bookmark.setForeground(muted);bookmark.putClientProperty("JComponent.minimumWidth",0);
        state(marked);bookmark.addActionListener(e->state(toggle.getAsBoolean()));
        JPanel header=new JPanel(new BorderLayout(5,0));header.setOpaque(false);header.add(title);header.add(bookmark,BorderLayout.EAST);add(header,BorderLayout.NORTH);
        excerpt.setText(text);excerpt.setFont(new Font(family,Font.PLAIN,12));excerpt.setForeground(muted);excerpt.setOpaque(false);excerpt.setEditable(false);excerpt.setFocusable(false);excerpt.setLineWrap(true);excerpt.setWrapStyleWord(true);excerpt.setRows(3);excerpt.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,2,0,0,outline),new EmptyBorder(0,7,0,1)));excerpt.setCaretPosition(0);add(excerpt);
        MouseAdapter jump=new MouseAdapter(){public void mouseClicked(MouseEvent e){if(SwingUtilities.isLeftMouseButton(e))navigate.run();}};
        for(Component component:new Component[]{this,header,title,excerpt}){component.addMouseListener(jump);component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));}
        getAccessibleContext().setAccessibleName(heading);
    }
    void show(Component anchor,int x,int y){
        dismiss();window=new JWindow(SwingUtilities.getWindowAncestor(anchor));window.setFocusableWindowState(false);window.setType(Window.Type.POPUP);window.setContentPane(this);window.pack();
        Point point=anchor.getLocationOnScreen();point.translate(x,y);var config=anchor.getGraphicsConfiguration();Rectangle screen=config.getBounds();Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(config);point.x=Math.max(screen.x+insets.left,Math.min(point.x,screen.x+screen.width-insets.right-window.getWidth()));point.y=Math.max(screen.y+insets.top,Math.min(point.y,screen.y+screen.height-insets.bottom-window.getHeight()));window.setLocation(point);
        try{window.setShape(new java.awt.geom.RoundRectangle2D.Double(0,0,getPreferredSize().width,getPreferredSize().height,14,14));}catch(UnsupportedOperationException ignored){}
        window.setVisible(true);
        dismissal=new Timer(200,e->{if(window==null||!window.isDisplayable()||!anchor.isShowing()){dismiss();return;}var pointer=MouseInfo.getPointerInfo();if(pointer==null)return;Point at=pointer.getLocation();Rectangle rail=new Rectangle(anchor.getLocationOnScreen(),anchor.getSize());if(!window.getBounds().contains(at)&&!rail.contains(at))dismiss();});dismissal.start();
    }
    void dismiss(){if(dismissal!=null){dismissal.stop();dismissal=null;}if(window!=null){window.dispose();window=null;}}
    private void state(boolean selected){bookmark.setSelected(selected);bookmark.setIcon(new UiIcon(selected?"bookmark-filled":"bookmark",15));bookmark.setToolTipText(I18n.text(selected?"Elimina semnul de carte":"Adauga semn de carte"));bookmark.getAccessibleContext().setAccessibleName(bookmark.getToolTipText());}
    public Dimension getPreferredSize(){return new Dimension(320,110);}
    protected void paintComponent(Graphics graphics){var g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(getBackground());g.fillRoundRect(0,0,getWidth(),getHeight(),14,14);g.setColor(outline);g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,14,14);g.dispose();}
}
