package ameba.dev.classloading;

import ameba.core.Addon;
import ameba.core.Application;
import ameba.dev.Enhancing;
import ameba.dev.classloading.enhancers.Enhancer;
import ameba.dev.compiler.JavaSource;
import ameba.exception.UnexpectedException;
import com.google.common.collect.Maps;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * @author icode
 */
public class ClassCache {

    private static final Map<String, ClassDescription> byteCodeCache = Maps.newConcurrentMap();
    private static Logger logger = LoggerFactory.getLogger(ClassCache.class);
    private Application application;

    private String hashSignature;

    public ClassCache(Application application) {
        this.application = application;
        this.hashSignature = getHashSignature(application);
    }

    public static String getJavaSourceSignature(String name, Application application) {
        Hasher hasher = Hashing.md5().newHasher();
        File javaFile = JavaSource.getJavaFile(name, application);
        hasher.putChar('_');
        if (javaFile != null) {
            try {
                hasher.putBytes(Files.readAllBytes(javaFile.toPath()));
            } catch (IOException e) {
                throw new UnexpectedException("Read java source file error", e);
            }
        }

        return hasher.hash().toString();
    }

    public static String getHashSignature(Application app) {
        Set<Addon> addOns = app.getAddons();
        Hasher hasher = Hashing.md5().newHasher();

        for (Addon addOn : addOns) {
            hasher.putUnencodedChars(addOn.getClass().getName() + addOn.getVersion());
        }
        for (Enhancer enhancer : Enhancing.getEnhancers()) {
            hasher.putUnencodedChars(enhancer.getClass().getName() + enhancer.getVersion());
        }
        return hasher.hash().toString();
    }

    public ClassDescription get(String name) {
        if (name.startsWith("java.")) return null;
        ClassDescription desc = byteCodeCache.get(name);
        if (desc == null) {
            File javaFile = JavaSource.getJavaFile(name, application);
            if (javaFile == null) return null;
            logger.trace("finding class cache for {}...", name);
            File classFile = JavaSource.getClassFile(name);
            if (classFile == null) return null;
            desc = new AppClassDesc();
            desc.className = name;
            try {
                desc.classByteCode = Files.readAllBytes(classFile.toPath());
            } catch (IOException e) {
                throw new UnexpectedException("Read java source file error", e);
            }
            desc.classFile = classFile;
            desc.javaFile = javaFile;
            desc.classSimpleName = JavaSource.getClassSimpleName(name);
            desc.signature = getCacheSignature(name);
            desc.lastModified = classFile.lastModified();
            File cacheFile = getCacheFile(desc);
            desc.enhancedClassFile = cacheFile;
            if (cacheFile.exists()) {
                desc.lastModified = desc.enhancedClassFile.lastModified();
                try {
                    desc.enhancedByteCode = Files.readAllBytes(cacheFile.toPath());
                    logger.trace("loaded class cache {}", name);
                } catch (IOException e) {
                    throw new UnexpectedException("read class cache file error", e);
                }
            }
            byteCodeCache.put(name, desc);
        }
        return desc;
    }

    public void writeCache(ClassDescription desc) {
        File cacheFile = desc.enhancedClassFile;
        logger.trace("write class cache file {}", cacheFile);
        try {
            FileUtils.writeByteArrayToFile(cacheFile,
                    desc.enhancedByteCode == null ? desc.classByteCode : desc.enhancedByteCode, false);
            if (desc.classFile != null && desc.classFile.exists()) {
                desc.classFile.setLastModified(System.currentTimeMillis());
            }
        } catch (IOException e) {
            throw new UnexpectedException("create class cache file error", e);
        }
    }

    public Set<String> keys() {
        return byteCodeCache.keySet();
    }

    public Collection<ClassDescription> values() {
        return byteCodeCache.values();
    }

    private File getCacheFile(ClassDescription desc) {
        String classPath = desc.classFile.getAbsolutePath();
        File pFile = new File(classPath.substring(0,
                classPath.length() - (desc.className.length() + JavaSource.CLASS_EXTENSION.length())));
        try {
            return new File(pFile,
                    "../generated-classes/ameba/enhanced-cache/"
                            .concat(desc.className.replace(".", "/")
                                    .concat("_")
                                    .concat(getCacheSignature(desc.className))
                                    .concat(JavaSource.CLASS_EXTENSION)))
                    .getCanonicalFile();
        } catch (IOException e) {
            throw new UnexpectedException("get cache file error", e);
        }
    }

    String getCacheSignature(String name) {
        String javaHash = getJavaSourceSignature(name, application);
        return Hashing.md5().newHasher().putUnencodedChars(hashSignature + javaHash).hash().toString();
    }

    private class AppClassDesc extends ClassDescription {
        @Override
        public synchronized void refresh() {
            deleteEnhanced();
            enhancedClassFile = getCacheFile(this);
            lastModified = System.currentTimeMillis();
        }

        private void deleteEnhanced() {
            FileUtils.deleteQuietly(enhancedClassFile);
            enhancedByteCode = null;
        }

        @Override
        public void delete() {
            deleteEnhanced();
            FileUtils.deleteQuietly(javaFile);
            FileUtils.deleteQuietly(classFile);
            byteCodeCache.remove(className);
        }
    }

}
