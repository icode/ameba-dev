package ameba.dev.classloading.enhance;

import ameba.db.model.Model;
import ameba.dev.classloading.ClassDescription;
import ameba.exception.UnexpectedException;
import javassist.*;
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
            CtClass ctClass = makeClass(description);
            if (!hasAnnotation(ctClass, Entity.class.getName())) {
                createAnnotation(getAnnotations(ctClass), Entity.class);
            }
            logger.debug("增强模型类[{}]", ctClass.getName());

            boolean idGetSetFixed = false;

            // Add a default constructor if needed
            try {
                boolean hasDefaultConstructor = false;
                for (CtConstructor constructor : ctClass.getDeclaredConstructors()) {
                    if (constructor.getParameterTypes().length == 0) {
                        hasDefaultConstructor = true;
                        break;
                    }
                }
                if (!hasDefaultConstructor) {
                    CtConstructor defaultConstructor = CtNewConstructor.defaultConstructor(ctClass);
                    ctClass.addConstructor(defaultConstructor);
                }
            } catch (Exception e) {
                logger.error("Error in enhance Model", e);
                throw new UnexpectedException("Error in PropertiesEnhancer", e);
            }

            for (CtField field : ctClass.getDeclaredFields()) {
                if (!isProperty(field)) {
                    continue;
                }
                //add getter method
                String fieldName = StringUtils.capitalize(field.getName());
                String getterName = "get" + fieldName;
                CtMethod getter = null;
                CtClass fieldType = field.getType();
                try {
                    getter = ctClass.getDeclaredMethod(getterName);
                } catch (NotFoundException e) {
                    //noop
                }
                if (getter == null && (fieldType.getName().equals(Boolean.class.getName())
                        || fieldType.getName().equals(boolean.class.getName()))) {
                    getterName = "is" + fieldName;
                    try {
                        getter = ctClass.getDeclaredMethod(getterName);
                    } catch (NotFoundException e) {
                        //noop
                    }
                }
                if (getter == null) {
                    createGetter(ctClass, field);
                }
                String setterName = getSetterName(field);
                CtClass[] args = new CtClass[]{fieldType};
                if (!isFinal(field)) {
                    try {
                        CtMethod ctMethod = ctClass.getDeclaredMethod(setterName, args);
                        if (ctMethod.getParameterTypes().length != 1 || !ctMethod.getParameterTypes()[0].equals(field.getType())
                                || Modifier.isStatic(ctMethod.getModifiers())) {
                            throw new NotFoundException("it's not a setter !");
                        }
                    } catch (NotFoundException e) {
                        //add setter method
                        createSetter(ctClass, field);
                    }
                }
                // 查找作为id的字段
                if (!idGetSetFixed) {
                    if (field.getAnnotation(javax.persistence.Id.class) != null) {
                        try {
                            //must argument[1] is null
                            ctClass.getDeclaredMethod(Model.ID_GETTER_NAME, null);
                        } catch (NotFoundException e) {
                            createIdGetter(ctClass, getterName, fieldType);
                        }

                        try {
                            ctClass.getDeclaredMethod(Model.ID_SETTER_NAME, args);
                        } catch (NotFoundException e) {
                            createIdSetter(ctClass, setterName, args);
                        }

                        CtClass[] _fArgs = new CtClass[]{classPool.get("java.lang.String")};
                        String genericSignatureStart = "<ID:L" + fieldType.getName().replace(".", "/") + ";T:L" + ctClass.getName().replace(".", "/") + ";>(";
                        String genericSignatureEnd = ")L" + Model.FINDER_C_NAME.replace(".", "/") + "<TID;TT;>;";

                        String genericSignature = genericSignatureStart + "Ljava/lang/String;" + genericSignatureEnd;
                        try {
                            ctClass.getDeclaredMethod(Model.GET_FINDER_M_NAME, _fArgs);
                        } catch (Exception e) {
                            classPool.importPackage(fieldType.getPackageName());
                            classPool.importPackage(ctClass.getName());

                            CtMethod _getFinder = new CtMethod(classPool.get(Model.FINDER_C_NAME),
                                    Model.GET_FINDER_M_NAME,
                                    _fArgs,
                                    ctClass);
                            _getFinder.setModifiers(Modifier.setPublic(Modifier.STATIC));
                            _getFinder.setGenericSignature(genericSignature);
                            try {
                                _getFinder.setBody("{Finder finder = getFinderCache(" + ctClass.getSimpleName() + ".class);" +
                                        "if(finder == null)" +
                                        "try {" +
                                        "   finder = (Finder) getFinderConstructor().newInstance(new Object[]{$1," +
                                        fieldType.getSimpleName() + ".class," + ctClass.getSimpleName() + ".class});" +
                                        "   putFinderCache(" + ctClass.getSimpleName() + ".class , finder);" +
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
                            ctClass.addMethod(_getFinder);
                        }
                        try {
                            ctClass.getDeclaredMethod(Model.GET_FINDER_M_NAME, null);
                        } catch (Exception e) {
                            CtMethod _getFinder = new CtMethod(classPool.get(Model.FINDER_C_NAME),
                                    Model.GET_FINDER_M_NAME,
                                    null,
                                    ctClass);

                            _getFinder.setModifiers(Modifier.setPublic(Modifier.STATIC));
                            _getFinder.setGenericSignature(genericSignatureStart + genericSignatureEnd);
                            _getFinder.setBody("{return (Finder) " + Model.GET_FINDER_M_NAME + "(ameba.db.model.ModelManager.getDefaultDBName());}");
                            ctClass.addMethod(_getFinder);
                        }
                        idGetSetFixed = true;
                    }
                }
            }

            description.enhancedByteCode = ctClass.toBytecode();
            ctClass.defrost();
        } catch (Exception e) {
            throw new EnhancingException(e);
        }
    }
}
