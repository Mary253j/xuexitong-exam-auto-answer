package cn.local.quizhelper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Request desktop presentation using the installed engine's Chrome version. */
final class WebUserAgent {
    private WebUserAgent(){}
    static String desktop(String original){
        Matcher chrome=Pattern.compile("Chrome/([0-9]+(?:\\.[0-9]+){0,3})").matcher(original==null?"":original);
        if(!chrome.find())return original;
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"+chrome.group(1)+" Safari/537.36";
    }
}
