package ameba.dev.classloading.enhancers;

import ameba.dev.classloading.ClassDescription;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.dev.classloading.ReloadClassPath;
import ameba.util.ClassUtils;
import ameba.util.IOUtils;
import com.google.common.collect.Sets;
import javassist.*;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.FieldInfo;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.annotation.MemberValue;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author icode
 */
public abstract class Enhancer {
    public static CtClass objectType;
    private static ClassPool classPool = null;

    static {
        try {
            objectType = ClassPool.getDefault().get(Object.class.getName());
        } catch (NotFoundException e) {
            //
        }
    }

    protected String version = null;
    protected Map<String, Object> properties;

    public Enhancer(Map<String, Object> properties) {
        this(false, properties);
    }

    protected Enhancer(boolean initClassPool, Map<String, Object> properties) {
        if (initClassPool && classPool == null)
            classPool = newClassPool();
        this.properties = properties;
    }

    public static ClassPool newClassPool() {
        ClassPool classPool = new ClassPool();
        ClassLoader cl = ClassUtils.getContextClassLoader();
        classPool.insertClassPath(new ReloadClassPath(cl instanceof ReloadClassLoader ? cl.getParent() : cl));
        classPool.appendSystemPath();
        return classPool;
    }

    public static ClassPool getClassPool() {
        if (classPool == null) {
            synchronized (Enhancer.class) {
                if (classPool == null)
                    classPool = newClassPool();
            }
        }
        return classPool;
    }

    /**
     * Create a new annotation to be dynamically inserted in the byte code.
     *
     * @param attribute      attribute
     * @param annotationType annotation type
     * @param members        members
     */
    protected static void addAnnotation(AnnotationsAttribute attribute, String annotationType, Map<String, MemberValue> members) {
        javassist.bytecode.annotation.Annotation annotation = new javassist.bytecode.annotation.Annotation(annotationType, attribute.getConstPool());
        for (Map.Entry<String, MemberValue> member : members.entrySet()) {
            annotation.addMemberValue(member.getKey(), member.getValue());
        }
        attribute.addAnnotation(annotation);
    }

    protected static void addAnnotation(AnnotationsAttribute attribute, Class annotationType, Map<String, MemberValue> members) {
        addAnnotation(attribute, annotationType.getName(), members);
    }

    /**
     * Create a new annotation to be dynamically inserted in the byte code.
     *
     * @param attribute      attribute
     * @param annotationType annotation type
     */
    protected static void addAnnotation(AnnotationsAttribute attribute, Class<? extends Annotation> annotationType) {
        addAnnotation(attribute, annotationType.getName(), new HashMap<String, MemberValue>());
    }

    protected static void addAnnotation(AnnotationsAttribute attribute, String annotationType) {
        addAnnotation(attribute, annotationType, new HashMap<String, MemberValue>());
    }

    /**
     * Retrieve all class annotations.
     *
     * @param ctClass ctClass
     * @return AnnotationsAttribute
     */
    protected static AnnotationsAttribute getAnnotations(CtClass ctClass) {
        ClassFile classFile = ctClass.getClassFile();
        AnnotationsAttribute annotationsAttribute = (AnnotationsAttribute) classFile.getAttribute(AnnotationsAttribute.visibleTag);
        if (annotationsAttribute == null) {
            annotationsAttribute = new AnnotationsAttribute(classFile.getConstPool(), AnnotationsAttribute.visibleTag);
            ctClass.getClassFile().addAttribute(annotationsAttribute);
        }
        return annotationsAttribute;
    }

    /**
     * Retrieve all field annotations.
     *
     * @param ctField ctField
     * @return AnnotationsAttribute
     */
    protected static AnnotationsAttribute getAnnotations(CtField ctField) {
        FieldInfo fieldInfo = ctField.getFieldInfo();
        AnnotationsAttribute annotationsAttribute = (AnnotationsAttribute) fieldInfo.getAttribute(AnnotationsAttribute.visibleTag);
        if (annotationsAttribute == null) {
            annotationsAttribute = new AnnotationsAttribute(fieldInfo.getConstPool(), AnnotationsAttribute.visibleTag);
            ctField.getFieldInfo().addAttribute(annotationsAttribute);
        }
        return annotationsAttribute;
    }

    /**
     * Retrieve all method annotations.
     *
     * @param ctMethod ctMethod
     * @return AnnotationsAttribute
     */
    protected static AnnotationsAttribute getAnnotations(CtMethod ctMethod) {
        MethodInfo methodInfo = ctMethod.getMethodInfo();
        AnnotationsAttribute annotationsAttribute = (AnnotationsAttribute) methodInfo.getAttribute(AnnotationsAttribute.visibleTag);
        if (annotationsAttribute == null) {
            annotationsAttribute = new AnnotationsAttribute(methodInfo.getConstPool(), AnnotationsAttribute.visibleTag);
            ctMethod.getMethodInfo().addAttribute(annotationsAttribute);
        }
        return annotationsAttribute;
    }

    public String getVersion() {
        if (version == null) {
            version = IOUtils.getJarImplVersion(getClass());
        }
        return version;
    }

    public abstract void enhance(ClassDescription description) throws Exception;

    protected boolean isFinal(CtField ctField) {
        return Modifier.isFinal(ctField.getModifiers());
    }

    protected boolean isStatic(CtField ctField) {
        return Modifier.isStatic(ctField.getModifiers());
    }

    protected Set<CtField> getAllDeclaredFields(CtClass ctClass) {
        Set<CtField> fields = Sets.newLinkedHashSet();
        Collections.addAll(fields, ctClass.getDeclaredFields());
        CtClass superClass = null;
        try {
            superClass = ctClass.getSuperclass();
        } catch (NotFoundException e) {
            //no op
        }

        if (superClass != null && !superClass.getName().equals(Object.class.getName())) {
            fields.addAll(getAllDeclaredFields(superClass));
        }

        return fields;
    }

    /**
     * Test if a class has the provided annotation
     *
     * @param ctClass    the javassist class representation
     * @param annotation fully qualified name of the annotation class eg."javax.persistence.Entity"
     * @return true if class has the annotation
     * @throws java.lang.ClassNotFoundException error
     */
    protected boolean hasAnnotation(CtClass ctClass, String annotation) throws ClassNotFoundException {
        for (Object object : ctClass.getAvailableAnnotations()) {
            Annotation ann = (Annotation) object;
            if (ann.annotationType().getName().equals(annotation)) {
                return true;
            }
        }
        return false;
    }

    protected boolean hasAnnotation(CtClass ctClass, Class<? extends Annotation> annotation) throws ClassNotFoundException {
        String name = annotation.getName();
        return hasAnnotation(ctClass, name);
    }

    /**
     * Test if a field has the provided annotation
     *
     * @param ctField    the javassist field representation
     * @param annotation fully qualified name of the annotation class eg."javax.persistence.Entity"
     * @return true if field has the annotation
     * @throws java.lang.ClassNotFoundException error
     */
    protected boolean hasAnnotation(CtField ctField, String annotation) throws ClassNotFoundException {
        for (Object object : ctField.getAvailableAnnotations()) {
            Annotation ann = (Annotation) object;
            if (ann.annotationType().getName().equals(annotation)) {
                return true;
            }
        }
        return false;
    }

    protected boolean hasAnnotation(CtField ctField, Class<? extends Annotation> annotation) throws ClassNotFoundException {
        String name = annotation.getName();
        return hasAnnotation(ctField, name);
    }

    /**
     * Test if a method has the provided annotation
     *
     * @param ctMethod   the javassist method representation
     * @param annotation fully qualified name of the annotation class eg."javax.persistence.Entity"
     * @return true if field has the annotation
     * @throws java.lang.ClassNotFoundException error
     */
    protected boolean hasAnnotation(CtMethod ctMethod, String annotation) throws ClassNotFoundException {
        for (Object object : ctMethod.getAvailableAnnotations()) {
            Annotation ann = (Annotation) object;
            if (ann.annotationType().getName().equals(annotation)) {
                return true;
            }
        }
        return false;
    }

    protected boolean isProperty(CtField ctField) {
        return isProperty(ctField, true);
    }

    protected boolean isProperty(CtField ctField, boolean mustPublic) {
        return !(ctField.getName().equals(ctField.getName().toUpperCase())
                || ctField.getName().substring(0, 1).equals(ctField.getName().substring(0, 1).toUpperCase()))
                && (!mustPublic || Modifier.isPublic(ctField.getModifiers()))
                && !Modifier.isStatic(ctField.getModifiers())
                && Modifier.isPublic(ctField.getDeclaringClass().getModifiers());
    }

    protected CtMethod createSetter(CtClass clazz, CtField field) throws CannotCompileException, NotFoundException {
        CtMethod setter = new CtMethod(CtClass.voidType,
                getSetterName(field),
                new CtClass[]{field.getType()},
                clazz);
        setter.setModifiers(Modifier.PUBLIC);
        setter.setBody("{this." + field.getName() + "=$1;}");
        String gs = field.getGenericSignature();
        if (gs != null) {
            setter.setGenericSignature("(" + gs + ")V;");
        }
        clazz.addMethod(setter);
        return setter;
    }

    protected CtMethod createGetter(CtClass clazz, CtField field) throws CannotCompileException, NotFoundException {
        CtClass fieldType = field.getType();
        CtMethod getter = new CtMethod(fieldType,
                getGetterName(field), null, clazz);
        getter.setModifiers(Modifier.PUBLIC); //访问权限
        getter.setBody("{ return this." + field.getName() + "; }");
        String gs = field.getGenericSignature();
        if (gs != null) {
            getter.setGenericSignature("()" + gs);
        }
        clazz.addMethod(getter);
        return getter;
    }

    protected boolean isAnon(Class clazz) {
        return clazz.getName().contains("$anonfun$") || clazz.getName().contains("$anon$");
    }

    protected CtClass makeClass(ClassDescription desc) throws IOException {
        return classPool.makeClass(desc.getEnhancedByteCodeStream());
    }

    protected String getGetterName(CtField field) throws NotFoundException {
        CtClass fieldType = field.getType();
        String fieldName = StringUtils.capitalize(field.getName());
        if (fieldType.getName().equals(Boolean.class.getName())
                || fieldType.getName().equals(boolean.class.getName())) {
            return "is" + fieldName;
        }
        return "get" + fieldName;
    }

    protected String getSetterName(CtField field) throws NotFoundException {
        return "set" + StringUtils.capitalize(field.getName());
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public Object getProperty(String key) {
        return properties.get(key);
    }

    public void setProperty(String key, Object value) {
        properties.put(key, value);
    }
}
