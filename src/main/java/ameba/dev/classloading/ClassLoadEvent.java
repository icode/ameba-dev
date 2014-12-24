package ameba.dev.classloading;

import ameba.dev.classloading.enhance.ClassDescription;
import ameba.event.Event;

/**
 * @author icode
 * @since 14-12-24
 */
public class ClassLoadEvent implements Event {
    private ClassDescription classDescription;

    public ClassDescription getClassDescription() {
        return classDescription;
    }

    public ClassLoadEvent(ClassDescription classDescription) {
        this.classDescription = classDescription;
    }
}
