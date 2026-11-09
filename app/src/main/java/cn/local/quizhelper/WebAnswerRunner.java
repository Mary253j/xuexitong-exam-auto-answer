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

/** Native key/network handling; the page receives only verified question data and answer letters. */
final class WebAnswerRunner {
    interface Reply {void done(JSONObject value);void failed();}
    interface Host {
        boolean current(String url,long generation);
        void evaluate(JSONObject request,Reply reply);
        void show(String message);
    }
    private static final class Session {
        final QuizService.Run api;
        final String token=UUID.randomUUID().toString(),url;
        final long generation;
        final int ordinal;
        final Map<Integer,String> notes=new LinkedHashMap<>();
        WebAnswerQueue queue;
        WebSnapshot.Entry entry;
        List<String> labels;
        JSONObject previous;
        long started;
        int retries,waits,clicks;
        Session(QuizService.Run api,String url,long generation,int ordinal){this.api=api;this.url=url;this.generation=generation;this.ordinal=ordinal;}
    }
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
            Session session=new Session(new QuizService.Run(key,model,multiple,false),url,generation,ordinal);current=session;
            AppState.lastIssue="暂无失败记录。";AppState.answer="网页答题已开始，等待模型答案。";AppState.latestTextAnswer="";
            JSONObject arm=new JSONObject().put("operation","arm").put("token",session.token);
            host.evaluate(arm,new Reply(){
                public void done(JSONObject result){if(!alive(session))return;if(!result.optString("status").equals("armed")){fail("无法准备网页选择操作，请重新检测页面。");return;}
                    report("网页答题已开始，5 秒后处理第一道题。请保持题页前台，完成后自行核对并提交。");handler.postDelayed(()->read(session),WebAnswerQueue.INTERVAL);
                }
                public void failed(){if(alive(session))fail("无法准备网页答题，请复制问题反馈。");}
            });
        }catch(Exception error){stop(null);issue("网页答题准备失败，请检查主页已保存的配置并重新检测题页。");}
    }
    void stop(String message){
        Session session=current;current=null;handler.removeCallbacksAndMessages(null);
        if(session!=null){
            session.api.active.set(false);session.api.key="";
            final javax.net.ssl.HttpsURLConnection connection=session.api.connection;
            if(connection!=null)new Thread(connection::disconnect,"cancel-web-model").start();
            try{host.evaluate(new JSONObject().put("operation","cancel").put("token",session.token),new Reply(){public void done(JSONObject value){}public void failed(){}});}catch(Exception ignored){}
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
                    session.entry=entries.get(session.queue.current());session.labels=null;session.previous=null;session.waits=0;session.clicks=0;
                    if(!session.entry.problem.isEmpty()){AppState.question=session.entry.problem;finish(session,false,"未自动选择："+session.entry.problem);return;}
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
        }else finish(session,false,"模型回复格式或答案字段不匹配，本题未自动选择。");
    }
    private void afterAnswer(Session session){
        if(session.labels==null){finish(session,false,"模型放弃回答，本题未自动选择。");return;}
        if(session.entry.question.type.equals("text")){
            AppState.latestTextAnswer=session.labels.get(0);finish(session,false,"问答题参考答案已生成，可回助手复制后自行填写。");return;
        }
        if(session.entry.target==null){finish(session,false,"已得到模型答案，但缺少网页选择位置，请手动核对。");return;}
        updateNote(session,"模型答案已得到，正在核对网页选中状态。");select(session);
    }
    private void select(Session session){
        if(!alive(session))return;
        try{
            JSONObject request=new JSONObject().put("operation","select").put("token",session.token).put("expires",System.currentTimeMillis()+3000)
                .put("expected",session.entry.expected).put("target",session.entry.target).put("kind",session.entry.question.type).put("answers",new JSONArray(session.labels));
            if(session.previous!=null)request.put("previous",session.previous);
            host.evaluate(request,new Reply(){
                public void done(JSONObject value){
                    if(!alive(session))return;String status=value.optString("status");
                    if(status.equals("done")){finish(session,true,"网页选中状态已核对，请自行检查并提交。");return;}
                    if(status.equals("clicked")){
                        try{
                            String label=value.getString("label");if(!label.matches("[A-H]") || !(value.get("desired") instanceof Boolean) || ++session.clicks>8)throw new IllegalArgumentException();
                            session.previous=new JSONObject().put("label",label).put("desired",value.getBoolean("desired"));session.waits=0;
                            report("本页第 "+(session.queue.current()+1)+" 题：已操作选项 "+label+"，正在核对状态…");handler.postDelayed(()->select(session),250);
                        }catch(Exception error){fail("网页操作结果不符合预期，已停止，请核对选项。");}
                        return;
                    }
                    if(status.equals("pending")){
                        if(++session.waits<=8){handler.postDelayed(()->select(session),250);return;}
                        finish(session,false,"网页尚未确认点击后的状态，已保留模型答案，请手动核对。");return;
                    }
                    String code=value.optString("code");
                    if(code.equals("UNKNOWN_STATE") || code.equals("UNSUPPORTED_LAYOUT")){
                        finish(session,false,code.equals("UNKNOWN_STATE")?"网页选中状态无法确认，已保留模型答案，请手动选择。":"当前选项布局尚未适配点击，已保留模型答案。");
                    }else fail("网页题目或操作条件已变化（"+safeCode(code)+"），已停止。已得到的模型答案保留。");
                }
                public void failed(){if(alive(session))fail("网页操作结果未能确认，已停止，请核对页面选项。");}
            });
        }catch(Exception error){fail("网页操作数据未能生成，已停止。");}
    }
    private static String safeCode(String code){
        for(String known:new String[]{"NOT_ACTIVE","HIDDEN","EXPIRED","STALE","AMBIGUOUS","FRAME_UNAVAILABLE","WRONG_ORIGIN","LOGIN_PAGE","BAD_REQUEST","LABEL_MISMATCH","UNSAFE_TARGET","SCRIPT_ERROR"})if(known.equals(code))return code;
        return "UNKNOWN";
    }
    private void updateNote(Session session,String outcome){
        String text=session.labels==null?session.entry.question.stem+"\n暂无可用模型答案。":AppState.describeAnswer(session.entry.question,session.labels);
        session.notes.put(session.queue.current(),"本页第 "+(session.queue.current()+1)+" 题\n"+text+"\n结果："+outcome);
        StringBuilder out=new StringBuilder();
        for(String note:session.notes.values()){if(out.length()+note.length()>60000){out.append("\n[答案展示达到长度上限，后续省略]");break;}out.append(note).append("\n\n");}
        AppState.answer=out.toString();
    }
    private void finish(Session session,boolean selected,String outcome){
        if(!alive(session))return;
        session.retries=0;
        if(session.entry.question!=null)updateNote(session,outcome);
        if(!selected)issue("本页第 "+(session.queue.current()+1)+" 题："+outcome);
        session.queue.finish(selected);
        if(session.queue.done()){
            stop("网页答题本轮完成：已确认选择 "+session.queue.selected()+" 题，未自动选择 "+session.queue.manual()+" 题。请核对答案后手动提交。");return;
        }
        handler.postDelayed(()->read(session),WebAnswerQueue.delay(session.started,SystemClock.uptimeMillis()));
    }
    private static String describe(Core.Question q){
        StringBuilder out=new StringBuilder(q.stem).append('\n');for(Core.Option o:q.options)out.append(o.label).append(". ").append(o.text).append('\n');return out.toString();
    }
}
