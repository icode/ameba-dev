package ameba.dev.classloading.enhancers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author icode
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.CLASS)
public @interface Enhanced {
}
