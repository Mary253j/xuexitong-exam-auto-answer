package cn.local.quizhelper;

import java.util.Arrays;

/** Check that the diagnostic preserves evidence the normal parser can discard. */
public final class ReadAuditTest {
    private static int count;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;}
    private static ReadAudit.Node node(String raw){
        ReadAudit.Node n=new ReadAudit.Node();n.path=new int[]{0,2};n.raw=raw;
        n.visible=true;n.kind="android.widget.TextView";n.window=5;n.packageName="com.chaoxing.mobile";
        return n;
    }
    public static void main(String[] args){
        String stem="六十多年来，计算机根据电子元器件的发展划分为若干阶段，以下选项哪一项正确？";
        String parent=stem+"A 四代 B 五代 C 六代 D 三代";
        // Existing de-duplication can remove an aggregate with unique option text.
        boolean retained=NodeTextPolicy.retain(parent,Arrays.asList(stem,"A","B","C","D","以下选项"));
        check(!retained,"fixture must expose a real filter ambiguity");
        ReadAudit audit=new ReadAudit();ReadAudit.Node aggregate=node(parent);
        aggregate.retained=retained;aggregate.decision="现有文字去重规则";audit.node(aggregate);
        ReadAudit.Node option=node("A");option.description="科学计算领域";option.retained=true;audit.node(option);
        String report=audit.report("Android 测试版本");
        check(report.contains(parent),"filtered original text must survive");
        check(report.contains("原始文字：A\n辅助描述：科学计算领域"),"raw sources must remain separate");
        check(report.contains("过滤=1") && report.contains("[过滤]"),"filter decision must be disclosed");
        check(report.contains("报告省略条目=0"),"no implicit report truncation");
        ReadAudit.Node password=node("private-passphrase");password.password=true;password.description="private-description";
        password.hint="private-hint";password.state="private-state";audit.node(password);
        check(!audit.report("").contains("private-"),"password content must not be exported");
        audit.rejected(new int[]{8},"other.app",9,5);audit.missing(new int[]{0,4});
        report=audit.report("");
        check(report.contains("窗口不匹配") && report.contains("[系统返回 null]"),"missing and skipped nodes are distinct");
        check(report.contains("null 子节点=1") && report.contains("原因未知"),"do not assign a cause to a missing child");
        check(report.contains("均不能证明其他节点未受系统限制"),"negative metadata is not proof of unrestricted access");
        ReadAudit.Node empty=node("");empty.sensitive=false;audit.node(empty);
        check(audit.report("").contains("可见空文字节点=1"),"count visible blank nodes without counting password nodes");
        ReadAudit broken=new ReadAudit();broken.error(new int[]{0},"IllegalStateException");
        check(!broken.complete() && broken.report("").contains("未完成遍历"),"exceptions cannot look like a complete audit");
        broken=new ReadAudit();broken.traversalLimit();
        check(!broken.complete() && broken.report("").contains("节点上限=true"),"node cap cannot look like a complete traversal");
        ReadAudit refreshed=new ReadAudit();refreshed.refreshed(new int[]{0},true);
        check(refreshed.complete() && refreshed.report("").contains("逐节点刷新：成功=1，失败=0"),"report successful refresh counts");
        refreshed.refreshed(new int[]{0,1},false);
        check(!refreshed.complete() && refreshed.report("").contains("未读取此旧节点"),"obsolete nodes cannot be reported as fresh blank text");
        ReadAudit bounded=new ReadAudit();String large=new String(new char[ReadAudit.FIELD_LIMIT+10]).replace('\0','字');
        for(int i=0;i<100;i++)bounded.node(node(large));
        report=bounded.report("");
        check(report.length()<ReadAudit.REPORT_LIMIT+2000,"reports must stay bounded for clipboard and memory");
        check(report.contains("[本字段截断]") && !report.contains("报告省略条目=0"),"all truncation must be visible");
        check(report.contains("节点=100"),"aggregate counts must include omitted report entries");
        System.out.println("Raw diagnostic evidence checks passed: "+count);
    }
}
