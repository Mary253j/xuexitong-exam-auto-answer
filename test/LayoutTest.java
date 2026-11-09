package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Synthetic accessibility nodes matching the supplied layout; no OCR or device claims. */
public final class LayoutTest {
    private static int count;
    private static final String STEM="以下哪一项不属于重点经济领域安全范畴？";
    private static final String[] OPTIONS={"科技安全","信息安全","房地产安全","能源安全"};
    private static void check(boolean value,String description) {
        if(!value)throw new AssertionError(description);count++;
    }
    private static void rejects(Runnable operation,String description) {
        try{operation.run();}catch(IllegalArgumentException expected){count++;return;}
        throw new AssertionError(description);
    }
    private static Core.Container box(int[] path,int top,int bottom) {
        Core.Container c=new Core.Container();c.path=path;c.left=20;c.right=1260;c.top=top;c.bottom=bottom;c.visible=true;return c;
    }
    private static Core.Record node(String text,int[] path,int left,int top,int right,int bottom,Core.Container... containers) {
        Core.Record r=new Core.Record(text,true,null);r.sourcePath=path;r.left=left;r.top=top;r.right=right;r.bottom=bottom;
        r.ancestors=Arrays.asList(containers);return r;
    }
    private static List<Core.Record> page(boolean footer, boolean rows) {
        Core.Container optionBox=box(new int[]{0,1,2},400,1350);
        List<Core.Record> out=new ArrayList<>();
        out.add(node("返回",new int[]{0,0,0},20,15,130,75));
        out.add(node("某某课程 · 单选题练习记录",new int[]{0,0,1},180,15,950,75));
        out.add(node("1.[单选题]",new int[]{0,1,0},40,160,400,225));
        out.add(node(STEM,new int[]{0,1,1},40,260,1200,330));
        out.add(node("剩余时间 09:59",new int[]{0,4,0},900,170,1250,220));
        for(int i=0;i<4;i++){
            int y=430+i*230;
            int[] labelPath=rows?new int[]{0,1,2,i,0}:new int[]{0,1,2,i*2};
            int[] textPath=rows?new int[]{0,1,2,i,1}:new int[]{0,1,2,i*2+1};
            Core.Target t=new Core.Target();t.path=new int[]{0,1,2,i};t.signature="row"+i;
            t.left=40;t.right=1200;t.top=y-20;t.bottom=y+150;
            Core.Record label=node(String.valueOf((char)('A'+i)),labelPath,70,y,140,y+100,optionBox);
            Core.Record content=node(OPTIONS[i],textPath,220,y+15,900,y+90,optionBox);
            label.target=t;content.target=rows?t:null;out.add(label);out.add(content);
        }
        out.add(node("温馨提示：本课程还有其他练习",new int[]{0,2,0},40,1420,1200,1490));
        if(footer)out.add(node("下一题",new int[]{0,3,0},800,1600,1220,1680));
        return out;
    }
    public static void main(String[] args) {
        Core.Question q=Core.parse(page(false,true));
        check(q.stem.equals(STEM),"only question text");
        check(q.type.equals("single") && q.options.size()==4,"numbered bracket type");
        for(int i=0;i<4;i++)check(q.options.get(i).text.equals(OPTIONS[i]),"option text "+i);
        check(!q.option("D").text.contains("温馨提示"),"footer noise excluded");
        check(!q.stem.contains("09:59") && !q.stem.contains("课程"),"header and timer excluded");
        List<Core.Record> scrambled=page(false,true);
        Collections.shuffle(scrambled,new Random(42));
        check(Core.parse(scrambled).canonical.equals(q.canonical),"tree traversal order can differ from visual order");
        Core.Question flat=Core.parse(page(false,false));
        check(flat.canonical.equals(q.canonical),"separate letter and content columns");
        List<Core.Record> wrapped=page(false,true);
        wrapped.add(node("的组成部分",new int[]{0,1,2,0,2},220,535,900,590));
        check(Core.parse(wrapped).option("A").text.equals("科技安全 的组成部分"),"multiline option inside row");
        List<Core.Record> missing=page(false,true);missing.removeIf(r->r.text.equals("B") || r.text.equals("信息安全"));
        rejects(()->Core.parse(missing),"missing option must not be inferred");
        List<Core.Record> offscreen=page(false,true);
        for(Core.Record r:offscreen)if(r.text.equals("能源安全"))r.visible=false;
        rejects(()->Core.parse(offscreen),"offscreen final content");
        List<Core.Record> hiddenExtra=page(false,true);
        Core.Record e=node("E",new int[]{0,1,2,4,0},70,1500,140,1600);e.visible=false;hiddenExtra.add(e);
        Core.Record extra=node("其他答案",new int[]{0,1,2,4,1},220,1515,900,1590);extra.visible=false;hiddenExtra.add(extra);
        rejects(()->Core.parse(hiddenExtra),"must not treat visible A-D as complete when E exists");
        List<Core.Record> unclear=page(false,true);
        for(Core.Record r:unclear)if(r.ancestors!=null)for(Core.Container c:r.ancestors)c.scrollable=true;
        rejects(()->Core.parse(unclear),"scrolling option container cannot prove completeness without footer");
        List<Core.Record> withFooter=page(true,true);
        for(Core.Record r:withFooter)if(r.ancestors!=null)for(Core.Container c:r.ancestors)c.scrollable=true;
        check(Core.parse(withFooter).canonical.equals(q.canonical),"footer can bound a scrolling container");
        List<Core.Record> interleaved=page(false,true);
        interleaved.add(node("姓名：测试用户",new int[]{0,6,0},930,820,1260,890));
        interleaved.add(node("页面旁边的说明文字",new int[]{0,6,1},920,1050,1270,1120));
        check(Core.parse(interleaved).canonical.equals(q.canonical),"side text at option height excluded by ancestry");
        List<Core.Record> scaled=page(false,true);
        java.util.Set<Core.Container> changedBoxes=Collections.newSetFromMap(new java.util.IdentityHashMap<Core.Container,Boolean>());
        for(Core.Record r:scaled){
            r.left=r.left/2+25;r.right=r.right/2+25;r.top=r.top/2+50;r.bottom=r.bottom/2+50;
            if(r.ancestors!=null)for(Core.Container c:r.ancestors)if(changedBoxes.add(c)){
                c.left=c.left/2+25;c.right=c.right/2+25;c.top=c.top/2+50;c.bottom=c.bottom/2+50;
            }
        }
        check(Core.parse(scaled).canonical.equals(q.canonical),"no fixed pixel positions or crop assumptions");
        List<Core.Record> inlineStem=page(false,true);
        inlineStem.removeIf(r->r.text.equals(STEM));
        for(Core.Record r:inlineStem)if(r.text.equals("1.[单选题]"))r.text="1.[单选题] "+STEM;
        check(Core.parse(inlineStem).canonical.equals(q.canonical),"inline question header");
        List<Core.Record> sectionHeader=page(false,true);
        sectionHeader.add(node("单选题",new int[]{0,0,2},40,90,300,135));
        check(Core.parse(sectionHeader).canonical.equals(q.canonical),"page section title does not replace actual question marker");
        List<Core.Record> unknownInside=page(false,false);
        unknownInside.add(node("未知的额外行",new int[]{0,1,2,10},220,1330,1000,1345));
        rejects(()->Core.parse(unknownInside),"unassigned content means container end is uncertain");
        List<Core.Record> duplicateTarget=page(false,true);
        for(Core.Record r:duplicateTarget)if(r.text.equals("B") || r.text.equals("信息安全"))r.target.path=new int[]{0,1,2,0};
        Core.Question ambiguousClicks=Core.parse(duplicateTarget);
        rejects(()->Core.plan(ambiguousClicks,Collections.singletonList("A")),"options may not share click target");
        List<Core.Record> second=page(false,true);
        for(Core.Record r:page(false,true)){
            if(r.sourcePath.length<2 || r.sourcePath[1]!=1)continue;
            r.sourcePath=r.sourcePath.clone();r.sourcePath[1]=7;r.top+=1500;r.bottom+=1500;
            if(r.ancestors!=null){
                List<Core.Container> moved=new ArrayList<>();
                for(Core.Container c:r.ancestors){Core.Container m=box(new int[]{0,7,2},c.top+1500,c.bottom+1500);moved.add(m);}r.ancestors=moved;
            }
            if(r.target!=null){r.target.path=r.target.path.clone();r.target.path[1]=7;}
            second.add(r);
        }
        rejects(()->Core.parse(second),"two complete questions must remain ambiguous");
        System.out.println("Layout checks passed: "+count);
    }
}
