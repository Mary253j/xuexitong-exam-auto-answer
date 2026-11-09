package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse each explicitly identified web question separately; no page-wide text guessing. */
final class WebQuestionParser {
    static final class Block {
        String title="";
        String extractionIssue="";
        final List<String> options=new ArrayList<>();
        boolean media,truncated;
    }
    private static final Pattern TYPE=Pattern.compile("[【\\[（(]?\\s*(单选题|多选题|判断题|简答题|问答题|论述题)\\s*[】\\]）)]?");
    private static final Pattern OPTION=Pattern.compile("^([A-HＡ-Ｈ])(?:[.．、:：)）]\\s*|\\s+)(.+)$");
    static Core.Question parse(Block block){
        if(!block.extractionIssue.isEmpty())throw new IllegalArgumentException(block.extractionIssue);
        if(block.truncated)throw new IllegalArgumentException("题目文字被截断，不能认定完整。");
        if(block.media)throw new IllegalArgumentException("题目含图片、公式或画布，当前文字检测不能认定题目完整。");
        String title=Core.clean(block.title);
        if(title.matches(".*[\\uE000-\\uF8FF\\uFFFD].*"))throw new IllegalArgumentException("题干含无法确认的字体编码，请核对网页显示与复制文字。");
        Matcher marker=TYPE.matcher(title);
        if(!marker.find())throw new IllegalArgumentException("该题没有可读的题型标记。");
        String kind=marker.group(1);String prefix=title.substring(0,marker.start()).trim();
        if(!prefix.matches("^(?:第\\s*\\d+\\s*题|\\d+\\s*[.．、)）]?)?\\s*$"))throw new IllegalArgumentException("题型标记位置不明确。");
        String stem=title.substring(marker.end()).replaceFirst("^[，,、：: ]*(?:\\d+(?:\\.\\d+)?\\s*分[）)]?\\s*)?","");
        Core.Question q=new Core.Question();q.stem=Core.clean(stem);q.type=kind.equals("单选题")?"single":kind.equals("多选题")?"multiple":kind.equals("判断题")?"boolean":"text";
        for(int i=0;i<block.options.size();i++){
            String row=Core.clean(block.options.get(i));
            if(row.matches(".*[\\uE000-\\uF8FF\\uFFFD].*"))throw new IllegalArgumentException("选项含无法确认的字体编码。");
            Matcher option=OPTION.matcher(row);String label,body;
            if(option.matches()){
                char c=option.group(1).charAt(0);label=String.valueOf((char)(c>255?c-65248:c));body=Core.clean(option.group(2));
            }else if(q.type.equals("boolean") && row.matches("^(正确|错误|对|错|是|否|√|×)$")){
                label=String.valueOf((char)('A'+i));body=row;
            }else throw new IllegalArgumentException("选项字母和正文没有完整配对。");
            if(!label.equals(String.valueOf((char)('A'+i))))throw new IllegalArgumentException("选项字母不连续或重复。");
            Core.Option result=new Core.Option();result.label=label;result.text=body;q.options.add(result);
        }
        if(q.type.equals("text") && !block.options.isEmpty())throw new IllegalArgumentException("问答题出现了未识别的选项。");
        return Core.finish(q);
    }
}
