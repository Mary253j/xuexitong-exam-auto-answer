package cn.local.quizhelper;

/** HTTPS-only redirection compatibility and credential-free load feedback. */
public final class WebNavigationTest {
    private static int count;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;}
    public static void main(String[] args){
        String http="http://i.chaoxing.com/base?refer=https%3A%2F%2Fmooc1.chaoxing.com%2Fwork%3Fx%3Da%2526b&enc=fixture%2Bvalue#section";
        String https=WebAddress.upgrade(http);
        check(https.equals("https"+http.substring(4)),"upgrade without decoding or changing path, query or fragment");
        check(WebAddress.allowed(https) && !WebAddress.allowed(http),"wire navigation must remain HTTPS only");
        check("https://I.ChaoXing.COM/base".equals(WebAddress.upgrade("HTTP://I.ChaoXing.COM:80/base")),"drop only default HTTP port");
        for(String url:new String[]{"http://chaoxing.com.evil.test/path","http://foreign.test/","http://user:private@i.chaoxing.com/base","http://i.chaoxing.com:8080/base","https://i.chaoxing.com/base","javascript:private()","file:///private",null})
            check(WebAddress.upgrade(url)==null,"do not expand allowed origin, scheme or port");
        String origin=WebAddress.origin("https://user:private-password@passport2.chaoxing.com/private-path?password=private-query#private-fragment");
        check(origin.contains("passport2.chaoxing.com") && !origin.contains("private") && !origin.contains("user:"),"origin metadata must omit credentials, paths, queries and fragments");
        check(WebAddress.origin("javascript:private-password").equals("协议=javascript，主机=<不记录>"),"opaque URL payload is never logged");
        WebLoadLog log=new WebLoadLog();
        log.event(WebLoadLog.Event.BLOCK_NAVIGATION,"intent://private-path?password=private-query#Intent;private-data",0);
        log.event(WebLoadLog.Event.HTTP_ERROR,"https://passport2.chaoxing.com/private-path?password=private-query",503);
        log.console("Uncaught ReferenceError: loginByPhoneAndPwd is not defined; private-password","https://passport2-static.chaoxing.com/js/private-file.js?enc=private-query",41);
        log.console("My password is private-password","https://passport2.chaoxing.com/",2);
        String report=log.report();
        check(!report.contains("private-"),"feedback must never retain raw console data or URL payloads");
        check(report.contains("ReferenceError") && report.contains("缺少=loginByPhoneAndPwd"),"retain only known JS error categories and public symbol names");
        check(report.contains("HTTP_ERROR code=503") && report.contains("协议=intent"),"retain enough metadata to distinguish HTTP failure from protocol interception");
        check(report.contains("JS_ERROR_OTHER"),"unknown console error becomes a fixed category");
        for(int i=0;i<100;i++)log.event(WebLoadLog.Event.NETWORK_ERROR,"https://passport2.chaoxing.com/?secret=private-query",i);
        report=log.report();
        check(report.contains("保留=60") && report.length()<20000,"bound feedback size while keeping latest events");
        check(!report.contains("private-") && !report.contains("secret="),"truncation cannot introduce unredacted fields");
        String original="Mozilla/5.0 (Linux; Android 17; PrivateDevice; wv) AppleWebKit/537.36 Version/4.0 Chrome/155.0.1000.1 Mobile Safari/537.36";
        String desktop=WebUserAgent.desktop(original);
        check(desktop.contains("Chrome/155.0.1000.1") && !desktop.contains("Mobile") && !desktop.contains("PrivateDevice") && !desktop.contains("; wv"),"desktop presentation uses the real engine version and omits device-specific UA fields");
        check(desktop.equals(WebUserAgent.desktop(desktop)),"desktop formatting must be stable");
        check("custom-agent".equals(WebUserAgent.desktop("custom-agent")),"do not invent an engine version");
        System.out.println("Web redirect and load-log privacy checks passed: "+count);
    }
}
