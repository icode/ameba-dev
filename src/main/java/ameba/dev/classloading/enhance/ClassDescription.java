package ameba.dev.classloading.enhance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * @author icode
 */
public class ClassDescription {
    private static Logger logger = LoggerFactory.getLogger(ClassDescription.class);

    public String className;
    public String classFile;
    public String classSimpleName;
    public byte[] classBytecode;
    private InputStream in = null;

    public static boolean isClass(String name) {
        return name != null && !name.endsWith("package-info");
    }

    public String getClassSimpleName() {
        return classSimpleName;
    }

    public InputStream getClassByteCodeStream() {
        if (classBytecode != null) {
            in = new ByteArrayInputStream(classBytecode);
        } else {
            try {
                in = new URL(classFile).openStream();
            } catch (IOException e) {
                logger.warn("read class file stream error", e);
            }
        }
        return in;
    }
}
