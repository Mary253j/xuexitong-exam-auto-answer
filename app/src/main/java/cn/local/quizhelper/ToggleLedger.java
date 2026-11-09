package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Write-ahead record for toggles whose resulting selection cannot be observed. */
final class ToggleLedger {
    interface Store {
        String get(String key);
        void put(String key,String value);
        void clear();
    }
    private final Store store;
    ToggleLedger(Store store){this.store=store;}
    synchronized void prepare(String id,List<String> labels){
        List<String> sorted=new ArrayList<>(labels);Collections.sort(sorted);
        String expected=String.join("",sorted),old=store.get(id+":answers");
        if(!old.isEmpty() && !old.equals(expected))throw new IllegalArgumentException("本题以前的多选答案与本次不同，请核对后清空页面勾选，再重置多选记录。");
        if(old.isEmpty())store.put(id+":answers",expected);
        for(char c='A';c<='H';c++)if(store.get(id+":"+c).equals("pending"))
            throw new IllegalArgumentException("本题存在未确认的多选点击记录，请核对已选项；清空页面勾选后可重置多选记录。");
    }
    synchronized Set<String> sent(String id){
        Set<String> labels=new HashSet<>();
        for(char c='A';c<='H';c++)if(store.get(id+":"+c).equals("sent"))labels.add(String.valueOf(c));
        return labels;
    }
    synchronized boolean reserve(String id,String label){
        if(!label.matches("[A-H]"))throw new IllegalArgumentException("无效的选项记录。");
        String current=store.get(id+":"+label);
        if(current.equals("sent"))return false;
        if(current.equals("pending"))throw new IllegalArgumentException("多选点击状态尚未确认，请先核对题目。");
        store.put(id+":"+label,"pending");return true;
    }
    synchronized void sent(String id,String label){store.put(id+":"+label,"sent");}
    synchronized void release(String id,String label){if(store.get(id+":"+label).equals("pending"))store.put(id+":"+label,"");}
    synchronized void clear(){store.clear();}
}
