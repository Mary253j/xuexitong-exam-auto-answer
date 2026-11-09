package cn.local.quizhelper;

import java.net.URI;
import java.util.Locale;

/** Official HTTPS navigation only; never put query parameters into feedback. */
final class WebAddress {
    static final String START="https://i.chaoxing.com/base";
    private WebAddress(){}
    static boolean allowed(String value){
        try{
            URI uri=new URI(value);String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme()) || host==null || uri.getRawUserInfo()!=null)return false;
            host=host.toLowerCase(Locale.ROOT);
            return (uri.getPort()==-1 || uri.getPort()==443) && (host.equals("chaoxing.com") || host.endsWith(".chaoxing.com"));
        }catch(Exception invalid){return false;}
    }
    static boolean resource(String value){
        return value!=null && (value.startsWith("data:") || value.startsWith("blob:") || value.equals("about:blank") || allowed(value));
    }
    /** Convert only an ordinary HTTP URL on the same official hosts; never send it in cleartext. */
    static String upgrade(String value){
        try{
            URI uri=new URI(value);String host=uri.getHost();
            if(!"http".equalsIgnoreCase(uri.getScheme()) || host==null || uri.getRawUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=80))return null;
            String path=uri.getRawPath(),query=uri.getRawQuery(),fragment=uri.getRawFragment();
            String secure="https://"+host+(path==null?"":path)+(query==null?"":"?"+query)+(fragment==null?"":"#"+fragment);
            return allowed(secure)?secure:null;
        }catch(Exception invalid){return null;}
    }
    /** Loading diagnostics need only the protocol, host and port, never paths or parameters. */
    static String origin(String value){
        try{
            URI uri=new URI(value);String scheme=uri.getScheme(),host=uri.getHost();
            if(scheme==null || scheme.length()>16 || !scheme.matches("[A-Za-z][A-Za-z0-9+.-]*"))return "协议=<未知>，主机=<未取得>";
            if(!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))return "协议="+scheme.toLowerCase(Locale.ROOT)+"，主机=<不记录>";
            return "协议="+scheme.toLowerCase(Locale.ROOT)+"，主机="+(host==null?"<未取得>":host.toLowerCase(Locale.ROOT))+"，端口="+uri.getPort();
        }catch(Exception invalid){return "地址格式不符合预期（不记录原文）";}
    }
    static boolean login(String value){
        try{
            URI uri=new URI(value);String host=uri.getHost(),path=uri.getPath();
            return host==null || host.toLowerCase(Locale.ROOT).startsWith("passport") ||
                (path!=null && path.toLowerCase(Locale.ROOT).matches(".*(?:^|/)(?:m?login|register|findpwd)(?:/.*)?$"));
        }catch(Exception invalid){return true;}
    }
    static String display(String value){
        if(!allowed(value))return "非允许地址";
        try{
            URI uri=new URI(value);String path=uri.getRawPath();
            if(path==null || path.isEmpty())path="/";
            path=path.replaceAll("[0-9]{5,}","[数字]");
            if(path.length()>180)path=path.substring(0,180)+"…";
            return uri.getHost()+path;
        }catch(Exception invalid){return "地址未取得";}
    }
}
