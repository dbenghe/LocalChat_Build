package localchat;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
public final class ModelFavoritesTest {
    static int checks;static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        var config=new Properties();String prefix="provider.saved.test",url="https://example.invalid/v1",id="nvidia/nemotron-3-ultra-550b-a55b";
        config.setProperty("default.model","unchanged");
        ModelFavorites.toggle(config,prefix,url,id);check(ModelFavorites.contains(config,prefix,url,id),"favorite added");
        check(!ModelFavorites.contains(config,"other",url,id)&&!ModelFavorites.contains(config,prefix,url+"/other",id),"provider and route isolation");
        check(ModelFavorites.order(config,prefix,url,List.of("b",id,"a")).equals(List.of(id,"b","a")),"favorite first, stable rest");
        Path home=Path.of(System.getProperty("localchat.home"));Files.createDirectories(home);try(var out=Files.newOutputStream(home.resolve("favorites.properties"))){config.store(out,"test");}
        var loaded=new Properties();try(var in=Files.newInputStream(home.resolve("favorites.properties"))){loaded.load(in);}check(ModelFavorites.contains(loaded,prefix,url,id),"favorite survives reload");
        ModelFavorites.toggle(loaded,prefix,url,id);check(!ModelFavorites.contains(loaded,prefix,url,id),"favorite removed");check(loaded.getProperty("default.model").equals("unchanged"),"favorite never changes default");
        try{FeatureTest.edt(()->{
            LocalChat app=AttachmentTest.window(home,"http://127.0.0.1:1/v1");
            JComboBox<String> models=FeatureTest.field(app,"modelBox");models.addItem("favorite-b");models.addItem("favorite-a");models.setSelectedItem("favorite-b");
            JButton star=FeatureTest.field(app,"favoriteModelButton");star.doClick();
            check(models.getItemAt(0).equals("favorite-b")&&models.getSelectedItem().equals("favorite-b"),"toggle reorders and preserves current model");
            Properties settings=FeatureTest.field(app,"config");check(settings.stringPropertyNames().stream().anyMatch(k->k.contains(".favorites.")),"UI saves scoped preference");
            for(String code:I18n.CODES){I18n.setLanguage(code);FeatureTest.call(app,"refreshFavoriteButton",new Class<?>[]{});check(star.getToolTipText().equals(I18n.text("Scoate modelul din favorite")),"translated favorite action "+code);}
            for(String theme:new String[]{"dark","light"}){((JComboBox<?>)FeatureTest.field(app,"themeBox")).setSelectedIndex(theme.equals("dark")?1:0);JLabel label=new JLabel();var image=new java.awt.image.BufferedImage(60,25,2);var graphics=image.createGraphics();ModelFavorites.icon(null,true).paintIcon(label,graphics,0,0);ModelFavorites.icon(null,false).paintIcon(label,graphics,20,0);graphics.dispose();check(image.getRGB(9,8)!=0,"drawn star in "+theme);}
            star.doClick();check(models.getSelectedItem().equals("favorite-b"),"unfavorite retains selection");
            var cache=new ModelCatalog.Cache(home,"catalog-favorite-test",Provider.CUSTOM,"http://127.0.0.1:1/v1","");
            var entries=List.of(ModelCatalog.entry(Json.obj("id","a"),url),ModelCatalog.entry(Json.obj("id","b"),url));
            var selected=new HashSet<String>();selected.add("b");
            var dialog=new ModelCatalogDialog(app,"test",entries,cache,Provider.CUSTOM,url,"",false,e->{},v->{},()->{});
            dialog.configureFavorites(selected::contains,model->{if(!selected.remove(model))selected.add(model);});
            check(dialog.visible.get(0).id().equals("b"),"catalog favorites precede status order");
            dialog.favoritesOnly.doClick();check(dialog.visible.size()==1&&dialog.selected().id().equals("b"),"catalog favorites filter");
            dialog.favoriteButton.doClick();check(dialog.visible.isEmpty()&&!dialog.choose.isEnabled(),"unfavorite updates filtered catalog");
            dialog.favoritesOnly.doClick();check(dialog.visible.size()==2,"all models remain available after unfavorite");dialog.dispose();
            app.dispose();
        });System.out.println("PASS: "+checks+" model favorite checks; persistence, isolation, selection, six languages and themes");}finally{FeatureTest.edt(()->{for(var window:java.awt.Window.getWindows())window.dispose();});}System.exit(0);
    }
}
