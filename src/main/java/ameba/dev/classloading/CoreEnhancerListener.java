package ameba.dev.classloading;

import ameba.dev.classloading.enhance.*;
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
public class CoreEnhancerListener implements Listener<EnhanceClassEvent> {
    private static final Logger logger = LoggerFactory.getLogger(CoreEnhancerListener.class);

    @Override
    public void onReceive(EnhanceClassEvent event) {
        Class<?>[] enhancers = new Class[]{
                ModelEnhancer.class,
                FieldAccessEnhancer.class,
                EbeanEnhancer.class,
                FieldAccessEnhancer.class
        };

        ClassDescription desc = event.getClassDescription();
        if (desc == null) return;
        ClassPool classPool = ClassPool.getDefault();
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
        for (Class<?> enhancer : enhancers) {
            enhance(enhancer, desc);
        }
    }

    private void enhance(Class enhancer, ClassDescription desc) {
        try {
            long start = System.currentTimeMillis();
            ((Enhancer) enhancer.newInstance()).enhance(desc);
            logger.trace("{}ms to apply {} to {}", System.currentTimeMillis() - start, enhancer.getSimpleName(), desc.className);
        } catch (Exception e) {
            throw new EnhancingException("While applying " + enhancer + " on " + desc.className, e);
        }
    }
}
