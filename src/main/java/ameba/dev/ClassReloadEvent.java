package ameba.dev;

import ameba.event.Event;

import java.lang.instrument.ClassDefinition;
import java.util.Set;

/**
 * @author icode
 */
public class ClassReloadEvent implements Event {
    Set<ClassDefinition> classes;

    public ClassReloadEvent(Set<ClassDefinition> classes) {
        this.classes = classes;
    }

    public Set<ClassDefinition> getClasses() {
        return classes;
    }
}
