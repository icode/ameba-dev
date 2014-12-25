package ameba.dev.classloading.enhance;

import ameba.db.model.Model;
import ameba.exception.UnexpectedException;
import javassist.*;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.Annotation;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.Entity;

/**
 * 模型管理器
 *
 * @author ICode
 * @since 13-8-18 上午10:39
 */
public class ModelEnhancer extends Enhancer {
    public static final Logger logger = LoggerFactory.getLogger(ModelEnhancer.class);

    public ModelEnhancer() {
        super(true);
    }

    private CtMethod createSetter(CtClass clazz, String methodName, CtClass[] args, CtField field) throws CannotCompileException {
        CtMethod setter = new CtMethod(CtClass.voidType,
                methodName,
                args,
                clazz);
        setter.setModifiers(Modifier.PUBLIC);
        setter.setBody("{this." + field.getName() + "=$1;}");
        clazz.addMethod(setter);
        return setter;
    }

    private CtMethod createGetter(CtClass clazz, String methodName, CtClass fieldType, CtField field) throws CannotCompileException {
        CtMethod getter = new CtMethod(fieldType,
                methodName, null, clazz);
        getter.setModifiers(Modifier.PUBLIC); //访问权限
        getter.setBody("{ return this." + field.getName() + "; }");
        clazz.addMethod(getter);
        return getter;
    }

    private CtMethod createIdSetter(CtClass clazz, String methodName, CtClass[] args) throws CannotCompileException {
        CtMethod setter = new CtMethod(CtClass.voidType,
                Model.ID_SETTER_NAME,
                args,
                clazz);
        setter.setModifiers(Modifier.PUBLIC);
        setter.setBody("{this." + methodName + "($1);}");
        clazz.addMethod(setter);
        return setter;
    }

    private CtMethod createIdGetter(CtClass clazz, String methodName, CtClass fieldType) throws CannotCompileException {
        CtMethod getter = new CtMethod(fieldType,
                Model.ID_GETTER_NAME, null, clazz);
        getter.setModifiers(Modifier.PUBLIC); //访问权限
        getter.setBody("{ return this." + methodName + "(); }");
        clazz.addMethod(getter);
        return getter;
    }

    @Override
    public void enhance(ClassDescription description) {
        try {
            classPool.importPackage(Model.BASE_MODEL_PKG);
            CtClass clazz = classPool.makeClass(description.getClassByteCodeStream());
            boolean hasAnnon = clazz.hasAnnotation(Entity.class);
            if (!hasAnnon) {
                ClassFile classFile = clazz.getClassFile();
                ConstPool constPool = classFile.getConstPool();
                AnnotationsAttribute attr = (AnnotationsAttribute) classFile.getAttribute(AnnotationsAttribute.visibleTag);
                if (attr == null) {
                    attr = new AnnotationsAttribute(constPool, AnnotationsAttribute.visibleTag);
                }
                attr.addAnnotation(new Annotation(Entity.class.getName(), constPool));
                classFile.addAttribute(attr);
            }
            logger.debug("增强模型类[{}]", clazz.getName());

            boolean idGetSetFixed = false;

            // Add a default constructor if needed
            try {
                boolean hasDefaultConstructor = false;
                for (CtConstructor constructor : clazz.getDeclaredConstructors()) {
                    if (constructor.getParameterTypes().length == 0) {
                        hasDefaultConstructor = true;
                        break;
                    }
                }
                if (!hasDefaultConstructor) {
                    CtConstructor defaultConstructor = CtNewConstructor.defaultConstructor(clazz);
                    clazz.addConstructor(defaultConstructor);
                }
            } catch (Exception e) {
                logger.error("Error in enhance Model", e);
                throw new UnexpectedException("Error in PropertiesEnhancer", e);
            }

            for (CtField field : clazz.getDeclaredFields()) {
                if (!isProperty(field)) {
                    continue;
                }
                //add getter method
                String fieldName = StringUtils.capitalize(field.getName());
                String getterName = "get" + fieldName;
                CtMethod getter = null;
                CtClass fieldType = classPool.get(field.getType().getName());
                try {
                    getter = clazz.getDeclaredMethod(getterName);
                } catch (NotFoundException e) {
                    //noop
                }
                if (getter == null && (fieldType.getName().equals(Boolean.class.getName())
                        || fieldType.getName().equals(boolean.class.getName()))) {
                    getterName = "is" + fieldName;
                    try {
                        getter = clazz.getDeclaredMethod(getterName);
                    } catch (NotFoundException e) {
                        //noop
                    }
                }
                if (getter == null) {
                    createGetter(clazz, getterName, fieldType, field);
                }
                String setterName = "set" + fieldName;
                CtClass[] args = new CtClass[]{fieldType};
                if (!isFinal(field)) {
                    try {
                        CtMethod ctMethod = clazz.getDeclaredMethod(setterName, args);
                        if (ctMethod.getParameterTypes().length != 1 || !ctMethod.getParameterTypes()[0].equals(field.getType())
                                || Modifier.isStatic(ctMethod.getModifiers())) {
                            throw new NotFoundException("it's not a setter !");
                        }
                    } catch (NotFoundException e) {
                        //add setter method
                        createSetter(clazz, setterName, args, field);
                    }
                }
                // 查找作为id的字段
                if (!idGetSetFixed) {
                    if (field.getAnnotation(javax.persistence.Id.class) != null) {
                        try {
                            //must argument[1] is null
                            clazz.getDeclaredMethod(Model.ID_GETTER_NAME, null);
                        } catch (NotFoundException e) {
                            createIdGetter(clazz, getterName, fieldType);
                        }

                        try {
                            clazz.getDeclaredMethod(Model.ID_SETTER_NAME, args);
                        } catch (NotFoundException e) {
                            createIdSetter(clazz, setterName, args);
                        }

                        CtClass[] _fArgs = new CtClass[]{classPool.get("java.lang.String")};
                        String genericSignature = "<ID:L" + fieldType.getName().replace(".", "/") +
                                ";T:L" + clazz.getName().replace(".", "/") + ";>(Ljava/lang/String;)L"
                                + Model.FINDER_C_NAME.replace(".", "/") + "<TID;TT;>;";
                        try {
                            clazz.getDeclaredMethod(Model.GET_FINDER_M_NAME, _fArgs);
                        } catch (Exception e) {
                            classPool.importPackage(fieldType.getPackageName());
                            classPool.importPackage(clazz.getName());

                            CtMethod _getFinder = new CtMethod(classPool.get(Model.FINDER_C_NAME),
                                    Model.GET_FINDER_M_NAME,
                                    _fArgs,
                                    clazz);
                            _getFinder.setModifiers(Modifier.setPublic(Modifier.STATIC));
                            _getFinder.setGenericSignature(genericSignature);
                            try {
                                _getFinder.setBody("{Finder finder = getFinderCache(" + clazz.getSimpleName() + ".class);" +
                                        "if(finder == null)" +
                                        "try {" +
                                        "   finder = (Finder) getFinderConstructor().newInstance(new Object[]{$1," +
                                        fieldType.getSimpleName() + ".class," + clazz.getSimpleName() + ".class});" +
                                        "   putFinderCache(" + clazz.getSimpleName() + ".class , finder);" +
                                        "} catch (Exception e) {" +
                                        "    throw new ameba.exception.AmebaException(e);" +
                                        "}" +
                                        "if (finder == null) {\n" +
                                        "    throw new ameba.db.model.Model.NotFinderFindException();\n" +
                                        "}" +
                                        "return finder;}");
                            } catch (CannotCompileException ex) {
                                throw new CannotCompileException("Entity Model must be extends ameba.db.model.Model", ex);
                            }
                            clazz.addMethod(_getFinder);
                        }
                        try {
                            clazz.getDeclaredMethod(Model.GET_FINDER_M_NAME, null);
                        } catch (Exception e) {
                            CtMethod _getFinder = new CtMethod(classPool.get(Model.FINDER_C_NAME),
                                    Model.GET_FINDER_M_NAME,
                                    null,
                                    clazz);

                            _getFinder.setModifiers(Modifier.setPublic(Modifier.STATIC));
                            _getFinder.setGenericSignature(genericSignature);
                            _getFinder.setBody("{return (Finder) " + Model.GET_FINDER_M_NAME + "(ameba.db.model.ModelManager.getDefaultDBName());}");
                            clazz.addMethod(_getFinder);
                        }
                        idGetSetFixed = true;
                    }
                }
            }

            description.classBytecode = clazz.toBytecode();
        } catch (Exception e) {
            throw new EnhancingException(e);
        }
    }
}
