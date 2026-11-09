package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class WebAnswerQueueTest {
    private static int count;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;}
    public static void main(String[] args){
        WebAnswerQueue one=new WebAnswerQueue(10,false,6);
        check(one.current()==5,"single mode targets the requested page ordinal");one.finish(true);
        check(one.done() && one.selected()==1 && one.manual()==0,"single mode stops after one question");
        WebAnswerQueue many=new WebAnswerQueue(10,true,1);
        for(int i=0;i<10;i++){check(many.current()==i,"multi mode preserves page order");many.finish(i%2==0);}
        check(many.done() && many.selected()==5 && many.manual()==5,"completion counts distinguish chosen and manual questions");
        for(int[] invalid:new int[][]{{0,1},{41,1},{10,0},{10,11}}){
            boolean rejected=false;try{new WebAnswerQueue(invalid[0],false,invalid[1]);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"reject empty page or out-of-range single ordinal");
        }
        check(WebAnswerQueue.delay(1000,2000)==4000,"fast model answers retain a five-second question interval");
        check(WebAnswerQueue.delay(1000,6000)==0 && WebAnswerQueue.delay(1000,10000)==0,"slow answers never overlap or add another fixed wait");
        String source="(function(command){return command;})(/* nativeCommand */ null);";
        String json="{\"operation\":\"select\",\"answers\":[\"B\"],\"expected\":{\"title\":\"quoted \\\" data\"}}";
        check(WebScript.bind(source,json).contains(json) && !WebScript.bind(source,json).contains("/* nativeCommand */"),"bind one native JSON literal into the bundled script");
        boolean rejected=false;try{WebScript.bind(source+source,json);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"reject ambiguous script slots");
        WebAnswerQueue stable=new WebAnswerQueue(3,true,1);
        List<String> page=new ArrayList<>(Arrays.asList("first question","second question","third question"));
        stable.verifyPage(page);stable.finish(true);stable.verifyPage(new ArrayList<>(page));
        check(stable.current()==1,"same page contents preserve the next ordinal");
        page.set(0,"mutated outside queue");
        stable.verifyPage(Arrays.asList("first question","second question","third question"));
        check(stable.current()==1,"baseline identities are copied, not aliased to the caller list");
        for(List<String> changed:Arrays.asList(Arrays.asList("second question","first question","third question"),Arrays.asList("first question","new second question","third question"),Collections.singletonList("first question"))){
            rejected=false;try{stable.verifyPage(changed);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected && stable.current()==1,"a changed or reordered page cannot silently skip or repeat a question");
        }
        System.out.println("Web answer queue and script checks passed: "+count);
    }
}
