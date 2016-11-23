package ameba.dev;

import ameba.container.event.BeginReloadEvent;
import ameba.dev.classloading.enhancers.Enhancer;
import ameba.event.SystemEventBus;
import ameba.i18n.Messages;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * @author icode
 */
public class Enhancing {

    private static final Logger logger = LoggerFactory.getLogger(Enhancing.class);
    private static Set<Enhancer> ENHANCERS = init();

    private static Set<Enhancer> init() {
        Set<Enhancer> enhancers = Sets.newLinkedHashSet();
        SystemEventBus.subscribe(BeginReloadEvent.class, event -> ENHANCERS = init());
        return enhancers;
    }

    @SuppressWarnings("unchecked")
    public static void loadEnhancers(Map<String, Object> properties) {
        properties.keySet().stream().filter(key -> key.startsWith("enhancer.")).forEachOrdered(key -> {
            String value = (String) properties.get(key);

            try {
                logger.debug(Messages.get("dev.loading.enhancer", key, value));
                Class clazz = Class.forName(value);
                if (Enhancer.class.isAssignableFrom(clazz)) {
                    try {
                        Constructor<Enhancer> enhancerConstructor = clazz.<Enhancer>getConstructor(Map.class);
                        ENHANCERS.add(enhancerConstructor.newInstance(properties));
                    } catch (NoSuchMethodException e) {
                        logger.error(
                                Messages.get("dev.enhancer.constructor.error",
                                        "`Enhancer(Map<String, Object> properties)`"
                                ), e);
                    }
                }
            } catch (Exception e) {
                logger.error(Messages.get("dev.loading.enhancer.error"), e);
            }
        });
    }

    public static Set<Enhancer> getEnhancers() {
        return Collections.unmodifiableSet(ENHANCERS);
    }

}
