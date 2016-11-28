package ameba.dev.classloading.enhancers;

import ameba.dev.classloading.ClassDescription;
import ameba.dev.classloading.EnhancerListener;
import ameba.util.ClassUtils;
import co.paralleluniverse.fibers.instrument.Log;
import co.paralleluniverse.fibers.instrument.LogLevel;
import co.paralleluniverse.fibers.instrument.QuasarInstrumentor;
import org.glassfish.jersey.internal.util.PropertiesHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;

/**
 * @author icode
 */
public class QuasarEnhancer extends Enhancer {
    private static final Logger logger = LoggerFactory.getLogger(EnhancerListener.class);
    private static final String CFG_PREFIX = "quasar.enhancer.";
    private QuasarInstrumentor instrumentor;
    private ClassLoader loader;

    public QuasarEnhancer(Map<String, Object> properties) {
        super(false, properties);
        instrumentor = new QuasarInstrumentor(true);
        System.setProperty("co.paralleluniverse.fibers.verifyInstrumentation", "true");
        instrumentor.setCheck(PropertiesHelper.getValue(properties, CFG_PREFIX + "check", false, null));
        instrumentor.setVerbose(PropertiesHelper.getValue(properties, CFG_PREFIX + "verbose", false, null));
        instrumentor.setDebug(PropertiesHelper.getValue(properties, CFG_PREFIX + "debug", false, null));
        instrumentor.setAllowMonitors(
                PropertiesHelper.getValue(properties, CFG_PREFIX + "allow.monitors", false, null)
        );
        instrumentor.setAllowBlocking(
                PropertiesHelper.getValue(properties, CFG_PREFIX + "allow.blocking", false, null)
        );
        instrumentor.setLog(new Log() {
            @Override
            public void log(LogLevel level, String msg, Object... args) {
                switch (level) {
                    case DEBUG:
                        logger.debug(String.format(msg, args));
                        break;
                    case INFO:
                        logger.info(String.format(msg, args));
                        break;
                    case WARNING:
                        logger.warn(String.format(msg, args));
                        break;
                    default:
                        throw new AssertionError("Unhandled log level: " + level);
                }
            }

            @Override
            public void error(String msg, Throwable ex) {
                logger.error(msg, ex);
            }
        });
        loader = new LoadCacheClassLoader(ClassUtils.getContextClassLoader());
    }

    @Override
    public void enhance(ClassDescription description) throws Exception {
        if (!instrumentor.shouldInstrument(description.className))
            return;
        try (InputStream in = description.getEnhancedByteCodeStream()) {
            final byte[] transformed = instrumentor.instrumentClass(
                    loader,
                    description.className,
                    in
            );
            if (transformed != null)
                description.enhancedByteCode = transformed;
        }
    }
}
