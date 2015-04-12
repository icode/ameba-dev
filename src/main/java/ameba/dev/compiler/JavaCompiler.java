package ameba.dev.compiler;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public abstract class JavaCompiler {
    protected ClassLoader classLoader;

    public static JavaCompiler create(ClassLoader classloader, Config config) {
        try {
            JavaCompiler jc = config.getCompiler();
            jc.classLoader = classloader;
            jc.initialize();
            return jc;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void initialize() {
    }

    public Set<JavaSource> compile(JavaSource... sources) {
        return compile(Arrays.asList(sources));
    }

    public Set<JavaSource> compile(List<JavaSource> sources) {
        return compile(sources, false);
    }

    public Set<JavaSource> compile(List<JavaSource> sources, boolean isSave) {
        try {
            Set<JavaSource> result = generateJavaClass(sources);

            if (isSave) {
                for (JavaSource source : sources) {
                    source.saveClassFile();
                }
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Set<JavaSource> generateJavaClass(JavaSource... source) {
        return generateJavaClass(Arrays.asList(source));
    }

    public abstract Set<JavaSource> generateJavaClass(List<JavaSource> sources);
}
