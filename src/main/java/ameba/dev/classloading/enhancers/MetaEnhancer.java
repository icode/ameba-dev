package ameba.dev.classloading.enhancers;

import ameba.dev.classloading.ClassDescription;
import ameba.meta.Description;
import ameba.meta.Display;
import ameba.meta.Tag;
import ameba.meta.Tags;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.*;
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

    private static final String SPACE = " ";
    private static final String NEWLINE = "\n";
    private static final String COMMA = ",";
    private static final String EMPTY = "";
    private static final String PARANAMER_FIELD_NAME = "__PARANAMER_DATA";
    private static final String PARANAMER_FIELD = "private static final String " + PARANAMER_FIELD_NAME + " = \"By-Ameba-MetaEnhancer-v";

    public MetaEnhancer() {
        super(true);
    }

    @Override
    public void enhance(ClassDescription description) throws Exception {
        if (description.javaFile != null && description.javaFile.isFile() && description.javaFile.exists()) {
            JavaProjectBuilder builder = new JavaProjectBuilder();
            String encoding = (String) getApplication().getProperty("app.encoding");
            if (StringUtils.isBlank(encoding)) encoding = "utf-8";
            builder.setEncoding(encoding);
            builder.addSource(description.javaFile);
            JavaClass javaClass = builder.getClassByName(description.className);
            if (javaClass != null) {
                CtClass ctClass = makeClass(description);
                boolean hasParanamerFiled = false;
                try {
                    ctClass.getField(PARANAMER_FIELD_NAME);
                    hasParanamerFiled = true;
                } catch (Exception e) {
                    // no op
                }
                classMetaGenerate(ctClass, javaClass);
                for (JavaField field : javaClass.getFields()) {
                    if (field.isPublic()) {
                        fieldMetaGenerate(ctClass.getField(field.getName()), field);
                    }
                }

                StringBuilder buffer = new StringBuilder();

                if (!hasParanamerFiled) {
                    for (JavaConstructor constructor : javaClass.getConstructors()) {
                        if (!constructor.isPrivate() && constructor.getParameters().size() > 0) {
                            formatConstructor(buffer, constructor);
                        }
                    }
                }

                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.isPublic()) {
                        methodMetaGenerate(ctClass.getDeclaredMethod(method.getName()), method);
                    } else if (!hasParanamerFiled && !method.isPrivate() && method.getParameters().size() > 0) {
                        formatMethod(buffer, method);
                    }
                }
                if (!hasParanamerFiled) {
                    ctClass.addField(CtField.make(PARANAMER_FIELD
                            + getVersion() + " \\n"
                            + buffer.toString().replace("\n", "\\n")
                            + "\";", ctClass));
                }
                description.enhancedByteCode = ctClass.toBytecode();
                ctClass.defrost();
            }
        }
    }

    private void fieldMetaGenerate(CtField ctField, JavaField javaField) {
        boolean hasDisplay = ctField.hasAnnotation(Display.class);
        boolean hasDescription = ctField.hasAnnotation(Description.class);
        metaGenerate(hasDisplay, hasDescription, ctField, javaField);
    }

    private void methodMetaGenerate(CtMethod ctMethod, JavaMethod javaMethod) {
        boolean hasDisplay = ctMethod.hasAnnotation(Display.class);
        boolean hasDescription = ctMethod.hasAnnotation(Description.class);
        metaGenerate(hasDisplay, hasDescription, ctMethod, javaMethod);
    }

    private void metaGenerate(boolean hasDisplay, boolean hasDescription, Object ct, JavaAnnotatedElement element) {
        if (!hasDisplay || !hasDescription) {
            Meta meta = parseComment(element.getComment());
            if (meta != null) {
                AnnotationsAttribute attribute = null;
                if (ct instanceof CtMethod)
                    attribute = getAnnotations((CtMethod) ct);
                else if (ct instanceof CtClass)
                    attribute = getAnnotations((CtClass) ct);
                else if (ct instanceof CtField)
                    attribute = getAnnotations((CtField) ct);
                meta.docletTags = element.getTags();
                metaGenerate(hasDisplay, hasDescription, attribute, meta);
            }
        }
    }

    private void classMetaGenerate(CtClass ctClass, JavaClass javaClass) {
        boolean hasDisplay = ctClass.hasAnnotation(Display.class);
        boolean hasDescription = ctClass.hasAnnotation(Description.class);
        metaGenerate(hasDisplay, hasDescription, ctClass, javaClass);
    }

    private void metaGenerate(
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

    private Meta parseComment(String comment) {
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

    private void formatMethod(StringBuilder sb, JavaMethod method) {
        String methodName = method.getName();
        List<JavaParameter> parameters = method.getParameters();
        // processClasses line structure:  methodName paramTypes paramNames
        sb.append(methodName).append(SPACE);
        appendParamInfo(sb, methodName, parameters);
    }

    private void formatConstructor(StringBuilder sb, JavaConstructor constructor) {
        String methodName = "<init>";
        List<JavaParameter> parameters = constructor.getParameters();
        appendParamInfo(sb, methodName, parameters);
    }

    private void appendParamInfo(StringBuilder sb, String methodName, List<JavaParameter> parameters) {
        // processClasses line structure:  methodName paramTypes paramNames
        sb.append(methodName).append(SPACE);
        if (parameters.size() > 0) {
            formatParameterTypes(sb, parameters);
            sb.append(SPACE);
            formatParameterNames(sb, parameters);
            sb.append(SPACE);
        }
        sb.append(NEWLINE);
    }

    private void formatParameterNames(StringBuilder sb, List<JavaParameter> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            sb.append(parameters.get(i).getName());
            sb.append(comma(i, parameters.size()));
        }
    }

    private void formatParameterTypes(StringBuilder sb, List<JavaParameter> parameters) {
        for (int i = 0; i < parameters.size(); i++) {

            // This code is a bit dodgy to ensure that both inner classes and arrays shows up correctly.
            // It is based in the Type.toString() method, but using getFullyQualifiedName() instead of getValue().
            JavaType t = parameters.get(i).getType();
            sb.append(t.getFullyQualifiedName())
                    .append(comma(i, parameters.size()));
        }
    }

    private String comma(int index, int size) {
        return (index + 1 < size) ? COMMA : EMPTY;
    }

    private class Meta {
        String display;
        String desc;
        List<DocletTag> docletTags;
    }
}
