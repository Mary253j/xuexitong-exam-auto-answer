package cn.local.quizhelper;

/** Temporarily disables this service's node cache and restores its original state. */
final class FreshReadControl {
    interface Cache {
        boolean supported();
        boolean enabled();
        boolean enable(boolean value);
        boolean clear();
    }
    interface Read<T>{T get();}
    static final class Outcome<T>{T value;String log;}
    static <T> Outcome<T> run(Cache cache,Read<T> read) {
        Outcome<T> result=new Outcome<>();StringBuilder log=new StringBuilder("节点缓存检测：");
        Boolean before=null;boolean attempted=false;
        try {
            if(!cache.supported()){
                log.append("系统不支持关闭缓存；仍逐节点刷新。");
            }else{
                try {
                    before=cache.enabled();log.append("原状态=").append(before);
                    attempted=true;
                    log.append("；禁用调用=").append(cache.enable(false));
                    log.append("；读取前缓存=").append(cache.enabled());
                    log.append("；清除缓存=").append(cache.clear());
                }catch(RuntimeException error){log.append("；设置异常=").append(error.getClass().getSimpleName());}
            }
            result.value=read.get();
        }finally{
            if(attempted && before!=null){
                try {
                    log.append("；恢复调用=").append(cache.enable(before));
                    log.append("；恢复后缓存=").append(cache.enabled());
                }catch(RuntimeException error){log.append("；恢复异常=").append(error.getClass().getSimpleName());}
            }
            result.log=log.toString();
        }
        return result;
    }
}
