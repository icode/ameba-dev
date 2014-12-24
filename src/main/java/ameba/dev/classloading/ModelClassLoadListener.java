package ameba.dev.classloading;

import ameba.db.model.Model;
import ameba.dev.classloading.enhance.*;
import ameba.event.Listener;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.Entity;
import java.io.IOException;

/**
 * @author icode
 * @since 14-12-24
 */
public class ModelClassLoadListener implements Listener<ClassLoadEvent> {
    private static final Logger logger = LoggerFactory.getLogger(ModelClassLoadListener.class);

    @Override
    public void onReceive(ClassLoadEvent event) {
        Class<?>[] enhancers = new Class[]{
                ModelEnhancer.class,
                EbeanEnhancer.class
        };

        ClassDescription desc = event.getClassDescription();
        if (desc == null) return;
        ClassPool classPool = ClassPool.getDefault();
        for (Class<?> enhancer : enhancers) {
            CtClass clazz;
            try {
                clazz = classPool.makeClass(desc.getClassByteCodeStream());
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
                continue;
            }
            boolean modelSub;
            try {
                modelSub = clazz.subclassOf(classPool.getCtClass(Model.class.getName()));
            } catch (NotFoundException e) {
                throw new EnhancingException(e);
            }
            boolean hasAnnon = clazz.hasAnnotation(Entity.class);
            if (!hasAnnon && !modelSub) {
                continue;
            }
            try {
                long start = System.currentTimeMillis();
                ((Enhancer) enhancer.newInstance()).enhance(desc);
                logger.trace("{}ms to apply {} to {}", System.currentTimeMillis() - start, enhancer.getSimpleName(), desc.className);
            } catch (Exception e) {
                throw new EnhancingException("While applying " + enhancer + " on " + desc.className, e);
            }
        }
    }
}
