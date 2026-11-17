package cn.local.quizhelper;

import java.util.List;

/** Bounded page reads; diagnostic previews have a separate, smaller display budget. */
final class WebReadLimits {
    static final int MAX_QUESTIONS=500;
    static final int MAX_ENCODED_CHARS=8000000;
    static final int PREVIEW_CHARS=40000;

    static String incompleteReason(boolean truncated,boolean error,List<String> reasons){
        if(error)return "网页文字读取未完成，已停止获取答案；请重新检测网页文字。";
        if(!truncated)return "";
        if(reasons.contains("QUESTION_LIMIT"))return "本页超过 "+MAX_QUESTIONS+" 道题的读取上限，已停止获取答案；请打开题目较少的页面。";
        if(reasons.contains("CANDIDATE_LIMIT"))return "本页题目节点过多，读取已截断，已停止获取答案。";
        if(reasons.contains("TEXT_LIMIT"))return "本页题目文字总量超出读取上限，已停止获取答案。";
        if(reasons.contains("NODE_LIMIT"))return "本页文字节点过多，读取未完成，已停止获取答案。";
        if(reasons.contains("FIELD_LIMIT"))return "部分题干或选项超过长度上限，已停止获取答案。";
        if(reasons.contains("OPTION_LIMIT"))return "部分题目的选项数量超出读取范围，已停止获取答案。";
        if(reasons.contains("DOCUMENT_LIMIT") || reasons.contains("FRAME_LIMIT"))return "本页题目框架过多或嵌套过深，读取未完成，已停止获取答案。";
        return "本页读取不完整，已停止获取答案；请先检测网页文字。";
    }
}
