import java.lang.reflect.*;
import org.json.JSONObject;
public class ManifestProbe {
    static String entry(String pkg) {
        return "{\"packageName\":\""+pkg+"\",\"versionCode\":2000000274,\"versionName\":\"7.4.274\",\"sizeBytes\":123,\"file\":\"app.apk\",\"url\":\"/updates/app.apk\",\"sha256\":\""+"a".repeat(64)+"\"}";
    }
    public static void main(String[] args) throws Exception {
        int count=0;
        for(String namespace: new String[]{"childwatch","parentwatch"}) {
            Class<?> type=Class.forName("ru.example."+namespace+".update.UpdateManifest");
            Object parser=type.getField("INSTANCE").get(null);
            Method parse=type.getMethod("parse",String.class,String.class);
            for(String pkg: new String[]{"ru.example.childwatch","ru.example.parentwatch","ru.example.parentwatch.debug"}) {
                String other=pkg.contains("parentwatch")?"ru.example.childwatch":"ru.example.parentwatch.debug";
                for(boolean reversed: new boolean[]{false,true}) {
                    String ownKey=pkg.contains("parentwatch")?"child":"parent";
                    String otherKey=ownKey.equals("child")?"parent":"child";
                    if(reversed) { String swap=ownKey;ownKey=otherKey;otherKey=swap; }
                    String raw="{\"schema\":1,\"apps\":{\""+ownKey+"\":"+entry(pkg)+",\""+otherKey+"\":"+entry(other)+"}}";
                    if(parse.invoke(parser,raw,pkg)==null) throw new AssertionError(namespace+": missing "+pkg+" reversed="+reversed);
                    count++;
                }
            }
            String raw="{\"schema\":1,\"apps\":{\"parent\":"+entry("ru.example.childwatch")+"}}";
            if(parse.invoke(parser,raw,"ru.example.unknown")!=null) throw new AssertionError("foreign package accepted");
            count++;
        }
        System.out.println("PASS "+count+" manifest package checks (both parsers)");
    }
}
