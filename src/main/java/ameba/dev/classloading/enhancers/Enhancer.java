package ameba.dev.classloading.enhancers;

import ameba.container.Container;
import ameba.dev.classloading.ClassDescription;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.event.Listener;
import ameba.event.SystemEventBus;
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
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author icode
 */
public abstract class Enhancer {
    private static ClassPool classPool = null;
    protected String version = null;

    protected Enhancer(boolean initClassPool) {
        if (initClassPool && classPool == null)
            classPool = newClassPool();
    }

    public static ClassPool newClassPool() {
        ClassPool classPool = new ClassPool();
        classPool.appendClassPath(new AppClassPath(ClassUtils.getContextClassLoader()));
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

    protected static ClassDescription getClassDesc(String classname) {
        if (classname.startsWith("java.")) return null;
        ClassLoader classLoader = ClassUtils.getContextClassLoader();
        if (classLoader instanceof ReloadClassLoader) {
            ReloadClassLoader loader = (ReloadClassLoader) classLoader;
            return loader.getClassCache().get(classname);
        }
        return null;
    }

    /**
     * Create a new annotation to be dynamically inserted in the byte code.
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
     */
    protected static void addAnnotation(AnnotationsAttribute attribute, Class<? extends Annotation> annotationType) {
        addAnnotation(attribute, annotationType.getName(), new HashMap<String, MemberValue>());
    }

    protected static void addAnnotation(AnnotationsAttribute attribute, String annotationType) {
        addAnnotation(attribute, annotationType, new HashMap<String, MemberValue>());
    }

    /**
     * Retrieve all class annotations.
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
     * @throws java.lang.ClassNotFoundException
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
        for (Object object : ctClass.getAvailableAnnotations()) {
            Annotation ann = (Annotation) object;
            if (ann.annotationType().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Test if a field has the provided annotation
     *
     * @param ctField    the javassist field representation
     * @param annotation fully qualified name of the annotation class eg."javax.persistence.Entity"
     * @return true if field has the annotation
     * @throws java.lang.ClassNotFoundException
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

    /**
     * Test if a method has the provided annotation
     *
     * @param ctMethod   the javassist method representation
     * @param annotation fully qualified name of the annotation class eg."javax.persistence.Entity"
     * @return true if field has the annotation
     * @throws java.lang.ClassNotFoundException
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
        return !(ctField.getName().equals(ctField.getName().toUpperCase())
                || ctField.getName().substring(0, 1).equals(ctField.getName().substring(0, 1).toUpperCase()))
                && Modifier.isPublic(ctField.getModifiers())
                && !Modifier.isStatic(ctField.getModifiers()) // protected classes will be considered public by this call
                && Modifier.isPublic(ctField.getDeclaringClass().getModifiers());
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
        clazz.addMethod(setter);
        return setter;
    }

    protected CtMethod createGetter(CtClass clazz, CtField field) throws CannotCompileException, NotFoundException {
        CtClass fieldType = field.getType();
        //field.setModifiers(Modifier.PRIVATE);
        CtMethod getter = new CtMethod(fieldType,
                getGetterName(field), null, clazz);
        getter.setModifiers(Modifier.PUBLIC); //访问权限
        getter.setBody("{ return this." + field.getName() + "; }");
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

    public static class AppClassPath extends LoaderClassPath {

        /**
         * Creates a search path representing a class loader.
         *
         * @param cl ClassLoader
         */
        public AppClassPath(ClassLoader cl) {
            super(cl);
        }

        @Override
        public InputStream openClassfile(String classname) {
            ClassDescription desc = getClassDesc(classname);
            preLoadClass(classname, desc);
            if (hasEnhancedClassFile(desc)) {
                return desc.getEnhancedByteCodeStream();
            }
            return super.openClassfile(classname);
        }

        @Override
        public URL find(String classname) {
            ClassDescription desc = getClassDesc(classname);
            preLoadClass(classname, desc);
            if (hasEnhancedClassFile(desc)) {
                try {
                    return desc.getEnhancedClassFile().toURI().toURL();
                } catch (MalformedURLException e) {
                    return super.find(classname);
                }
            }
            return super.find(classname);
        }

        private boolean hasEnhancedClassFile(ClassDescription desc) {
            return desc != null && desc.getEnhancedClassFile().exists();
        }

        private void preLoadClass(String classname, ClassDescription desc) {
            ReloadClassLoader classLoader = (ReloadClassLoader) Thread.currentThread().getContextClassLoader();
            if (!hasEnhancedClassFile(desc)) {
                try {
                    classLoader.loadClass(classname);
                } catch (ClassNotFoundException e) {
                    // no op
                }
            }
        }
    }

}
