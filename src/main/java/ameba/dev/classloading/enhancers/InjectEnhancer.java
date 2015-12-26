package ameba.dev.classloading.enhancers;

import ameba.dev.classloading.ClassDescription;
import ameba.exception.AmebaException;
import com.google.common.collect.Lists;
import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.hk2.api.ServiceLocator;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.ws.rs.core.Context;
import java.util.List;

/**
 * @author icode
 */
public class InjectEnhancer extends Enhancer {
    private static final CtClass PROV_CLASS;
    private static final CtClass LOCATOR_CLASS;

    static {
        try {
            PROV_CLASS = ClassPool.getDefault().getCtClass(Provider.class.getName());
            LOCATOR_CLASS = ClassPool.getDefault().getCtClass(ServiceLocator.class.getName());
        } catch (NotFoundException e) {
            throw new AmebaException(e);
        }
    }

    public InjectEnhancer() {
        super(true);
    }


    private boolean isInjectField(CtField field) throws NotFoundException {
        return !isFinal(field) && !isStatic(field)
                && (field.hasAnnotation(Inject.class)
                || field.hasAnnotation(Context.class))
                && !field.getType().subclassOf(PROV_CLASS)
                && !field.getType().subclassOf(LOCATOR_CLASS);
    }

    @Override
    public void enhance(ClassDescription description) throws Exception {
        CtClass ctClass = makeClass(description);

        final List<String> changeFields = Lists.newArrayList();

        for (CtField field : ctClass.getDeclaredFields()) {
            if (!isInjectField(field)) continue;
            String type = field.getGenericSignature();
            if (StringUtils.isBlank(type)) type = "L" + field.getType().getName().replace(".", "/") + ";";
            field.setType(PROV_CLASS);
            field.setGenericSignature("Ljavax/inject/Provider<" + type + ">;");
            changeFields.add(field.getName());
        }

        if (changeFields.size() > 0) {
            for (final CtBehavior ctBehavior : ctClass.getDeclaredBehaviors()) {
                ctBehavior.instrument(new ExprEditor() {
                    @Override
                    public void edit(FieldAccess f) throws CannotCompileException {
                        String fName = f.getFieldName();
                        if (changeFields.contains(fName)) {
                            if (f.isReader()) {
                                f.replace("$_ = ($r)$0." + fName + ".get();");
                            } else if (f.isWriter()) {
                                f.replace("$0." + fName + " = ameba.inject.DelegateProvider.create($1);");
                            }
                        }
                    }
                });
            }
            changeFields.clear();
        }
        description.enhancedByteCode = ctClass.toBytecode();
        ctClass.defrost();
    }
}
