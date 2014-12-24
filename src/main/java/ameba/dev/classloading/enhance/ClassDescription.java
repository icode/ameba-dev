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

    String className;
    String classFile;
    String classSimpleName;
    byte[] classBytecode;

    public byte[] getClassByteCode() {
        return classBytecode;
    }

    public String getClassName() {
        return className;
    }

    public String getClassFile() {
        return classFile;
    }

    public String getClassSimpleName() {
        return classSimpleName;
    }

    public InputStream getClassByteCodeStream() {
        InputStream in = null;
        if (getClassByteCode() != null) {
            in = new ByteArrayInputStream(getClassByteCode());
        } else {
            try {
                in = new URL(getClassFile()).openStream();
            } catch (IOException e) {
                logger.warn("read class file stream error", e);
            }
        }
        return in;
    }

}
