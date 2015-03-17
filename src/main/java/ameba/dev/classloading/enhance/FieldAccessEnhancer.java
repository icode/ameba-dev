package ameba.dev.classloading.enhance;

import ameba.dev.classloading.ClassDescription;
import javassist.*;
import javassist.bytecode.Descriptor;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
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

        for (CtMethod method : ctClass.getDeclaredMethods()){
            method.instrument(new Access2Function());
        }

        description.enhancedByteCode = ctClass.toBytecode();
        ctClass.defrost();
    }

    private class Access2Function extends ExprEditor {

        @Override
        public void edit(FieldAccess f) throws CannotCompileException {
            CtBehavior behavior = f.where();
            CtField field;
            try {
                field = f.getField();
            } catch (NotFoundException e) {
                return;
            }
            if (!isProperty(field, false)
                    || behavior.getName().startsWith("_")) return;

            String className = behavior.getDeclaringClass().getName();
            CtClass dClass = field.getDeclaringClass();
            if (f.isWriter()) {
                try {
                    String name = getSetterName(field);

                    //check has method, if not exist throw exception
                    CtMethod method = dClass.getMethod(name,
                            Descriptor.ofMethod(CtClass.voidType, new CtClass[]{field.getType()}));
                    //not same method in same class
                    if (!className.equals(dClass.getName()) || !behavior.getSignature().equals(method.getSignature()))
                        f.replace("$0." + name + "($1);");
                } catch (NotFoundException e) {
                    logger.trace("not found setter", e);
                }
            } else if (f.isReader()) {
                try {
                    String name = getGetterName(field);

                    //check has method, if not exist throw exception
                    CtMethod method = dClass.getMethod(name,
                            Descriptor.ofMethod(field.getType(), null));
                    //not same method in same class
                    if (!className.equals(dClass.getName()) || !behavior.getSignature().equals(method.getSignature()))
                        f.replace("$_ = $0." + name + "();");
                } catch (NotFoundException e) {
                    logger.trace("not found getter", e);
                }
            }
        }
    }
}
