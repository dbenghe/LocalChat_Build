package localchat;
import java.util.*;
import javax.swing.*;
import java.awt.*;

/** Local preferences scoped to a saved provider and API route, never to conversations. */
final class ModelFavorites {
    static String key(String prefix,String url){return prefix+".favorites."+ModelCatalog.hash(url.strip());}
    static Set<String> get(Properties config,String prefix,String url){
        Set<String> result=new LinkedHashSet<>();
        try{Object value=Json.parse(config.getProperty(key(prefix,url),"[]"));if(value instanceof java.util.List<?> list)for(Object item:list)if(item instanceof String id&&!id.isBlank())result.add(id);}catch(RuntimeException ignored){}
        return result;
    }
    static boolean contains(Properties config,String prefix,String url,String id){return get(config,prefix,url).contains(id);}
    static void toggle(Properties config,String prefix,String url,String id){if(id==null||id.isBlank())return;Set<String> ids=get(config,prefix,url);if(!ids.remove(id))ids.add(id);config.setProperty(key(prefix,url),Json.write(new ArrayList<>(ids)));}
    static java.util.List<String> order(Properties config,String prefix,String url,java.util.Collection<String> ids){var sorted=new ArrayList<>(ids);Set<String> favorites=get(config,prefix,url);sorted.sort(Comparator.comparingInt(id->favorites.contains(id)?0:1));return sorted;}
    static Icon icon(Icon rest,boolean filled){return new Icon(){
        public int getIconWidth(){return 19+(rest==null?0:rest.getIconWidth());}public int getIconHeight(){return Math.max(18,rest==null?0:rest.getIconHeight());}
        public void paintIcon(Component c,Graphics g,int x,int y){Graphics2D p=(Graphics2D)g.create();try{p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Polygon star=new Polygon();for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5,r=i%2==0?7:3;star.addPoint(x+9+(int)Math.round(Math.cos(a)*r),y+9+(int)Math.round(Math.sin(a)*r));}p.setColor(filled?new Color(0xD49A18):c.getForeground());if(filled)p.fill(star);else p.draw(star);if(rest!=null)rest.paintIcon(c,p,x+19,y);}finally{p.dispose();}}
    };}
}
