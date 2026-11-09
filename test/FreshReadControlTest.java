package cn.local.quizhelper;

/** Restore cache state even if a read or a platform cache operation fails. */
public final class FreshReadControlTest {
    private static int count;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;}
    private static final class Cache implements FreshReadControl.Cache {
        boolean supported=true,state=true,cleared,throwClear,throwDisable,rejectDisable,rejectRestore;
        int changes,queries;
        public boolean supported(){return supported;}
        public boolean enabled(){queries++;return state;}
        public boolean enable(boolean value){
            changes++;
            if(!value && rejectDisable)return false;
            if(value && rejectRestore)return false;
            state=value;
            if(!value && throwDisable)throw new IllegalStateException();
            return true;
        }
        public boolean clear(){cleared=true;if(throwClear)throw new IllegalStateException();return true;}
    }
    public static void main(String[] args){
        Cache cache=new Cache();final Cache normal=cache;
        FreshReadControl.Outcome<String> result=FreshReadControl.run(cache,()->{
            check(!normal.state && normal.cleared,"read must run after disabling and clearing cache");return "snapshot";
        });
        check(cache.state && result.value.equals("snapshot"),"preserve snapshot and restore initially enabled cache");
        check(result.log.contains("读取前缓存=false") && result.log.contains("恢复后缓存=true"),"report observed cache state");
        cache=new Cache();cache.state=false;
        FreshReadControl.run(cache,()->"snapshot");
        check(!cache.state,"an initially disabled cache must remain disabled");
        Cache throwingRead=new Cache();RuntimeException sentinel=new RuntimeException("read failed");
        boolean caught=false;
        try{FreshReadControl.run(throwingRead,()->{throw sentinel;});}catch(RuntimeException error){caught=error==sentinel;}
        check(caught && throwingRead.state,"a failed read must preserve its exception and restore cache state");
        cache=new Cache();cache.throwClear=true;
        result=FreshReadControl.run(cache,()->"read despite cache failure");
        check(cache.state && result.value.equals("read despite cache failure"),"cache clear failure must not prevent reading or restoration");
        check(result.log.contains("设置异常=IllegalStateException"),"cache failure cannot be silently reported as success");
        cache=new Cache();cache.throwDisable=true;
        result=FreshReadControl.run(cache,()->"snapshot");
        check(cache.state && result.log.contains("恢复调用=true"),"restore even if disabling mutated the cache before throwing");
        cache=new Cache();cache.rejectDisable=true;
        result=FreshReadControl.run(cache,()->"snapshot");
        check(result.log.contains("禁用调用=false") && result.log.contains("读取前缓存=true"),"failed disabling must not look uncached");
        cache=new Cache();cache.supported=false;
        result=FreshReadControl.run(cache,()->"old system");
        check(result.value.equals("old system") && cache.changes==0 && cache.queries==0 && !cache.cleared,"older systems must not call newer APIs");
        check(result.log.contains("不支持关闭缓存"),"unsupported operations must be explicit");
        cache=new Cache();cache.rejectRestore=true;
        result=FreshReadControl.run(cache,()->"snapshot");
        check(result.log.contains("恢复调用=false") && result.log.contains("恢复后缓存=false"),"failed restoration must be visible");
        System.out.println("Fresh node cache control checks passed: "+count);
    }
}
