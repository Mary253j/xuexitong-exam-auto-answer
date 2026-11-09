package cn.local.quizhelper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only fixed event codes, origin metadata and sanitized JS error categories enter feedback. */
final class WebLoadLog {
    enum Event {OPEN,FINISH,BLOCK_NAVIGATION,UPGRADE_HTTPS,BLOCK_RESOURCE,HTTP_ERROR,NETWORK_ERROR,TLS_ERROR}
    private static final Pattern JS_KIND=Pattern.compile("\\b(ReferenceError|TypeError|SyntaxError|RangeError|URIError|EvalError|Error):");
    private static final String[] SYMBOLS={"loginByPhoneAndPwd","hasAgreePolicy","jQuery","CryptoJS","util"};
    private final Deque<String> entries=new ArrayDeque<>();
    private final long started=System.nanoTime();
    private int total;
    private void add(String item){
        if(!entries.isEmpty() && entries.peekLast().endsWith(item))return;
        entries.addLast("+"+((System.nanoTime()-started)/1000000)+"ms "+item);total++;
        while(entries.size()>60)entries.removeFirst();
    }
    synchronized void event(Event kind,String url,int code){add(kind.name()+" code="+code+" "+WebAddress.origin(url));}
    synchronized void console(String raw,String source,int line){
        Matcher match=JS_KIND.matcher(raw==null?"":raw);String type=match.find()?match.group(1):"JS_ERROR_OTHER";
        String symbol="";
        for(String known:SYMBOLS)if(raw!=null && raw.contains(known+" is not defined")){symbol="，缺少="+known;break;}
        add("JS_ERROR "+type+symbol+"，行="+line+" "+WebAddress.origin(source));
    }
    synchronized String report(){
        StringBuilder out=new StringBuilder("网页登录与加载记录：仅保留事件、错误码、协议和主机。\n不记录表单内容、请求体、响应内容、Cookie、路径、链接参数或控制台原文。\n")
            .append("事件总数=").append(total).append("，保留=").append(entries.size()).append('\n');
        for(String line:entries)out.append(line).append('\n');return out.toString();
    }
}
