package localchat;
import javax.swing.*;import java.awt.*;
/** A new provider starts with empty independent credentials and endpoint. */
final class ProviderEditor extends JPanel {
 final JTextField name=new JTextField(26),url=new JTextField(26);
 final JPasswordField key=new JPasswordField(26);
 final JComboBox<Provider> protocol=new JComboBox<>(Provider.values());
 ProviderEditor(){
  super(new GridLayout(0,2,8,8));protocol.setSelectedItem(Provider.CUSTOM);
  protocol.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){return super.getListCellRendererComponent(list,I18n.text(value==Provider.ANTHROPIC?"Compatibil Anthropic":value==Provider.OPENAI?"OpenAI":"Compatibil OpenAI"),index,selected,focus);}});
  add(new JLabel(I18n.text("Nume furnizor")));add(name);add(new JLabel(I18n.text("Protocol API")));add(protocol);add(new JLabel(I18n.text("URL de baza:")));add(url);add(new JLabel(I18n.text("Cheie API:")));add(key);
  name.getAccessibleContext().setAccessibleName(I18n.text("Nume furnizor"));url.getAccessibleContext().setAccessibleName(I18n.text("URL de baza:"));key.getAccessibleContext().setAccessibleName(I18n.text("Cheie API:"));protocol.getAccessibleContext().setAccessibleName(I18n.text("Protocol API"));
 }
}
