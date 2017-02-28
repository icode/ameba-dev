package ameba.dev.classloading.enhancers;

import ameba.dev.classloading.ClassDescription;
import ameba.meta.Description;
import ameba.meta.Display;
import ameba.util.ClassUtils;
import com.google.common.collect.Maps;
import io.ebean.Ebean;
import io.ebean.annotation.DbComment;
import io.ebean.enhance.Transformer;
import io.ebean.enhance.common.InputStreamTransform;
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
import java.util.Map;

/**
 * @author icode
 * @since 14-12-23
 */
public class EbeanEnhancer extends Enhancer {
    private static final Logger logger = LoggerFactory.getLogger(EbeanEnhancer.class);
    private static final int EBEAN_TRANSFORM_LOG_LEVEL = LoggerFactory.getLogger(Ebean.class).isDebugEnabled() ? 9 : 0;
    private InputStreamTransform transformer;

    public EbeanEnhancer(Map<String, Object> properties) {
        super(true, properties);
        String logLevel = (String) getProperty("ebean.enhancer.log.level");
        int level = EBEAN_TRANSFORM_LOG_LEVEL;
        if (StringUtils.isNotBlank(logLevel)) {
            level = Integer.parseInt(logLevel);
        }
        ClassLoader classLoader =
                new LoadCacheClassLoader(ClassUtils.getContextClassLoader());
        Transformer transformer = new Transformer(classLoader, "debug=" + level);
        this.transformer = new InputStreamTransform(transformer, classLoader);
    }

    @Override
    public void enhance(ClassDescription desc) throws Exception {
        byte[] result;
        try (InputStream in = desc.getEnhancedByteCodeStream()) {
            result = transformer.transform(desc.className, in);
        }
        if (result != null)
            desc.enhancedByteCode = result;
        else
            logger.trace("{} class not change.", desc.className);
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
}
