package cn.local.quizhelper;

import java.util.ArrayList;
import java.util.List;

final class QuestionMode {
    private QuestionMode() {}
    static List<Core.Question> select(List<Core.Question> questions,boolean multiple) {
        return new ArrayList<>(questions.subList(0,multiple?questions.size():Math.min(1,questions.size())));
    }
}
