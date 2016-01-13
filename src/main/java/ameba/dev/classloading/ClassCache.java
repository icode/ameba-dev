package ameba.dev.classloading;

import ameba.dev.Enhancing;
import ameba.dev.classloading.enhancers.Enhancer;
import ameba.dev.compiler.JavaSource;
import ameba.dev.info.ProjectInfo;
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
    private ProjectInfo projectInfo;

    private String hashSignature;

    public ClassCache(ProjectInfo projectInfo) {
        this.projectInfo = projectInfo;
        this.hashSignature = getHashSignature();
    }

    public static String getJavaSourceSignature(String name, ProjectInfo projectInfo) {
        Hasher hasher = Hashing.murmur3_32().newHasher()
                .putUnencodedChars(name)
                .putChar('_');
        JavaSource.FoundInfo info = JavaSource.findInfoByJavaFile(name, projectInfo);
        if (info != null) {
            try {
                hasher.putBytes(Files.readAllBytes(info.getJavaFile().toPath()));
            } catch (IOException e) {
                throw new UnexpectedException("Read java source file error", e);
            }
        }
        return hasher.hash().toString();
    }

    public static String getHashSignature() {
        Hasher hasher = Hashing.murmur3_32().newHasher();

        for (Enhancer enhancer : Enhancing.getEnhancers()) {
            hasher.putUnencodedChars(enhancer.getClass().getName())
                    .putChar('.')
                    .putUnencodedChars(enhancer.getVersion());
        }
        return hasher.hash().toString();
    }

    public ClassDescription get(String name) {
        if (name.startsWith("java.")) return null;
        ClassDescription desc = byteCodeCache.get(name);
        if (desc == null) {
            JavaSource.FoundInfo foundInfo = JavaSource.findInfoByJavaFile(name, projectInfo);
            if (foundInfo == null) return null;
            File classFile = JavaSource.getExistsClassFile(name);
            if (classFile == null) {
                classFile = foundInfo.getClassFile();
            }
            desc = new AppClassDesc();
            desc.className = name;
            if (classFile.isFile() && classFile.exists()) {
                try {
                    desc.classByteCode = Files.readAllBytes(classFile.toPath());
                    desc.lastModified = classFile.lastModified();
                } catch (IOException e) {
                    throw new UnexpectedException("Read java source file error", e);
                }
            }
            desc.projectInfo = foundInfo.getProjectInfo();
            desc.classFile = classFile;
            desc.javaFile = foundInfo.getJavaFile();
            desc.classSimpleName = JavaSource.getClassSimpleName(name);
            desc.signature = getCacheSignature(name);
            File cacheFile = getCacheFile(desc, foundInfo.getProjectInfo());
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
            if (desc.lastModified == null) {
                desc.lastModified = desc.javaFile.lastModified();
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

    public ProjectInfo getProjectInfo() {
        return projectInfo;
    }

    private File getCacheFile(ClassDescription desc, ProjectInfo info) {
        return info.getOutputDirectory()
                .resolve("../generated-classes/ameba/enhanced-cache/"
                        .concat(desc.className.replace(".", "/")
                                .concat("_")
                                .concat(getCacheSignature(desc.className))
                                .concat(JavaSource.CLASS_EXTENSION)))
                .normalize().toFile();
    }

    String getCacheSignature(String name) {
        String javaHash = getJavaSourceSignature(name, projectInfo);
        return Hashing.murmur3_32().newHasher()
                .putUnencodedChars(hashSignature)
                .putUnencodedChars(javaHash)
                .hash().toString();
    }

    private class AppClassDesc extends ClassDescription {
        @Override
        public synchronized void refresh() {
            deleteEnhanced();
            enhancedClassFile = getCacheFile(this, projectInfo);
            lastModified = System.currentTimeMillis();
        }

        private void deleteEnhanced() {
            FileUtils.deleteQuietly(enhancedClassFile);
            enhancedByteCode = null;
        }

        @Override
        public void destroy() {
            deleteEnhanced();
            FileUtils.deleteQuietly(javaFile);
            FileUtils.deleteQuietly(classFile);
            byteCodeCache.remove(className);
        }
    }

}
