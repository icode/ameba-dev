package ameba.dev;

import ameba.core.Application;
import ameba.feature.AmebaFeature;
import org.glassfish.jersey.filter.LoggingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.FeatureContext;

/**
 * @author icode
 */

public class DevFeature extends AmebaFeature {

    private static final Logger logger = LoggerFactory.getLogger(DevFeature.class);

    @Inject
    private Application app;

    @Override
    public boolean configure(FeatureContext context) {
        if (app.getMode().isDev()) {
            logger.info("注册热加载过滤器");
            subscribeEvent(Application.RequestEvent.class, ReloadRequestListener.class);
            if (!context.getConfiguration().isRegistered(LoggingFilter.class)) {
                logger.debug("注册日志过滤器");
                context.register(LoggingFilter.class);
            }
        }
        return true;
    }
}
