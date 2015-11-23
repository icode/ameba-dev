package ameba.dev.classloading;

import ameba.core.Addon;
import ameba.core.Application;
import ameba.dev.HotswapJvmAgent;
import ameba.dev.compiler.JavaSource;
import ameba.exception.AmebaException;
import ameba.exception.UnexpectedException;
import ameba.util.IOUtils;
import ameba.util.UrlExternalFormComparator;
import sun.misc.Resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.net.*;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * @author icode
 */
public class ReloadClassLoader extends URLClassLoader {

    private static final Set<URL> urls = new TreeSet<>(new UrlExternalFormComparator());
    public ProtectionDomain protectionDomain;
    private File packageRoot;
    private ClassCache classCache;

    public ReloadClassLoader(ClassLoader parent, Application app) {
        this(parent, app.getPackageRoot());
        this.classCache = new ClassCache(app);
    }

    public ReloadClassLoader(Application app) {
        this(ReloadClassLoader.class.getClassLoader(), app);
    }

    protected ReloadClassLoader(ClassLoader parent, File pkgRoot) {
        super(new URL[0], parent);
        if (pkgRoot == null) return;
        packageRoot = pkgRoot;
        File resourceRoot = new File(pkgRoot.getParent(), "resources");
        try {
            addURL(resourceRoot.toURI().toURL());
        } catch (MalformedURLException e) {
            //no op
        }
        addClassLoaderUrls(parent);
        boolean hasClassesDir = false;
        for (URL url : urls) {
            try {
                if (url != null) {
                    URI uri = url.toURI();
                    if (uri.normalize().getPath().endsWith("/target/classes/")) {
                        hasClassesDir = true;
                    }
                }
            } catch (URISyntaxException e) {
                //no op
            }
            addURL(url);
        }
        if (!hasClassesDir) {
            try {
                File f = new File(pkgRoot, "../../../target/classes").getCanonicalFile();
                f.mkdir();
                addURL(f.toURI().toURL());
            } catch (IOException e) {
                // no op
            }
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
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                return c;
            }

            Class<?> clazz;
            try {
                clazz = loadAppClass(name);
            } catch (IOException e) {
                throw new AmebaException("load class error", e);
            }
            if (clazz != null) {
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            }
        }
        try {
            Class<?> c = findClass(name);
            if (resolve) {
                resolveClass(c);
            }
            return c;
        } catch (ClassNotFoundException e) {
            return super.loadClass(name, resolve);
        }
    }

    protected boolean isAppClass(String name) {
        boolean is = ClassDescription.isClass(name)
                && !(name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("ameba.")
                || name.startsWith("org.glassfish.jersey.")
                || name.startsWith("org.glassfish.hk2.")
                || name.startsWith("com.google.common.")
                || name.startsWith("com.google.common.")
                || name.startsWith("org.apache.thirdparty.")
                || name.startsWith("org.apache.log4j")
                || name.startsWith("groovy.")
                || name.startsWith("scala.")
                || name.startsWith("org.slf4j.")
                || name.startsWith("ch.qos.logback.")
                || name.startsWith("com.alibaba.druid.")
                || name.startsWith("org.relaxng.")
                || name.startsWith("akka.")
                || name.startsWith("com.typesafe.")
                || name.startsWith("org.hibernate.validator.")
                || name.startsWith("org.jboss.logging.")
                || name.startsWith("httl.")
                || name.startsWith("com.fasterxml.")
                || name.startsWith("org.oracle.")
                || name.startsWith("com.sun.")
                || name.startsWith("sun.applet.")
                || name.startsWith("sun.jvmstat.")
                || name.startsWith("sun.rmi.")
                || name.startsWith("sun.security.tools.")
                || name.startsWith("sun.tools.")
                || name.startsWith("javassist.")
                || name.startsWith("org.eclipse.jdt.")
                || name.startsWith("org.jvnet.")
                || name.startsWith("sun.reflect."));
        if (is) return true;
        // Scan includes, then excludes
        File f = JavaSource.getJavaFile(name, packageRoot);
        return f != null && f.exists();
    }

    protected void loadPackage(String name, Resource res) throws IOException {
        int i = name.lastIndexOf('.');
        URL url = res.getCodeSourceURL();
        if (i != -1) {
            String pkgname = name.substring(0, i);
            // Check if package already loaded.
            Manifest man = res.getManifest();
            if (getAndVerifyPackage(pkgname, man, url) == null) {
                try {
                    if (man != null) {
                        definePackage(pkgname, man, url);
                    } else {
                        definePackage(pkgname, null, null, null, null, null, null, null);
                    }
                } catch (IllegalArgumentException iae) {
                    // parallel-capable class loaders: re-verify in case of a
                    // race condition
                    if (getAndVerifyPackage(pkgname, man, url) == null) {
                        // Should never happen
                        throw new AssertionError("Cannot find package " +
                                pkgname);
                    }
                }
            }
        }
    }

    /*
     * Retrieve the package using the specified package name.
     * If non-null, verify the package using the specified code
     * source and manifest.
     */
    private Package getAndVerifyPackage(String pkgname,
                                        Manifest man, URL url) {
        Package pkg = getPackage(pkgname);
        if (pkg != null) {
            // Package found, so check package sealing.
            if (pkg.isSealed()) {
                // Verify that code source URL is the same.
                if (!pkg.isSealed(url)) {
                    throw new SecurityException(
                            "sealing violation: package " + pkgname + " is sealed");
                }
            } else {
                // Make sure we are not attempting to seal the package
                // at this code source URL.
                if ((man != null) && isSealed(pkgname, man)) {
                    throw new SecurityException(
                            "sealing violation: can't seal package " + pkgname +
                                    ": already loaded");
                }
            }
        }
        return pkg;
    }

    /*
     * Returns true if the specified package name is sealed according to the
     * given manifest.
     */
    private boolean isSealed(String name, Manifest man) {
        String path = name.replace('.', '/').concat("/");
        Attributes attr = man.getAttributes(path);
        String sealed = null;
        if (attr != null) {
            sealed = attr.getValue(Attributes.Name.SEALED);
        }
        if (sealed == null) {
            if ((attr = man.getMainAttributes()) != null) {
                sealed = attr.getValue(Attributes.Name.SEALED);
            }
        }
        return "true".equalsIgnoreCase(sealed);
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

    protected Class<?> loadAppClass(final String name) throws IOException {
        if (isAppClass(name)) {
            final URL url = getResource(JavaSource.getClassFileName(name));
            if (url == null) return null;
            byte[] code;
            try {
                code = IOUtils.toByteArray(url);
            } catch (IOException e) {
                return null;
            }

            loadPackage(name, new ClassResource(JavaSource.getClassSimpleName(name), url));

            return defineClassInternal(name, code);
        }

        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    protected Class defineClassInternal(String name, byte[] bytecode) {
        ClassDescription desc = enhanceClass(name, bytecode);
        if (desc == null) return null;
        // must be recheck loaded class
        Class<?> c = findLoadedClass(name);
        if (c != null) {
            return c;
        }
        bytecode = desc.enhancedByteCode == null ? desc.classByteCode : desc.enhancedByteCode;
        return defineClass(desc.className, bytecode, 0, bytecode.length, protectionDomain);
    }

    public Class defineClass(String name, byte[] bytecode) {
        synchronized (getClassLoadingLock(name)) {
            Class maybeAlreadyLoaded = findLoadedClass(name);
            if (maybeAlreadyLoaded != null) {
                return maybeAlreadyLoaded;
            }

            if (isAppClass(name)) {
                Class clazz = defineClassInternal(name, bytecode);
                if (clazz != null) return clazz;
            }

            return defineClass(name, bytecode, 0, bytecode.length, protectionDomain);
        }
    }

    protected ClassDescription enhanceClass(String name, byte[] bytecode) {
        ClassDescription desc = classCache.get(name);
        if (desc == null) return null;
        desc.classByteCode = bytecode;

        if (desc.enhancedByteCode == null) {
            enhanceClass(desc);
            classCache.writeCache(desc);
            desc.lastModified = System.currentTimeMillis();
        }
        return desc;
    }

    protected void enhanceClass(ClassDescription desc) {
        Addon.publishEvent(new EnhanceClassEvent(desc));
    }

    public void detectChanges(Set<ClassDefinition> classes) throws UnmodifiableClassException, ClassNotFoundException {
        HotswapJvmAgent.reload(classes.toArray(new ClassDefinition[classes.size()]));
    }

    public ClassCache getClassCache() {
        return classCache;
    }

    protected static class ClassResource extends Resource {

        private String name;
        private URL url;

        public ClassResource(String name, URL url) {
            this.name = name;
            this.url = url;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public URL getURL() {
            return url;
        }

        @Override
        public URL getCodeSourceURL() {
            return url;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return url.openStream();
        }

        @Override
        public int getContentLength() throws IOException {
            return url.openConnection().getContentLength();
        }

        @Override
        public Manifest getManifest() throws IOException {
            URLConnection connection = url.openConnection();
            if (connection instanceof JarURLConnection)
                return ((JarURLConnection) connection).getManifest();
            else return null;
        }
    }
}