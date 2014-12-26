package ameba.dev.classloading.enhance;

import ameba.util.ClassUtils;
import ameba.util.IOUtils;
import com.avaje.ebean.Ebean;
import com.avaje.ebean.enhance.agent.InputStreamTransform;
import com.avaje.ebean.enhance.agent.Transformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * @author icode
 * @since 14-12-23
 */
public class EbeanEnhancer extends Enhancer {
    private static final Logger logger = LoggerFactory.getLogger(EbeanEnhancer.class);
    private static final int EBEAN_TRANSFORM_LOG_LEVEL = LoggerFactory.getLogger(Ebean.class).isDebugEnabled() ? 9 : 0;

    public EbeanEnhancer() {
        super(false);
    }

    @Override
    public void enhance(ClassDescription desc) throws Exception {
        Transformer transformer = new Transformer("", "debug=" + EBEAN_TRANSFORM_LOG_LEVEL);
        InputStreamTransform streamTransform = new InputStreamTransform(transformer, ClassUtils.getContextClassLoader());
        InputStream in = desc.getClassByteCodeStream();
        byte[] result = null;
        try {
            result = streamTransform.transform(desc.getClassSimpleName(), in);
            if (result != null)
                desc.classBytecode = result;
        } finally {
            IOUtils.closeQuietly(in);
        }
        if (result == null) {
            logger.debug("{} class not change.", desc.className);
        }
    }
}
