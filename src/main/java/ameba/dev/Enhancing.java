package ameba.dev;

import ameba.container.Container;
import ameba.dev.classloading.enhancers.Enhancer;
import ameba.event.Listener;
import ameba.event.SystemEventBus;
import com.google.common.collect.Sets;
import httl.util.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        SystemEventBus.subscribe(Container.BeginReloadEvent.class, new Listener<Container.BeginReloadEvent>() {
            @Override
            public void onReceive(Container.BeginReloadEvent event) {
                ENHANCERS = init();
            }
        });
        return enhancers;
    }

    public static void loadEnhancers(Map<String, Object> properties) {
        for (String key : properties.keySet()) {
            if (key.startsWith("enhancer.")) {
                String value = (String) properties.get(key);

                try {
                    logger.trace("Loading Enhancer [{}({})]", key, value);
                    Class clazz = ClassUtils.forName(value);
                    if (Enhancer.class.isAssignableFrom(clazz)) {
                        ENHANCERS.add((Enhancer) clazz.newInstance());
                    }
                } catch (Exception e) {
                    logger.error("Enhancing class error", e);
                }
            }
        }
    }

    public static Set<Enhancer> getEnhancers() {
        return Collections.unmodifiableSet(ENHANCERS);
    }

}
