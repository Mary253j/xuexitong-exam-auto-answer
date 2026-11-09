package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Coordinates and ancestry transcribed from the user's diagnostic, not an emulator run. */
public final class RealLayoutTest {
    private static int checks;
    private static final int[] ROOT={0,5,0,0,2,1,3,0,0,0,0,0,5};
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static Core.Record node(String text,boolean visible,int l,int t,int r,int b,int... suffix){
        int[] path=Arrays.copyOf(ROOT,ROOT.length+suffix.length);System.arraycopy(suffix,0,path,ROOT.length,suffix.length);
        Core.Record record=new Core.Record(text,visible,null);record.sourcePath=path;record.left=l;record.top=t;record.right=r;record.bottom=b;
        return record;
    }
    private static List<Core.Record> scene(){
        List<Core.Record> list=new ArrayList<>();
        list.add(node("1.",true,175,538,210,594,0,0));
        list.add(node("[单选题]",true,206,538,385,594,0,1));
        list.add(node("以下哪一项不属于重点经济领域安全范畴？",true,175,605,1176,692,0,2));
        String[] first={"科技安全","信息安全","房地产安全","能源安全"};
        int[] ys={745,906,1063,1224};
        for(int i=0;i<4;i++){
            list.add(node(String.valueOf((char)('A'+i)),true,175,ys[i],283,ys[i]+105,1,i,0));
            list.add(node(first[i],true,308,ys[i]+10,1176,ys[i]+94,1,i,1));
        }
        list.add(node("2.",true,175,1483,220,1543,2,0));
        list.add(node("[单选题]",true,213,1483,395,1543,2,1));
        list.add(node("中美贸易摩擦以来，中美之间实施了（ ）轮互征关税。",true,175,1553,1176,1718,2,2));
        int[] secondYs={1774,1931,2092,2253};String[] second={"6","5","3","4"};
        for(int i=0;i<4;i++){
            list.add(node(String.valueOf((char)('A'+i)),true,175,secondYs[i],283,secondYs[i]+105,3,i,0));
            list.add(node(second[i],true,308,secondYs[i]+10,1004,secondYs[i]+94,3,i,1,0));
        }
        list.add(node("3.",true,175,2512,220,2568,4,0));
        list.add(node("[单选题]",true,213,2512,395,2568,4,1));
        list.add(node("我国粮食安全面临的挑战之一是？",true,175,2579,1176,2666,4,2));
        list.add(node("A",true,175,2719,283,2760,5,0,0));
        list.add(node("粮食需求减少",true,308,2729,1176,2760,5,0,1));
        for(int i=1;i<4;i++){
            list.add(node(String.valueOf((char)('A'+i)),false,175,2760,283,2760,5,i,0));
            list.add(node("未显示的选项",false,308,2760,1176,2760,5,i,1));
        }
        for(int q=3;q<30;q++){
            list.add(node(q<20?"[多选题]":"[判断题]",false,217,2760,395,2760,q*2,1));
            list.add(node("页面外的其他题干",false,175,2760,1176,2760,q*2,2));
        }
        list.add(node("提交",false,742,2760,1176,2760,60,0));
        return list;
    }
    public static void main(String[] args){
        List<Core.Question> all=Core.parseAll(scene());
        check(all.size()==2,"two complete questions, exclude partial third and all collapsed questions");
        check(all.get(0).options.size()==4 && all.get(0).option("C").text.equals("房地产安全"),"first question grouped separately");
        check(all.get(1).option("A").text.equals("6") && all.get(1).option("D").text.equals("4"),"numeric options preserved");
        check(!all.get(0).stem.contains("贸易"),"question texts not merged");
        check(QuestionMode.select(all,false).size()==1,"single-question mode retained");
        check(QuestionMode.select(all,true).size()==2,"multi-question mode supported");
        check(!Core.clickProblem(all.get(0)).isEmpty(),"missing ACTION_CLICK is reported independently from extraction");
        List<Core.Record> gestures=scene();
        for(Core.Record r:gestures)if(r.visible && r.right>r.left && r.bottom>r.top){
            Core.Target target=new Core.Target();target.gesture=true;target.path=r.sourcePath;target.left=r.left;target.top=r.top;target.right=r.right;target.bottom=r.bottom;target.signature=r.text;r.target=target;
        }
        Core.Question q=Core.parseAll(gestures).get(0);
        check(Core.clickProblem(q).isEmpty(),"gesture targets support WebView options");
        check(q.option("C").target.left==175 && q.option("C").target.right==283,"prefer option-circle geometry over content geometry");
        check(Core.plan(q,Collections.singletonList("C")).size()==1,"one bounded choice operation");
        check(NodeTextPolicy.retain("[单选题] 以下哪项正确？",Arrays.asList("A","B")),"retain independent parent question text");
        check(!NodeTextPolicy.retain("科技安全",Collections.singletonList("科技安全")),"exclude repeated parent text");
        check(!NodeTextPolicy.retain("A 科技安全 B 信息安全",Arrays.asList("A 科技安全","B 信息安全")),"exclude aggregate container text");
        System.out.println("Real diagnostic regression checks passed: "+checks);
    }
}
