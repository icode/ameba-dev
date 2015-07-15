package ameba.dev.classloading;

import ameba.dev.compiler.JavaSource;
import ameba.util.ClassUtils;
import ameba.util.IOUtils;
import javassist.LoaderClassPath;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * @author icode
 */
public class ReloadClassPath extends LoaderClassPath {

    /**
     * Creates a search path representing a class loader.
     *
     * @param cl ClassLoader
     */
    public ReloadClassPath(ClassLoader cl) {
        super(cl);
    }

    protected static ClassDescription getClassDesc(String classname) {
        if (classname.startsWith("java.")) return null;
        ClassLoader classLoader = ClassUtils.getContextClassLoader();
        if (classLoader instanceof ReloadClassLoader) {
            ReloadClassLoader loader = (ReloadClassLoader) classLoader;
            return loader.getClassCache().get(classname);
        }
        return null;
    }

    @Override
    public InputStream openClassfile(String classname) {
        ClassDescription desc = getClassDesc(classname);
        if (desc != null) {
            preLoadClass(classname, desc);
            if (hasEnhancedClassFile(desc)) {
                return desc.getEnhancedByteCodeStream();
            }
        }
        return super.openClassfile(classname);
    }

    @Override
    public URL find(String classname) {
        ClassDescription desc = getClassDesc(classname);
        if (desc != null) {
            preLoadClass(classname, desc);
            if (hasEnhancedClassFile(desc)) {
                try {
                    return desc.getEnhancedClassFile().toURI().toURL();
                } catch (MalformedURLException e) {
                    return super.find(classname);
                }
            }
        }
        return super.find(classname);
    }

    protected boolean hasEnhancedClassFile(ClassDescription desc) {
        return desc != null && desc.getEnhancedClassFile().exists();
    }

    public void preLoadClass(String classname, ClassDescription desc) {
        if (!hasEnhancedClassFile(desc)) {
            ClassLoader cl = ClassUtils.getContextClassLoader();
            if (cl instanceof ReloadClassLoader) {
                ReloadClassLoader classLoader = (ReloadClassLoader) cl;
                if (classLoader.isAppClass(classname)) {
                    final URL url = classLoader.getResource(JavaSource.getClassFileName(classname));
                    if (url != null) {
                        byte[] code;
                        try {
                            code = IOUtils.toByteArray(url);
                        } catch (IOException e) {
                            return;
                        }

                        try {
                            classLoader.loadPackage(classname,
                                    new ReloadClassLoader.ClassResource(JavaSource.getClassSimpleName(classname), url));
                        } catch (IOException e) {
                            // no op
                        }
                        classLoader.enhanceClass(classname, code);
                    }
                }
            }
        }
    }
}