package ameba.dev.classloading;

import ameba.exception.UnexpectedException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * @author icode
 */
public abstract class ClassDescription {
    public String className;
    public File classFile;
    public String classSimpleName;
    public File javaFile;
    public byte[] enhancedByteCode;
    public String signature;
    File enhancedClassFile;
    byte[] classByteCode;
    transient Long lastModified;

    public static boolean isClass(String name) {
        return name != null && !name.endsWith("package-info");
    }

    public File getEnhancedClassFile() {
        return enhancedClassFile;
    }

    public byte[] getClassByteCode() {
        return classByteCode;
    }

    public Long getLastModified() {
        return lastModified;
    }

    public String getClassSimpleName() {
        return classSimpleName;
    }

    public abstract void refresh();

    public InputStream getEnhancedByteCodeStream() {

        if (enhancedByteCode == null) {
            if (classByteCode == null) {
                try {
                    enhancedByteCode = Files.readAllBytes(classFile.toPath());
                } catch (IOException e) {
                    throw new UnexpectedException("Read class byte code error", e);
                }
            }
            enhancedByteCode = classByteCode;
        }
        return new ByteArrayInputStream(enhancedByteCode);
    }
}
