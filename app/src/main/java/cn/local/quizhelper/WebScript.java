package cn.local.quizhelper;

/** Bind only native-generated JSON into one fixed, bundled script. */
final class WebScript {
    private static final String SLOT="/* nativeCommand */ null";
    static String bind(String source,String json){
        int at=source.indexOf(SLOT);
        if(at<0 || at!=source.lastIndexOf(SLOT))throw new IllegalArgumentException("网页脚本入口不明确。");
        return source.substring(0,at)+json+source.substring(at+SLOT.length());
    }
}
