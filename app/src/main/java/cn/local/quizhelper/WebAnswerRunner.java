package cn.local.quizhelper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native key/network handling and reference display; the page is only read. */
final class WebAnswerRunner {
    interface Reply {void done(JSONObject value);void failed();}
    interface Host {
        boolean current(String url,long generation);
        void evaluate(JSONObject request,Reply reply);
        void show(String message);
        void showAnswers(String text);
    }
    private static final class Session {
        final QuizService.Run api;
        final String url;
        final long generation;
        final int ordinal;
        final Map<Integer,WebAnswerDisplay.Item> notes=new LinkedHashMap<>();
        WebAnswerQueue queue;
        WebSnapshot.Entry entry;
        List<String> labels;
        long started;
        int retries;
        Session(QuizService.Run api,String url,long generation,int ordinal){this.api=api;this.url=url;this.generation=generation;this.ordinal=ordinal;}
    }
    static final class TextReference {
        final int ordinal;final String stem,text;
        TextReference(int ordinal,String stem,String text){this.ordinal=ordinal;this.stem=stem;this.text=text;}
    }
    private final Map<Integer,TextReference> textReferences=new LinkedHashMap<>();
    List<TextReference> textReferences(){return new ArrayList<>(textReferences.values());}
    private final Context context;
    private final Host host;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private volatile Session current;
    WebAnswerRunner(Context context,Host host){this.context=context.getApplicationContext();this.host=host;}
    boolean running(){return current!=null && current.api.active.get();}
    private boolean alive(Session session){return session==current && session.api.active.get() && host.current(session.url,session.generation);}
    private void report(String message){host.show(message);AppState.publish(message);}
    private void issue(String message){host.show(message);AppState.issue(message);}
    private void fail(String message){stop(null);issue(message);}
    void start(String url,long generation,boolean multiple,int ordinal){
        stop(null);
        if(!host.current(url,generation)){issue("请保持助手内题目网页在前台。");return;}
        try{
            String key=new KeyVault(context).load(),model=context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("model","").trim();
            if(key.isEmpty() || model.isEmpty() || key.contains("\n") || key.contains("\r") || model.contains("\n") || model.contains("\r")){
                issue("请先在助手主页保存 API Key 和完整模型名称，再进入网页答题。");return;
            }
            Session session=new Session(new QuizService.Run(key,model,multiple,false),url,generation,ordinal);current=session;textReferences.clear();
            AppState.lastIssue="暂无失败记录。";AppState.answer="正在获取答案，请稍候。";AppState.latestTextAnswer="";
            host.showAnswers(AppState.answer);
            report("已开始获取答案，5 秒后读取第一道题；请自行选择网页选项。");
            handler.postDelayed(()->read(session),WebAnswerQueue.INTERVAL);
        }catch(Exception error){stop(null);issue("网页答题准备失败，请检查主页已保存的配置并重新检测题页。");}
    }
    void stop(String message){
        Session session=current;current=null;handler.removeCallbacksAndMessages(null);
        if(session!=null){
            session.api.active.set(false);session.api.key="";
            if(session.notes.isEmpty()){
                AppState.answer="暂无答案。";host.showAnswers(AppState.answer);
            }
            final javax.net.ssl.HttpsURLConnection connection=session.api.connection;
            if(connection!=null)new Thread(connection::disconnect,"cancel-web-model").start();
        }
        if(message!=null)report(message);
    }
    void close(){stop(null);network.shutdownNow();}
    private void read(Session session){
        if(!alive(session))return;session.started=SystemClock.uptimeMillis();
        host.evaluate(null,new Reply(){
            public void done(JSONObject data){
                if(!alive(session))return;
                try{
                    List<WebSnapshot.Entry> entries=WebSnapshot.entries(data);
                    if(session.queue==null)session.queue=new WebAnswerQueue(entries.size(),session.api.multiple,session.ordinal);
                    List<String> identity=new ArrayList<>();
                    for(WebSnapshot.Entry entry:entries)identity.add(entry.expected.toString());
                    session.queue.verifyPage(identity);
                    session.entry=entries.get(session.queue.current());session.labels=null;
                    if(!session.entry.problem.isEmpty()){AppState.question=session.entry.problem;finish(session,false,"未生成答案："+session.entry.problem);return;}
                    Core.Question q=session.entry.question;AppState.question=describe(q);
                    List<String> cached=session.api.answers.get(q.canonical);
                    if(cached!=null){session.labels=cached;afterAnswer(session);return;}
                    report("正在请求本页第 "+(session.queue.current()+1)+" 题的模型答案…");
                    network.execute(()->{
                        try{
                            List<String> labels=ModelClient.call(session.api,q);
                            handler.post(()->{if(!alive(session))return;session.retries=0;session.labels=labels;
                                if(labels!=null)session.api.answers.put(q.canonical,new ArrayList<>(labels));afterAnswer(session);
                            });
                        }catch(Exception error){handler.post(()->modelFailure(session,error));}
                    });
                }catch(Exception error){fail(error instanceof IllegalArgumentException?error.getMessage():"网页题目数据无法解析，请先检测网页文字。");}
            }
            public void failed(){if(alive(session))fail("网页读取失败，已停止；请复制问题反馈。");}
        });
    }
    private void modelFailure(Session session,Exception error){
        if(!alive(session))return;
        if(error instanceof ModelClient.ApiException){
            ModelClient.ApiException api=(ModelClient.ApiException)error;
            issue(api.getMessage());
            if(api.fatal || ++session.retries>=3){stop("网页答题已停止："+api.getMessage());return;}
            long delay=Math.min(60000,WebAnswerQueue.INTERVAL*(1L<<session.retries));
            report(api.getMessage()+"约 "+(delay/1000)+" 秒后重试当前题。");handler.postDelayed(()->read(session),delay);
        }else finish(session,false,"模型回复格式或答案字段不匹配，本题未得到答案。");
    }
    private void afterAnswer(Session session){
        if(session.labels==null){finish(session,false,"模型未给出可用答案。");return;}
        if(session.entry.question.type.equals("text")){
            AppState.latestTextAnswer=session.labels.get(0);
            textReferences.put(session.queue.current(),new TextReference(session.queue.current()+1,session.entry.question.stem,session.labels.get(0)));
        }
        finish(session,true,"参考答案已显示，请核对后自行填写或选择。");
    }
    private void updateNote(Session session,String outcome){
        session.notes.put(session.queue.current(),new WebAnswerDisplay.Item(session.queue.current()+1,session.entry.question,session.labels));
        AppState.answer=WebAnswerDisplay.render(session.notes.values());host.showAnswers(AppState.answer);
    }
    private void finish(Session session,boolean selected,String outcome){
        if(!alive(session))return;
        session.retries=0;
        updateNote(session,outcome);
        if(!selected)issue("本页第 "+(session.queue.current()+1)+" 题："+outcome);
        session.queue.finish(selected);
        if(session.queue.done()){
            stop("本轮完成：已生成答案 "+session.queue.selected()+" 题，未得到答案 "+session.queue.manual()+" 题。请核对答案后手动提交。");return;
        }
        handler.postDelayed(()->read(session),WebAnswerQueue.delay(session.started,SystemClock.uptimeMillis()));
    }
    private static String describe(Core.Question q){
        StringBuilder out=new StringBuilder(q.stem).append('\n');for(Core.Option o:q.options)out.append(o.label).append(". ").append(o.text).append('\n');return out.toString();
    }
}
