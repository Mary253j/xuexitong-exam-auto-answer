package cn.local.quizhelper;

import android.content.Context;
import android.content.SharedPreferences;

final class SelectionJournal {
    private SelectionJournal(){}
    static ToggleLedger open(Context context){
        SharedPreferences preferences=context.getSharedPreferences("multiple_toggle_journal",Context.MODE_PRIVATE);
        return new ToggleLedger(new ToggleLedger.Store(){
            public String get(String key){return preferences.getString(key,"");}
            public void put(String key,String value){
                SharedPreferences.Editor edit=preferences.edit();
                if(value.isEmpty())edit.remove(key);else edit.putString(key,value);
                if(!edit.commit())throw new IllegalArgumentException("无法保存多选点击记录，已取消点击。");
            }
            public void clear(){if(!preferences.edit().clear().commit())throw new IllegalArgumentException("无法清除多选记录，请重试。");}
        });
    }
}
