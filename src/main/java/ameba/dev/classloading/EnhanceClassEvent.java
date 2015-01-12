package ameba.dev.classloading;

import ameba.event.Event;

/**
 * @author icode
 * @since 14-12-24
 */
public class EnhanceClassEvent implements Event {
    private ClassDescription classDescription;

    public ClassDescription getClassDescription() {
        return classDescription;
    }

    public EnhanceClassEvent(ClassDescription classDescription) {
        this.classDescription = classDescription;
    }
}
