package ameba.dev.classloading.enhancers;

import ameba.db.annotation.DataSource;
import ameba.db.model.Model;
import ameba.db.model.ModelProperties;
import ameba.dev.classloading.ClassDescription;
import ameba.exception.UnexpectedException;
import javassist.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Map;

/**
 * 模型增强
 *
 * @author icode
 */
public class ModelEnhancer extends Enhancer {
    public static final Logger logger = LoggerFactory.getLogger(ModelEnhancer.class);

    private static final String ENTITY_ANNOTATION = "javax.persistence.Entity";
    private static final String MAPPED_ANNOTATION = "javax.persistence.MappedSuperclass";
    private static final String ID_ANNOTATION = "javax.persistence.Id";
    private static final String EMBEDDED_ID_ANNOTATION = "javax.persistence.EmbeddedId";
    private static final String EMBEDDABLE_ANNOTATION = "javax.persistence.Embeddable";
    private static final String LANG3_BUILDER_PKG = "org.apache.commons.lang3.builder.";
    private static final String HASH_CODE_BUILDER = LANG3_BUILDER_PKG + "HashCodeBuilder";
    private static final String EQUALS_BUILDER = LANG3_BUILDER_PKG + "EqualsBuilder";
    private static CtClass serializableType;
    private static CtClass modelType;

    public ModelEnhancer(Map<String, Object> properties) {
        super(true, properties);

        if (modelType == null) {
            try {
                modelType = getClassPool().get(Model.class.getName());
            } catch (NotFoundException e) {
                //
            }
        }
        if (serializableType == null) {
            try {
                serializableType = getClassPool().get(Serializable.class.getName());
            } catch (NotFoundException e) {
                //
            }
        }
    }

    @Override
    public void enhance(ClassDescription description) {
        try {
            CtClass ctClass = makeClass(description);

            boolean idGetSetFixed = false;
            boolean isEntity = true;

            if (!hasAnnotation(ctClass, ENTITY_ANNOTATION)) {
                boolean modelSub = ctClass.subclassOf(modelType);
                if (modelSub && !hasAnnotation(ctClass, MAPPED_ANNOTATION)) {
                    addAnnotation(getAnnotations(ctClass), ENTITY_ANNOTATION);
                } else
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

            if (hasAnnotation(ctClass, EMBEDDABLE_ANNOTATION)) {

                boolean isSer = false;
                for (CtClass inter : ctClass.getInterfaces()) {
                    if (inter.getName().equals(serializableType.getName())) {
                        isSer = true;
                        break;
                    }
                }

                if (!isSer) {
                    ctClass.addInterface(serializableType);
                }

                try {
                    ctClass.getDeclaredMethod("equals", new CtClass[]{objectType});
                } catch (NotFoundException e) {
                    String className = ctClass.getName();
                    StringBuilder builder = new StringBuilder("public boolean equals(Object o) {")
                            .append("if (this == o) return true;")
                            .append("if (!(o instanceof ").append(className).append(")) return false;")
                            .append(className).append(" other = (").append(className).append(") o;");
                    builder.append("return new ").append(EQUALS_BUILDER).append("()");
                    appendEqBuilderField(ctClass.getDeclaredFields(), builder);

                    CtClass suClass = ctClass.getSuperclass();
                    if (suClass != null)
                        appendEqBuilderField(suClass.getFields(), builder);
                    builder.append(".isEquals();}");
                    ctClass.addMethod(
                            CtNewMethod.make(builder.toString(), ctClass)
                    );
                }
                try {
                    ctClass.getDeclaredMethod("hashCode", new CtClass[]{});
                } catch (NotFoundException e) {
                    StringBuilder builder = new StringBuilder("public int hashCode() {");
                    builder.append("return new ").append(HASH_CODE_BUILDER).append("(17, 37)");
                    appendHashCodeBuilderField(ctClass.getDeclaredFields(), builder);

                    CtClass suClass = ctClass.getSuperclass();
                    if (suClass != null)
                        appendHashCodeBuilderField(suClass.getFields(), builder);
                    builder.append(".toHashCode();}");
                    ctClass.addMethod(
                            CtNewMethod.make(builder.toString(), ctClass)
                    );
                }
            }

            for (CtField field : getAllDeclaredFields(ctClass)) {
                if (isProperty(field)) {
                    CtClass where = field.getDeclaringClass();
                    if (where != null && where.getName().equals(ctClass.getName())) {
                        //add getter method
                        String fieldName = StringUtils.capitalize(field.getName());
                        String getterName = "get" + fieldName;
                        CtMethod getter = null;
                        CtClass fieldType = field.getType();
                        try {
                            getter = ctClass.getDeclaredMethod(getterName);
                        } catch (NotFoundException e) {
                            //no op
                        }
                        if (getter == null && (fieldType.getName().equals(Boolean.class.getName())
                                || fieldType.getName().equals(boolean.class.getName()))) {
                            getterName = "is" + fieldName;
                            try {
                                getter = ctClass.getDeclaredMethod(getterName);
                            } catch (NotFoundException e) {
                                //no op
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
                                if (ctMethod.getParameterTypes().length != 1
                                        || !ctMethod.getParameterTypes()[0].equals(field.getType())
                                        || Modifier.isStatic(ctMethod.getModifiers())) {
                                    throw new NotFoundException("it's not a setter !");
                                }
                            } catch (NotFoundException e) {
                                //add setter method
                                createSetter(ctClass, field);
                            }
                        }
                    }
                }

                // 查找作为id的字段
                if (isEntity && !idGetSetFixed) {
                    idGetSetFixed = entityEnhancer(ctClass, field);
                }
            }

            // 查找作为id的字段
            if (isEntity && !idGetSetFixed) {
                CtField field = CtField.make("private java.lang.Object id;", ctClass);
                addAnnotation(getAnnotations(field), ID_ANNOTATION);
                entityEnhancer(ctClass, field);
            }

            description.enhancedByteCode = ctClass.toBytecode();
            ctClass.defrost();
        } catch (Exception e) {
            throw new EnhancingException(e);
        }
    }

    private void appendHashCodeBuilderField(CtField[] fields, StringBuilder builder) {
        for (CtField ctField : fields) {
            if (isProperty(ctField, false)) {
                builder.append(".append(").append(ctField.getName()).append(")");
            }
        }
    }

    private void appendEqBuilderField(CtField[] fields, StringBuilder builder) {
        for (CtField ctField : fields) {
            if (isProperty(ctField, false)) {
                String fn = ctField.getName();
                builder.append(".append(").append(fn).append(", other.").append(fn).append(")");
            }
        }
    }

    boolean entityEnhancer(CtClass ctClass, CtField field) throws ClassNotFoundException, NotFoundException, CannotCompileException {
        if ((hasAnnotation(field, ID_ANNOTATION)
                || hasAnnotation(field, EMBEDDED_ID_ANNOTATION))
                && !hasAnnotation(ctClass, EMBEDDABLE_ANNOTATION)) {
            String classPath = ctClass.getName().replace(".", "/");
            CtClass fieldType = field.getType();


            DataSource dataSource = (DataSource) ctClass.getAnnotation(DataSource.class);

            String dataSouceName = dataSource == null || StringUtils.isBlank(dataSource.value())
                    ? "ameba.db.DataSourceManager.getDefaultDataSourceName()"
                    : "\"" + dataSource.value() + "\"";

            CtClass stringType = getClassPool().get("java.lang.String");
            CtClass[] _fArgs = new CtClass[]{stringType};
            String genericSignatureStart = "(";
            String genericSignatureEnd = ")L" + ModelProperties.FINDER_C_NAME.replace(".", "/") +
                    "<L" + fieldType.getName().replace(".", "/") + ";L" + classPath + ";>;";
            String genericSignature = genericSignatureStart + "Ljava/lang/String;" + genericSignatureEnd;
            String _getFMN = "_getFinder";
            try {
                ctClass.getDeclaredMethod(_getFMN, _fArgs);
            } catch (Exception e) {

                CtMethod _getFinder = new CtMethod(getClassPool().get(ModelProperties.FINDER_C_NAME),
                        _getFMN,
                        _fArgs,
                        ctClass);
                _getFinder.setModifiers(Modifier.setProtected(Modifier.STATIC));
                _getFinder.setGenericSignature(genericSignature);
                _getFinder.setBody("{return new " +
                        ((String) getProperty("orm.finder")).replace("$", ".")
                        + "($1," + fieldType.getName() + ".class," + ctClass.getName() + ".class);}");
                ctClass.addMethod(_getFinder);
            }
            try {
                ctClass.getDeclaredMethod(ModelProperties.GET_FINDER_M_NAME, _fArgs);
            } catch (Exception e) {
                CtMethod _getFinder = new CtMethod(getClassPool().get(ModelProperties.FINDER_C_NAME),
                        ModelProperties.GET_FINDER_M_NAME,
                        _fArgs,
                        ctClass);

                _getFinder.setModifiers(Modifier.setPublic(Modifier.STATIC));
                _getFinder.setGenericSignature(genericSignature);
                _getFinder.setBody("{return " + _getFMN + "($$);}");
                ctClass.addMethod(_getFinder);
            }
            try {
                ctClass.getDeclaredMethod(ModelProperties.GET_FINDER_M_NAME, null);
            } catch (Exception e) {
                CtMethod _getFinder = new CtMethod(getClassPool().get(ModelProperties.FINDER_C_NAME),
                        ModelProperties.GET_FINDER_M_NAME,
                        null,
                        ctClass);

                _getFinder.setModifiers(Modifier.setPublic(Modifier.STATIC));
                _getFinder.setGenericSignature(genericSignatureStart + genericSignatureEnd);
                _getFinder.setBody("{return " + ModelProperties.GET_FINDER_M_NAME + "(" + dataSouceName + ");}");
                ctClass.addMethod(_getFinder);
            }

            String _getPSN = "_getPersister";
            CtClass[] _pArgs = new CtClass[]{stringType};
            String persisterGenericSignatureStart = "(";
            String persisterGenericSignatureEnd = ")L" + ModelProperties.PERSISTER_C_NAME.replace(".", "/")
                    + "<L" + classPath + ";>;";
            String persisterGenericSignature = persisterGenericSignatureStart + "Ljava/lang/String;"
                    + persisterGenericSignatureEnd;
            try {
                ctClass.getDeclaredMethod(_getPSN, _pArgs);
            } catch (Exception e) {
                CtMethod _getPersister = new CtMethod(getClassPool().get(ModelProperties.PERSISTER_C_NAME),
                        _getPSN,
                        _pArgs,
                        ctClass);
                _getPersister.setModifiers(Modifier.PROTECTED);
                _getPersister.setGenericSignature(persisterGenericSignature);
                _getPersister.setBody("{return new "
                        + ((String) getProperty("orm.persister")).replace("$", ".")
                        + "($1,this);}");
                ctClass.addMethod(_getPersister);
            }

            try {
                ctClass.getDeclaredMethod(ModelProperties.GET_PERSISTER_M_NAME, _pArgs);
            } catch (Exception e) {
                CtMethod _getPersister = new CtMethod(getClassPool().get(ModelProperties.PERSISTER_C_NAME),
                        ModelProperties.GET_PERSISTER_M_NAME,
                        _pArgs,
                        ctClass);
                _getPersister.setModifiers(Modifier.PUBLIC);
                _getPersister.setGenericSignature(persisterGenericSignature);
                _getPersister.setBody("{return " + _getPSN + "($$);}");
                ctClass.addMethod(_getPersister);
            }

            try {
                ctClass.getDeclaredMethod(ModelProperties.GET_PERSISTER_M_NAME, null);
            } catch (Exception e) {
                CtMethod _getPersister = new CtMethod(getClassPool().get(ModelProperties.PERSISTER_C_NAME),
                        ModelProperties.GET_PERSISTER_M_NAME,
                        null,
                        ctClass);
                _getPersister.setModifiers(Modifier.PUBLIC);
                _getPersister.setGenericSignature(persisterGenericSignatureStart + persisterGenericSignatureEnd);
                _getPersister.setBody("{return " + ModelProperties.GET_PERSISTER_M_NAME + "(" + dataSouceName + ");}");
                ctClass.addMethod(_getPersister);
            }


            String _getUMN = "_getUpdater";
            CtClass[] _uArgs = new CtClass[]{stringType, stringType};
            String updaterGenericSignatureStart = "(Ljava/lang/String;";
            String updaterGenericSignatureEnd = ")L" + ModelProperties.UPDATER_C_NAME.replace(".", "/") + "<L" + classPath + ";>;";
            String updaterGenericSignature = updaterGenericSignatureStart + "Ljava/lang/String;" + updaterGenericSignatureEnd;
            try {
                ctClass.getDeclaredMethod(_getUMN, _uArgs);
            } catch (Exception e) {
                CtMethod _getUpdater = new CtMethod(getClassPool().get(ModelProperties.UPDATER_C_NAME),
                        _getUMN,
                        _uArgs,
                        ctClass);
                _getUpdater.setModifiers(Modifier.setProtected(Modifier.STATIC));
                _getUpdater.setGenericSignature(updaterGenericSignature);
                _getUpdater.setBody("{return new " +
                        ((String) getProperty("orm.updater")).replace("$", ".") +
                        "($1," + ctClass.getName() + ".class, $2);}");
                ctClass.addMethod(_getUpdater);
            }
            try {
                ctClass.getDeclaredMethod(ModelProperties.GET_UPDATE_M_NAME, _uArgs);
            } catch (Exception e) {
                CtMethod _getUpdater = new CtMethod(getClassPool().get(ModelProperties.UPDATER_C_NAME),
                        ModelProperties.GET_UPDATE_M_NAME,
                        _uArgs,
                        ctClass);

                _getUpdater.setModifiers(Modifier.setPublic(Modifier.STATIC));
                _getUpdater.setGenericSignature(updaterGenericSignature);
                _getUpdater.setBody("{return " + _getUMN + "($$);}");
                ctClass.addMethod(_getUpdater);
            }
            _uArgs = new CtClass[]{stringType};
            try {
                ctClass.getDeclaredMethod(ModelProperties.GET_UPDATE_M_NAME, _uArgs);
            } catch (Exception e) {
                CtMethod _getUpdater = new CtMethod(getClassPool().get(ModelProperties.UPDATER_C_NAME),
                        ModelProperties.GET_UPDATE_M_NAME,
                        _uArgs,
                        ctClass);

                _getUpdater.setModifiers(Modifier.setPublic(Modifier.STATIC));
                _getUpdater.setGenericSignature(updaterGenericSignatureStart + updaterGenericSignatureEnd);
                _getUpdater.setBody("{return " + ModelProperties.GET_UPDATE_M_NAME + "(" + dataSouceName + ", $1);}");
                ctClass.addMethod(_getUpdater);
            }
            return true;
        }
        return false;
    }
}
