package ameba.dev;

import ameba.core.AddOn;
import ameba.core.Application;
import ameba.dev.classloading.ClassCache;
import ameba.dev.classloading.ClassDescription;
import ameba.dev.classloading.EnhanceClassEvent;
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
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import org.glassfish.jersey.server.spi.ContainerResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.util.List;
import java.util.Set;

/**
 * @author icode
 */
public class ReloadRequestListener implements Listener<Application.RequestEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ReloadRequestListener.class);
    private static final String TEST_CLASSES_DIR = "/test-classes/";
    static ReloadClassLoader _classLoader = (ReloadClassLoader) Thread.currentThread().getContextClassLoader();
    private final ThreadLocal<Reload> reloadThreadLocal = new ThreadLocal<>();
    @Inject
    private Application app;

    @Override
    public void onReceive(Application.RequestEvent requestEvent) {
        Reload reload;
        try {
            switch (requestEvent.getType()) {
                case START:
                    reload = scanChanges();
                    if (reload.needReload) {
                        if (reload.classes != null && reload.classes.size() > 0) {
                            reloadThreadLocal.set(reload);
                            try {
                                requestEvent.getContainerRequest().abortWith(
                                        Response.temporaryRedirect(requestEvent.getUriInfo().getRequestUri())
                                                .build());
                            } catch (Exception e) {
                                // no op
                            }
                        } else {
                            reload(reload.classes, _classLoader);
                        }
                    }
                    break;
                case FINISHED:
                    reload = reloadThreadLocal.get();
                    if (reload != null && reload.classes != null && reload.classes.size() > 0) {
                        try {
                            ContainerResponseWriter writer = requestEvent.getContainerRequest().getResponseWriter();
                            writer.writeResponseStatusAndHeaders(0, requestEvent.getContainerResponse()).flush();
                        } catch (Exception e) {
                            // no op
                        }
                        reload(reload.classes, _classLoader);
                    }
                    break;
            }
        } catch (Exception e) {
            requestEvent.getContainerRequest().abortWith(Response.serverError().entity(e).build());
        }
    }

    Reload scanChanges() {
        ReloadClassLoader classLoader = (ReloadClassLoader) app.getClassLoader();

        File pkgRoot = app.getPackageRoot();
        Reload reload = new Reload();
        if (pkgRoot != null) {
            FluentIterable<File> iterable = Files.fileTreeTraverser()
                    .breadthFirstTraversal(pkgRoot);

            List<JavaSource> javaFiles = Lists.newArrayList();

            for (File f : iterable) {
                if (f.isFile() && f.getName().endsWith(".java")) {
                    String path = pkgRoot.toPath().relativize(f.toPath()).toString();
                    String className = path.substring(0, path.length() - 5).replace(File.separator, ".");
                    ClassDescription desc = classLoader.getClassCache().get(className);
                    if (desc == null || f.lastModified() > desc.getLastModified()) {
                        String classPath;
                        if (desc == null) {
                            File clazz = JavaSource.getClassFile(className);
                            if (clazz == null) {
                                String outDir = IOUtils.getResource("/").getFile();
                                if (outDir.endsWith(TEST_CLASSES_DIR)) {
                                    outDir = outDir.substring(0, outDir.length() - TEST_CLASSES_DIR.length())
                                            + "/classes/";
                                }
                                classPath = outDir + JavaSource.getClassFileName(className);
                            } else {
                                classPath = clazz.getPath();
                            }
                        } else {
                            classPath = desc.classFile.getPath();
                        }
                        javaFiles.add(new JavaSource(className.replace(File.separator, "."),
                                pkgRoot, new File(classPath.substring(0,
                                classPath.length() - (className.length() + JavaSource.CLASS_EXTENSION.length())
                        ))));
                    }
                }
            }


            if (javaFiles.size() > 0) {
                final Set<ClassDefinition> classes = Sets.newHashSet();

                _classLoader = createClassLoader();
                JavaCompiler compiler = JavaCompiler.create(_classLoader, new Config());
                ClassCache classCache = classLoader.getClassCache();
                try {
                    Set<JavaSource> compileClasses = compiler.compile(javaFiles);
                    // 1. 先将编译好的新class全部写入，否则会找不到类
                    for (JavaSource source : compileClasses) {
                        if (classCache.get(source.getClassName()) == null) {
                            reload.needReload = true;//新class，重新加载容器
                            source.saveClassFile();
                        }
                    }
                    // 2. 加载所有编译好的类
                    for (JavaSource source : compileClasses) {
                        ClassDescription desc = classCache.get(source.getClassName());
                        if (desc != null && desc.javaFile.lastModified() > desc.getLastModified()) {
                            desc.refresh();
                        }

                        byte[] bytecode = source.getByteCode();
                        if (!reload.needReload && desc != null && classLoader.hasClass(desc.className)) {
                            desc.classByteCode = bytecode;
                            AddOn.publishEvent(new EnhanceClassEvent(desc));
                            classCache.writeCache(desc);
                            bytecode = desc.enhancedByteCode == null ? desc.getClassByteCode() : desc.enhancedByteCode;
                        }

                        classes.add(new ClassDefinition(classLoader.loadClass(source.getClassName()), bytecode));
                    }
                } catch (Exception e) {
                    throw new CompileErrorException(e);
                }

                if (classes.size() > 0) {
                    try {
                        classLoader.detectChanges(classes);
                    } catch (UnsupportedOperationException e) {
                        reload.needReload = true;
                    } catch (ClassNotFoundException e) {
                        logger.warn("在重新加载时未找到类", e);
                    } catch (UnmodifiableClassException e) {
                        logger.warn("在重新加载时失败", e);
                    }

                    reload.classes = classes;
                }
            }

            for (ClassDescription description : classLoader.getClassCache().values()) {
                if (!description.isAvailable()) {
                    description.delete();
                    reload.needReload = true;
                }
            }

        } else {
            logger.warn("未找到包根目录，无法识别更改！请设置JVM参数，添加 -Dapp.source.root=${yourAppRootDir}");
        }
        if (!reload.needReload)
            Thread.currentThread().setContextClassLoader(_classLoader);
        return reload;
    }

    ReloadClassLoader createClassLoader() {
        return new ReloadClassLoader(app.getClassLoader().getParent(), app);
    }

    /**
     * 重新加载容器
     * 1.当出现一个没有的class，新编译的
     * 2.强制加载，当类/方法签名改变时
     */
    void reload(Set<ClassDefinition> reloadClasses, ReloadClassLoader nClassLoader) {
        try {
            synchronized (reloadThreadLocal) {
                AmebaFeature.publishEvent(new ClassReloadEvent(
                        reloadClasses == null ? Sets.<ClassDefinition>newHashSet() : reloadClasses
                ));

                // 新加入类注册resource
                if (reloadClasses != null) {
                    for (final ClassDefinition cf : reloadClasses) {
                        Class clazz = cf.getDefinitionClass();
                        nClassLoader.defineClass(clazz.getName(), cf.getDefinitionClassFile());
                    }
                }
                Thread.currentThread().setContextClassLoader(nClassLoader.getParent());

                app.getContainer().reload();
            }
        } catch (Throwable e) {
            logger.error("热加载出错", e);
        }
    }

    static class Reload {
        Set<ClassDefinition> classes;

        boolean needReload = false;

        public Reload(Set<ClassDefinition> classes) {
            this.classes = classes;
        }

        public Reload() {
        }
    }
}
