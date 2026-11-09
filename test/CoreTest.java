package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class CoreTest {
    private static int count;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message); count++;
    }
    private static void rejects(Runnable operation, String description) {
        try { operation.run(); } catch (IllegalArgumentException expected) { count++; return; }
        throw new AssertionError(description);
    }
    private static Core.Record record(String text, int index) {
        Core.Target target = new Core.Target(); target.path = new int[]{index};
        return new Core.Record(text, true, target);
    }
    private static List<Core.Record> page(String type) {
        return new ArrayList<>(Arrays.asList(record("返回",0), record("【"+type+"】",1), record("中国的首都是哪座城市？",2),
            record("A. 北京",3), record("B. 上海",4), record("C. 广州",5), record("下一题",6)));
    }
    public static void main(String[] args) {
        Core.Question q = Core.parse(page("单选题"));
        check(q.type.equals("single"), "type");
        check(q.stem.equals("中国的首都是哪座城市？"), "exclude chrome");
        check(q.options.size()==3 && q.option("A").text.equals("北京"), "options");
        check(Core.validate(q,q.id,false,Collections.singletonList(new Core.Answer("A","北京")),"stop").equals(Collections.singletonList("A")), "answer");
        rejects(() -> Core.validate(q,"old",false,Collections.singletonList(new Core.Answer("A","北京")),"stop"), "stale tag");
        rejects(() -> Core.validate(q,q.id,false,Collections.singletonList(new Core.Answer("A","上海")),"stop"), "wrong text");
        rejects(() -> Core.validate(q,q.id,false,Collections.singletonList(new Core.Answer("Z","北京")),"stop"), "unknown label");
        rejects(() -> Core.validate(q,q.id,false,Collections.singletonList(new Core.Answer("A","北京")),"length"), "truncated response");
        rejects(() -> Core.validate(q,q.id,false,Arrays.asList(new Core.Answer("A","北京"),new Core.Answer("B","上海")),"stop"), "single cardinality");
        check(Core.validate(q,q.id,true,Collections.emptyList(),"stop")==null,"abstention");
        List<Core.Record> invisible=page("单选题"); invisible.get(5).visible=false;
        rejects(() -> Core.parse(invisible), "offscreen options");
        List<Core.Record> shared=page("单选题"); shared.get(4).target.path=shared.get(3).target.path;
        Core.Question sharedQuestion=Core.parse(shared);
        rejects(() -> Core.plan(sharedQuestion,Collections.singletonList("A")), "shared targets must block clicking");
        List<Core.Record> unclickable=page("单选题"); unclickable.get(3).target=null;
        Core.Question textOnly=Core.parse(unclickable);
        check(!Core.clickProblem(textOnly).isEmpty(),"text can be extracted without click targets");
        rejects(() -> Core.plan(textOnly,Collections.singletonList("A")), "no target must block clicking");
        List<Core.Record> incomplete=page("单选题"); incomplete.remove(incomplete.size()-1);
        rejects(() -> Core.parse(incomplete), "no footer");
        List<Core.Record> gap=page("单选题"); gap.remove(4);
        rejects(() -> Core.parse(gap), "missing option");
        List<Core.Record> duplicate=page("单选题"); duplicate.addAll(page("单选题"));
        rejects(() -> Core.parse(duplicate), "multiple questions");
        List<Core.Record> unknown=page("填空题");
        rejects(() -> Core.parse(unknown), "unsupported type");
        Core.Question bool=Core.parse(Arrays.asList(record("判断题",1), record("水的化学式为 H₂O。",2), record("正确",3), record("错误",4), record("提交",5)));
        check(bool.type.equals("boolean") && bool.option("B").text.equals("错误"), "boolean");
        Core.Question wrapped=Core.parse(Arrays.asList(record("单选题",1),record("哪个选项包含两个城市？",2),record("A",3),record("北京",4),record("和上海",5),record("B",6),record("广州",7),record("下一题",8)));
        check(wrapped.option("A").text.equals("北京 和上海"), "wrapped content");
        check(wrapped.option("A").target.path[0]==5,"prefer content target");
        List<Core.Record> multi=page("多选题");
        for(int i=3;i<6;i++)multi.get(i).target.checked=i==4;
        Core.Question m=Core.parse(multi);
        List<Core.Action> actions=Core.plan(m,Arrays.asList("A","C"));
        check(actions.size()==3 && !actions.get(1).desired,"reconcile deselection");
        m.option("B").checked=null;
        rejects(() -> Core.plan(m,Arrays.asList("A","C")),"unknown multi selection");
        q.option("A").checked=true;
        check(Core.plan(q,Collections.singletonList("A")).isEmpty(),"no duplicate toggle");
        List<Core.Record> changed=page("单选题"); changed.get(3).text="A. 上海"; changed.get(4).text="B. 北京";
        check(!q.canonical.equals(Core.parse(changed).canonical),"identity follows option order");
        System.out.println("Core checks passed: "+count);
    }
}
