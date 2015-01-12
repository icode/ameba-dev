package ameba.dev.classloading;

import ameba.core.AddOn;
import ameba.core.Application;
import ameba.dev.HotswapJvmAgent;
import ameba.dev.compiler.JavaSource;
import ameba.exception.UnexpectedException;
import ameba.util.IOUtils;
import ameba.util.UrlExternalFormComparator;

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
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;

/**
 * @author icode
 */
public class ReloadClassLoader extends URLClassLoader {

    private static final Set<URL> urls = new TreeSet<URL>(new UrlExternalFormComparator());
    public ProtectionDomain protectionDomain;
    private File packageRoot;
    private File resourceRoot;
    private Application application;
    private ClassCache classCache;

    public ReloadClassLoader(ClassLoader parent, Application app) {
        this(parent, app.getPackageRoot());
        this.application = app;
        this.classCache = new ClassCache(app);
    }

    public ReloadClassLoader(Application app) {
        this(ReloadClassLoader.class.getClassLoader(), app);
    }

    private ReloadClassLoader(ClassLoader parent, File pkgRoot) {
        super(new URL[0], parent);
        packageRoot = pkgRoot;
        resourceRoot = new File(pkgRoot.getParent(), "resources");
        try {
            addURL(resourceRoot.toURI().toURL());
        } catch (MalformedURLException e) {
            //no op
        }
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
        File f = JavaSource.getJavaFile(name, packageRoot);
        return f != null && f.exists();
    }

    private String getPackageName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > -1 ? name.substring(0, dot) : "";
    }

    private void loadPackage(String className) {
        String simpleName = JavaSource.getClassSimpleName(className);
        if (simpleName != null) {
            className = simpleName + "." + JavaSource.PKG_TAG;
        } else {
            className = JavaSource.PKG_TAG;
        }
        if (findLoadedClass(className) == null) {
            loadAppClass(className);
        }
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
            URL url = getResource(JavaSource.getClassFileName(name));
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

            return defineClass(name, code);
        }

        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public Class defineClass(String name, byte[] bytecode) {

        if (ClassDescription.isClass(name)) {
            Class maybeAlreadyLoaded = findLoadedClass(name);
            if (maybeAlreadyLoaded != null) {
                return maybeAlreadyLoaded;
            }
        }

        ClassDescription desc = classCache.get(name);

        if (desc == null) {
            desc = classCache.put(name, bytecode);
        } else {
            desc.classByteCode = bytecode;
        }

        if (desc.enhancedByteCode == null) {
            AddOn.publishEvent(new EnhanceClassEvent(desc));
            classCache.writeCache(desc);
            desc.lastModified = System.currentTimeMillis();
        }

        bytecode = desc.enhancedByteCode == null ? desc.classByteCode : desc.enhancedByteCode;

        return defineClass(desc.className, bytecode, 0, bytecode.length, protectionDomain);
    }

    public void detectChanges(Set<ClassDefinition> classes) throws UnmodifiableClassException, ClassNotFoundException {
        HotswapJvmAgent.reload(classes.toArray(new ClassDefinition[classes.size()]));
    }

    public ClassCache getClassCache() {
        return classCache;
    }
}