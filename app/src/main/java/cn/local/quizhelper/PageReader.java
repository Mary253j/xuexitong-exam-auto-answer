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
        CharSequence value = node.getText();
        if (value == null || value.toString().trim().isEmpty()) value = node.getContentDescription();
        return value == null ? "" : value.toString().trim();
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
                    if (!targetPackage(n)) continue;
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
                    if (!targetPackage(n)) return null;
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
                if (!targetPackage(n)) break;
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
    Page read() {
        if (!unlocked()) return null;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return null;
        Deque<Entry> queue = new ArrayDeque<>();
        try {
            // Do not inspect child nodes, text, or descriptions from another app.
            if (!targetPackage(root)) return null;
            Page page = new Page(); page.windowId = root.getWindowId();
            DisplayMetrics dims = dimensions();
            queue.push(new Entry(root, new int[0], new ArrayList<>())); root = null;
            int count = 0;
            while (!queue.isEmpty()) {
                if (++count > 2500) throw new IllegalArgumentException("页面节点过多，请切到单题页面。");
                Entry e = queue.pop();
                AccessibilityNodeInfo n = e.node;
                try {
                    if (!targetPackage(n)) continue;
                    Rect box = bounds(n);
                    Core.Container container = new Core.Container();
                    container.path = e.path; container.left = box.left; container.top = box.top;
                    container.right = box.right; container.bottom = box.bottom;
                    container.visible = visible(n, box, dims); container.scrollable = n.isScrollable();
                    List<Core.Container> ancestry = new ArrayList<>(e.ancestors); ancestry.add(container);
                    List<Entry> children = new ArrayList<>(); List<String> childText = new ArrayList<>();
                    for (int i = 0; i < n.getChildCount(); i++) {
                        AccessibilityNodeInfo child = n.getChild(i);
                        if (child == null) continue;
                        int[] path = Arrays.copyOf(e.path, e.path.length + 1); path[path.length - 1] = i;
                        if (targetPackage(child)) childText.add(text(child));
                        children.add(new Entry(child, path, ancestry));
                    }
                    for (int i = children.size() - 1; i >= 0; i--) queue.push(children.get(i));
                    String value = text(n);
                    if(n.isEditable() && value.isEmpty())value=n.getHintText()==null?"请输入答案":n.getHintText().toString();
                    if (n.isEditable() || NodeTextPolicy.retain(value, childText)) {
                        if (value.length() > 12000) throw new IllegalArgumentException("页面文字没有拆分为独立节点。");
                        Core.Record record = new Core.Record(value, visible(n, box, dims), target(n, e.path, dims));
                        record.sourcePath = e.path; record.ancestors = ancestry;
                        record.editable=n.isEditable();
                        record.left = box.left; record.top = box.top; record.right = box.right; record.bottom = box.bottom;
                        page.records.add(record);
                    }
                } finally { release(n); }
            }
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
            if (!targetPackage(n) || (!expected.gesture && !supportsClick(n)) || !n.isEnabled()) return false;
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
