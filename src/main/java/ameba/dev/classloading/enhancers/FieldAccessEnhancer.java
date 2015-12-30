package ameba.dev.classloading.enhancers;

import ameba.dev.classloading.ClassDescription;
import javassist.*;
import javassist.bytecode.Descriptor;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 替换方法内调用 model.field=value 为getter/setter
 *
 * @author icode
 * @since 15-1-8
 */
public class FieldAccessEnhancer extends Enhancer {
    private static final Logger logger = LoggerFactory.getLogger(FieldAccessEnhancer.class);

    public FieldAccessEnhancer() {
        super(true);
    }

    @Override
    public void enhance(ClassDescription description) throws Exception {
        CtClass ctClass = makeClass(description);
        for (final CtBehavior ctBehavior : ctClass.getDeclaredBehaviors()) {
            ctBehavior.instrument(new ExprEditor() {
                @Override
                public void edit(FieldAccess fieldAccess) throws CannotCompileException {
                    try {
                        CtField field = fieldAccess.getField();
                        if (!isProperty(field)) return;

                        CtClass dClass = field.getDeclaringClass();
                        CtClass _bDClass = ctBehavior.getDeclaringClass();

                        // check getter or setter inner
                        String propertyName = null;

                        if (dClass.equals(_bDClass) || _bDClass.subclassOf(dClass)) {
                            String bName = ctBehavior.getName();
                            if (bName.length() > 3 && (bName.startsWith("get")
                                    || (!isFinal(field) && bName.startsWith("set")))) {
                                propertyName = StringUtils.uncapitalize(bName.substring(3));
                            } else if (bName.length() > 2) {
                                String fieldType = field.getType().getName();
                                if (fieldType.equals(Boolean.class.getName())
                                        || fieldType.equals(boolean.class.getName())
                                        && bName.startsWith("is")) {
                                    propertyName = StringUtils.uncapitalize(bName.substring(2));
                                }
                            }
                        }

                        if (propertyName == null || !propertyName.equals(fieldAccess.getFieldName())) {
                            if (fieldAccess.isReader()) {
                                String name = getGetterName(field);
                                if (hasMethod(dClass, name, Descriptor.ofMethod(field.getType(), null))) {
                                    fieldAccess.replace("$_ = $0." + name + "();");
                                }
                            } else if (!isFinal(field) && fieldAccess.isWriter()) {
                                String name = getSetterName(field);
                                if (hasMethod(dClass, name, Descriptor.ofMethod(
                                        CtClass.voidType, new CtClass[]{field.getType()}
                                ))) {
                                    fieldAccess.replace("$0." + name + "($1);");
                                }
                            }
                        }
                    } catch (Exception e) {
                        throw new EnhancingException(e);
                    }
                }
            });
        }
        description.enhancedByteCode = ctClass.toBytecode();
        ctClass.defrost();
    }

    private boolean hasMethod(CtClass dClass, String name, String desc) throws ClassNotFoundException {
        try {
            dClass.getMethod(name, desc);
        } catch (NotFoundException ex) {
            logger.trace("Can not change field access", ex);
            return false;
        }
        return true;
    }
}
