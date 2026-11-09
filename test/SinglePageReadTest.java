package cn.local.quizhelper;
import java.util.*;
public final class SinglePageReadTest {
    static int n;
    static void check(boolean ok){if(!ok)throw new AssertionError("case "+n);n++;}
    static Core.Record r(String text,int top,int bottom,int... suffix){
        int[] prefix={0,0,0,0,0,0,0,0,1,0,1,0,0,0};
        Core.Record r=new Core.Record(text,true,null);r.sourcePath=Arrays.copyOf(prefix,prefix.length+suffix.length);
        System.arraycopy(suffix,0,r.sourcePath,prefix.length,suffix.length);r.left=49;r.right=1179;r.top=top;r.bottom=bottom;return r;
    }
    public static void main(String[] args){
        check(AccessibleText.sameWindowPackage(null,9,9));
        check(AccessibleText.sameWindowPackage("",9,9));
        check(!AccessibleText.sameWindowPackage(null,10,9));
        check(!AccessibleText.sameWindowPackage("other.app",9,9));
        check(AccessibleText.combine("A","科学计算领域").equals("A 科学计算领域"));
        check(AccessibleText.combine("A","A 科学计算领域").equals("A 科学计算领域"));
        check(AccessibleText.combine("A","选项A：科学计算领域").startsWith("A "));
        check(AccessibleText.combine("A","A 未选中").equals("A"));
        check(AccessibleText.combine("科学计算领域","科学计算领域").equals("科学计算领域"));
        String stem="计算机的一项应用称为\"办公自动化\",按计算机应用的分类,应该属于______。";
        List<Core.Record> actual=new ArrayList<>();actual.add(r(stem,518,672,1,1));
        int[] tops={766,966,1169,1368};String[] options={"科学计算领域","数据处理领域","自动控制领域","辅助设计领域"};
        for(int i=0;i<4;i++)actual.add(r(String.valueOf((char)('A'+i)),tops[i],tops[i]+116,i+2,0));
        actual.add(r("下一题",1617,1788,9,2));
        boolean rejected=false;try{Core.parseAll(actual);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        // A missing marker and missing answer bodies must never be replaced with guesses.
        actual.add(r("1. 单选题",380,450,1,0));
        rejected=false;try{Core.parseAll(actual);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        for(int i=0;i<4;i++)actual.get(i+1).text=AccessibleText.combine(actual.get(i+1).text,options[i]);
        Core.Question q=Core.parse(actual);check(q.stem.equals(stem));
        for(int i=0;i<4;i++)check(q.options.get(i).text.equals(options[i]));
        System.out.println("Single-page accessibility checks passed: "+n);
    }
}
