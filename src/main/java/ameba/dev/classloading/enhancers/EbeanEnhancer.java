package ameba.dev.classloading.enhancers;

import ameba.dev.classloading.ClassDescription;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.dev.compiler.JavaSource;
import ameba.util.ClassUtils;
import ameba.util.IOUtils;
import com.avaje.ebean.Ebean;
import com.avaje.ebean.enhance.agent.InputStreamTransform;
import com.avaje.ebean.enhance.agent.Transformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * @author icode
 * @since 14-12-23
 */
public class EbeanEnhancer extends Enhancer {
    private static final Logger logger = LoggerFactory.getLogger(EbeanEnhancer.class);
    private static final int EBEAN_TRANSFORM_LOG_LEVEL = LoggerFactory.getLogger(Ebean.class).isDebugEnabled() ? 9 : 0;
    private static InputStreamTransform streamTransform = null;

    public EbeanEnhancer() {
        super(false);
    }

    private static InputStreamTransform getTransform() {
        if (streamTransform == null) {
            synchronized (EbeanEnhancer.class) {
                if (streamTransform == null) {
                    Transformer transformer = new Transformer("", "debug=" + EBEAN_TRANSFORM_LOG_LEVEL);
                    streamTransform = new InputStreamTransform(transformer,
                            new LoadCacheClassLoader(ClassUtils.getContextClassLoader()));
                }
            }
        }
        return streamTransform;
    }

    @Override
    public void enhance(ClassDescription desc) throws Exception {
        InputStream in = desc.getEnhancedByteCodeStream();
        byte[] result = null;
        try {
            result = getTransform().transform(desc.getClassSimpleName(), in);
            if (result != null)
                desc.enhancedByteCode = result;
        } finally {
            IOUtils.closeQuietly(in);
        }
        if (result == null) {
            logger.trace("{} class not change.", desc.className);
        }
    }

    private static class LoadCacheClassLoader extends ClassLoader {
        public LoadCacheClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public URL getResource(String name) {

            if (name != null && name.endsWith(JavaSource.CLASS_EXTENSION)) {
                String className = name.replace("/", ".").substring(0, name.length() - JavaSource.CLASS_EXTENSION.length());

                ClassDescription desc = ((ReloadClassLoader) getParent()).getClassCache().get(className);

                if (desc != null && desc.getEnhancedClassFile().exists()) {
                    try {
                        return desc.getEnhancedClassFile().toURI().toURL();
                    } catch (MalformedURLException e) {
                        //no op
                    }
                }
            }

            return super.getResource(name);
        }
    }
}
