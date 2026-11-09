package cn.local.quizhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Path;
import android.os.PowerManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** All traversal and actions are gated to the one supported package. */
@SuppressWarnings("deprecation")
final class PageReader {
    static final String TARGET = "com.chaoxing.mobile";
    static final class Page {
        int windowId;
        boolean complete=true;
        String rawAudit="";
        final List<Core.Record> records = new ArrayList<>();
    }
    private static final class Entry {
        final AccessibilityNodeInfo node;
        final int[] path;
        final List<Core.Container> ancestors;
        Entry(AccessibilityNodeInfo n, int[] p, List<Core.Container> a) { node = n; path = p; ancestors = a; }
    }
    private final AccessibilityService service;
    PageReader(AccessibilityService service) { this.service = service; }
    boolean unlocked() {
        PowerManager power = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) service.getSystemService(Context.KEYGUARD_SERVICE);
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
    }
    private DisplayMetrics dimensions() {
        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) service.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }
    private static String text(AccessibilityNodeInfo node) {
        CharSequence value=node.getText(),description=node.getContentDescription();
        return AccessibleText.combine(value==null?"":value.toString(),description==null?"":description.toString());
    }
    private static boolean descendant(AccessibilityNodeInfo node,int window) {
        return node!=null && AccessibleText.sameWindowPackage(node.getPackageName()==null?null:node.getPackageName().toString(),node.getWindowId(),window);
    }
    private static boolean targetPackage(AccessibilityNodeInfo node) {
        return node != null && TARGET.contentEquals(node.getPackageName() == null ? "" : node.getPackageName());
    }
    private static Rect bounds(AccessibilityNodeInfo node) {
        Rect rect = new Rect(); node.getBoundsInScreen(rect); return rect;
    }
    private static boolean visible(AccessibilityNodeInfo node, Rect r, DisplayMetrics d) {
        return node.isVisibleToUser() && !r.isEmpty() && r.left >= 0 && r.top >= 0 && r.right <= d.widthPixels && r.bottom <= d.heightPixels;
    }
    private static void release(AccessibilityNodeInfo n) { if (n != null) n.recycle(); }
    private static boolean supportsClick(AccessibilityNodeInfo node) {
        if (node.isClickable()) return true;
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getId() == AccessibilityNodeInfo.ACTION_CLICK) return true;
        }
        return false;
    }

    private static Boolean selection(AccessibilityNodeInfo node) {
        Boolean labelled=Core.labelState(node.getContentDescription()==null?"":node.getContentDescription().toString());
        if(labelled==null)labelled=Core.labelState(text(node));
        if(labelled!=null)return labelled;
        if(Build.VERSION.SDK_INT>=30){
            CharSequence raw=node.getStateDescription();String state=raw==null?"":raw.toString().trim();
            if(state.matches("(?i)^(未选中|未勾选|未选择|unchecked|not selected)$"))return false;
            if(state.matches("(?i)^(已选中|已勾选|已选择|checked|selected)$"))return true;
        }
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.push(AccessibilityNodeInfo.obtain(node));
        int count = 0, checks = 0; boolean checked = false;
        try {
            while (!queue.isEmpty() && count++ < 80) {
                AccessibilityNodeInfo n = queue.pop();
                try {
                    if (!descendant(n,node.getWindowId())) continue;
                    if (n.isCheckable()) { checks++; checked = n.isChecked(); }
                    for (int i = 0; i < n.getChildCount(); i++) {
                        AccessibilityNodeInfo child = n.getChild(i);
                        if (child != null) queue.push(child);
                    }
                } finally { release(n); }
            }
        } finally { while (!queue.isEmpty()) release(queue.pop()); }
        if (checks == 1) return checked;
        // Generic view selection may represent focus rather than a checked answer.
        return null;
    }
    private static String signature(AccessibilityNodeInfo node) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.push(AccessibilityNodeInfo.obtain(node));
        StringBuilder result = new StringBuilder();
        int count = 0;
        try {
            while (!queue.isEmpty()) {
                if (++count > 80 || result.length() > 20000) return null;
                AccessibilityNodeInfo n = queue.pop();
                try {
                    if (!descendant(n,node.getWindowId())) return null;
                    String value = text(n);
                    result.append(value.length()).append(':').append(value);
                    for (int i = n.getChildCount() - 1; i >= 0; i--) {
                        AccessibilityNodeInfo child = n.getChild(i);
                        if (child != null) queue.push(child);
                    }
                } finally { release(n); }
            }
            return result.toString();
        } finally { while (!queue.isEmpty()) release(queue.pop()); }
    }
    private static Core.Target target(AccessibilityNodeInfo original, int[] sourcePath, DisplayMetrics dims) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(original);
        int[] path = sourcePath.clone();
        Boolean rowSelection=null;
        try {
            for (int depth = 0; n != null && depth < 6; depth++) {
                if (!descendant(n,original.getWindowId())) break;
                Rect r = bounds(n);
                if(depth<=2 && rowSelection==null && visible(n,r,dims) && r.height()<=dims.heightPixels*0.35)rowSelection=selection(n);
                if (supportsClick(n) && n.isEnabled() && visible(n, r, dims) && r.height() <= dims.heightPixels * 0.4) {
                    String sig = signature(n);
                    if (sig == null) return null;
                    Core.Target t = new Core.Target();
                    t.path = path; t.left = r.left; t.top = r.top; t.right = r.right; t.bottom = r.bottom;
                    t.checked = rowSelection!=null?rowSelection:selection(n); t.signature = sig;
                    return t;
                }
                if (path.length == 0) break;
                AccessibilityNodeInfo parent = n.getParent(); release(n); n = parent;
                path = Arrays.copyOf(path, path.length - 1);
            }
            // Some WebViews expose text and geometry but no ACTION_CLICK handler.
            // The option parser will only use this fallback for matched option nodes.
            Rect r = bounds(original);
            if (original.isEnabled() && visible(original,r,dims)) {
                String sig = signature(original);
                if (sig == null) return null;
                Core.Target t = new Core.Target(); t.path = sourcePath.clone(); t.gesture = true;
                t.left=r.left; t.top=r.top; t.right=r.right; t.bottom=r.bottom;
                t.signature=sig; t.checked=rowSelection!=null?rowSelection:selection(original); return t;
            }
            return null;
        } finally { release(n); }
    }
    private String auditEnvironment() {
        String version="未取得";
        try {
            android.content.pm.PackageInfo info=service.getPackageManager().getPackageInfo(TARGET,0);
            version=info.versionName+" ("+(Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode)+")";
        }catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
        android.accessibilityservice.AccessibilityServiceInfo info=service.getServiceInfo();
        return "Android "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+"\n学习通 "+version+
            "\n无障碍配置 flags="+(info==null?"未取得":info.flags)+"，isAccessibilityTool="+
            (Build.VERSION.SDK_INT>=31 && info!=null?info.isAccessibilityTool():"系统不支持查询")+"\n";
    }
    private static String chars(CharSequence value){return value==null?"":value.toString();}
    private static ReadAudit.Node auditNode(AccessibilityNodeInfo n,int[] path,Rect box,boolean visible,
            String raw,String description) {
        ReadAudit.Node entry=new ReadAudit.Node();entry.path=path;entry.left=box.left;entry.top=box.top;
        entry.right=box.right;entry.bottom=box.bottom;entry.window=n.getWindowId();
        entry.packageName=chars(n.getPackageName());entry.kind=chars(n.getClassName());entry.viewId=n.getViewIdResourceName();
        entry.visible=visible;entry.decision="节点读取未完成";entry.password=n.isPassword();
        if(!entry.password){
            entry.raw=raw;entry.description=description;entry.hint=chars(n.getHintText());
            if(Build.VERSION.SDK_INT>=30)entry.state=chars(n.getStateDescription());
        }
        if(Build.VERSION.SDK_INT>=34)entry.sensitive=n.isAccessibilityDataSensitive();
        List<String> keys=n.getAvailableExtraData();if(keys!=null && !keys.isEmpty())entry.extraKeys=keys.toString();
        return entry;
    }
    Page read() {return read(false);}
    Page read(boolean diagnostic) {return read(diagnostic,false);}
    Page readFresh() {
        FreshReadControl.Outcome<Page> result=FreshReadControl.run(new FreshReadControl.Cache(){
            public boolean supported(){return Build.VERSION.SDK_INT>=33;}
            public boolean enabled(){return service.isCacheEnabled();}
            public boolean enable(boolean value){return service.setCacheEnabled(value);}
            public boolean clear(){return service.clearCache();}
        },()->read(true,true));
        if(result.value!=null)result.value.rawAudit=result.log+"\n"+result.value.rawAudit;
        return result.value;
    }
    private Page read(boolean diagnostic,boolean fresh) {
        if (!unlocked()) return null;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return null;
        Deque<Entry> queue = new ArrayDeque<>();
        try {
            // Do not inspect child nodes, text, or descriptions from another app.
            if (!targetPackage(root)) return null;
            Page page = new Page(); page.windowId = root.getWindowId();
            ReadAudit audit=diagnostic?new ReadAudit():null;
            DisplayMetrics dims = dimensions();
            queue.push(new Entry(root, new int[0], new ArrayList<>())); root = null;
            int count = 0;
            while (!queue.isEmpty()) {
                if (++count > 2500) {
                    if(audit==null)throw new IllegalArgumentException("页面节点过多，请切到单题页面。");
                    audit.traversalLimit();break;
                }
                Entry e = queue.pop();
                AccessibilityNodeInfo n = e.node;
                ReadAudit.Node auditEntry=null;
                try {
                    if (!descendant(n,page.windowId)) {
                        if(audit!=null)audit.rejected(e.path,chars(n.getPackageName()),n.getWindowId(),page.windowId);
                        continue;
                    }
                    if(fresh){
                        boolean refreshed=n.refresh();audit.refreshed(e.path,refreshed);
                        if(!refreshed)continue;
                        if(!descendant(n,page.windowId)){
                            audit.rejected(e.path,chars(n.getPackageName()),n.getWindowId(),page.windowId);continue;
                        }
                    }
                    Rect box = bounds(n);
                    boolean isVisible=visible(n,box,dims);
                    String raw=chars(n.getText()),description=chars(n.getContentDescription());
                    if(audit!=null)auditEntry=auditNode(n,e.path,box,isVisible,raw,description);
                    Core.Container container = new Core.Container();
                    container.path = e.path; container.left = box.left; container.top = box.top;
                    container.right = box.right; container.bottom = box.bottom;
                    container.visible = visible(n, box, dims); container.scrollable = n.isScrollable();
                    List<Core.Container> ancestry = new ArrayList<>(e.ancestors); ancestry.add(container);
                    List<String> childText = new ArrayList<>();
                    int childCount=n.getChildCount();
                    int returnedChildren=0;
                    if(auditEntry!=null)auditEntry.children=childCount;
                    for (int i = childCount-1; i >= 0; i--) {
                        int[] path = Arrays.copyOf(e.path, e.path.length + 1); path[path.length - 1] = i;
                        AccessibilityNodeInfo child = n.getChild(i);
                        if (child == null) {if(audit!=null)audit.missing(path);continue;}
                        queue.push(new Entry(child,path,ancestry));returnedChildren++;
                        if(auditEntry!=null)auditEntry.returnedChildren=returnedChildren;
                        if (descendant(child,page.windowId)) childText.add(0,text(child));
                    }
                    String value = AccessibleText.combine(raw,description);
                    if(n.isEditable() && value.isEmpty())value=n.getHintText()==null?"请输入答案":n.getHintText().toString();
                    boolean retained=n.isEditable() || NodeTextPolicy.retain(value, childText) || (returnedChildren==0 && isVisible);
                    if(auditEntry!=null){
                        auditEntry.retained=retained;
                        auditEntry.decision=retained?"进入现有识别记录":Core.clean(value).isEmpty()?"空文字容器或不可见空节点":"现有文字去重规则";
                    }
                    if (retained) {
                        if (value.length() > 12000) throw new IllegalArgumentException("页面文字没有拆分为独立节点。");
                        Core.Record record = new Core.Record(value, visible(n, box, dims), target(n, e.path, dims));
                        record.sourcePath = e.path; record.ancestors = ancestry;
                        record.editable=n.isEditable();
                        record.rawDescription=n.getContentDescription()==null?"":n.getContentDescription().toString();
                        record.nodeClass=n.getClassName()==null?"":n.getClassName().toString();
                        record.left = box.left; record.top = box.top; record.right = box.right; record.bottom = box.bottom;
                        page.records.add(record);
                    }
                } catch(RuntimeException error) {
                    if(audit==null)throw error;
                    audit.error(e.path,error.getClass().getSimpleName());
                } finally { if(auditEntry!=null)audit.node(auditEntry);release(n); }
            }
            if(audit!=null){page.complete=audit.complete();page.rawAudit=audit.report(auditEnvironment());}
            return unlocked() ? page : null;
        } finally {
            release(root); while (!queue.isEmpty()) release(queue.pop().node);
        }
    }
    boolean click(int windowId, Core.Target expected) {
        if (!unlocked()) return false;
        AccessibilityNodeInfo n = service.getRootInActiveWindow();
        try {
            if (!targetPackage(n) || n.getWindowId() != windowId) return false;
            for (int index : expected.path) {
                AccessibilityNodeInfo child = n.getChild(index); release(n); n = child;
                if (n == null) return false;
            }
            if (!descendant(n,windowId) || (!expected.gesture && !supportsClick(n)) || !n.isEnabled()) return false;
            Rect r = bounds(n);
            if (!r.equals(new Rect(expected.left, expected.top, expected.right, expected.bottom))) return false;
            if (!visible(n, r, dimensions()) || !expected.signature.equals(signature(n))) return false;
            if (expected.gesture) {
                Path path = new Path(); path.moveTo(r.exactCenterX(),r.exactCenterY());
                GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path,0,60)).build();
                if (!service.dispatchGesture(gesture,null,null)) {
                    throw new IllegalArgumentException("系统未接受选项轻点。请关闭再开启题页助手的无障碍服务后重试。");
                }
                return true;
            }
            return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } finally { release(n); }
    }
}
