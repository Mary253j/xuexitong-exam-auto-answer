package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.List;

/** Stable page ordinals, one question at a time, with a five-second minimum start interval. */
final class WebAnswerQueue {
    static final long INTERVAL=5000;
    final int count;
    private final int[] ordinals;
    private List<String> pageIdentity;
    private int cursor,selected,manual;
    WebAnswerQueue(int count,boolean multiple,int singleOrdinal){
        if(count<1 || count>40 || (!multiple && (singleOrdinal<1 || singleOrdinal>count)))throw new IllegalArgumentException("单题题号超出本页范围，或没有完整题目。");
        this.count=count;ordinals=new int[multiple?count:1];
        for(int i=0;i<ordinals.length;i++)ordinals[i]=multiple?i:singleOrdinal-1;
    }
    int current(){if(done())throw new IllegalStateException();return ordinals[cursor];}
    void verifyPage(List<String> identity){
        if(identity==null || identity.size()!=count)throw new IllegalArgumentException("本页题目数量发生变化，已停止；请核对后重新开始。");
        if(pageIdentity==null)pageIdentity=new ArrayList<>(identity);
        else if(!pageIdentity.equals(identity))throw new IllegalArgumentException("本页题目内容或顺序发生变化，已停止；请核对后重新开始。");
    }
    void finish(boolean chosen){if(done())throw new IllegalStateException();if(chosen)selected++;else manual++;cursor++;}
    boolean done(){return cursor==ordinals.length;}
    int selected(){return selected;}
    int manual(){return manual;}
    static long delay(long started,long now){return Math.max(0,INTERVAL-Math.max(0,now-started));}
}
