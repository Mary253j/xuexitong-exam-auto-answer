package cn.local.quizhelper;

import java.util.Arrays;

/** Bounded, in-memory evidence for an explicit diagnostic read, before text filtering. */
final class ReadAudit {
    static final int REPORT_LIMIT = 120000, FIELD_LIMIT = 4000;
    static final class Node {
        int[] path;
        int left, top, right, bottom, window, children, returnedChildren;
        String packageName="", kind="", viewId="", raw="", description="", hint="", state="", extraKeys="", decision="";
        boolean visible, retained, password;
        Boolean sensitive;
    }
    private final StringBuilder entries = new StringBuilder();
    private int nodes, kept, dropped, skipped, missing, errors, sensitive, emptyVisible, omitted, shortened;
    private int refreshOk,refreshFailed;
    private boolean limit;

    private String field(String value) {
        if(value==null || value.isEmpty())return "<空>";
        if(value.length()>FIELD_LIMIT){shortened++;value=value.substring(0,FIELD_LIMIT)+"…[本字段截断]";}
        return value.replace("\r","\\r").replace("\n","\\n").replace("\t","\\t");
    }
    private void append(String value) {
        if(entries.length()+value.length()>REPORT_LIMIT){omitted++;return;}
        entries.append(value);
    }
    void node(Node n) {
        nodes++;
        if(n.retained)kept++;else dropped++;
        if(Boolean.TRUE.equals(n.sensitive))sensitive++;
        if(!n.password && n.visible && (n.raw==null || n.raw.isEmpty()) && (n.description==null || n.description.isEmpty()))emptyVisible++;
        StringBuilder out=new StringBuilder("\n节点 ").append(Arrays.toString(n.path))
            .append(n.visible?" [可见] ":" [不可见或边界无效] ").append(n.retained?"[保留] ":"[过滤] ").append(n.decision).append('\n')
            .append("位置 ").append(n.left).append(',').append(n.top).append(',').append(n.right).append(',').append(n.bottom)
            .append(" 类型 ").append(field(n.kind)).append('\n')
            .append("包 ").append(field(n.packageName)).append(" 窗口 ").append(n.window)
            .append(" 子节点 ").append(n.returnedChildren).append('/').append(n.children)
            .append(" 数据敏感 ").append(n.sensitive==null?"系统不支持查询":n.sensitive).append('\n');
        if(n.password){
            out.append("密码节点：文字、描述、提示与状态均省略。\n");
        }else{
            out.append("原始文字：").append(field(n.raw)).append('\n')
                .append("辅助描述：").append(field(n.description)).append('\n');
            if(n.hint!=null && !n.hint.isEmpty())out.append("输入提示：").append(field(n.hint)).append('\n');
            if(n.state!=null && !n.state.isEmpty())out.append("状态说明：").append(field(n.state)).append('\n');
        }
        if(n.viewId!=null && !n.viewId.isEmpty())out.append("资源 ID：").append(field(n.viewId)).append('\n');
        if(n.extraKeys!=null && !n.extraKeys.isEmpty())out.append("可请求附加数据的键：").append(field(n.extraKeys)).append('\n');
        append(out.toString());
    }
    void rejected(int[] path,String packageName,int window,int expected) {
        skipped++;
        append("\n节点 "+Arrays.toString(path)+" [跳过分支] "+(window!=expected?"窗口不匹配":"包名不匹配")+
            "；包 "+field(packageName)+" 窗口 "+window+"；未读取该分支文字。\n");
    }
    void missing(int[] path) {
        missing++;
        append("\n节点 "+Arrays.toString(path)+" [系统返回 null] 未取得该子节点，原因未知。\n");
    }
    void error(int[] path,String kind) {
        errors++;
        append("\n节点 "+Arrays.toString(path)+" [读取异常] "+kind+"；本次检测不完整。\n");
    }
    void traversalLimit(){limit=true;}
    void refreshed(int[] path,boolean ok){
        if(ok)refreshOk++;else{refreshFailed++;error(path,"节点刷新返回 false，未读取此旧节点");}
    }
    boolean complete(){return !limit && errors==0;}
    String report(String environment) {
        return "分享前请删除姓名、学号等页面个人信息。\n过滤前节点报告（当前服务可获取范围）\n"+environment+
            "\n采集："+(complete()?"完成遍历":"未完成遍历")+"；节点="+nodes+"，保留="+kept+"，过滤="+dropped+
            "，跳过分支="+skipped+"，null 子节点="+missing+"，异常="+errors+
            "，已返回的敏感节点="+sensitive+"，可见空文字节点="+emptyVisible+
            "\n逐节点刷新：成功="+refreshOk+"，失败="+refreshFailed+
            "\n截断：节点上限="+limit+"，报告省略条目="+omitted+"，字段截断="+shortened+
            "\n原始文字和辅助描述在合并、去重前分别记录。只检测不调用 API、不点击、不保存到磁盘。"+
            "\n提示：敏感标记只描述已返回的节点；false 或没有 null 子节点，均不能证明其他节点未受系统限制。\n"+entries;
    }
}
