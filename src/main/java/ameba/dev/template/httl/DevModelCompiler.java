package ameba.dev.template.httl;

import ameba.Ameba;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.dev.classloading.ReloadClassPath;
import ameba.exception.AmebaException;
import ameba.util.ClassUtils;
import httl.spi.compilers.JavassistCompiler;
import httl.spi.compilers.JdkCompiler;
import javassist.ClassPool;
import javassist.LoaderClassPath;

/**
 * @author icode
 */
public class DevModelCompiler extends JavassistCompiler {

    @Override
    @SuppressWarnings("unchecked")
    protected void init() {
        pool = new ClassPool();
        if (Ameba.getApp().getMode().isDev()) {
            try {
                ClassLoader cl = ClassUtils.getContextClassLoader();
                super.pool.insertClassPath(new ReloadClassPath(cl instanceof ReloadClassLoader ? cl.getParent() : cl));
            } catch (java.lang.Exception e) {
                throw new AmebaException("DevModelCompiler must be use for dev model and has dev module", e);
            }
        } else {
            ClassLoader contextLoader = ClassUtils.getContextClassLoader();
            try {
                contextLoader.loadClass(JdkCompiler.class.getName());
            } catch (ClassNotFoundException e) { // 如果线程上下文的ClassLoader不能加载当前httl.jar包中的类，则切换回httl.jar所在的ClassLoader
                contextLoader = JdkCompiler.class.getClassLoader();
            }

            pool.appendClassPath(new LoaderClassPath(contextLoader));
        }
        pool.appendSystemPath();
    }
}
