package ameba.dev;

import ameba.event.Event;

import java.lang.instrument.ClassDefinition;
import java.util.List;

/**
 * @author icode
 */
public class ClassReloadEvent implements Event {
    List<ClassDefinition> classes;

    public ClassReloadEvent(List<ClassDefinition> classes) {
        this.classes = classes;
    }

    public List<ClassDefinition> getClasses() {
        return classes;
    }
}
