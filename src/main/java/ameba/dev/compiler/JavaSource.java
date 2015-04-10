package ameba.dev.compiler;

import ameba.core.Application;
import ameba.util.IOUtils;

import java.io.*;
import java.net.URL;

public class JavaSource {
    public static final String CLASS_EXTENSION = ".class";
    public static final String JAVA_EXTENSION = ".java";

    public static final String JAVA_FILE_ENCODING = "utf-8";
    private final String qualifiedClassName;
    private final File outputDir;
    private final File javaFile;
    private final File classFile;
    private final File inputDir;
    private String sourceCode;
    private byte[] byteCode;

    public JavaSource(String qualifiedClassName, File inputDir, File outputDir) {
        this.qualifiedClassName = qualifiedClassName;
        this.outputDir = outputDir;
        this.inputDir = inputDir;
        String fileName = qualifiedClassName.replace(".", "/");
        this.javaFile = new File(inputDir, fileName + JAVA_EXTENSION);
        this.classFile = new File(outputDir, fileName + CLASS_EXTENSION);
    }

    public JavaSource(String qualifiedClassName, String sourceCode) {
        this(qualifiedClassName, null, new File(IOUtils.getResource("").getFile()));
    }

    public static File getJavaFile(String name, Application app) {
        return getJavaFile(name, app.getPackageRoot());
    }

    public static File getJavaFile(String name, File pkgRoot) {
        String fileName = name;
        if (fileName.contains("$")) {
            fileName = fileName.substring(0, fileName.indexOf("$"));
        }
        fileName = fileName.replace(".", "/").concat(JAVA_EXTENSION);
        if (pkgRoot != null) {
            File javaFile = new File(pkgRoot, fileName);
            if (javaFile.exists()) {
                return javaFile;
            }
        }
        return null;
    }

    public static String getClassSimpleName(String className) {
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

    public static File getClassFile(String name) {
        URL url = IOUtils.getResource(getClassFileName(name));
        if (url == null) return null;
        File file = new File(url.getFile());
        if (file.exists()) {
            return file;
        } else {
            return null;
        }
    }

    public static String getClassFileName(String qualifiedClassName) {
        return qualifiedClassName.replace(".", "/").concat(CLASS_EXTENSION);
    }

    public File getInputDir() {
        return inputDir;
    }

    public byte[] getByteCode() {
        return byteCode;
    }

    public void setByteCode(byte[] byteCode) {
        this.byteCode = byteCode;
    }

    public void clean() {
        if (javaFile.exists()) {
            javaFile.delete();
        }
        if (classFile.exists()) {
            classFile.delete();
        }
    }

    public void saveJavaFile() throws IOException {
        javaFile.getParentFile().mkdirs();

        OutputStream out = new FileOutputStream(javaFile);
        try {
            out.write(sourceCode.getBytes(JAVA_FILE_ENCODING));
        } finally {
            IOUtils.closeQuietly(out);
        }
    }

    public void saveClassFile() throws IOException {
        classFile.getParentFile().mkdirs();

        OutputStream out = new FileOutputStream(classFile);
        try {
            out.write(byteCode);
        } finally {
            IOUtils.closeQuietly(out);
        }
    }

    public String getClassName() {
        return qualifiedClassName;
    }

    public String getSourceCode() {
        if (sourceCode == null) {
            synchronized (this) {
                InputStream in = null;
                try {
                    in = new FileInputStream(getJavaFile());
                    sourceCode = IOUtils.read(in);
                } catch (FileNotFoundException e) {
                    IOUtils.closeQuietly(in);
                }
            }
        }
        return sourceCode;
    }

    public File getOutputDir() {
        return outputDir;
    }

    public File getJavaFile() {
        return javaFile;
    }

    public File getClassFile() {
        return classFile;
    }
}
