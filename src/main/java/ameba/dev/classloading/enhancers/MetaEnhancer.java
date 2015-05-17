package ameba.dev.classloading.enhancers;

import ameba.dev.classloading.ClassDescription;
import ameba.meta.Description;
import ameba.meta.Display;
import com.google.common.collect.Maps;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaField;
import com.thoughtworks.qdox.model.JavaMethod;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.MemberValue;
import javassist.bytecode.annotation.StringMemberValue;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 转换注释为meta信息
 *
 * @author icode
 * @since 15-1-8
 */
public class MetaEnhancer extends Enhancer {


    public MetaEnhancer() {
        super(true);
    }

    @Override
    public void enhance(ClassDescription description) throws Exception {
        if (description.javaFile != null && description.javaFile.isFile() && description.javaFile.exists()) {
            JavaProjectBuilder builder = new JavaProjectBuilder();
            builder.addSource(description.javaFile);
            JavaClass javaClass = builder.getClassByName(description.className);
            if (javaClass != null) {
                CtClass ctClass = makeClass(description);
                classMetaGenerate(ctClass, javaClass);
                for (JavaField field : javaClass.getFields()) {
                    fieldMetaGenerate(ctClass.getField(field.getName()), field);
                }

                for (JavaMethod method : javaClass.getMethods()) {
                    methodMetaGenerate(ctClass.getDeclaredMethod(method.getName()), method);
                }

                description.enhancedByteCode = ctClass.toBytecode();
                ctClass.defrost();
            }
        }
    }

    void fieldMetaGenerate(CtField ctField, JavaField javaField) {
        boolean hasDisplay = ctField.hasAnnotation(Display.class);
        boolean hasDescription = ctField.hasAnnotation(Description.class);
        if (!hasDisplay || !hasDescription) {
            Meta meta = parseComment(javaField.getComment());
            if (meta != null) {
                AnnotationsAttribute attribute = getAnnotations(ctField);
                ConstPool cp = ctField.getFieldInfo().getConstPool();

                metaGenerate(hasDisplay, hasDescription, cp, attribute, meta);
            }
        }
    }

    void methodMetaGenerate(CtMethod ctMethod, JavaMethod javaMethod) {
        boolean hasDisplay = ctMethod.hasAnnotation(Display.class);
        boolean hasDescription = ctMethod.hasAnnotation(Description.class);
        if (!hasDisplay || !hasDescription) {
            Meta meta = parseComment(javaMethod.getComment());
            if (meta != null) {
                AnnotationsAttribute attribute = getAnnotations(ctMethod);
                ConstPool cp = ctMethod.getMethodInfo().getConstPool();

                metaGenerate(hasDisplay, hasDescription, cp, attribute, meta);
            }
        }
    }

    void metaGenerate(
            boolean hasDisplay,
            boolean hasDescription,
            ConstPool cp,
            AnnotationsAttribute attribute,
            Meta meta) {

        Map<String, MemberValue> valueMap = Maps.newHashMap();
        if (!hasDisplay && meta.display != null) {
            valueMap.put("value", new StringMemberValue(meta.display, cp));
            createAnnotation(attribute, Display.class, valueMap);
        }
        if (!hasDescription && meta.desc != null) {
            try {
                valueMap.put("value", new StringMemberValue(meta.desc, cp));
                createAnnotation(attribute, Description.class, valueMap);
            } catch (Exception e) {
                //no op
            }
        }
    }

    void classMetaGenerate(CtClass ctClass, JavaClass javaClass) {
        boolean hasDisplay = ctClass.hasAnnotation(Display.class);
        boolean hasDescription = ctClass.hasAnnotation(Description.class);
        if (!hasDisplay || !hasDescription) {
            Meta meta = parseComment(javaClass.getComment());
            if (meta != null) {
                AnnotationsAttribute attribute = getAnnotations(ctClass);
                ConstPool cp = ctClass.getClassFile().getConstPool();

                metaGenerate(hasDisplay, hasDescription, cp, attribute, meta);
            }
        }
    }

    Meta parseComment(String comment) {
        Meta meta = null;
        if (StringUtils.isNotBlank(comment)) {
            String[] comments = StringUtils.split(comment, "\n", 3);
            meta = new Meta();
            meta.display = comments[0];
            try {
                int index = comments.length == 3 ? 2 : 1;
                meta.desc = comments[index];
            } catch (Exception e) {
                //no op
            }
        }
        return meta;
    }

    private class Meta {
        String display;
        String desc;
    }
}
