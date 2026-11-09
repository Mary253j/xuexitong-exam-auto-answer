package cn.local.quizhelper;

import java.util.Arrays;

public final class WebQuestionTest {
    private static int count;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;}
    private static void reject(WebQuestionParser.Block b){boolean rejected=false;try{WebQuestionParser.parse(b);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"incomplete web question must not parse");}
    private static WebQuestionParser.Block block(){
        WebQuestionParser.Block b=new WebQuestionParser.Block();b.title="1. (单选题, 3.0分) 办公自动化属于哪个应用领域？";
        b.options.addAll(Arrays.asList("A. 科学计算领域","B. 数据处理领域","C. 自动控制领域","D. 辅助设计领域"));return b;
    }
    public static void main(String[] args){
        Core.Question q=WebQuestionParser.parse(block());check(q.stem.equals("办公自动化属于哪个应用领域？"),"remove title numbering, kind and points");
        check(q.type.equals("single") && q.options.size()==4 && q.option("B").text.equals("数据处理领域"),"preserve labeled option bodies");
        WebQuestionParser.Block b=block();b.title="2. [多选题] 下列哪些属于基本经济制度？";
        check(WebQuestionParser.parse(b).type.equals("multiple"),"multiple-choice remains supported");
        b=block();b.title="3. [判断题] 计算机可以处理数据。";b.options.clear();b.options.addAll(Arrays.asList("对","错"));
        check(WebQuestionParser.parse(b).option("B").text.equals("错"),"explicit true-false rows");
        b=block();b.title="4. [简答题] 请说明计算机的发展过程。";b.options.clear();
        check(WebQuestionParser.parse(b).type.equals("text"),"explicit free-text question");
        b=block();b.media=true;reject(b);
        b=block();b.truncated=true;reject(b);
        b=block();b.options.set(1,"B");reject(b);
        b=block();b.options.set(1,"A. 重复字母");reject(b);
        b=block();b.options.set(1,"B. \uE123\uE456");reject(b);
        b=block();b.title="只有题干，没有题型";reject(b);
        b=block();b.title="课程说明：单选题练习";reject(b);
        b=block();b.title="1. [单选题]";reject(b);
        b=block();b.extractionIssue="选项字母标签存在冲突。";reject(b);
        check(QuestionMode.select(Arrays.asList(q,q),false).size()==1,"single-question mode remains available on a multi-question page");
        check(QuestionMode.select(Arrays.asList(q,q),true).size()==2,"multi-question mode remains available");
        check(WebAddress.allowed("https://i.chaoxing.com/base") && WebAddress.allowed("https://mooc1-1.chaoxing.com/work?token=private"),"official HTTPS origins");
        for(String url:Arrays.asList("http://i.chaoxing.com/base","https://chaoxing.com.evil.test/","https://i.chaoxing.com@evil.test/","https://evil@i.chaoxing.com/","file:///private","javascript:alert(1)","https://i.chaoxing.com:8443/",null))
            check(!WebAddress.allowed(url),"reject URL spoofing and unsupported schemes");
        String safe=WebAddress.display("https://mooc1.chaoxing.com/work/123456789?token=private-key&username=student#password");
        check(!safe.contains("private") && !safe.contains("student") && !safe.contains("password") && !safe.contains("123456789"),"feedback must not expose query, fragment or long numeric path identifiers");
        check(WebAddress.login("https://passport2.chaoxing.com/mlogin?x=y") && !WebAddress.login("https://i.chaoxing.com/base"),"login documents must not be analyzed");
        System.out.println("Web question and origin checks passed: "+count);
    }
}
