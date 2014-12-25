package ameba.dev.classloading;

import ameba.core.AddOn;
import ameba.core.Application;
import ameba.dev.HotswapJvmAgent;
import ameba.dev.classloading.enhance.ClassDescription;
import ameba.dev.compiler.JavaSource;
import ameba.exception.UnexpectedException;
import ameba.util.IOUtils;
import ameba.util.UrlExternalFormComparator;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.*;

/**
 * @author icode
 */
public class ReloadClassLoader extends URLClassLoader {

    private static final Set<URL> urls = new TreeSet<URL>(new UrlExternalFormComparator());
    public ProtectionDomain protectionDomain;
    private File packageRoot;

    public ReloadClassLoader(ClassLoader parent, Application app) {
        this(parent, app.getPackageRoot());
    }

    public ReloadClassLoader(Application app) {
        this(ReloadClassLoader.class.getClassLoader(), app.getPackageRoot());
    }

    public ReloadClassLoader(ClassLoader parent, File pkgRoot) {
        super(new URL[0], parent);
        addClassLoaderUrls(parent);
        for (URL url : urls) {
            addURL(url);
        }
        try {
            CodeSource codeSource = new CodeSource(new URL("file:" + pkgRoot.getAbsolutePath()), (Certificate[]) null);
            Permissions permissions = new Permissions();
            permissions.add(new AllPermission());
            protectionDomain = new ProtectionDomain(codeSource, permissions);
        } catch (MalformedURLException e) {
            throw new UnexpectedException(e);
        }
        packageRoot = pkgRoot;
    }

    /**
     * Add all the url locations we can find for the provided class loader
     *
     * @param loader class loader
     */
    private static void addClassLoaderUrls(ClassLoader loader) {
        if (loader != null) {
            final Enumeration<URL> resources;
            try {
                resources = loader.getResources("");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            while (resources.hasMoreElements()) {
                URL location = resources.nextElement();
                addLocation(location);
            }
        }
    }

    /**
     * Add the location of a directory containing class files
     *
     * @param url the URL for the directory
     */
    public static void addLocation(URL url) {
        urls.add(url);
    }

    /**
     * Returns the list of all configured locations of directories containing class files
     *
     * @return list of locations as URL
     */
    public static Set<URL> getLocations() {
        return urls;
    }

    public boolean hasClass(String clazz) {
        return findLoadedClass(clazz) != null;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c != null) {
                    return c;
                }

                Class<?> clazz = loadAppClass(name);
                if (clazz != null) {
                    if (resolve) {
                        resolveClass(clazz);
                    }
                    return clazz;
                }
            }
        } catch (Exception e) {
            //no op
        }
        return super.loadClass(name, resolve);
    }

    protected boolean tryClassHere(String name) {
        // don't include classes in the java or javax.servlet package
        if (name == null || !ClassDescription.isClass(name) || (name.startsWith("java.") || name.startsWith("javax.servlet"))) {
            return false;
        }
        // Scan includes, then excludes
        File f = JavaSource.getJava(name, packageRoot);
        return f != null && f.exists();
    }

    private String getPackageName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > -1 ? name.substring(0, dot) : "";
    }

    private void loadPackage(String className) {
        String simpleName = getClassSimpleName(className);
        if (simpleName != null) {
            className = simpleName + "." + JavaSource.PKG_TAG;
        } else {
            className = JavaSource.PKG_TAG;
        }
        if (findLoadedClass(className) == null) {
            loadAppClass(className);
        }
    }

    private String getClassSimpleName(String className) {
        int symbol = className.indexOf("$");
        if (symbol > -1) {
            className = className.substring(0, symbol);
        }
        symbol = className.lastIndexOf(".");
        if (symbol > -1) {
            return className.substring(0, symbol);
        }
        return null;
    }

    @Override
    public final URL getResource(final String name) {
        URL resource = findResource(name);
        ClassLoader parent = getParent();
        if (resource == null && parent != null) {
            resource = parent.getResource(name);
        }

        return resource;
    }

    private Class<?> loadAppClass(String name) {
        if (tryClassHere(name)) {
            URL url = getResource(name.replace(".", "/").concat(JavaSource.CLASS_EXTENSION));
            if (url == null) return null;
            byte[] code;
            try {
                code = IOUtils.toByteArray(url);
            } catch (IOException e) {
                return null;
            }
            if (name.endsWith(JavaSource.PKG_TAG)) {
                definePackage(getPackageName(name), null, null, null, null, null, null, null);
            } else {
                loadPackage(name);
            }

            ClassDescription desc = new ClassDescription();
            desc.classBytecode = code;
            desc.classFile = url.getFile();
            desc.className = name;
            desc.classSimpleName = getClassSimpleName(name);

            AddOn.publishEvent(new ClassLoadEvent(desc));

            if (!Arrays.equals(code, desc.classBytecode)) {
                try {
                    FileUtils.writeByteArrayToFile(new File(desc.classFile), desc.classBytecode, false);
                } catch (IOException e) {
                    throw new UnexpectedException("write class file error", e);
                }
            }

            return defineClass(desc.className, desc.classBytecode, 0, desc.classBytecode.length, protectionDomain);
        }

        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public void detectChanges(List<ClassDefinition> classes) throws UnmodifiableClassException, ClassNotFoundException {
        HotswapJvmAgent.reload(classes.toArray(new ClassDefinition[classes.size()]));
    }
}