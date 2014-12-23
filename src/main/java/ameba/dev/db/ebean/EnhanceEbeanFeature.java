package ameba.dev.db.ebean;

import ameba.db.DataSourceFeature;
import ameba.db.ebean.EbeanFeature;
import ameba.enhancer.model.EnhanceModelFeature;
import ameba.enhancer.model.ModelDescription;
import ameba.enhancer.model.ModelManager;
import ameba.feature.AmebaFeature;
import ameba.util.IOUtils;
import com.avaje.ebean.Ebean;
import com.avaje.ebean.enhance.agent.InputStreamTransform;
import com.avaje.ebean.enhance.agent.Transformer;
import javassist.CannotCompileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.FeatureContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * @author icode
 * @since 14-12-23
 */
public class EnhanceEbeanFeature extends AmebaFeature {
    private static final Logger logger = LoggerFactory.getLogger(EnhanceEbeanFeature.class);
    private static final int EBEAN_TRANSFORM_LOG_LEVEL = LoggerFactory.getLogger(Ebean.class).isDebugEnabled() ? 9 : 0;

    private static byte[] ehModel(ModelDescription desc) throws URISyntaxException, IOException, IllegalClassFormatException,
            ClassNotFoundException, NoSuchMethodException, IllegalAccessException,
            InvocationTargetException, CannotCompileException {
        Transformer transformer = new Transformer("", "debug=" + EBEAN_TRANSFORM_LOG_LEVEL);
        InputStreamTransform streamTransform = new InputStreamTransform(transformer, Ebean.class.getClassLoader());
        InputStream in;
        if (desc.getClassByteCode() != null) {
            in = new ByteArrayInputStream(desc.getClassByteCode());
        } else {
            in = new URL(desc.getClassFile()).openStream();
        }
        byte[] result = null;
        try {
            result = streamTransform.transform(desc.getClassSimpleName(), in);
        } finally {
            IOUtils.closeQuietly(in);
        }
        if (result == null) {
            logger.debug("{} class not entity.", desc.getClassName());
            result = desc.getClassByteCode();
        }
        return result;
    }

    @Override
    public boolean configure(FeatureContext context) {
        for (final String name : DataSourceFeature.getDataSourceNames()) {
            ModelEventListener listener = new ModelEventListener();
            listener.bindManager(ModelManager.getManager(name));
            if (name.equals(EbeanFeature.getDefaultDBName())) {
                listener.bindManager(EnhanceModelFeature.getModulesModelManager());
            }
        }
        return true;
    }

    private static class ModelEventListener extends ModelManager.ModelEventListener {

        @Override
        protected byte[] enhancing(ModelDescription desc) {
            try {
                return ehModel(desc);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
