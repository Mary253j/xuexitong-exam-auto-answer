package cn.local.quizhelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Official-site WebView with explicit detection and answer controls. No native JS bridge. */
@SuppressWarnings("deprecation")
public final class WebActivity extends Activity {
    private WebView web;
    private TextView location,status;
    private RadioGroup mode;
    private boolean foreground;
    private long generation;
    private String script;
    private final List<String> frames=new ArrayList<>();
    private final Set<String> blocked=Collections.synchronizedSet(new LinkedHashSet<>());
    private final WebLoadLog loadLog=new WebLoadLog();
    private String webEnvironment="";
    private String defaultAgent;
    private boolean desktop;
    private WebAnswerRunner answers;
    private EditText ordinal;
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void text(LinearLayout parent,String value,int size){
        TextView item=new TextView(this);item.setText(value);item.setTextSize(size);item.setTextColor(0xff243b53);
        item.setPadding(dp(8),dp(4),dp(8),dp(4));parent.addView(item);
    }
    private LinearLayout row(LinearLayout parent){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);parent.addView(row);return row;
    }
    private void button(LinearLayout row,String label,Runnable action){
        Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextSize(12);
        row.addView(button,new LinearLayout.LayoutParams(0,dp(46),1));button.setOnClickListener(v->action.run());
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(QuizService.instance!=null)QuizService.instance.stopRun("已停止 APP 模式，进入网页文字检测。");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setFitsSystemWindows(true);root.setBackgroundColor(Color.WHITE);
        text(root,"网页答题 · 不截屏",19);
        text(root,"进入题目后点击“开始网页答题”，使用主页已保存的 API 配置，每 5 秒逐题处理。保持题页前台，完成后请自行核对并提交。",12);
        location=new TextView(this);location.setTextSize(12);location.setPadding(dp(8),0,dp(8),0);root.addView(location);
        LinearLayout navigation=row(root);
        button(navigation,"返回助手",this::finish);
        button(navigation,"网页后退",()->{stopAnswers("已停止网页答题。");if(web.canGoBack())web.goBack();else status.setText("没有上一页。");});
        button(navigation,"官方首页",()->{stopAnswers("已停止网页答题。");web.loadUrl(WebAddress.START);});
        button(navigation,"清除网页登录",this::clearLogin);
        LinearLayout zoom=row(root);
        button(zoom,"缩小",()->{if(!web.zoomOut())status.setText("已到当前页面最小比例；若仍显示不全，可切换电脑版。");});
        button(zoom,"放大",()->web.zoomIn());
        button(zoom,"切换手机/电脑版",this::toggleDesktop);
        mode=new RadioGroup(this);mode.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton single=new RadioButton(this);single.setId(1);single.setText("单题：指定本页题号");single.setTextSize(12);
        RadioButton multiple=new RadioButton(this);multiple.setId(2);multiple.setText("多题：本页可解析题");multiple.setTextSize(12);
        mode.addView(single);mode.addView(multiple);mode.check(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("web_multiple_questions",false)?2:1);root.addView(mode);
        LinearLayout numbering=row(root);text(numbering,"单题题号",12);ordinal=new EditText(this);ordinal.setSingleLine();ordinal.setTextSize(13);ordinal.setInputType(InputType.TYPE_CLASS_NUMBER);
        ordinal.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);ordinal.setText(String.valueOf(getSharedPreferences("settings",MODE_PRIVATE).getInt("web_single_ordinal",1)));
        numbering.addView(ordinal,new LinearLayout.LayoutParams(dp(60),dp(38)));
        text(numbering,"仅用于单题模式，按本页顺序计数",11);
        LinearLayout tools=row(root);
        button(tools,"检测网页文字",this::detect);
        button(tools,"打开题目框架",this::openFrame);
        button(tools,"复制问题反馈",this::copyFeedback);
        LinearLayout actions=row(root);button(actions,"开始网页答题",this::startAnswers);button(actions,"停止网页答题",()->{if(answers!=null)answers.stop("已停止网页答题，已得到的答案和保存的 Key 保留。");});
        status=new TextView(this);status.setTextSize(12);status.setMaxLines(3);status.setPadding(dp(8),dp(4),dp(8),dp(4));status.setText("进入题目后可先检测文字，再开始网页答题。");root.addView(status);
        web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        android.content.pm.PackageInfo provider=WebView.getCurrentWebViewPackage();
        webEnvironment="Android "+android.os.Build.VERSION.RELEASE+" / API "+android.os.Build.VERSION.SDK_INT+
            "；WebView "+(provider==null?"未取得版本":provider.packageName+" "+provider.versionName)+"\n";
        publishLoadLog();
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);settings.setDisplayZoomControls(false);
        defaultAgent=settings.getUserAgentString();desktop=getSharedPreferences("settings",MODE_PRIVATE).getBoolean("web_desktop",false);
        if(desktop)settings.setUserAgentString(WebUserAgent.desktop(defaultAgent));
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSafeBrowsingEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
            @Override public boolean onConsoleMessage(ConsoleMessage message){
                if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR){loadLog.console(message.message(),message.sourceId(),message.lineNumber());publishLoadLog();}
                return true;
            }
        });
        web.setDownloadListener((url,userAgent,disposition,mime,length)->status.setText("测试版不下载文件。"));
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                String target=request.getUrl().toString();
                if(WebAddress.allowed(target))return false;
                String upgraded=WebAddress.upgrade(target);
                if(upgraded!=null && request.isForMainFrame() && "GET".equals(request.getMethod())){
                    loadLog.event(WebLoadLog.Event.UPGRADE_HTTPS,target,0);publishLoadLog();
                    AppState.publish("已将超星官方网页的 HTTP 跳转转换为 HTTPS，未发送明文请求。");
                    view.loadUrl(upgraded);return true;
                }
                noteBlocked(target);loadLog.event(WebLoadLog.Event.BLOCK_NAVIGATION,target,0);publishLoadLog();
                if(request.isForMainFrame()){
                    String problem="网页跳转已拦截（"+WebAddress.origin(target)+"）。请复制问题反馈；若已登录可尝试“官方首页”。";
                    status.setText(problem);AppState.issue(problem);
                }return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                if(WebAddress.resource(request.getUrl().toString()))return null;
                noteBlocked(request.getUrl().toString());loadLog.event(WebLoadLog.Event.BLOCK_RESOURCE,request.getUrl().toString(),0);publishLoadLog();
                return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){
                stopAnswers("网页已跳转，已停止网页答题。");
                loadLog.event(WebLoadLog.Event.OPEN,url,0);publishLoadLog();
                generation++;frames.clear();location.setText("官方页面："+WebAddress.display(url)+"（不显示链接参数）");status.setText("正在加载网页；进入作业后点击检测。");
            }
            @Override public void onPageFinished(WebView view,String url){loadLog.event(WebLoadLog.Event.FINISH,url,0);publishLoadLog();status.setText("网页已加载。打开题目后点击检测；登录失败也可直接复制问题反馈。");}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){
                handler.cancel();loadLog.event(WebLoadLog.Event.TLS_ERROR,error.getUrl(),error.getPrimaryError());publishLoadLog();
                status.setText("网站证书验证失败，已停止加载。");AppState.issue("网站证书验证失败，已停止加载。");
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                loadLog.event(WebLoadLog.Event.NETWORK_ERROR,request.getUrl().toString(),error.getErrorCode());publishLoadLog();
                if(request.isForMainFrame()){status.setText("网页加载失败，请复制问题反馈。");AppState.issue("网页加载失败，错误码 "+error.getErrorCode()+"。");}
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){
                loadLog.event(WebLoadLog.Event.HTTP_ERROR,request.getUrl().toString(),response.getStatusCode());publishLoadLog();
                if(request.isForMainFrame()){status.setText("网页返回 HTTP "+response.getStatusCode()+"，请复制问题反馈。");AppState.issue("网页返回 HTTP "+response.getStatusCode()+"。");}
            }
        });
        answers=new WebAnswerRunner(this,new WebAnswerRunner.Host(){
            public boolean current(String url,long epoch){return web!=null && foreground && generation==epoch && WebAddress.allowed(web.getUrl()) && !WebAddress.login(web.getUrl()) && url!=null && url.equals(web.getUrl());}
            public void show(String message){status.setText(message);}
            public void evaluate(JSONObject request,WebAnswerRunner.Reply reply){
                if(web==null || !WebAddress.allowed(web.getUrl())){reply.failed();return;}
                try{
                    String executable=request==null?loadScript():WebScript.bind(loadScript(),request.toString());
                    web.evaluateJavascript(executable,value->{
                        try{reply.done(WebSnapshot.decode(value));}catch(Exception error){reply.failed();}
                    });
                }catch(Exception error){reply.failed();}
            }
        });
        mode.setOnCheckedChangeListener((group,id)->{stopAnswers("页面模式已改变，请重新开始网页答题。");generation++;});
        ordinal.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){stopAnswers("题号已改变，请重新开始网页答题。");generation++;}
            public void afterTextChanged(Editable value){}
        });
        web.loadUrl(WebAddress.START);
    }
    private void stopAnswers(String message){if(answers!=null && answers.running())answers.stop(message);}
    private void startAnswers(){
        int number=1;
        if(mode.getCheckedRadioButtonId()!=2){try{number=Integer.parseInt(ordinal.getText().toString().trim());}catch(Exception error){number=0;}}
        if(number<1 || number>40){status.setText("请输入本页 1 到 40 范围内的题号。");return;}
        android.content.SharedPreferences.Editor preferences=getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("web_multiple_questions",mode.getCheckedRadioButtonId()==2);
        if(mode.getCheckedRadioButtonId()!=2)preferences.putInt("web_single_ordinal",number);
        preferences.apply();
        generation++;
        answers.start(web.getUrl(),generation,mode.getCheckedRadioButtonId()==2,number);
    }
    private void publishLoadLog(){AppState.webDiagnostic=webEnvironment+"网页显示模式="+(desktop?"电脑版":"手机版")+"；按宽度适配=true；缩放=true\n"+loadLog.report();}
    private void toggleDesktop(){
        stopAnswers("网页显示模式已改变，已停止网页答题。");
        desktop=!desktop;getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("web_desktop",desktop).apply();
        generation++;frames.clear();web.stopLoading();
        web.getSettings().setUserAgentString(desktop?WebUserAgent.desktop(defaultAgent):defaultAgent);
        publishLoadLog();status.setText("已切换为"+(desktop?"电脑版":"手机版")+"，正在重新加载当前网页。");
        web.reload();
        Toast.makeText(this,"已切换"+(desktop?"电脑版":"手机版")+"，可双指缩放或点击上方缩放按钮。",Toast.LENGTH_LONG).show();
    }
    private void noteBlocked(String value){
        try{String host=new java.net.URI(value).getHost();if(host==null)return;synchronized(blocked){if(blocked.size()<20)blocked.add(host);}}catch(Exception ignored){}
    }
    private String loadScript() throws Exception{
        if(script!=null)return script;
        try(InputStream input=getResources().openRawResource(R.raw.web_read);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>100000)throw new IllegalArgumentException();out.write(buffer,0,n);}
            script=new String(out.toByteArray(),StandardCharsets.UTF_8);return script;
        }
    }
    private void detect(){
        stopAnswers("已停止网页答题，准备只检测文字。");
        String url=web.getUrl();
        if(!WebAddress.allowed(url) || WebAddress.login(url)){status.setText("请先在官方网页自行登录并打开作业；登录表单不参与检测。");return;}
        final long ticket=++generation;final boolean multiple=mode.getCheckedRadioButtonId()==2;
        getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("web_multiple_questions",multiple).apply();
        status.setText("正在读取题目区域的网页文字，不调用模型或点击答案…");
        try{
            web.evaluateJavascript(loadScript(),value->{
                if(!foreground || ticket!=generation || !WebAddress.allowed(web.getUrl()) || !url.equals(web.getUrl()))return;
                try{result(value,multiple,url);}catch(Exception error){
                    AppState.question="网页文字检测未完成。";AppState.diagnostic="网页返回格式不符合预期；未复制整页网页或登录表单。";
                    AppState.issue("网页检测结果无法解析（"+error.getClass().getSimpleName()+"）。");status.setText(AppState.lastIssue);
                }
            });
        }catch(Exception error){status.setText("无法执行本地网页检测脚本，请复制反馈。");AppState.issue("网页检测脚本执行未完成。");}
    }
    private void result(String encoded,boolean multiple,String url) throws Exception{
        Object decoded=new JSONTokener(encoded).nextValue();if(!(decoded instanceof String))throw new IllegalArgumentException();
        JSONObject object=new JSONObject((String)decoded);if(object.optInt("schema")!=1)throw new IllegalArgumentException();
        JSONArray candidates=object.optJSONArray("questions"),documents=object.optJSONArray("documents"),foundFrames=object.optJSONArray("frames");
        if(candidates==null || candidates.length()>40 || documents==null || documents.length()>12 || foundFrames==null || foundFrames.length()>12)throw new IllegalArgumentException();
        frames.clear();StringBuilder report=new StringBuilder("网页只读检测。分享前请删除页面个人信息。\n来源：").append(WebAddress.display(url))
            .append("\n未读取输入框、Cookie、登录表单或整页正文；未请求模型或操作选项。\n")
            .append("候选题目：").append(candidates.length()).append("；检测截断：").append(object.optBoolean("truncated"))
            .append("；跳过登录文档：").append(object.optInt("loginDocuments")).append("；跳过其他站点框架：").append(object.optInt("blockedFrames")).append('\n');
        if(!object.optString("error").isEmpty())report.append("脚本提示：网页检测没有完整执行。\n");
        for(int i=0;i<documents.length();i++){
            JSONObject doc=documents.getJSONObject(i);report.append("\n文档 ").append(doc.optString("location")).append("；候选容器 ").append(doc.optInt("candidates")).append('\n');
            JSONArray selectors=doc.optJSONArray("selectors");if(selectors!=null)for(int j=0;j<Math.min(10,selectors.length());j++){
                JSONObject selector=selectors.getJSONObject(j);report.append(selector.optString("selector")).append(" = ").append(selector.optInt("count")).append('\n');
            }
        }
        for(int i=0;i<foundFrames.length();i++){
            String frame=foundFrames.getJSONObject(i).optString("url");
            if(WebAddress.allowed(frame) && !WebAddress.login(frame) && !frames.contains(frame)){frames.add(frame);report.append("可打开的题目框架 ").append(frames.size()).append(": ").append(WebAddress.display(frame)).append('\n');}
        }
        List<Core.Question> parsed=new ArrayList<>(),byOrdinal=new ArrayList<>();boolean cut=false;int warnings=0;
        for(int i=0;i<candidates.length();i++){
            JSONObject raw=candidates.getJSONObject(i);WebQuestionParser.Block block=new WebQuestionParser.Block();
            block.title=raw.optString("title");block.media=raw.optBoolean("media");block.truncated=raw.optBoolean("truncated");
            block.extractionIssue=raw.optString("optionIssue");if(block.extractionIssue.length()>200)throw new IllegalArgumentException();
            JSONArray options=raw.optJSONArray("options");if(options==null || options.length()>8 || block.title.length()>6000)throw new IllegalArgumentException();
            for(int j=0;j<options.length();j++){String option=options.getString(j);if(option.length()>3500)throw new IllegalArgumentException();block.options.add(option);}
            Core.Question resolved=null;String issue="";try{resolved=WebQuestionParser.parse(block);parsed.add(resolved);}catch(IllegalArgumentException failure){issue=failure.getMessage();}
            byOrdinal.add(resolved);
            if(raw.optBoolean("customFont")){warnings++;issue+=(issue.isEmpty()?"":"；")+"存在 font-cxsecret 标记，请核对文字是否与网页显示一致。";}
            if(report.length()<40000){
                report.append("\n候选题 ").append(i+1).append(" 标题/题干：").append(block.title).append('\n');
                String matched=raw.optString("optionSelector");if(matched.length()>100)throw new IllegalArgumentException();
                report.append("选项规则：").append(matched.isEmpty()?"尚未匹配":matched).append("；读取行数：").append(block.options.size()).append('\n');
                JSONArray stats=raw.optJSONArray("optionStats");if(stats!=null)for(int j=0;j<Math.min(12,stats.length());j++){
                    JSONObject stat=stats.getJSONObject(j);String selector=stat.optString("selector");if(selector.length()>100)throw new IllegalArgumentException();
                    report.append("选项节点 ").append(selector).append(" = ").append(stat.optInt("count")).append("，可显示 ").append(stat.optInt("rendered")).append('\n');
                }
                JSONArray structure=raw.optJSONArray("structure");if(structure!=null)for(int j=0;j<Math.min(10,structure.length());j++){
                    JSONObject part=structure.getJSONObject(j);String selector=part.optString("selector");if(selector.length()>100)throw new IllegalArgumentException();
                    report.append("结构 ").append(selector).append(" = ").append(part.optInt("count")).append('\n');
                }
                for(String option:block.options)report.append("选项行：").append(option).append('\n');
                JSONArray selection=raw.optJSONArray("selection");if(selection!=null){
                    report.append("选中状态：");for(int j=0;j<Math.min(8,selection.length());j++)report.append((char)('A'+j)).append('=').append(selection.opt(j) instanceof Boolean?selection.opt(j):"未知").append(' ');report.append('\n');
                }
                report.append(issue.isEmpty()?"结构可解析；尚未进行手机显示一致性验证。":issue).append('\n');
            }else cut=true;
        }
        if(report.length()>45000){report.setLength(45000);cut=true;}
        if(cut)report.append("\n[反馈文字已截断]\n");
        synchronized(blocked){if(!blocked.isEmpty())report.append("\n被拦截的站点主机名（不含路径或参数）：").append(blocked).append('\n');}
        AppState.diagnostic=report.toString();
        if(parsed.isEmpty()){
            AppState.question="尚未从网页识别到完整题目。";
            AppState.issue(frames.isEmpty()?"网页题目结构尚未适配，请复制网页检测反馈。":"题目可能在独立框架中，请点“打开题目框架”，进入后再检测。");
            status.setText(AppState.lastIssue);
        }else{
            List<Core.Question> shown=parsed;
            if(!multiple){
                int requested;try{requested=Integer.parseInt(ordinal.getText().toString().trim());}catch(Exception invalid){requested=0;}
                if(requested<1 || requested>byOrdinal.size() || byOrdinal.get(requested-1)==null){
                    AppState.question="指定的本页题号超出范围或该题未完整读取。";AppState.issue(AppState.question);status.setText(AppState.lastIssue);return;
                }
                shown=Collections.singletonList(byOrdinal.get(requested-1));
            }
            StringBuilder text=new StringBuilder("网页读到 ").append(parsed.size()).append(" 道可解析题目，本次展示 ").append(shown.size()).append(" 道。请核对内容是否与网页一致。\n");
            for(Core.Question q:shown){text.append('\n').append(q.stem).append('\n');for(Core.Option option:q.options)text.append(option.label).append(". ").append(option.text).append('\n');}
            AppState.question=text.toString();AppState.lastIssue=warnings>0?"检测到自定义字体标记，文字仍需核对。":"暂无失败记录。";
            AppState.publish("网页文字检测完成：可解析 "+parsed.size()+" 道题。可在题页点击“开始网页答题”。");
            status.setText("检测到 "+parsed.size()+" 道可解析题目。核对文字后可点击“开始网页答题”。");
        }
    }
    private void openFrame(){
        stopAnswers("已停止网页答题，准备打开题目框架。");
        if(frames.isEmpty()){status.setText("请先检测网页文字；若找到独立题目框架会列在这里。");return;}
        List<String> snapshot=new ArrayList<>(frames);String[] labels=new String[snapshot.size()];
        for(int i=0;i<labels.length;i++)labels[i]=(i+1)+". "+WebAddress.display(snapshot.get(i));
        new AlertDialog.Builder(this).setTitle("打开已有网页中的官方题目框架").setItems(labels,(dialog,index)->{
            String target=snapshot.get(index);if(WebAddress.allowed(target))web.loadUrl(target);
        }).setNegativeButton("取消",null).show();
    }
    private void copyFeedback(){
        publishLoadLog();
        ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("题页助手网页检测反馈",AppState.feedback()));
        Toast.makeText(this,"已复制。分享前请检查题目文字中的个人信息。",Toast.LENGTH_LONG).show();
    }
    private void clearLogin(){
        stopAnswers("已停止网页答题，准备清除网页登录。");
        generation++;web.stopLoading();CookieManager.getInstance().removeAllCookies(done->{
            if(web==null)return;CookieManager.getInstance().flush();WebStorage.getInstance().deleteAllData();web.clearCache(true);web.clearFormData();web.clearHistory();web.loadUrl(WebAddress.START);
            Toast.makeText(this,"已清除助手内的网页登录状态；保存的 API Key 保留。",Toast.LENGTH_LONG).show();
        });
    }
    @Override protected void onResume(){super.onResume();foreground=true;if(web!=null)web.onResume();}
    @Override protected void onPause(){stopAnswers("题页已离开前台，已停止网页答题；已得到的答案保留。");foreground=false;generation++;if(web!=null)web.onPause();super.onPause();}
    @Override protected void onDestroy(){
        if(answers!=null)answers.close();
        if(web!=null){web.stopLoading();web.clearCache(true);((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy();web=null;}super.onDestroy();
    }
    @Override public void onBackPressed(){finish();}
}
