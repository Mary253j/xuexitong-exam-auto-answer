package cn.local.quizhelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java parsing and selection rules; no Android or network dependencies. */
public final class Core {
    private Core() {}
    public static final class Target {
        public int[] path;
        public int left, top, right, bottom;
        public Boolean checked;
        public String signature;
        public boolean gesture;
    }
    public static final class Container {
        public int[] path;
        public int left, top, right, bottom;
        public boolean visible, scrollable;
    }
    public static final class Record {
        public String text;
        public boolean visible;
        public Target target;
        public int[] sourcePath;
        public int left, top, right, bottom;
        public List<Container> ancestors;
        public boolean editable;
        public Record(String text, boolean visible, Target target) {
            this.text = text; this.visible = visible; this.target = target;
        }
    }
    public static final class Option {
        public String label, text;
        public final List<Record> sources = new ArrayList<>();
        public Target target;
        public Boolean checked;
    }
    public static final class Question {
        public String type, stem, canonical, id;
        public Record anchor;
        public final List<Option> options = new ArrayList<>();
        public Option option(String label) {
            for (Option option : options) if (option.label.equals(label)) return option;
            return null;
        }
    }
    public static final class Answer {
        public final String label, text;
        public Answer(String label, String text) { this.label = label; this.text = text; }
    }
    public static final class Action {
        public final String label;
        public final boolean desired;
        public Action(String label, boolean desired) { this.label = label; this.desired = desired; }
    }
    private static final class Line {
        final String text;
        final Record record;
        Line(String text, Record record) { this.text = text; this.record = record; }
    }
    private static final Pattern TYPE = Pattern.compile("[【\\[（(]?(单选题|多选题|判断题|简答题|问答题|论述题)[】\\]）)]?");
    private static final Pattern OPTION = Pattern.compile("^([A-HＡ-Ｈ])(?:[.．、:：)）]\\s*|\\s+)(.*)$");
    private static final Pattern LETTER = Pattern.compile("^([A-HＡ-Ｈ])$");
    private static final Pattern FOOTER = Pattern.compile("^(上一题|下一题|上题|下题|提交|提交答案|提交作业|提交试卷|交卷|答题卡|保存|暂存|返回|收藏|纠错|解析|答案解析|参考答案|我的答案|正确答案|教师评语)(?:\\s|[：:]|$)");
    private static final Pattern META = Pattern.compile("^(?:\\d+(?:\\.\\d+)?\\s*分|共\\s*\\d+\\s*题|\\d+\\s*/\\s*\\d+|剩余时间.*|[（(]\\d+(?:\\.\\d+)?\\s*分[）)])$");
    private static final Pattern LABEL_STATE=Pattern.compile("(?i)^([A-HＡ-Ｈ])[,，\\s]+(已选中|未选中|已选择|未选择|已勾选|未勾选|checked|unchecked)$");
    public static String clean(String s) { return s == null ? "" : s.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim(); }
    public static String cleanLabel(String value){
        Matcher state=LABEL_STATE.matcher(clean(value));return state.matches()?state.group(1):clean(value);
    }
    public static Boolean labelState(String value){
        Matcher state=LABEL_STATE.matcher(clean(value));if(!state.matches())return null;
        String status=state.group(2).toLowerCase(java.util.Locale.ROOT);
        return !(status.startsWith("未") || status.equals("unchecked"));
    }
    private static IllegalArgumentException bad(String s) { return new IllegalArgumentException(s); }
    private static String field(String s) { return s.length() + ":" + s; }
    private static String id(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("q_");
            for (int i = 0; i < 8; i++) out.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
            return out.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    public static Question parse(List<Record> records) {
        for (Record record : records) {
            if (record.sourcePath != null) return LayoutParser.parse(records);
        }
        List<Line> lines = new ArrayList<>();
        for (Record record : records) {
            for (String part : record.text.split("[\\r\\n]+")) {
                String text = clean(part);
                if (!text.isEmpty()) lines.add(new Line(text, record));
            }
        }
        int marker = -1, markerEnd = 0, count = 0;
        String kind = "";
        for (int i = 0; i < lines.size(); i++) {
            Matcher match = TYPE.matcher(lines.get(i).text);
            if (match.find()) { marker = i; markerEnd = match.end(); kind = match.group(1); count++; }
        }
        if (count != 1) throw bad("需要恰好一道带题型标记的题目，请切换到单题页面。");
        Question q = new Question();
        q.type = kind.equals("多选题") ? "multiple" : kind.equals("判断题") ? "boolean" : kind.equals("单选题") ? "single" : "text";
        StringBuilder stem = new StringBuilder();
        Option current = null;
        boolean ended = false;
        for (int i = marker; i < lines.size(); i++) {
            Line line = lines.get(i);
            String text = line.text;
            if (i == marker) {
                text = clean(text.substring(markerEnd)).replaceFirst("^[（(]\\d+(?:\\.\\d+)?\\s*分[）)]\\s*", "");
                if (text.isEmpty() || META.matcher(text).matches()) continue;
            }
            if (FOOTER.matcher(text).find()) { ended = true; break; }
            if (META.matcher(text).matches()) continue;
            if (q.type.equals("text")) { stem.append(' ').append(text); continue; }
            String optionText=cleanLabel(text);
            Matcher option = OPTION.matcher(optionText);
            boolean match = option.matches();
            String label = null, content = "";
            if (match) { label = option.group(1); content = clean(option.group(2)); }
            else {
                Matcher letter = LETTER.matcher(optionText);
                if (letter.matches()) label = letter.group(1);
            }
            if (label != null) {
                char c = label.charAt(0);
                label = String.valueOf((char) (c > 255 ? c - 65248 : c));
            } else if (q.type.equals("boolean") && text.matches("^(正确|错误|对|错|是|否|√|×)$")) {
                label = String.valueOf((char) ('A' + q.options.size())); content = text;
            }
            if (label != null) {
                if (!label.equals(String.valueOf((char) ('A' + q.options.size())))) throw bad("选项顺序不连续、缺失或重复。");
                current = new Option(); current.label = label; current.text = content;
                current.sources.add(line.record); q.options.add(current);
            } else if (current != null) {
                current.text = clean(current.text + " " + text);
                if (!current.sources.contains(line.record)) current.sources.add(line.record);
            } else { stem.append(' ').append(text); }
        }
        if (!ended) throw bad("未读到题目结束标记，例如“下一题”或“提交”。");
        q.stem = clean(stem.toString());
        return finish(q);
    }
    public static List<Question> parseAll(List<Record> records) {
        for (Record record : records) if (record.sourcePath != null) return LayoutParser.parseAll(records);
        List<Question> questions = new ArrayList<>(); questions.add(parse(records)); return questions;
    }
    static Question finish(Question q) {
        if (!q.type.equals("text") && (q.options.size() < 2 || q.options.size() > 8 || (q.type.equals("boolean") && q.options.size() != 2))) throw bad("未读到完整选项。");
        q.stem = clean(q.stem);
        if (q.stem.length() < 2 || q.stem.length() > 5000) throw bad("题干为空、过短或过长。");
        StringBuilder identity = new StringBuilder(field(q.type)).append(field(q.stem));
        for (Option o : q.options) {
            if (o.text.isEmpty() || o.text.length() > 3000) throw bad("选项为空或过长。");
            for (Record source : o.sources) {
                if (!source.visible) throw bad("选项未全部显示在屏幕内，请手动调整页面。");
                if (source.target != null && (o.target == null || !source.target.gesture)) o.target = source.target;
            }
            o.checked = o.target == null ? null : o.target.checked;
            identity.append(field(o.label)).append(field(o.text));
        }
        q.canonical = identity.toString(); q.id = id(q.canonical);
        return q;
    }
    public static List<String> validate(Question q, String questionId, boolean abstain, List<Answer> answers, String finishReason) {
        if (!"stop".equals(finishReason)) throw bad("模型回复未完整结束，本题已跳过。");
        if (!q.id.equals(questionId)) throw bad("模型回复的题号不匹配。");
        if (abstain) return null;
        if (answers.isEmpty() || (!q.type.equals("multiple") && answers.size() != 1)) throw bad("答案数量与题型不匹配。");
        List<String> labels = new ArrayList<>();
        for (Answer answer : answers) {
            Option o = q.option(answer.label);
            if (o == null || !o.text.equals(clean(answer.text)) || labels.contains(answer.label)) throw bad("模型答案与原选项不一致。");
            labels.add(answer.label);
        }
        return labels;
    }
    public static List<Action> plan(Question q, List<String> labels) {
        return plan(q, labels, false, java.util.Collections.emptySet());
    }
    public static List<Action> plan(Question q,List<String> labels,boolean allowBlankMultiple,Set<String> alreadySent) {
        String problem = clickProblem(q,allowBlankMultiple);
        if (!problem.isEmpty()) throw bad(problem);
        List<Action> actions = new ArrayList<>();
        for (Option o : q.options) {
            boolean desired = labels.contains(o.label);
            if (q.type.equals("multiple")) {
                if (o.checked == null) {
                    if (desired && !alreadySent.contains(o.label)) actions.add(new Action(o.label,true));
                } else if (o.checked != desired) actions.add(new Action(o.label, desired));
            } else if (desired && !Boolean.TRUE.equals(o.checked)) actions.add(new Action(o.label, true));
        }
        return actions;
    }
    public static String clickProblem(Question q) {
        return clickProblem(q,false);
    }
    public static String clickProblem(Question q,boolean allowBlankMultiple) {
        if (q.type.equals("text")) return "问答题提供参考答案，核对后可复制到学习通；不自动填写。";
        Set<String> paths = new HashSet<>();
        for (Option o : q.options) {
            if (o.target == null) return "题目文字已识别，但页面没有为每个选项提供可点击操作；可先查看模型答案。";
            if (!paths.add(java.util.Arrays.toString(o.target.path))) return "多个选项共用同一个点击区域，无法可靠自动选择；可先查看模型答案。";
            if (q.type.equals("multiple") && o.checked == null && !allowBlankMultiple) return "这道多选题无法读取选中状态；可开启空白题模式，或查看模型答案后手动选择。";
        }
        return "";
    }
    public static boolean unknownMultiple(Question q) {
        if (!q.type.equals("multiple")) return false;
        for (Option o:q.options) if (o.checked==null) return true;
        return false;
    }
    public static String validateText(Question q,String questionId,boolean abstain,String text,String finishReason) {
        if (!"text".equals(q.type) || !q.id.equals(questionId) || !"stop".equals(finishReason)) throw bad("问答题回复未完整结束或题号不匹配。");
        if (abstain) return null;
        if (text==null || text.trim().isEmpty() || text.length()>12000) throw bad("问答题答案为空或过长。");
        return text.trim();
    }
}
