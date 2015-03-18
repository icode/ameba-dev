package ameba.dev.classloading.enhance;

import ameba.db.TransactionFeature;
import ameba.db.model.Finder;
import ameba.db.model.Model;
import ameba.db.model.Persister;
import ameba.db.model.Updater;
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
    public static final String ID_SETTER_NAME = "_set_model_id";
    public static final String ID_GETTER_NAME = "_get_model_id";

    public ModelEnhancer() {
        super(true);
    }

    private CtMethod createIdSetter(CtClass clazz, String methodName, CtClass[] args) throws CannotCompileException {
        CtMethod setter = new CtMethod(CtClass.voidType,
                ID_SETTER_NAME,
                args,
                clazz);
        setter.setModifiers(Modifier.PUBLIC);
        setter.setBody("{this." + methodName + "($1);}");
        clazz.addMethod(setter);
        return setter;
    }

    private CtMethod createIdGetter(CtClass clazz, String methodName, CtClass fieldType) throws CannotCompileException {
        CtMethod getter = new CtMethod(fieldType,
                ID_GETTER_NAME, null, clazz);
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
            logger.debug("增强模型类[{}]", ctClass.getName());

            boolean idGetSetFixed = false;
            boolean isEntity = true;

            if (!ctClass.hasAnnotation(Entity.class)) {
                boolean modelSub;
                try {
                    modelSub = ctClass.subclassOf(classPool.getCtClass(Model.class.getName()));
                } catch (NotFoundException e) {
                    throw new EnhancingException(e);
                }
                if (modelSub)
                    createAnnotation(getAnnotations(ctClass), Entity.class);
                else
                    isEntity = false;
            }


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
                if (isEntity && !idGetSetFixed) {
                    idGetSetFixed = entityEnhancer(ctClass, field);
                }
            }

            description.enhancedByteCode = ctClass.toBytecode();
            ctClass.defrost();
        } catch (Exception e) {
            throw new EnhancingException(e);
        }
    }

    boolean entityEnhancer(CtClass ctClass, CtField field) throws ClassNotFoundException, NotFoundException, CannotCompileException {
        if (field.getAnnotation(javax.persistence.Id.class) != null) {
            String classPath = ctClass.getName().replace(".", "/");
            CtClass fieldType = field.getType();
            try {
                //must argument[1] is null
                ctClass.getDeclaredMethod(ID_GETTER_NAME, null);
            } catch (NotFoundException e) {
                createIdGetter(ctClass, ID_GETTER_NAME, fieldType);
            }

            CtClass[] args = new CtClass[]{fieldType};
            try {
                ctClass.getDeclaredMethod(ID_SETTER_NAME, args);
            } catch (NotFoundException e) {
                createIdSetter(ctClass, ID_SETTER_NAME, args);
            }

            CtClass stringType = classPool.get("java.lang.String");
            CtClass[] _fArgs = new CtClass[]{stringType};
            String genericSignatureStart = "(";
            String genericSignatureEnd = ")L" + Model.FINDER_C_NAME.replace(".", "/") +
                    "<L" + fieldType.getName().replace(".", "/") + ";L" + classPath + ";>;";

            String genericSignature = genericSignatureStart + "Ljava/lang/String;" + genericSignatureEnd;
            String _getFMN = "_getFinder";
            classPool.importPackage(ctClass.getPackageName());
            try {
                ctClass.getDeclaredMethod(_getFMN, _fArgs);
            } catch (Exception e) {
                classPool.importPackage(fieldType.getPackageName());

                CtMethod _getFinder = new CtMethod(classPool.get(Model.FINDER_C_NAME),
                        _getFMN,
                        _fArgs,
                        ctClass);
                _getFinder.setModifiers(Modifier.setProtected(Modifier.STATIC));
                _getFinder.setGenericSignature(genericSignature);
                try {
                    _getFinder.setBody("{" + Finder.class.getName() + " finder = new " + TransactionFeature.getFinderClass().getName().replace("$", ".") + "($1," +
                            fieldType.getName() + ".class," + ctClass.getName() + ".class);" +
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
                ctClass.getDeclaredMethod(Model.GET_FINDER_M_NAME, _fArgs);
            } catch (Exception e) {
                CtMethod _getFinder = new CtMethod(classPool.get(Model.FINDER_C_NAME),
                        Model.GET_FINDER_M_NAME,
                        _fArgs,
                        ctClass);

                _getFinder.setModifiers(Modifier.setPublic(Modifier.STATIC));
                _getFinder.setGenericSignature(genericSignature);
                try {
                    _getFinder.setBody("{return " + _getFMN + "($$);}");
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
                _getFinder.setBody("{return " + Model.GET_FINDER_M_NAME + "(ameba.db.DataSource.getDefaultDataSourceName());}");
                ctClass.addMethod(_getFinder);
            }

            String _getPSN = "_getPersister";
            CtClass[] _pArgs = new CtClass[]{stringType};
            String persisterGenericSignature = "(Ljava/lang/String;)L" + Model.PERSISTER_C_NAME.replace(".", "/") + "<L" + classPath + ";>;";
            try {
                ctClass.getDeclaredMethod(_getPSN, _pArgs);
            } catch (Exception e) {
                CtMethod _getPersister = new CtMethod(classPool.get(Model.PERSISTER_C_NAME),
                        _getPSN,
                        _pArgs,
                        ctClass);
                _getPersister.setModifiers(Modifier.PROTECTED);
                _getPersister.setGenericSignature(persisterGenericSignature);
                try {
                    _getPersister.setBody("{" + Persister.class.getName()
                            + " persister = new " + TransactionFeature.getPersisterClass().getName().replace("$", ".") + "($1,this);" +
                            "if (persister == null) {\n" +
                            "    throw new ameba.db.model.Model.NotPersisterFindException();\n" +
                            "}" +
                            "return persister;}");
                } catch (CannotCompileException ex) {
                    throw new CannotCompileException("Entity Model must be extends ameba.db.model.Model", ex);
                }
                ctClass.addMethod(_getPersister);
            }


            String _getUMN = "_getUpdater";
            CtClass[] _uArgs = new CtClass[]{stringType, stringType};
            String updaterGenericSignatureStart = "(Ljava/lang/String;";
            String updaterGenericSignatureEnd = ")L" + Model.UPDATER_C_NAME.replace(".", "/") + "<L" + classPath + ";>;";
            String updaterGenericSignature = updaterGenericSignatureStart + "Ljava/lang/String;" + updaterGenericSignatureEnd;
            try {
                ctClass.getDeclaredMethod(_getUMN, _uArgs);
            } catch (Exception e) {
                CtMethod _getUpdater = new CtMethod(classPool.get(Model.UPDATER_C_NAME),
                        _getUMN,
                        _uArgs,
                        ctClass);
                _getUpdater.setModifiers(Modifier.setProtected(Modifier.STATIC));
                _getUpdater.setGenericSignature(updaterGenericSignature);
                try {
                    _getUpdater.setBody("{" + Updater.class.getName() + " updater = new " + TransactionFeature.getUpdaterClass().getName().replace("$", ".") + "($1," +
                            ctClass.getName() + ".class, $2);" +
                            "if (updater == null) {\n" +
                            "    throw new ameba.db.model.Model.NotUpdaterFindException();\n" +
                            "}" +
                            "return updater;}");
                } catch (CannotCompileException ex) {
                    throw new CannotCompileException("Entity Model must be extends ameba.db.model.Model", ex);
                }
                ctClass.addMethod(_getUpdater);
            }
            try {
                ctClass.getDeclaredMethod(Model.GET_UPDATE_M_NAME, _uArgs);
            } catch (Exception e) {
                CtMethod _getUpdater = new CtMethod(classPool.get(Model.UPDATER_C_NAME),
                        Model.GET_UPDATE_M_NAME,
                        _uArgs,
                        ctClass);

                _getUpdater.setModifiers(Modifier.setPublic(Modifier.STATIC));
                _getUpdater.setGenericSignature(updaterGenericSignature);
                try {
                    _getUpdater.setBody("{return " + _getUMN + "($$);}");
                } catch (CannotCompileException ex) {
                    throw new CannotCompileException("Entity Model must be extends ameba.db.model.Model", ex);
                }
                ctClass.addMethod(_getUpdater);
            }
            _uArgs = new CtClass[]{stringType};
            try {
                ctClass.getDeclaredMethod(Model.GET_UPDATE_M_NAME, _uArgs);
            } catch (Exception e) {
                CtMethod _getUpdater = new CtMethod(classPool.get(Model.UPDATER_C_NAME),
                        Model.GET_UPDATE_M_NAME,
                        _uArgs,
                        ctClass);

                _getUpdater.setModifiers(Modifier.setPublic(Modifier.STATIC));
                _getUpdater.setGenericSignature(updaterGenericSignatureStart + updaterGenericSignatureEnd);
                _getUpdater.setBody("{return " + Model.GET_UPDATE_M_NAME + "(ameba.db.DataSource.getDefaultDataSourceName(), $1);}");
                ctClass.addMethod(_getUpdater);
            }
            return true;
        }
        return false;
    }
}
