package cn.local.quizhelper;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HttpsURLConnection;

public final class QuizService extends AccessibilityService {
    public static volatile QuizService instance;
    static final long INTERVAL = 5000;
    static final class Run {
        final AtomicBoolean active = new AtomicBoolean(true), busy = new AtomicBoolean(false);
        final AtomicLong epoch = new AtomicLong();
        final String model;
        final boolean multiple;
        final boolean blankMultiple;
        volatile String key, observed = "";
        volatile long retryAt;
        volatile int failures;
        volatile HttpsURLConnection connection;
        final long startedAt=SystemClock.uptimeMillis();
        int diagnosticSamples;
        final StringBuilder diagnosticSummary=new StringBuilder();
        String diagnosticRaw="尚未取得学习通页面的原始记录。";
        final Map<String, String> seen = Collections.synchronizedMap(new LinkedHashMap<String, String>() {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 500; }
        });
        final Map<String,List<String>> answers = Collections.synchronizedMap(new LinkedHashMap<String,List<String>>() {
            @Override protected boolean removeEldestEntry(Map.Entry<String,List<String>> e) { return size()>100; }
        });
        final Map<String,String> answerText = Collections.synchronizedMap(new LinkedHashMap<>());
        Run(String key,String model) { this(key,model,false,false); }
        Run(String key,String model,boolean multiple,boolean blankMultiple) { this.key=key;this.model=model;this.multiple=multiple;this.blankMultiple=blankMultiple; }
    }
    private final Object actionLock = new Object();
    private volatile Run run;
    private HandlerThread thread;
    private Handler handler;
    private PageReader reader;
    private ToggleLedger journal;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private Runnable scheduled;

    @Override public void onServiceConnected() {
        instance = this;
        thread = new HandlerThread("question-reader"); thread.start(); handler = new Handler(thread.getLooper());
        reader = new PageReader(this);
        journal=SelectionJournal.open(this);
        AppState.publish("无障碍已连接，尚未开始。停止状态不读取页面。");
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // No continuous inspection or event-content logging. Polling starts explicitly.
    }
    @Override public void onInterrupt() { stopRun("无障碍服务被系统中断，已停止。"); }
    @Override public void onDestroy() {
        stopRun("服务已关闭。"); instance = null;
        if (thread != null) thread.quitSafely(); network.shutdownNow(); super.onDestroy();
    }
    public boolean running() { Run current = run; return current != null && current.active.get(); }
    private boolean alive(Run current) { return current != null && current == run && current.active.get(); }
    private void report(Run current, String text) {
        synchronized (actionLock) { if (alive(current)) AppState.publish(text); }
    }

    public void stopRun(String message) {
        Run previous;
        synchronized (actionLock) {
            previous = run; run = null;
            if (previous != null) { previous.active.set(false); previous.key = ""; }
        }
        if (handler != null && scheduled != null) handler.removeCallbacks(scheduled);
        scheduled = null;
        // Cancelling I/O may block briefly; never wait for it on the UI thread.
        if (previous != null && previous.connection != null) {
            final HttpsURLConnection connection = previous.connection;
            new Thread(connection::disconnect, "cancel-model-request").start();
        }
        AppState.publish(message);
    }
    public void begin(String key, String model, boolean diagnostic,boolean multiple,boolean blankMultiple) {
        stopRun("正在准备…");
        Run current = new Run(key, model,multiple,blankMultiple); run = current;
        AppState.lastIssue="暂无失败记录。";
        if(diagnostic)AppState.diagnostic="正在准备本次原始节点检测，尚未读取页面。";
        if(!diagnostic){AppState.answer="正在读取题目，稍后显示模型答案。";AppState.latestTextAnswer="";}
        report(current, diagnostic ? "5 秒后开始连续检测 3 次，请保持同一道题约 20 秒。" : "已开始，5 秒后首次读取题目。");
        if (diagnostic) {
            scheduled = () -> diagnose(current);
        } else {
            scheduled = new Runnable() {
                @Override public void run() {
                    if (!alive(current)) return;
                    long started = SystemClock.uptimeMillis();
                    try { tick(current); } catch (Exception error) {
                        observe(current, "read-error"); report(current, "页面读取失败，请先运行文字检测。");
                    }
                    if (alive(current)) handler.postDelayed(this, Math.max(1, INTERVAL - (SystemClock.uptimeMillis() - started)));
                }
            };
        }
        handler.postDelayed(scheduled, INTERVAL);
    }
    private void observe(Run current, String identity) {
        if (!identity.equals(current.observed)) { current.observed = identity; current.epoch.incrementAndGet(); }
    }
    private List<Core.Question> questions(PageReader.Page page,Run current) {
        return QuestionMode.select(Core.parseAll(page.records),current.multiple);
    }
    private void issue(Run current,String text) {
        synchronized(actionLock){if(alive(current))AppState.issue(text);}
    }
    private String details(PageReader.Page page) {
        if(!page.rawAudit.isEmpty())return page.rawAudit;
        StringBuilder result=new StringBuilder("分享前请删除页面个人信息。\n");
        for(Core.Record record:page.records){
            result.append(record.visible?"[可见] ":"[屏幕外] ").append(record.text)
                .append(record.target==null?" [无点击操作]":record.target.gesture?" [按位置轻点]":" [原生点击]").append('\n')
                .append("位置 ").append(record.left).append(',').append(record.top).append(',').append(record.right).append(',').append(record.bottom)
                .append(" 节点 ").append(java.util.Arrays.toString(record.sourcePath)).append('\n');
            if(!record.rawDescription.isEmpty() && !record.rawDescription.equals(record.text))result.append("辅助描述 ").append(record.rawDescription).append('\n');
            if(record.text.isEmpty())result.append("此节点未提供文字；类型 ").append(record.nodeClass).append('\n');
            if(result.length()>40000){result.append("…后续省略");break;}
        }
        return result.toString();
    }
    private void diagnose(Run current) {
        if(!alive(current))return;
        long started=SystemClock.uptimeMillis();
        PageReader.Page page=null;String problem="",description="";
        try{
            page=reader.readFresh();
            if(page==null){
                problem="没有读到学习通题目页面。请保持亮屏、解锁，并留在同一道题直到连续检测完成。";
            }else{
                if(!page.complete)throw new IllegalArgumentException("原始节点检测不完整，报告已标记读取异常、刷新失败或节点上限。");
                List<Core.Question> found=questions(page,current);
                description=AppState.describe(found,current.blankMultiple);
            }
        }catch(IllegalArgumentException error){
            problem="题目识别未完成："+error.getMessage();
        }catch(Exception error){problem="页面读取失败（"+error.getClass().getSimpleName()+"），请复制问题反馈。";}
        synchronized(actionLock){
            if(!alive(current))return;
            int sample=++current.diagnosticSamples;
            current.diagnosticSummary.append("第 ").append(sample).append("/3 次，启动后 ")
                .append(started-current.startedAt).append(" ms：");
            if(page!=null){
                int words=0;for(Core.Record record:page.records)if(record.visible && !Core.clean(record.text).isEmpty())words++;
                current.diagnosticSummary.append("窗口 ").append(page.windowId).append("，可见非空记录 ").append(words).append("；");
                current.diagnosticRaw="以下为第 "+sample+" 次采样取得的原始节点报告（各次数据不合并）：\n"+details(page);
            }
            current.diagnosticSummary.append(problem.isEmpty()?"已识别完整题目":problem).append('\n');
            AppState.diagnostic="连续刷新检测进度 "+sample+"/3\n"+current.diagnosticSummary+"\n"+current.diagnosticRaw;
            AppState.question=problem.isEmpty()?description:problem;
            if(!problem.isEmpty())issue(current,problem);
            if(sample<3){
                report(current,"连续检测已完成 "+sample+"/3 次，请继续保持同一道题；检测不调用 API 或点击答案。");
                handler.postDelayed(scheduled,Math.max(1,INTERVAL-(SystemClock.uptimeMillis()-started)));
            }else{
                report(current,"连续检测完成（3/3），请复制问题反馈。检测未调用 API 或点击答案。");
                current.active.set(false);current.key="";
            }
        }
    }
    private void tick(Run current) {
        if(!alive(current))return;
        PageReader.Page page=reader.read();
        if(page==null){observe(current,"outside");report(current,"等待学习通题目页面。已得到的答案会保留，回到原题后继续。");return;}
        List<Core.Question> found;
        try{found=questions(page,current);}
        catch(IllegalArgumentException error){
            observe(current,"unreadable");AppState.diagnostic=details(page);AppState.question="题目识别未完成："+error.getMessage();
            issue(current,AppState.question);return;
        }
        AppState.question=AppState.describe(found,current.blankMultiple);
        StringBuilder identity=new StringBuilder().append(page.windowId);
        for(Core.Question q:found)identity.append(':').append(q.canonical);
        observe(current,identity.toString());
        if(current.busy.get())return;
        if(SystemClock.elapsedRealtime()<current.retryAt){
            report(current,"等待 API 重试，约 "+((current.retryAt-SystemClock.elapsedRealtime()+999)/1000)+" 秒。");return;
        }
        for(Core.Question q:found){
            if(current.seen.containsKey(q.canonical))continue;
            if(!current.busy.compareAndSet(false,true))return;
            long epoch=current.epoch.get();
            report(current,current.answers.containsKey(q.canonical)?"复用已得到的答案，准备选择选项…":"已识别题目，正在请求模型答案…");
            network.execute(()->answer(current,q,page.windowId,epoch));return;
        }
        report(current,current.multiple?"当前页完整题目已处理；请查看模型答案或手动向下滚动。":"这道题已处理；可查看模型答案，或手动切换 / 滚动到下一题。");
    }
    private void rememberAnswer(Run current,Core.Question question,List<String> labels){
        synchronized(actionLock){
        if(!alive(current))return;
        current.answers.put(question.canonical,labels);
        current.answerText.put(question.canonical,AppState.describeAnswer(question,labels));
        StringBuilder out=new StringBuilder();
        synchronized(current.answerText){for(String text:current.answerText.values())out.append(text).append("\n\n");}
        AppState.answer=out.toString().trim();
        if(question.type.equals("text"))AppState.latestTextAnswer=labels.get(0);
        }
    }
    private void answer(Run current,Core.Question question,int windowId,long epoch){
        try{
            if(!alive(current))return;
            List<String> labels=current.answers.get(question.canonical);
            if(labels==null){
                try{
                    labels=ModelClient.call(current,question);current.failures=0;current.retryAt=0;
                }catch(ModelClient.ApiException error){
                    if(!alive(current))return;
                    if(error.fatal){issue(current,error.getMessage()+" 已停止，保存的配置保留，可以修改后重试。");current.active.set(false);current.key="";}
                    else{current.failures++;current.retryAt=SystemClock.elapsedRealtime()+Math.min(300000L,30000L<<Math.min(4,current.failures-1));issue(current,error.getMessage());}
                    return;
                }
                if(!alive(current))return;
                if(labels==null){String message="模型无法确定本题答案，已跳过，请手动处理。";current.seen.put(question.canonical,message);issue(current,message);return;}
                rememberAnswer(current,question,labels);
            }
            if(question.type.equals("text")){
                String message="问答题参考答案已生成，可核对后点击“复制最新问答答案”并粘贴到学习通。";
                current.seen.put(question.canonical,message);report(current,message);return;
            }
            String problem=Core.clickProblem(question,current.blankMultiple);
            if(!problem.isEmpty()){current.seen.put(question.canonical,problem);issue(current,problem);return;}
            int clicked=apply(current,question,windowId,epoch,labels);
            String message="已发送选项选择："+String.join("、",labels)+"。请在学习通核对。";
            if(Core.unknownMultiple(question))message="已按空白题模式尝试勾选："+String.join("、",labels)+"；点击记录已保留，请核对选中结果。";
            if(Core.unknownMultiple(question) && clicked==0 && !journal.sent(question.id).isEmpty())
                message="本题已有多选点击记录，未重复点击。若是新一轮重测，请先清空勾选，再重置多选记录。";
            current.seen.put(question.canonical,message);AppState.lastIssue="暂无失败记录。";report(current,message);
        }catch(PageChanged changed){
            if(alive(current))report(current,"模型答案已保留。回到对应题目后会继续选择，不会重复请求模型。");
        }catch(Exception error){
            if(!alive(current))return;
            String message=error instanceof IllegalArgumentException?error.getMessage():"模型回复格式不兼容或选项操作失败。";
            message+=" 本题已暂停，重新开始可重试。";current.seen.put(question.canonical,message);issue(current,message);
        }finally{current.busy.set(false);}
    }
    private static final class PageChanged extends IllegalArgumentException {}
    private Core.Question fresh(Core.Question expected,int windowId){
        PageReader.Page page=reader.read();
        if(page==null || page.windowId!=windowId)throw new PageChanged();
        try {
            for(Core.Question q:Core.parseAll(page.records))if(q.canonical.equals(expected.canonical))return q;
        }catch(IllegalArgumentException changed){throw new PageChanged();}
        throw new PageChanged();
    }
    private void guard(Run current, long epoch) {
        if (!alive(current) || current.epoch.get() != epoch) throw new PageChanged();
    }
    private int apply(Run current, Core.Question original, int windowId, long epoch, List<String> labels) {
        guard(current, epoch);
        Core.Question q = fresh(original, windowId);
        boolean once=Core.unknownMultiple(q) && current.blankMultiple;
        java.util.Set<String> already=java.util.Collections.emptySet();
        if(once){
            synchronized(actionLock){guard(current,epoch);journal.prepare(q.id,labels);already=journal.sent(q.id);}
        }
        List<Core.Action> actions = Core.plan(q,labels,current.blankMultiple,already);
        int clicked=0;
        for (Core.Action action : actions) {
            guard(current, epoch); q = fresh(original, windowId);
            Core.Option option = q.option(action.label);
            if (option.checked != null && option.checked == action.desired) continue;
            boolean recordToggle=once && option.checked==null && action.desired;
            synchronized (actionLock) {
                guard(current, epoch);
                if(recordToggle && !journal.reserve(original.id,action.label))continue;
                if (!reader.click(windowId, option.target)) {
                    if(recordToggle)journal.release(original.id,action.label);
                    throw new IllegalArgumentException("节点或位置已变化，点击取消。");
                }
                if(recordToggle)journal.sent(original.id,action.label);
                clicked++;
            }
            SystemClock.sleep(350);
            if (q.type.equals("multiple")) {
                guard(current, epoch);
                Core.Option after = fresh(original, windowId).option(action.label);
                if (after.checked==null && recordToggle)continue;
                if (after.checked == null || after.checked != action.desired) throw new IllegalArgumentException("未确认选项变化，停止后续点击。");
            }
        }
        return clicked;
    }
}
