package cn.local.quizhelper;
final class AccessibleText {
    private AccessibleText(){}
    static boolean sameWindowPackage(String name,int window,int expected){
        return window==expected && (name==null || name.isEmpty() || name.equals("com.chaoxing.mobile"));
    }
    static String combine(String raw,String desc){
        String text=raw==null?"":raw.trim(),description=desc==null?"":desc.trim();
        if(text.isEmpty())return description;
        if(description.isEmpty() || Core.clean(text).equals(Core.clean(description)))return text;
        if(Core.labelState(description)!=null)return text;
        if(Core.cleanLabel(text).matches("[A-HＡ-Ｈ]")){
            if(description.matches("^[A-HＡ-Ｈ](?:[.．、:：)）]|\\s).*"))return description;
            return text+" "+description;
        }
        if(Core.clean(description).contains(Core.clean(text)))return description;
        if(Core.clean(text).contains(Core.clean(description)))return text;
        return text;
    }
}
