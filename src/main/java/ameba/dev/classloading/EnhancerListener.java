package ameba.dev.classloading;

import ameba.dev.classloading.enhancers.Enhancer;
import ameba.dev.classloading.enhancers.EnhancingException;
import ameba.event.Listener;
import javassist.ClassPool;
import javassist.CtClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * @author icode
 * @since 14-12-24
 */
public class EnhancerListener implements Listener<EnhanceClassEvent> {
    private static final Logger logger = LoggerFactory.getLogger(EnhancerListener.class);
    private static final
    String sp = "------------------------------------------------------------------------------------------------";

    @Override
    public void onReceive(EnhanceClassEvent event) {
        ClassDescription desc = event.getClassDescription();
        if (desc == null) return;
        ClassPool classPool = Enhancer.getClassPool();
        CtClass clazz;
        try {
            clazz = classPool.makeClass(desc.getEnhancedByteCodeStream());
        } catch (IOException e) {
            throw new EnhancingException(e);
        }
        if (clazz.isInterface()
                || clazz.getName().endsWith(".package")
                || clazz.isEnum()
                || clazz.isFrozen()
                || clazz.isPrimitive()
                || clazz.isAnnotation()
                || clazz.isArray()) {
            return;
        }
        logger.trace(sp);
        for (Enhancer enhancer : Enhancer.getEnhancers()) {
            enhance(enhancer, desc);
        }
        logger.trace(sp);
    }

    private void enhance(Enhancer enhancer, ClassDescription desc) {
        try {
            long start = System.currentTimeMillis();
            enhancer.enhance(desc);
            logger.trace("{}ms to apply {}[version: {}] to {}", System.currentTimeMillis() - start,
                    enhancer.getClass().getSimpleName(), enhancer.getVersion(), desc.className);
        } catch (Exception e) {
            throw new EnhancingException("While applying " + enhancer + " on " + desc.className, e);
        }
    }
}
