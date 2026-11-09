package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class MultiTextTest {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static void rejects(Runnable op,String message){try{op.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError(message);}
    private static Core.Record row(String text,int index){
        Core.Target t=new Core.Target();t.path=new int[]{index};t.gesture=true;
        return new Core.Record(text,true,t);
    }
    private static Core.Question multi(){
        return Core.parse(Arrays.asList(row("多选题",0),row("下列哪些属于我国的基本经济制度？",1),
            row("A. 按需分配为主体、多种分配方式并存",2),row("B. 社会主义市场经济体制",3),
            row("C. 公有制为主体、多种所有制经济共同发展",4),row("D. 资本主义市场经济体制",5),row("提交",6)));
    }
    private static Core.Record node(String text,int parent,int child,int top,int bottom,boolean editable){
        Core.Record r=new Core.Record(text,true,null);r.sourcePath=new int[]{0,parent,child};
        r.left=40;r.right=1100;r.top=top;r.bottom=bottom;r.editable=editable;return r;
    }
    public static void main(String[] args){
        Core.Question q=multi();List<String> labels=Arrays.asList("B","C");
        rejects(()->Core.plan(q,labels),"unknown selection remains strict unless enabled");
        List<Core.Action> once=Core.plan(q,labels,true,Collections.emptySet());
        check(once.size()==2 && once.get(0).label.equals("B") && once.get(1).label.equals("C"),"select all correct options");
        check(Core.plan(q,labels,true,new HashSet<>(labels)).isEmpty(),"do not toggle again after both were attempted");
        check(Core.labelState("B，已选中")==Boolean.TRUE && Core.labelState("C 未选择")==Boolean.FALSE,"read explicit label selection descriptions");
        check(Core.cleanLabel("B，已选中").equals("B"),"state changes must not change question identity");
        check(Core.cleanLabel("A. 已选中").equals("A. 已选中"),"do not strip actual option content");
        q.option("A").checked=true;q.option("B").checked=false;
        List<Core.Action> mixed=Core.plan(q,labels,true,Collections.singleton("C"));
        check(mixed.size()==2 && !mixed.get(0).desired && mixed.get(1).desired,"reconcile known states and skip recorded unknown state");

        Map<String,String> persisted=new HashMap<>();
        ToggleLedger.Store store=new ToggleLedger.Store(){
            public String get(String key){return persisted.getOrDefault(key,"");}
            public void put(String key,String value){if(value.isEmpty())persisted.remove(key);else persisted.put(key,value);}
            public void clear(){persisted.clear();}
        };
        ToggleLedger ledger=new ToggleLedger(store);ledger.prepare(q.id,labels);
        check(ledger.reserve(q.id,"B"),"write-ahead reservation");
        ToggleLedger restarted=new ToggleLedger(store);
        rejects(()->restarted.prepare(q.id,labels),"uncertain interruption must not trigger a second toggle");
        ledger.sent(q.id,"B");restarted.prepare(q.id,Arrays.asList("C","B"));
        check(restarted.sent(q.id).contains("B"),"record survives ledger recreation");
        check(!restarted.reserve(q.id,"B"),"sent option cannot be dispatched twice");
        check(restarted.reserve(q.id,"C"),"remaining option can continue");restarted.sent(q.id,"C");
        check(restarted.sent(q.id).equals(new HashSet<>(labels)),"all desired options recorded");
        rejects(()->restarted.prepare(q.id,Arrays.asList("B","D")),"changed unknown-state answer requires reset");
        restarted.clear();restarted.prepare(q.id,labels);
        check(restarted.reserve(q.id,"B"),"explicit reset permits a new blank attempt");
        restarted.release(q.id,"B");check(restarted.reserve(q.id,"B"),"undispatched cancelled reservation can retry");
        ToggleLedger broken=new ToggleLedger(new ToggleLedger.Store(){
            public String get(String key){return "";}
            public void put(String key,String value){throw new IllegalArgumentException("disk failure");}
            public void clear(){}
        });
        rejects(()->broken.reserve("q_test","A"),"failed persistence aborts before dispatch");

        Core.Question text=Core.parse(Arrays.asList(row("简答题",0),row("请简述市场在资源配置中的作用。",1),row("提交",2)));
        check(text.type.equals("text") && text.options.isEmpty(),"legacy text question");
        String answer="市场通过价格、供求与竞争等机制配置资源。\n同时需要完善相应制度。";
        check(Core.validateText(text,text.id,false,answer,"stop").equals(answer),"preserve answer paragraphs");
        rejects(()->Core.validateText(text,"stale",false,answer,"stop"),"text answer must match question ID");
        rejects(()->Core.validateText(text,text.id,false,"","stop"),"empty answer rejected");
        rejects(()->Core.validateText(text,text.id,false,answer,"length"),"truncated answer rejected");
        check(Core.validateText(text,text.id,true,"","stop")==null,"text abstention supported");

        List<Core.Record> page=new ArrayList<>();
        page.add(node("1.[问答题]",1,0,100,160,false));
        page.add(node("请简述市场在资源配置中的作用。",1,1,200,300,false));
        page.add(node("手工草稿，不应并入题干",1,2,400,600,true));
        Core.Question spatial=Core.parse(page);
        check(spatial.type.equals("text") && !spatial.stem.contains("草稿"),"editable draft is not question data");
        page.add(node("2.[论述题]",3,0,700,760,false));
        page.add(node("请论述保障粮食安全的主要措施。",3,1,800,950,false));
        page.add(node("请输入答案",3,2,1050,1250,true));
        List<Core.Question> both=Core.parseAll(page);
        check(both.size()==2 && both.get(0).type.equals("text") && both.get(1).type.equals("text"),"multiple text questions parsed independently");
        check(QuestionMode.select(both,false).size()==1,"single-question mode unchanged");
        page.get(1).visible=false;
        check(Core.parseAll(page).size()==1,"incomplete text question is excluded while complete second remains");
        List<Core.Record> ambiguous=Arrays.asList(node("[简答题]",1,0,100,160,false),node("请回答一个没有明确结束位置的问题。",1,1,200,300,false));
        rejects(()->Core.parse(ambiguous),"do not guess free-text boundaries");
        System.out.println("Multi-choice and free-text checks passed: "+checks);
    }
}
