package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

final class WebSnapshot {
    static final class Entry {
        Core.Question question;
        JSONObject expected,target;
        String problem="";
    }
    static JSONObject decode(String encoded) throws Exception{
        if(encoded==null || encoded.length()>800000)throw new IllegalArgumentException("网页回复过长或为空。");
        Object value=new JSONTokener(encoded).nextValue();if(!(value instanceof String))throw new IllegalArgumentException("网页没有返回文字数据。");
        JSONObject result=new JSONObject((String)value);if(result.optInt("schema")!=1)throw new IllegalArgumentException("网页数据版本不匹配。");return result;
    }
    static List<Entry> entries(JSONObject data) throws Exception{
        if(data.optBoolean("truncated") || !data.optString("error").isEmpty())throw new IllegalArgumentException("本页读取不完整，已停止网页答题；请先检测文字。");
        JSONArray questions=data.optJSONArray("questions");
        if(questions==null || questions.length()<1 || questions.length()>40)throw new IllegalArgumentException("没有读到题目，请先打开作业并检测网页文字。");
        List<Entry> result=new ArrayList<>();
        for(int i=0;i<questions.length();i++){
            JSONObject raw=questions.getJSONObject(i);Entry entry=new Entry();WebQuestionParser.Block block=new WebQuestionParser.Block();
            block.title=raw.optString("title");block.media=raw.optBoolean("media");block.truncated=raw.optBoolean("truncated");block.extractionIssue=raw.optString("optionIssue");
            JSONArray options=raw.optJSONArray("options");
            if(block.title.length()>6000 || block.extractionIssue.length()>200 || options==null || options.length()>8)throw new IllegalArgumentException("题目字段超出范围。");
            for(int j=0;j<options.length();j++){String text=options.getString(j);if(text.length()>3500)throw new IllegalArgumentException("选项字段超出范围。");block.options.add(text);}
            entry.expected=new JSONObject().put("title",block.title).put("options",new JSONArray(block.options));entry.target=raw.optJSONObject("target");
            try{
                entry.question=WebQuestionParser.parse(block);
                if(raw.optBoolean("customFont"))entry.problem="题目含自定义字体标记，需核对文字后手动作答。";
            }catch(IllegalArgumentException error){entry.problem=error.getMessage();}
            result.add(entry);
        }
        return result;
    }
}
