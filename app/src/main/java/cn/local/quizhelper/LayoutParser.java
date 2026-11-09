package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses accessibility geometry and ancestry, never pixels or fixed screen coordinates. */
final class LayoutParser {
    private static final Pattern TYPE = Pattern.compile("^(?:第\\s*\\d+\\s*题\\s*|\\d+\\s*[.．、)）]\\s*)?(?:[【\\[（(]\\s*(单选题|多选题|判断题|简答题|问答题|论述题)\\s*[】\\]）)]|(单选题|多选题|判断题|简答题|问答题|论述题)(?=\\s|[（(，,:：]|$))");
    private static final Pattern HEADER = Pattern.compile("^([A-HＡ-Ｈ])(?:[.．、:：)）]\\s*|\\s+)(.*)$");
    private static final Pattern LETTER = Pattern.compile("^([A-HＡ-Ｈ])$");
    private static final Pattern FOOTER = Pattern.compile("^(上一题|下一题|上题|下题|提交|提交答案|提交作业|提交试卷|交卷|答题卡|保存|暂存|返回|收藏|纠错|解析|答案解析|参考答案|我的答案|正确答案|教师评语)(?:\\s|[：:]|$)");
    private static final Pattern META = Pattern.compile("^(?:\\d+(?:\\.\\d+)?\\s*分|共\\s*\\d+\\s*题|\\d+\\s*/\\s*\\d+|剩余时间.*|[（(]\\d+(?:\\.\\d+)?\\s*分[）)]|已选中|未选中|已选择|未选择|已勾选|未勾选)$");
    private static final Comparator<Core.Record> ORDER = Comparator.comparingInt((Core.Record r) -> r.top).thenComparingInt(r -> r.left);
    private static final class Marker {
        Core.Record record;
        String type, suffix;
    }
    private static final class Anchor {
        Core.Record record;
        String label, content;
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
    private static boolean geometry(Core.Record r) { return r.sourcePath != null && r.right > r.left && r.bottom > r.top; }
    private static boolean under(int[] path, int[] prefix) {
        if (path == null || prefix == null || path.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (path[i] != prefix[i]) return false;
        return true;
    }
    private static int[] common(int[] a, int[] b) {
        int n = 0; while (n < a.length && n < b.length && a[n] == b[n]) n++;
        return Arrays.copyOf(a, n);
    }
    private static boolean metadata(String text) { return META.matcher(Core.clean(text)).matches(); }
    private static Marker marker(Core.Record r) {
        Matcher match = TYPE.matcher(Core.clean(r.text));
        if (!match.find()) return null;
        Marker result = new Marker(); result.record = r;
        String kind = match.group(1) == null ? match.group(2) : match.group(1);
        result.type = kind.equals("多选题") ? "multiple" : kind.equals("判断题") ? "boolean" : kind.equals("单选题") ? "single" : "text";
        result.suffix = Core.clean(Core.clean(r.text).substring(match.end())).replaceFirst("^[（(]\\d+(?:\\.\\d+)?\\s*分[）)]\\s*", "");
        return result;
    }
    private static Anchor anchor(Core.Record r, boolean bool) {
        String text = Core.cleanLabel(r.text); String label = null, content = "";
        Matcher match = HEADER.matcher(text);
        if (match.matches()) { label = match.group(1); content = Core.clean(match.group(2)); }
        else {
            Matcher letter = LETTER.matcher(text);
            if (letter.matches()) label = letter.group(1);
        }
        if (label == null && bool && text.matches("^(正确|错误|对|错|是|否|√|×)$")) { label = "?"; content = text; }
        if (label == null) return null;
        if (!label.equals("?")) {
            char c = label.charAt(0); label = String.valueOf((char) (c > 255 ? c - 65248 : c));
        }
        Anchor result = new Anchor(); result.record = r; result.label = label; result.content = content; return result;
    }
    private static Core.Container container(Core.Record r, int[] path) {
        if (r.ancestors != null) for (Core.Container c : r.ancestors) if (Arrays.equals(c.path, path)) return c;
        return null;
    }
    private static int[] optionPrefix(List<Anchor> anchors) {
        int[] prefix = anchors.get(0).record.sourcePath;
        for (Anchor a : anchors) prefix = common(prefix, a.record.sourcePath);
        return prefix;
    }
    private static boolean column(Core.Record a, Core.Record b) {
        return Math.abs(a.left - b.left) <= Math.max(a.bottom-a.top, b.bottom-b.top) * 0.75;
    }
    static Core.Question parse(List<Core.Record> records) {
        List<Core.Question> questions = parseAll(records);
        if (questions.size() > 1) throw bad("读到多个可能的题目区域，暂不自动选择。");
        return questions.get(0);
    }
    static List<Core.Question> parseAll(List<Core.Record> records) {
        List<Marker> markers = new ArrayList<>();
        for (Core.Record r : records) {
            if (!geometry(r)) continue;
            Marker m = marker(r); if (m != null && r.visible) markers.add(m);
        }
        if (markers.isEmpty()) throw bad("没有读到可见的题型标记，例如 1.[单选题]。");
        List<Core.Question> candidates = new ArrayList<>();
        String lastFailure = "未找到独立的题目与选项区域。";
        for (Marker m : markers) {
            if (m.type.equals("text")) {
                try { candidates.add(textCandidate(records,markers,m)); }
                catch (IllegalArgumentException error) { lastFailure=error.getMessage(); }
                continue;
            }
            for (Core.Record r : records) {
                if (!geometry(r) || !r.visible || r.top < m.record.bottom) continue;
                Anchor first = anchor(r, m.type.equals("boolean"));
                if (first == null || !(first.label.equals("A") || first.label.equals("?"))) continue;
                if (Math.abs(r.left - m.record.left) > Math.max(m.record.bottom-m.record.top, r.bottom-r.top) * 2.5) continue;
                try {
                    Core.Question q = candidate(records, markers, m, first);
                    boolean duplicate = false;
                    for (Core.Question existing : candidates) {
                        if (existing.canonical.equals(q.canonical) && Arrays.equals(existing.anchor.sourcePath,q.anchor.sourcePath)) duplicate = true;
                    }
                    if (!duplicate) candidates.add(q);
                } catch (IllegalArgumentException error) { lastFailure = error.getMessage(); }
            }
        }
        if (candidates.isEmpty()) throw bad(lastFailure);
        candidates.sort((a,b)->ORDER.compare(a.anchor,b.anchor));
        return candidates;
    }
    private static Core.Question candidate(List<Core.Record> records, List<Marker> markers, Marker marker, Anchor first) {
        Core.Record header = marker.record;
        int[] scope = common(header.sourcePath, first.record.sourcePath);
        int boundary = Integer.MAX_VALUE;
        for (Marker other : markers) {
            if (other != marker && other.record.top > header.top && under(other.record.sourcePath, scope)) boundary = Math.min(boundary, other.record.top);
        }
        List<Anchor> anchors = new ArrayList<>();
        for (Core.Record r : records) {
            if (!geometry(r) || !under(r.sourcePath, scope) || r.top < first.record.top || r.top >= boundary || !column(r, first.record)) continue;
            Anchor a = anchor(r, marker.type.equals("boolean")); if (a != null) anchors.add(a);
        }
        anchors.sort((a,b) -> ORDER.compare(a.record,b.record));
        if (anchors.size() < 2 || anchors.size() > 8) throw bad("没有找到完整、同列的选项组。");
        int[] optionPath = optionPrefix(anchors);
        boolean isolated = optionPath.length > scope.length && !under(header.sourcePath, optionPath);
        int footerTop = Integer.MAX_VALUE;
        for (Core.Record r : records) {
            if (!geometry(r) || !r.visible || r.top < anchors.get(anchors.size()-1).record.bottom || !FOOTER.matcher(Core.clean(r.text)).find()) continue;
            if (isolated && under(r.sourcePath,optionPath)) continue;
            footerTop = Math.min(footerTop,r.top);
        }
        Core.Container optionContainer = container(first.record, optionPath);
        boolean closed = isolated && ((optionContainer != null && optionContainer.visible && !optionContainer.scrollable)
            || (boundary != Integer.MAX_VALUE && anchors.get(anchors.size()-1).record.bottom < boundary));
        Set<Core.Record> used = new HashSet<>();
        Set<Core.Record> allLabels = new HashSet<>();
        for (int i=0;i<anchors.size();i++) {
            Anchor a = anchors.get(i);
            if (!a.label.equals("?") && !a.label.equals(String.valueOf((char)('A'+i)))) throw bad("选项缺失、重复或顺序不连续。");
            if (i>0 && a.record.top <= anchors.get(i-1).record.top) throw bad("选项区域重叠或存在多列，暂不点击。");
            allLabels.add(a.record);
        }
        Core.Question question = new Core.Question(); question.type = marker.type; question.anchor=header;
        List<Core.Record> stem = new ArrayList<>();
        for (Core.Record r : records) {
            if (r==header || r.editable || !geometry(r) || !under(r.sourcePath,scope) || r.top < header.bottom || r.bottom > first.record.top) continue;
            if (metadata(r.text) || FOOTER.matcher(Core.clean(r.text)).find()) continue;
            int tolerance = Math.max(header.bottom-header.top, r.bottom-r.top) * 2;
            if (Math.abs(r.left-header.left)>tolerance) continue;
            if (!r.visible) throw bad("题干没有完整显示。");
            stem.add(r);
        }
        stem.sort(ORDER);
        StringBuilder text = new StringBuilder(metadata(marker.suffix) ? "" : marker.suffix);
        for (Core.Record r : stem) text.append(' ').append(Core.clean(r.text));
        question.stem=Core.clean(text.toString());
        for (int i=0;i<anchors.size();i++) {
            Anchor a=anchors.get(i);
            Core.Option option = new Core.Option(); option.label=String.valueOf((char)('A'+i)); option.text=a.content;
            option.sources.add(a.record); used.add(a.record);
            int[] row = null;
            if (a.record.sourcePath.length > optionPath.length+1) {
                int[] possible=Arrays.copyOf(a.record.sourcePath,optionPath.length+1);
                boolean unique=true;
                for (Anchor other:anchors) if (other!=a && under(other.record.sourcePath,possible)) unique=false;
                if (unique) row=possible;
            }
            List<Core.Record> content=new ArrayList<>();
            for (Core.Record r:records) {
                if (r.editable || !geometry(r) || allLabels.contains(r) || !under(r.sourcePath,isolated?optionPath:scope)) continue;
                boolean belongs=row!=null && under(r.sourcePath,row);
                if (!belongs && row==null) {
                    int overlap=Math.min(r.bottom,a.record.bottom)-Math.max(r.top,a.record.top);
                    belongs=r.left >= a.record.right-(a.record.bottom-a.record.top)/4 && overlap>0;
                }
                if (!belongs || used.contains(r) || metadata(r.text)) continue;
                content.add(r);
            }
            content.sort(ORDER);
            for (Core.Record r:content) { option.text=Core.clean(option.text+" "+r.text); option.sources.add(r); used.add(r); }
            question.options.add(option);
        }
        if (closed) {
            for (Core.Record r:records) {
                if (under(r.sourcePath,optionPath) && !used.contains(r) && !metadata(r.text) && !Core.clean(r.text).isEmpty()) {
                    closed=false; break;
                }
            }
        }
        if (footerTop==Integer.MAX_VALUE && !closed) throw bad("选项结束位置不明确：需要独立且完整显示的选项容器，或题目结束按钮。");
        return Core.finish(question);
    }
    private static boolean answerControl(Core.Record record) {
        return record.editable || Core.clean(record.text).matches("^(请输入答案|点击输入答案|请输入作答内容|我的答案[：:]?|作答区域)$");
    }
    private static Core.Question textCandidate(List<Core.Record> records,List<Marker> markers,Marker marker) {
        Core.Record header=marker.record;
        String failure="问答题题干或结束位置不明确，请完整显示题干与作答区域。";
        int parentDepth=Math.max(0,header.sourcePath.length-1);
        for(int depth=parentDepth;depth>=Math.max(0,parentDepth-1);depth--) {
            int[] scope=Arrays.copyOf(header.sourcePath,depth);
            int end=Integer.MAX_VALUE;
            for(Marker other:markers)if(other!=marker && other.record.top>header.top && under(other.record.sourcePath,scope))end=Math.min(end,other.record.top);
            for(Core.Record r:records)if(geometry(r) && r.visible && under(r.sourcePath,scope) && r.top>=header.bottom
                && (answerControl(r)||FOOTER.matcher(Core.clean(r.text)).find()))end=Math.min(end,r.top);
            Core.Container box=container(header,scope);
            if(end==Integer.MAX_VALUE && (box==null || !box.visible || box.scrollable))continue;
            List<Core.Record> body=new ArrayList<>();boolean incomplete=false;
            for(Core.Record r:records){
                if(r==header || !under(r.sourcePath,scope) || r.top<header.bottom || r.top>=end || answerControl(r)
                    || metadata(r.text) || FOOTER.matcher(Core.clean(r.text)).find() || marker(r)!=null)continue;
                if(!geometry(r) || !r.visible || r.bottom>end){incomplete=true;break;}
                if(Math.abs(r.left-header.left)>Math.max(header.bottom-header.top,r.bottom-r.top)*2)continue;
                body.add(r);
            }
            if(incomplete){failure="问答题题干未完整显示，请先调整页面。";continue;}
            body.sort(ORDER);StringBuilder text=new StringBuilder(metadata(marker.suffix)?"":marker.suffix);
            for(Core.Record r:body)text.append(' ').append(Core.clean(r.text));
            if(Core.clean(text.toString()).length()<2)continue;
            Core.Question q=new Core.Question();q.type="text";q.stem=Core.clean(text.toString());q.anchor=header;
            return Core.finish(q);
        }
        throw bad(failure);
    }
}
