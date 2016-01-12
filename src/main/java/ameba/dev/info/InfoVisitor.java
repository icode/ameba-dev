package ameba.dev.info;

/**
 * @author icode
 */
public interface InfoVisitor<INFO, RESULT> {
    RESULT visit(INFO info);
}
