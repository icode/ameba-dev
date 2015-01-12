package ameba.dev.classloading;

import ameba.core.AddOn;
import ameba.core.Application;
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
import java.util.Map;
import java.util.Set;

/**
 * @author icode
 */
public class ClassCache {

    private static Logger logger = LoggerFactory.getLogger(ClassCache.class);


    private static final Map<String, ClassDescription> byteCodeCache = Maps.newConcurrentMap();

    private Application application;

    private String addOnsHash;

    public ClassCache(Application application) {
        this.application = application;
        this.addOnsHash = getAddOnsHash(application);
    }

    public ClassDescription get(String name) {
        ClassDescription desc = byteCodeCache.get(name);
        if (desc == null) {
            logger.trace("find class cache for {}...", name);
            File javaFile = JavaSource.getJavaFile(name, application);
            File classFile = JavaSource.getClassFile(name);
            if (javaFile != null && classFile != null) {
                desc = new ClassDescription();
                desc.className = name;
                try {
                    desc.classByteCode = Files.readAllBytes(classFile.toPath());
                } catch (IOException e) {
                    throw new UnexpectedException("Read java source file error", e);
                }
                desc.classFile = classFile;
                desc.javaFile = javaFile;
                desc.classSimpleName = JavaSource.getClassSimpleName(name);
                desc.signature = getCacheHash(name);
                desc.lastModified = classFile.lastModified();
                File cacheFile = getCacheFile(desc);
                if (cacheFile.exists()) {
                    try {
                        desc.enhancedByteCode = Files.readAllBytes(cacheFile.toPath());
                    } catch (IOException e) {
                        throw new UnexpectedException("read class cache file error", e);
                    }
                }
                logger.trace("loaded class cache {}", name);
            }
            byteCodeCache.put(name, desc);
        }
        return desc;
    }

    ClassDescription put(String name, byte[] bytecode) {
        ClassDescription desc = new ClassDescription();

        desc.className = name;
        desc.classByteCode = bytecode;
        desc.classFile = JavaSource.getClassFile(name);
        desc.javaFile = JavaSource.getJavaFile(name, application);
        desc.classSimpleName = JavaSource.getClassSimpleName(name);
        desc.signature = getCacheHash(name);
        desc.lastModified = System.currentTimeMillis();

        byteCodeCache.put(name, desc);
        return desc;
    }

    void writeCache(ClassDescription desc) {
        File cacheFile = getCacheFile(desc);
        logger.trace("write class cache file {}", cacheFile);
        try {
            FileUtils.forceMkdir(cacheFile.getParentFile());
        } catch (IOException e) {
            throw new UnexpectedException("mk cache dir error", e);
        }
        try {
            FileUtils.writeByteArrayToFile(cacheFile,
                    desc.enhancedByteCode == null ? desc.classByteCode : desc.enhancedByteCode, false);
        } catch (IOException e) {
            throw new UnexpectedException("create class cache file error", e);
        }
    }

    private File getCacheFile(ClassDescription desc) {
        String classPath = desc.classFile.getAbsolutePath();
        File pFile = new File(classPath.substring(0,
                classPath.length() - (desc.className.length() + JavaSource.CLASS_EXTENSION.length())));
        try {
            return new File(pFile, "../generated-sources/ameba/enhanced-classes/".concat(desc.className.replace(".", "/")
                    .concat("_").concat(getCacheHash(desc.className)).concat(JavaSource.CLASS_EXTENSION))).getCanonicalFile();
        } catch (IOException e) {
            throw new UnexpectedException("get cache file error", e);
        }
    }

    String getCacheHash(String name) {
        String javaHash = getJavaSourceHash(name, application);
        return Hashing.md5().newHasher().putUnencodedChars(addOnsHash + javaHash).hash().toString();
    }

    public static String getJavaSourceHash(String name, Application application) {
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


    public static String getAddOnsHash(Application app) {
        Set<AddOn> addOns = app.getAddOns();
        Hasher hasher = Hashing.md5().newHasher();

        for (AddOn addOn : addOns) {
            hasher.putUnencodedChars(addOn.getClass().getName());
        }
        return hasher.hash().toString();
    }

}
