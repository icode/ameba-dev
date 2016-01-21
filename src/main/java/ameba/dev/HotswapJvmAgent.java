package ameba.dev;

import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.URI;

/**
 * @author icode
 */
public class HotswapJvmAgent {
    public static boolean enabled = false;
    static Instrumentation instrumentation;

    //jvm特性
    public static void agentmain(String agentArgs, Instrumentation instrumentation) throws Exception {
        HotswapJvmAgent.instrumentation = instrumentation;
        enabled = true;
    }

    public static synchronized void initialize() {
        if (instrumentation == null) {
            String file = HotswapJvmAgent.class.getResource("").getPath();
            file = file.substring(0, file.lastIndexOf("!"));
            file = new File(URI.create(file)).getPath();
            AgentLoader.loadAgent(file);
        }
    }

    public static void reload(ClassDefinition... definitions) throws UnmodifiableClassException, ClassNotFoundException {
        if (enabled) {
            instrumentation.redefineClasses(definitions);
        } else {
            throw new UnmodifiableClassException();
        }
    }
}
