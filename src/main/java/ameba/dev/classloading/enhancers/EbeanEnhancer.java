package ameba.dev.classloading.enhancers;

import ameba.dev.classloading.ClassDescription;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.dev.compiler.JavaSource;
import ameba.meta.Description;
import ameba.meta.Display;
import ameba.util.ClassUtils;
import ameba.util.IOUtils;
import com.avaje.ebean.Ebean;
import com.avaje.ebean.annotation.DbComment;
import com.avaje.ebean.enhance.agent.InputStreamTransform;
import com.avaje.ebean.enhance.agent.Transformer;
import com.google.common.collect.Maps;
import javassist.CtClass;
import javassist.CtField;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.Annotation;
import javassist.bytecode.annotation.MemberValue;
import javassist.bytecode.annotation.StringMemberValue;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.Entity;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

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
        CtClass ctClass = makeClass(desc);
        if (ctClass.hasAnnotation(Entity.class)) {
            AnnotationsAttribute classAttribute = getAnnotations(ctClass);
            addDbCommentAnnotation(classAttribute);

            for (CtField field : ctClass.getFields()) {
                AnnotationsAttribute attribute = getAnnotations(field);
                addDbCommentAnnotation(attribute);
            }

            desc.enhancedByteCode = ctClass.toBytecode();
        }
        ctClass.defrost();
    }

    private void addDbCommentAnnotation(AnnotationsAttribute attribute) {
        if (attribute.getAnnotation(DbComment.class.getName()) != null) return;

        StringBuilder builder = new StringBuilder();
        String display = appendDbCommentValue(attribute, Display.class);
        if (StringUtils.isNotBlank(display))
            builder.append(display);
        String desc = appendDbCommentValue(attribute, Description.class);
        if (StringUtils.isNotBlank(desc))
            builder.append("\r\n").append(desc);

        if (builder.length() > 0) {
            Map<String, MemberValue> valueMap = Maps.newHashMap();
            ConstPool cp = attribute.getConstPool();
            valueMap.put("value", new StringMemberValue(builder.toString().replace("'", "''"), cp));
            addAnnotation(attribute, DbComment.class, valueMap);
        }
    }

    private String appendDbCommentValue(AnnotationsAttribute attribute,
                                        Class<? extends java.lang.annotation.Annotation> annotationClass) {
        Annotation annotation = attribute.getAnnotation(annotationClass.getName());
        if (annotation != null) {
            StringMemberValue memberValue = (StringMemberValue) annotation.getMemberValue("value");
            if (memberValue != null) {
                return memberValue.getValue();
            }
        }
        return null;
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
