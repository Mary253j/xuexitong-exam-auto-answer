package cn.local.quizhelper;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class AppState extends Application {
    public interface Listener { void changed(); }
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    public static volatile String status = "尚未开始";
    public static volatile String diagnostic = "尚未检测。";
    public static volatile String question = "尚未识别题目。";
    public static volatile String answer = "暂无模型答案。点击“开始自动答题”后会显示在这里。";
    public static volatile String lastIssue = "暂无失败记录。";
    public static volatile String latestTextAnswer="";
    public static volatile String webDiagnostic="尚未打开助手内的网页。";
    private static final Deque<String> history = new ArrayDeque<>();

    public static void add(Listener listener) { listeners.addIfAbsent(listener); }
    public static void remove(Listener listener) { listeners.remove(listener); }
    public static void publish(String message) {
        status = message;
        synchronized (history) {
            if (!message.equals(history.peekLast())) history.addLast(message);
            while (history.size()>25) history.removeFirst();
        }
        main.post(() -> { for (Listener listener : listeners) listener.changed(); });
    }
    public static void issue(String message) { lastIssue=message; publish(message); }
    public static String describe(List<Core.Question> questions) {
        return describe(questions,false);
    }
    public static String describe(List<Core.Question> questions,boolean blankMultiple) {
        StringBuilder out=new StringBuilder("本页读到 "+questions.size()+" 道完整题目\n");
        for(int i=0;i<questions.size();i++){
            Core.Question q=questions.get(i);
            out.append('\n').append(i+1).append(". ").append(q.stem).append('\n');
            for(Core.Option o:q.options)out.append(o.label).append(". ").append(o.text).append('\n');
            String problem=Core.clickProblem(q,blankMultiple);
            if(!problem.isEmpty())out.append(problem).append('\n');
            if(blankMultiple && Core.unknownMultiple(q))out.append("将按空白多选题选择一次，请先取消已有手动勾选。\n");
        }
        return out.toString();
    }
    public static String describeAnswer(Core.Question q,List<String> labels) {
        if(q.type.equals("text"))return q.stem+"\n参考答案（请核对）：\n"+labels.get(0);
        StringBuilder out=new StringBuilder(q.stem).append("\n模型建议：");
        for(String label:labels)out.append(label).append("（").append(q.option(label).text).append("） ");
        return out.toString();
    }
    public static String feedback() {
        StringBuilder out=new StringBuilder("题页助手 1.0.12（网页答题复查版）\n状态：").append(status)
            .append("\n最近问题：").append(lastIssue).append("\n\n").append(question)
            .append("\n\n").append(answer).append("\n\n状态记录：\n");
        synchronized(history){for(String line:history)out.append(line).append('\n');}
        return out.append("\n详细检测（可能含页面个人信息，分享前请检查）：\n").append(diagnostic)
            .append("\n\n网页登录与加载诊断：\n").append(webDiagnostic).toString();
    }
}
