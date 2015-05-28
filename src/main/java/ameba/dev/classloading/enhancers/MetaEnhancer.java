package ameba.dev.classloading.enhancers;

import ameba.dev.classloading.ClassDescription;
import ameba.meta.Description;
import ameba.meta.Display;
import ameba.meta.Tag;
import ameba.meta.Tags;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.DocletTag;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaField;
import com.thoughtworks.qdox.model.JavaMethod;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
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
                    if (field.isPublic()) {
                        fieldMetaGenerate(ctClass.getField(field.getName()), field);
                    }
                }

                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.isPublic()) {
                        methodMetaGenerate(ctClass.getDeclaredMethod(method.getName()), method);
                    }
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
                meta.docletTags = javaField.getTags();
                metaGenerate(hasDisplay, hasDescription, attribute, meta);
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
                meta.docletTags = javaMethod.getTags();
                metaGenerate(hasDisplay, hasDescription, attribute, meta);
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
                meta.docletTags = javaClass.getTags();
                metaGenerate(hasDisplay, hasDescription, attribute, meta);
            }
        }
    }

    void metaGenerate(
            boolean hasDisplay,
            boolean hasDescription,
            AnnotationsAttribute attribute,
            Meta meta) {

        Map<String, MemberValue> valueMap = Maps.newHashMap();
        ConstPool cp = attribute.getConstPool();
        if (!hasDisplay && meta.display != null) {
            valueMap.put("value", new StringMemberValue(meta.display, cp));
            addAnnotation(attribute, Display.class, valueMap);
        }
        if (!hasDescription && meta.desc != null) {
            try {
                valueMap.put("value", new StringMemberValue(meta.desc, cp));
                addAnnotation(attribute, Description.class, valueMap);
            } catch (Exception e) {
                //no op
            }
        }
        if (CollectionUtils.isNotEmpty(meta.docletTags)) {

            ArrayMemberValue memberValue = new ArrayMemberValue(cp);

            List<MemberValue> valueList = Lists.newArrayList();

            for (DocletTag tag : meta.docletTags) {
                try {
                    Annotation annotation = new Annotation(Tag.class.getName(), cp);
                    annotation.addMemberValue("name", new StringMemberValue(tag.getName(), cp));
                    annotation.addMemberValue("value", new StringMemberValue(tag.getValue(), cp));
                    valueList.add(new AnnotationMemberValue(annotation, cp));
                } catch (Exception e) {
                    // no op
                }
            }

            memberValue.setValue(valueList.toArray(new MemberValue[valueList.size()]));
            valueMap.put("value", memberValue);
            addAnnotation(attribute, Tags.class, valueMap);
        }
    }

    Meta parseComment(String comment) {
        Meta meta = null;
        if (StringUtils.isNotBlank(comment)) {
            String[] comments = StringUtils.split(StringUtils.remove(comment, "\r"), "\n", 3);
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
        List<DocletTag> docletTags;
    }
}
