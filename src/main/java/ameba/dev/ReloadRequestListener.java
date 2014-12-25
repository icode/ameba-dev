package ameba.dev;

import ameba.core.Application;
import ameba.db.model.Model;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.dev.compiler.CompileErrorException;
import ameba.dev.compiler.Config;
import ameba.dev.compiler.JavaCompiler;
import ameba.dev.compiler.JavaSource;
import ameba.event.Listener;
import ameba.feature.AmebaFeature;
import ameba.util.IOUtils;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spi.ContainerResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.persistence.Entity;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.net.URL;
import java.util.List;
import java.util.regex.Matcher;

/**
 * @author icode
 */
public class ReloadRequestListener implements Listener<Application.RequestEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ReloadRequestListener.class);
    private static ReloadClassLoader _classLoader = (ReloadClassLoader) Thread.currentThread().getContextClassLoader();

    @Inject
    private Application app;

    private ThreadLocal<Reload> reloadThreadLocal = new ThreadLocal<Reload>();

    @Override
    public void onReceive(Application.RequestEvent requestEvent) {
        Reload reload = reloadThreadLocal.get();
        switch (requestEvent.getType()) {
            case START:
                reload = scanChanges();
                if (reload.needReload) {
                    reloadThreadLocal.set(reload);
                    requestEvent.getContainerRequest().abortWith(
                            Response.temporaryRedirect(requestEvent.getUriInfo().getRequestUri())
                                    .build());
                }
                break;
            case FINISHED:
                if (reload != null) {
                    AmebaFeature.publishEvent(new DevReloadEvent(reload.classes));
                    ContainerResponseWriter writer = requestEvent.getContainerRequest().getResponseWriter();
                    try {
                        writer.writeResponseStatusAndHeaders(0, requestEvent.getContainerResponse()).flush();
                    } catch (IOException e) {
                        logger.warn("热加载-浏览器重新加载出错", e);
                    }
                    try {
                        reload(reload.classes, _classLoader);
                    } catch (Throwable e) {
                        logger.error("热加载出错", e);
                    }
                }
                break;
        }
    }

    private Reload scanChanges() {
        ReloadClassLoader classLoader = (ReloadClassLoader) app.getClassLoader();

        File pkgRoot = app.getPackageRoot();
        boolean needReload = false;
        Reload reload = null;
        if (pkgRoot != null) {
            FluentIterable<File> iterable = Files.fileTreeTraverser()
                    .breadthFirstTraversal(pkgRoot);

            File classesRoot = new File(IOUtils.getResource("").getFile());

            List<JavaSource> javaFiles = Lists.newArrayList();

            for (File f : iterable) {
                if (f.isFile() && f.getName().endsWith(".java")) {
                    String path = pkgRoot.toPath().relativize(f.toPath()).toString();
                    String className = path.substring(0, path.length() - 5);
                    File clazz = new File(classesRoot, className + ".class");
                    if (!clazz.exists() || f.lastModified() > clazz.lastModified()) {
                        javaFiles.add(new JavaSource(className.replaceAll(Matcher.quoteReplacement(File.separator), "."),
                                pkgRoot, classesRoot));
                    }
                }
            }


            if (javaFiles.size() > 0) {
                final List<ClassDefinition> classes = Lists.newArrayList();

                _classLoader = createClassLoader();
                JavaCompiler compiler = JavaCompiler.create(_classLoader, new Config());
                try {
                    compiler.compile(javaFiles);
                    for (JavaSource source : javaFiles) {
                        if (!needReload && !classLoader.hasClass(source.getClassName())) {
                            needReload = true;//新class，重新加载容器
                        }
                        classes.add(new ClassDefinition(classLoader.loadClass(source.getClassName()), source.getByteCode()));
                    }
                } catch (CompileErrorException e) {
                    throw e;
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }

                try {
                    classLoader.detectChanges(classes);
                } catch (UnsupportedOperationException e) {
                    needReload = true;
                } catch (ClassNotFoundException e) {
                    logger.warn("在重新加载时未找到类", e);
                } catch (UnmodifiableClassException e) {
                    logger.warn("在重新加载时失败", e);
                }

                reload = new Reload(classes);
            }

        } else {
            logger.warn("未找到包根目录，无法识别更改！请设置JVM参数，添加 -Dapp.source.root=${yourAppRootDir}");
        }
        if (!needReload)
            Thread.currentThread().setContextClassLoader(_classLoader);
        return reload == null ? new Reload() : reload;
    }
    
    ReloadClassLoader createClassLoader() {
        return new ReloadClassLoader(app.getClassLoader().getParent(), app);
    }

    /**
     * 重新加载容器
     * 1.当出现一个没有的class，新编译的
     * 2.强制加载，当类/方法签名改变时
     */
    void reload(List<ClassDefinition> reloadClasses, ReloadClassLoader nClassLoader) {
        //实例化一个没有被锁住的并且从原有app获得全部属性
        ResourceConfig resourceConfig = new ResourceConfig(app.getConfig());
        resourceConfig.setClassLoader(nClassLoader);
        resourceConfig = ResourceConfig.forApplication(resourceConfig);
        Thread.currentThread().setContextClassLoader(nClassLoader);

        for (ClassDefinition cf : reloadClasses) {
            try {
                Class clazz = cf.getDefinitionClass();
                if (!clazz.isAnnotationPresent(Entity.class) && !Model.class.isAssignableFrom(clazz))
                    resourceConfig.register(nClassLoader.loadClass(clazz.getName()));
            } catch (ClassNotFoundException e) {
                logger.error("重新获取class失败", e);
            }
        }

        String pkgPath = app.getSourceRoot().getAbsolutePath();
        //切换已有class，更换class loader
        for (Class clazz : app.getClasses()) {
            if (clazz != null)
                try {
                    URL url = clazz.getResource("");
                    if (url == null || !url.getPath()
                            .startsWith(pkgPath)//不是工程内的class

                            || JavaSource.getJava(clazz.getName(), app) != null) {//是工程内，且java原始文件仍然存在
                        clazz = nClassLoader.loadClass(clazz.getName());
                        if (!resourceConfig.isRegistered(clazz))
                            resourceConfig.register(clazz);
                    }
                } catch (Exception e) {
                    logger.error("重新获取class失败", e);
                }
        }

        app.getContainer().reload(resourceConfig);
    }

    static class Reload {
        List<ClassDefinition> classes;

        boolean needReload = false;

        public Reload(List<ClassDefinition> classes) {
            this.classes = classes;
            needReload = true;
        }

        public Reload() {
        }
    }
}
