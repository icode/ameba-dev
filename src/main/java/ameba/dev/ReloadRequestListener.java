package ameba.dev;

import ameba.core.Addon;
import ameba.core.Application;
import ameba.core.event.RequestEvent;
import ameba.dev.classloading.ClassCache;
import ameba.dev.classloading.ClassDescription;
import ameba.dev.classloading.EnhanceClassEvent;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.dev.compiler.CompileErrorException;
import ameba.dev.compiler.Config;
import ameba.dev.compiler.JavaCompiler;
import ameba.dev.compiler.JavaSource;
import ameba.dev.info.ProjectInfo;
import ameba.event.Listener;
import ameba.exception.AmebaException;
import ameba.feature.AmebaFeature;
import ameba.i18n.Messages;
import ameba.message.error.ErrorMessage;
import ameba.message.error.ExceptionMapperUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;
import org.glassfish.jersey.server.spi.ContainerResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;

/**
 * @author icode
 */
public class ReloadRequestListener implements Listener<RequestEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ReloadRequestListener.class);
    static ReloadClassLoader _classLoader = (ReloadClassLoader) Thread.currentThread().getContextClassLoader();
    private final ThreadLocal<Reload> reloadThreadLocal = new ThreadLocal<>();
    @Inject
    private Application app;

    @SuppressWarnings("unchecked")
    @Override
    public void onReceive(RequestEvent requestEvent) {
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
            logger.error(e.getMessage(), e);

            ErrorMessage errorMessage = ErrorMessage.fromStatus(500);
            errorMessage.setThrowable(e);
            errorMessage.setCode(Hashing.murmur3_32().hashUnencodedChars(e.getClass().getName()).toString());
            errorMessage.setErrors(ErrorMessage.parseErrors(e, errorMessage.getStatus()));

            requestEvent.getContainerRequest()
                    .abortWith(
                            Response.serverError()
                                    .entity(errorMessage)
                                    .type(ExceptionMapperUtils.getResponseType(requestEvent.getContainerRequest()))
                                    .build()
                    );
        }
    }

    Reload scanChanges() {
        final ReloadClassLoader classLoader = (ReloadClassLoader) app.getClassLoader();

        Reload reload = new Reload();
        final List<JavaSource> javaFiles = Lists.newArrayList();
        for (final Path sourceDir : ProjectInfo.root().getAllSourceDirectories()) {
            final File sourceDirFile = sourceDir.toFile();
            try {
                Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toString().endsWith(".java")) {
                            String path = sourceDir.relativize(file).toString();
                            String className = path.substring(0, path.length() - 5).replace(File.separator, ".");
                            ClassDescription desc = classLoader.getClassCache().get(className);
                            if (desc == null || attrs.lastModifiedTime().toMillis() > desc.getLastModified()) {
                                String classPath;
                                if (desc == null) {
                                    // todo output 目录应该确定一下?
                                    classPath = JavaSource.getClassFilePath(className);
                                } else {
                                    classPath = desc.classFile.getPath();
                                }
                                javaFiles.add(new JavaSource(className.replace(File.separator, "."),
                                        sourceDirFile, new File(classPath.substring(0,
                                        classPath.length() - (className.length() + JavaSource.CLASS_EXTENSION.length())
                                ))));
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                logger.error("walk file tree has error", e);
            }
        }


        if (javaFiles.size() > 0) {
            final Set<ClassDefinition> classes = Sets.newHashSet();

            ReloadClassLoader newCL = createClassLoader();// 有可能指示java结尾的文件格式，并不包含内容
            JavaCompiler compiler = JavaCompiler.create(newCL, new Config());
            ClassCache classCache = classLoader.getClassCache();
            try {
                Set<JavaSource> compileClasses = compiler.compile(javaFiles);
                if (compileClasses.size() > 0) {
                    _classLoader = newCL;
                }
                // 1. 检测新类
                for (JavaSource source : compileClasses) {
                    if (classCache.get(source.getClassName()) == null) {
                        reload.needReload = true;//新class，重新加载容器
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
                        Addon.publishEvent(new EnhanceClassEvent(desc));
                        classCache.writeCache(desc);
                        bytecode = desc.enhancedByteCode == null ? desc.getClassByteCode() : desc.enhancedByteCode;
                    }

                    classes.add(new ClassDefinition(classLoader.loadClass(source.getClassName()), bytecode));
                }
            } catch (CompileErrorException e) {
                throw e;
            } catch (Exception e) {
                throw new AmebaException(e);
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
                description.destroy();
                reload.needReload = true;
            }
        }

        if (!reload.needReload)
            Thread.currentThread().setContextClassLoader(_classLoader);
        return reload;
    }

    ReloadClassLoader createClassLoader() {
        return new ReloadClassLoader(app.getClassLoader().getParent(), ProjectInfo.root().getAllSourceDirectories());
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
            logger.error(Messages.get("dev.hotswap.error"), e);
        }
    }

    static class Reload {
        Set<ClassDefinition> classes;

        boolean needReload = false;

        public Reload() {
        }
    }
}
