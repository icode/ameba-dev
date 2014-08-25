package ameba.dev;

import ameba.event.Event;

import java.lang.instrument.ClassDefinition;
import java.util.List;

/**
 * @author icode
 */
public class ReloadEvent  extends Event {
    List<ClassDefinition> classes;

    public ReloadEvent(List<ClassDefinition> classes) {
        this.classes = classes;
    }

    public List<ClassDefinition> getClasses() {
        return classes;
    }
}
