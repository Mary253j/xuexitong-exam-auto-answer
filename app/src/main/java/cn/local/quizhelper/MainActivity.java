package cn.local.quizhelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.CheckBox;

public final class MainActivity extends Activity implements AppState.Listener {
    private static final int BLUE = 0xff265ddb, INK = 0xff182b4d, GRAY = 0xff54647c;
    private EditText key, model;
    private TextView status, permission, diagnostic, recognized, answer, issue, credential;
    private SharedPreferences preferences;
    private KeyVault vault;
    private volatile QuizService.Run apiTest;
    private RadioGroup pageMode;
    private CheckBox blankMultiple;
    private static final int SINGLE_MODE=1001,MULTI_MODE=1002;
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(3), 1); return view;
    }
    private GradientDrawable rounded(int color) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(16)); return shape;
    }
    private void add(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(gap); parent.addView(view, params);
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(16), dp(18), dp(18));
        box.setBackground(rounded(Color.WHITE)); add(parent, box, 16); return box;
    }
    private void button(LinearLayout parent, String text, boolean primary, View.OnClickListener action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextSize(16);
        button.setTextColor(primary ? Color.WHITE : BLUE); button.setBackground(rounded(primary ? BLUE : 0xffeaf0ff));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48)); params.topMargin = dp(10);
        parent.addView(button, params); button.setOnClickListener(action);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Keep the API key out of recent-app previews and screenshots of this configuration UI.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        vault = new KeyVault(this);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setFitsSystemWindows(true); scroll.setBackgroundColor(0xfff4f7fc);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(content);
        TextView title = label("题页助手", 30, INK); title.setTypeface(null, Typeface.BOLD); add(content, title, 0);
        add(content, label("学习通页面读取与选择 · 独立 Android 版", 14, GRAY), 6);

        LinearLayout scope = card(content);
        add(scope, label("先了解权限", 18, INK), 0);
        add(scope, label("需要无障碍权限读取题目并点击选项。只在开始后处理学习通页面；停止后不读取页面。", 14, GRAY), 6);
        button(scope, "查看权限与数据说明", false, v -> showPrivacy());
        permission = label("", 13, GRAY); add(scope, permission, 10);
        button(scope, "打开无障碍设置", false, v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        LinearLayout settings = card(content);
        add(settings, label("硅基流动配置", 18, INK), 0);
        add(settings, label("API Key · 加密保存在这台手机", 13, GRAY), 12);
        key = new EditText(this); key.setSingleLine(); key.setTextSize(15); key.setHint("填写一次并保存，切回或重启都能使用");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSaveEnabled(false); key.setFreezesText(false);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); add(settings, key, 0);
        add(settings, label("完整模型名称", 13, GRAY), 8);
        model = new EditText(this); model.setSingleLine(); model.setTextSize(15); model.setHint("从硅基流动控制台复制模型 ID");
        model.setText(preferences.getString("model", "")); add(settings, model, 0);
        credential = label("尚未保存 Key。",13,GRAY); add(settings,credential,6);
        button(settings,"保存 Key 和模型配置",false,v -> saveConfig(true));
        button(settings,"测试 API 连接",false,v -> testApi());
        button(settings,"删除已保存的 Key",false,v -> {
            if(QuizService.instance!=null)QuizService.instance.stopRun("已停止。");
            cancelApiTest();
            try { vault.clear(); key.setText(""); credential.setText("已删除本机保存的 Key。"); AppState.publish("已停止并删除保存的 Key。"); }
            catch(Exception error){AppState.issue("删除 Key 未完成，请重试或清除应用数据。");}
        });
        add(settings, label("DeepSeek 提供模型解答，当前没有联网搜题，答案可能有误。", 13, GRAY), 6);

        LinearLayout controls = card(content);
        add(controls, label("运行", 18, INK), 0);
        pageMode=new RadioGroup(this);
        RadioButton single=new RadioButton(this);single.setId(SINGLE_MODE);single.setText("单题模式：每次只处理一道完整题");
        RadioButton multiple=new RadioButton(this);multiple.setId(MULTI_MODE);multiple.setText("多题模式：依次处理当前页完整题目");
        pageMode.addView(single);pageMode.addView(multiple);pageMode.check(preferences.getBoolean("multiple_questions",false)?MULTI_MODE:SINGLE_MODE);
        add(controls,pageMode,6);
        blankMultiple=new CheckBox(this);blankMultiple.setText("多选状态读不到时，按空白题选择一次");
        blankMultiple.setChecked(preferences.getBoolean("blank_multiple",false));add(controls,blankMultiple,8);
        add(controls,label("开启前请先取消题目已有的手动勾选。程序会保留点击记录，避免再次点击把答案取消。新一轮重测前可清空勾选并重置记录。",13,GRAY),6);
        button(controls,"重置多选点击记录",false,v->{
            if(QuizService.instance!=null)QuizService.instance.stopRun("已停止，准备重置多选记录。");
            try{SelectionJournal.open(this).clear();AppState.publish("多选记录已重置。请先清空学习通中的已有勾选，再重新开始。");}
            catch(IllegalArgumentException error){AppState.issue(error.getMessage());}
        });
        button(controls, "只检测题目 · 不调用 API", false, v -> launch(true));
        add(controls,label("检测会分三次读取同一道题，请保持页面约 20 秒，再复制问题反馈。此版用于排查旧节点缓存，尚未保证修复单题页面。",13,GRAY),8);
        button(controls, "开始自动答题", true, v -> launch(false));
        button(controls, "停止答题 · 保留保存的 Key", false, v -> {
            if (QuizService.instance != null) QuizService.instance.stopRun("已停止答题，保存的 Key 可以继续使用。");
            cancelApiTest(); changed();
        });
        button(controls, "停止并关闭无障碍服务", false, v -> {
            QuizService service = QuizService.instance;
            if (service != null) { service.stopRun("已停止，正在关闭无障碍服务。"); service.disableSelf(); }
            changed();
        });
        add(controls, label("运行时保持亮屏、解锁，学习通题目页面在前台。每 5 秒处理完整题目；无原生点击操作时会轻点已核对的选项位置。处理后可手动向下滚动。", 13, GRAY), 12);

        LinearLayout webpage=card(content);
        add(webpage,label("不截屏的网页答题",18,INK),0);
        add(webpage,label("在助手内打开官方网页并进入题目。可先检测文字，再用已保存的 API 配置逐题答题；支持指定单题或处理本页多题，每 5 秒开始处理一道。网页模式不需要无障碍，保持题页前台；完成后自行核对并提交。",13,GRAY),8);
        button(webpage,"打开官方网页答题",false,v->{
            if(QuizService.instance!=null)QuizService.instance.stopRun("已停止 APP 模式，准备网页文字检测。");
            cancelApiTest();startActivity(new Intent(this,WebActivity.class));
        });

        LinearLayout output = card(content);
        add(output, label("当前状态", 18, INK), 0); status = label("", 15, BLUE); add(output, status, 8);
        add(output,label("识别到的题目",16,INK),16);
        recognized=label("",14,GRAY); recognized.setTextIsSelectable(true); add(output,recognized,8);
        add(output,label("模型答案",16,INK),16);
        answer=label("",15,BLUE); answer.setTextIsSelectable(true); add(output,answer,8);
        button(output,"复制最新问答答案",false,v->{
            String text=AppState.latestTextAnswer;
            if(text.isEmpty()){Toast.makeText(this,"还没有生成问答题答案。",Toast.LENGTH_SHORT).show();return;}
            ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("问答题参考答案",text));
            Toast.makeText(this,"已复制参考答案，请核对后粘贴到学习通。",Toast.LENGTH_LONG).show();
        });
        add(output,label("最近一次未完成的原因",16,INK),16);
        issue=label("",14,GRAY); issue.setTextIsSelectable(true); add(output,issue,8);
        button(output,"复制问题反馈",false,v -> {
            String report=AppState.feedback(); String secret=key.getText().toString().trim();
            if(secret.length()>5)report=report.replace(secret,"[Key 已隐藏]");
            report=report.replaceAll("(?i)Bearer\\s+[^\\s]+","Bearer [隐藏]");
            ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("题页助手问题反馈",report));
            Toast.makeText(this,"已复制。分享前请检查页面文字中的个人信息。",Toast.LENGTH_LONG).show();
        });
        button(output,"展开 / 收起详细检测信息",false,v -> diagnostic.setVisibility(diagnostic.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        diagnostic = label("", 12, GRAY); diagnostic.setTextIsSelectable(true); add(output, diagnostic, 8); diagnostic.setVisibility(View.GONE);
        add(content, label("不依赖 AutoX。无广告与统计 SDK，不请求存储、通讯录、相机、麦克风、悬浮窗、截屏或录屏权限。", 12, GRAY), 20);
        setContentView(scroll);
        try {
            String stored=vault.load(); key.setText(stored);
            if(!stored.isEmpty())credential.setText("已从本机加密存储恢复 Key；返回或重启无需重填。");
        }catch(Exception error){credential.setText("已保存的 Key 暂时无法解密，请重新输入并保存。");}
        changed();
    }
    @Override public void onStart() { super.onStart(); AppState.add(this); changed(); }
    @Override public void onStop() { AppState.remove(this); super.onStop(); }
    @Override public void onResume() { super.onResume(); changed(); }
    @Override public void onDestroy(){cancelApiTest();super.onDestroy();}
    @Override public void changed() {
        if (status == null) return;
        status.setText(AppState.status); diagnostic.setText(AppState.diagnostic);
        recognized.setText(AppState.question);answer.setText(AppState.answer);issue.setText(AppState.lastIssue);
        permission.setText(QuizService.instance == null ? "无障碍尚未连接，请在设置中手动启用“题页助手”。" : "无障碍已连接；可随时在系统设置中关闭。");
    }
    private void launch(boolean diagnosticOnly) {
        QuizService service = QuizService.instance;
        if (service == null) { Toast.makeText(this, "请先开启题页助手的无障碍服务。", Toast.LENGTH_LONG).show(); return; }
        Intent intent = getPackageManager().getLaunchIntentForPackage(PageReader.TARGET);
        if (intent == null) { Toast.makeText(this, "未找到学习通，请先安装。", Toast.LENGTH_LONG).show(); return; }
        String secret = key.getText().toString().trim(), modelId = model.getText().toString().trim();
        if (!diagnosticOnly && (secret.isEmpty() || modelId.isEmpty() || secret.contains("\n") || modelId.contains("\n") || secret.contains("\r") || modelId.contains("\r"))) {
            Toast.makeText(this, "请填写 API Key 和完整模型名称。", Toast.LENGTH_LONG).show(); return;
        }
        if (!diagnosticOnly && !saveConfig(false)) return;
        cancelApiTest();
        preferences.edit().putBoolean("multiple_questions",pageMode.getCheckedRadioButtonId()==MULTI_MODE).putBoolean("blank_multiple",blankMultiple.isChecked()).apply();
        service.begin(diagnosticOnly ? "" : secret, modelId, diagnosticOnly,pageMode.getCheckedRadioButtonId()==MULTI_MODE,blankMultiple.isChecked());
        try { moveTaskToBack(true); startActivity(intent); }
        catch (Exception error) { service.stopRun("无法打开学习通，已停止。"); }
    }
    private boolean saveConfig(boolean showToast) {
        String secret=key.getText().toString().trim(), modelId=model.getText().toString().trim();
        if(secret.isEmpty() || secret.contains("\n") || secret.contains("\r") || modelId.contains("\n") || modelId.contains("\r")){
            Toast.makeText(this,"请填写有效的 API Key。",Toast.LENGTH_LONG).show();return false;
        }
        try {
            vault.save(secret); preferences.edit().putString("model",modelId).putBoolean("multiple_questions",pageMode.getCheckedRadioButtonId()==MULTI_MODE).putBoolean("blank_multiple",blankMultiple.isChecked()).apply();
            credential.setText("Key 已加密保存在本机；切回、重启或停止后仍可使用。");
            if(showToast)Toast.makeText(this,"配置已保存。",Toast.LENGTH_SHORT).show();return true;
        }catch(Exception error){AppState.issue("本机加密保存失败，Key 没有保存；请重试。不会改为明文存储。");return false;}
    }
    private void cancelApiTest() {
        QuizService.Run current=apiTest;apiTest=null;
        if(current!=null){current.active.set(false);current.key="";
            javax.net.ssl.HttpsURLConnection connection=current.connection;
            if(connection!=null)new Thread(connection::disconnect,"cancel-api-test").start();}
    }
    private void testApi() {
        if(model.getText().toString().trim().isEmpty()){Toast.makeText(this,"请填写完整模型名称。",Toast.LENGTH_LONG).show();return;}
        if(!saveConfig(false))return;
        cancelApiTest();QuizService.Run test=new QuizService.Run(key.getText().toString().trim(),model.getText().toString().trim());apiTest=test;
        AppState.publish("正在向模型发送一条简短测试请求…");
        new Thread(()->{
            try{
                java.util.List<Core.Record> rows=new java.util.ArrayList<>();
                for(String value:new String[]{"单选题","1+1 等于几？","A. 2","B. 3","提交"})rows.add(new Core.Record(value,true,null));
                java.util.List<String> labels=ModelClient.call(test,Core.parse(rows));
                if(test.active.get())AppState.publish(labels==null?"API 已连接，但模型放弃回答测试题。":"API 连接和答案格式正常。可以开始自动答题。");
            }catch(Exception error){if(test.active.get())AppState.issue(error instanceof ModelClient.ApiException?error.getMessage():"API 已返回，但答案格式不符合要求。请检查模型或复制问题反馈。");}
            finally{test.active.set(false);test.key="";}
        },"test-model-connection").start();
    }
    private void showPrivacy() {
        new AlertDialog.Builder(this).setTitle("权限与数据说明").setMessage(
            "1. 无障碍是敏感权限：系统允许读取屏幕内容、操作界面。服务事件筛选为学习通，代码在读取节点文字前检查包名。请审查源码后决定是否授权，这不等于权限被系统硬隔离。\n\n" +
            "2. 只请求 INTERNET 普通权限，并通过受系统保护的无障碍服务声明绑定权限。不请求截屏、录屏、分屏、悬浮窗、存储、相机、麦克风、通讯录或设备管理员权限。\n\n" +
            "3. 模型请求仅向 https://api.siliconflow.cn/v1/chat/completions 发送解析后的题干、题型和选项。使用系统证书校验，不跟随重定向。网页模式另加载超星官方 HTTPS 页面、资源和网站脚本；导航与常规资源请求按域名过滤。这些是代码约束，并非系统网络硬隔离。\n\n" +
            "4. Key 使用 Android Keystore 的 AES-GCM 加密后保存到本机应用私有数据中，返回或重启会恢复。停止不会删除保存的 Key，可通过删除按钮清除。检测文字仅在内存展示；只有你点复制才会写入剪贴板。\n\n" +
            "5. 无广告 SDK、统计 SDK、自动更新或下载执行的原生程序。网页模式运行官方页面自带的 JavaScript；助手的脚本随安装包提供，没有 JavaScript 原生接口，不向网页注入 API Key。仅在主动检测或开始网页答题后读取题目区域，不读取登录表单。\n\n" +
            "6. APP 模式可通过无障碍手势轻点已核对的选项；多选空白题模式须先取消已有勾选。程序只保存题目哈希、答案字母及点击状态，不保存题干到磁盘。问答题只生成参考答案，由你核对后粘贴，不自动填写或提交。\n\n" +
            "7. 网页“检测文字”不调用模型或操作选项；“开始网页答题”使用本机保存的 API 配置，逐题请求模型，核对题目与选中状态后选择。Key 留在 Android 端，不放入网页。未知选中状态仅保留答案供手动核对；问答题只给参考答案，不自动填写。程序不点击保存、提交或交卷；网站自身可能保存选项变化。返回、跳转或停止会终止本轮操作。\n\n" +
            "8. 网页模式不采集屏幕图像。Cookie 和站点存储由本机 WebView 保存，可点“清除网页登录”删除，不影响 API Key。网页答题在助手内打开的页面中运行，不能读取其他浏览器的内部网页。"
        ).setPositiveButton("知道了", null).show();
    }
}
