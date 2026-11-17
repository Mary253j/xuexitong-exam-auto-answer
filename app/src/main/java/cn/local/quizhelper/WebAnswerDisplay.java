package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Answer-only presentation; the question remains data for validation, not display. */
final class WebAnswerDisplay {
    static final class Item {
        final int ordinal;final Core.Question question;final List<String> answers;
        Item(int ordinal,Core.Question question,List<String> answers){this.ordinal=ordinal;this.question=question;this.answers=answers==null?null:new ArrayList<>(answers);}
    }
    static String render(Iterable<Item> entries){
        StringBuilder out=new StringBuilder();boolean previousText=false;
        for(Item item:entries){
            boolean text=item.question!=null && "text".equals(item.question.type) && item.answers!=null;
            String answer;
            if(item.question==null || item.answers==null)answer="暂无答案";
            else if(text)answer=item.answers.get(0);
            else if("boolean".equals(item.question.type)){
                Core.Option option=item.question.option(item.answers.get(0));
                String body=Core.clean(option.text).toLowerCase(Locale.ROOT);
                answer=body.matches("对|正确|是|√|true|t")?"对":body.matches("错|错误|否|×|false|f")?"错":option.label;
            }else{
                StringBuilder labels=new StringBuilder();
                for(Core.Option option:item.question.options)if(item.answers.contains(option.label))labels.append(option.label);
                answer=labels.toString();
            }
            String row=item.ordinal+"."+answer;
            if(out.length()+row.length()>60000){out.append("\n[后续答案省略]");break;}
            if(out.length()>0 && (text || previousText))out.append('\n');
            out.append(row);previousText=text;
        }
        return out.toString();
    }
}
