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
        for(int[] invalid:new int[][]{{0,1},{501,1},{10,0},{10,11},{390,391}}){
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
        WebAnswerQueue last=new WebAnswerQueue(390,false,390);
        check(last.current()==389,"single mode can request the last question of a large page");last.finish(true);
        check(last.done(),"large-page single mode still answers only one question");
        WebAnswerQueue large=new WebAnswerQueue(390,true,1);
        List<String> identity=new ArrayList<>();for(int i=0;i<390;i++)identity.add("question-"+i);
        large.verifyPage(identity);
        for(int i=0;i<390;i++){check(large.current()==i,"large multi mode keeps all ordinals");large.finish(true);}
        check(large.done() && large.selected()==390,"large multi mode finishes after the actual page size");
        WebAnswerQueue guarded=new WebAnswerQueue(390,false,1);guarded.verifyPage(identity);
        List<String> reordered=new ArrayList<>(identity);Collections.swap(reordered,388,389);
        rejected=false;try{guarded.verifyPage(reordered);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"even single mode detects reordered questions beyond the old40 limit");
        check(new WebAnswerQueue(500,false,500).current()==499,"maximum supported ordinal is valid");
        check(WebReadLimits.incompleteReason(false,false,Collections.emptyList()).isEmpty(),"complete pages are usable");
        check(WebReadLimits.incompleteReason(true,false,Collections.singletonList("QUESTION_LIMIT")).contains("500"),"overflow reports the actual cap");
        check(!WebReadLimits.incompleteReason(true,false,Collections.emptyList()).isEmpty(),"old or unknown truncation codes still block partial reads");
        check(!WebReadLimits.incompleteReason(false,true,Collections.emptyList()).isEmpty(),"script errors cannot be announced as usable");
        for(String code:Arrays.asList("CANDIDATE_LIMIT","TEXT_LIMIT","NODE_LIMIT","FIELD_LIMIT","OPTION_LIMIT","DOCUMENT_LIMIT","FRAME_LIMIT")){
            check(!WebReadLimits.incompleteReason(true,false,Collections.singletonList(code)).isEmpty(),"each bounded read failure is explained");
        }
        System.out.println("Web answer queue and script checks passed: "+count);
    }
}
