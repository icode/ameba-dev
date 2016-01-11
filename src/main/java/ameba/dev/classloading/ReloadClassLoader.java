package ameba.dev.classloading;

import ameba.core.Addon;
import ameba.dev.HotswapJvmAgent;
import ameba.dev.compiler.JavaSource;
import ameba.exception.AmebaException;
import ameba.exception.UnexpectedException;
import ameba.util.IOUtils;
import ameba.util.UrlExternalFormComparator;
import com.google.common.collect.Lists;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final Set<URL> urls = new TreeSet<>(new UrlExternalFormComparator());
    public ProtectionDomain protectionDomain;
    private List<Path> sourceDirectories;
    private ClassCache classCache;

    public ReloadClassLoader(File sourceDirectory) {
        this(ReloadClassLoader.class.getClassLoader(), sourceDirectory);
    }

    public ReloadClassLoader(ClassLoader parent, File sourceDirectory) {
        this(parent, Lists.newArrayList(sourceDirectory.toPath()));
    }

    public ReloadClassLoader(List<Path> sourceDirectories) {
        this(ReloadClassLoader.class.getClassLoader(), sourceDirectories);
    }

    public ReloadClassLoader(ClassLoader parent, List<Path> sourceDirectories) {
        super(new URL[0], parent);
        if (sourceDirectories == null) return;
        this.sourceDirectories = Collections.unmodifiableList(sourceDirectories);
        classCache = new ClassCache(sourceDirectories);
        for (Path path : sourceDirectories) {
            try {
                addURL(path.resolveSibling("resources").toUri().toURL());
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
                    Path p = path.resolveSibling("../../target/classes").normalize();
                    Files.createDirectories(p);
                    addURL(p.toUri().toURL());
                } catch (IOException e) {
                    // no op
                }
            }
        }
        try {
            CodeSource codeSource = new CodeSource(new File("").getAbsoluteFile().toURI().toURL(), (Certificate[]) null);
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
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> clazz = findClassFromCache(name);
        if (clazz != null) {
            return clazz;
        }
        return super.findClass(name);
    }

    public Class<?> findClassFromCache(String name) {
        if (isAppClass(name)) {
            ClassDescription desc = classCache.get(name);
            if (desc != null && desc.enhancedByteCode != null) {
                return defineClass(desc.className,
                        desc.enhancedByteCode,
                        0,
                        desc.enhancedByteCode.length,
                        protectionDomain);
            }
        }
        return null;
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
        File f = JavaSource.getJavaFile(name, sourceDirectories);
        return f != null && f.exists();
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
            Class<?> clazz = findClassFromCache(name);
            if (clazz != null) {
                return clazz;
            }
            final URL url = getResource(JavaSource.getClassFileName(name));
            if (url == null) return null;
            byte[] code;
            try {
                code = IOUtils.toByteArray(url);
            } catch (IOException e) {
                return null;
            }

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

        if (desc.enhancedByteCode == null) {
            desc.classByteCode = bytecode;
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

    public List<Path> getSourceDirectories() {
        return sourceDirectories;
    }
}