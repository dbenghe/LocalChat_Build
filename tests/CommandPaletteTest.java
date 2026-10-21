package localchat;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
public final class CommandPaletteTest {
    static int checks;static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{Path home=Path.of(System.getProperty("localchat.home"));Files.createDirectories(home);try{FeatureTest.edt(()->{
        LocalChat app=AttachmentTest.window(home,"http://127.0.0.1:1/v1");String[][] commands=FeatureTest.field(app,"COMMANDS");CommandPalette palette=FeatureTest.field(app,"commandPopup");AtomicReference<String> selected=new AtomicReference<>();
        check(commands.length==25&&java.util.Arrays.stream(commands).anyMatch(c->c[0].equals("/agents")),"all twenty-five LocalChat commands including agents exposed");
        for(int language=0;language<6;language++)for(int theme=0;theme<2;theme++){
            ((JComboBox<?>)FeatureTest.field(app,"languageBox")).setSelectedIndex(language);((JComboBox<?>)FeatureTest.field(app,"themeBox")).setSelectedIndex(theme);palette.filter("/",commands,selected::set,620);
            check(palette.choices.size()==commands.length,"entire command list available in every language and theme");check(palette.scroll.getPreferredSize().height<=360,"long command list bounded with scrolling");
            Component cell=palette.list.getCellRenderer().getListCellRendererComponent(palette.list,commands[0],0,false,false);
            int brightness=cell.getBackground().getRed()+cell.getBackground().getGreen()+cell.getBackground().getBlue();check(theme==1?brightness<200:brightness>650,"popup background matches selected theme");check(!cell.getForeground().equals(cell.getBackground()),"popup text has contrasting foreground");
            Component chosen=palette.list.getCellRenderer().getListCellRendererComponent(palette.list,commands[0],0,true,false);check(!chosen.getBackground().equals(palette.list.getBackground()),"selected row visible in selected theme");
            palette.move(-1);palette.accept();check(selected.get().equals("/help"),"last command accessible by keyboard navigation");palette.filter("/comp",commands,selected::set,620);check(palette.choices.size()==1,"typed prefix filters to manual compact command");palette.accept();check(selected.get().equals("/compact"),"manual compact selectable");
            palette.filter("/",commands,selected::set,620);check(palette.scroll.getViewport().getViewPosition().y==0,"reopening full menu starts at first command");palette.setSize(palette.getPreferredSize());palette.doLayout();palette.scroll.setSize(palette.scroll.getPreferredSize());palette.scroll.doLayout();palette.scroll.getViewport().doLayout();BufferedImage image=new BufferedImage(palette.getWidth(),palette.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();palette.printAll(g);g.dispose();Path images=Files.createDirectories(home.resolve("screenshots"));javax.imageio.ImageIO.write(image,"png",images.resolve(I18n.language()+"-"+theme+".png").toFile());
        }
        FeatureTest.command(app,"/autocompact 85");Conversations.State state=FeatureTest.field(app,"sessionState");check(state.autoCompact&&state.autoThreshold==85,"manual autocompact threshold applies to current chat");FeatureTest.command(app,"/autocompact off");check(!state.autoCompact,"manual autocompact off honored");FeatureTest.command(app,"/help");String help=((JTextPane)FeatureTest.field(app,"transcript")).getText();for(String[] command:commands)check(help.contains(command[0]),"help lists "+command[0]);
        JTextArea input=FeatureTest.field(app,"input");check(input.getInputMap().get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_UP,0)).equals("command-up"),"up key wired to palette while preserving editor fallback");check(input.getInputMap().get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE,0)).equals("command-dismiss"),"escape dismisses palette");app.dispose();
        });System.out.println("PASS: "+checks+" command palette checks; six languages x two themes; complete list and manual controls");}finally{FeatureTest.edt(()->{for(var window:Window.getWindows())window.dispose();});}System.exit(0);}
}
