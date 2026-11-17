package cn.local.quizhelper;
import java.util.Arrays;
import java.util.Collections;
public final class WebAnswerDisplayTest {
    static int count;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);count++;}
    static Core.Question question(String type,String... options){
        Core.Question q=new Core.Question();q.type=type;q.stem="题干不得在答案区出现";
        for(int i=0;i<options.length;i++){Core.Option o=new Core.Option();o.label=String.valueOf((char)('A'+i));o.text=options[i];q.options.add(o);}return Core.finish(q);
    }
    static WebAnswerDisplay.Item item(int ordinal,Core.Question q,String... answers){return new WebAnswerDisplay.Item(ordinal,q,Arrays.asList(answers));}
    public static void main(String[] args){
        Core.Question single=question("single","甲","乙"),multi=question("multiple","甲","乙","丙"),reversed=question("boolean","错误","正确");
        String out=WebAnswerDisplay.render(Arrays.asList(item(1,single,"B"),item(2,multi,"C","A"),item(3,reversed,"B")));
        check(out.equals("1.B2.AC3.对"),"compact choice rows sort labels and map reversed boolean text correctly");
        check(!out.contains(single.stem) && !out.contains("乙"),"do not display stems or option bodies");
        check(WebAnswerDisplay.render(Collections.singletonList(item(4,reversed,"A"))).equals("4.错"),"boolean A can mean false");
        Core.Question unknown=question("boolean","成立但有条件","不成立但有例外");
        check(WebAnswerDisplay.render(Collections.singletonList(item(5,unknown,"B"))).equals("5.B"),"unknown boolean text keeps its explicit letter rather than guessing truth");
        Core.Question text=question("text");String essay="第一点。\n第二点。";
        check(WebAnswerDisplay.render(Arrays.asList(item(1,single,"B"),item(2,text,essay),item(3,single,"A"))).equals("1.B\n2."+essay+"\n3.A"),"free-text paragraphs remain separate from compact choice rows");
        check(WebAnswerDisplay.render(Collections.singletonList(new WebAnswerDisplay.Item(6,null,null))).equals("6.暂无答案"),"missing answers never produce a guessed letter");
        check(WebAnswerDisplay.render(Collections.singletonList(new WebAnswerDisplay.Item(7,single,null))).equals("7.暂无答案"),"model abstention is explicit");
        System.out.println("Compact answer presentation checks passed: "+count);
    }
}
