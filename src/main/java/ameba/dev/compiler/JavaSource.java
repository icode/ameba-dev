package ameba.dev.compiler;

import ameba.dev.info.InfoVisitor;
import ameba.dev.info.ProjectInfo;
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

    public static File getJavaFile(String className, File sourceDirectory) {
        if (className.contains("$")) {
            className = className.substring(0, className.indexOf("$"));
        }
        className = className.replace(".", "/").concat(JAVA_EXTENSION);
        if (sourceDirectory != null) {
            File javaFile = new File(sourceDirectory, className);
            if (javaFile.isFile() && javaFile.exists()) {
                return javaFile;
            }
        }
        return null;
    }

    public static FoundInfo findInfoByJavaFile(final String className, ProjectInfo projectInfo) {
        JavaFileVisitor vi = new JavaFileVisitor(className);
        projectInfo.forEach(vi);
        return vi.info;
    }

    public static File getExistsClassFile(String name) {
        URL url = IOUtils.getResource(getClassFileName(name));
        if (url == null) return null;
        File file = new File(url.getFile());
        if (file.isFile() && file.exists()) {
            return file;
        } else {
            return null;
        }
    }

    public static String getClassFilePath(ProjectInfo projectInfo, String className) {
        return projectInfo.getOutputDirectory().resolve(JavaSource.getClassFileName(className)).toString();
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

    private static class JavaFileVisitor implements InfoVisitor<ProjectInfo, Boolean> {
        private FoundInfo info;
        private String className;

        public JavaFileVisitor(String className) {
            this.className = className;
        }

        @Override
        public Boolean visit(ProjectInfo projectInfo) {
            File javaFile = getJavaFile(className, projectInfo.getSourceDirectory().toFile());
            if (javaFile != null) {
                info = new FoundInfo(className);
                info.projectInfo = projectInfo;
                info.javaFile = javaFile;
            }
            return javaFile == null;
        }
    }

    public static class FoundInfo {
        private ProjectInfo projectInfo;
        private String className;
        private File javaFile;
        private File classFile;

        public FoundInfo(String className) {
            this.className = className;
        }

        public ProjectInfo getProjectInfo() {
            return projectInfo;
        }

        public String getClassName() {
            return className;
        }

        public File getJavaFile() {
            return javaFile;
        }

        public File getClassFile() {
            if (classFile == null) {
                classFile = new File(JavaSource.getClassFilePath(projectInfo, className));
            }
            return classFile;
        }
    }
}
