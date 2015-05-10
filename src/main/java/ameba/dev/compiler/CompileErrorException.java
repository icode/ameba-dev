package ameba.dev.compiler;

import ameba.exception.AmebaExceptionWithJavaSource;

import javax.tools.Diagnostic;
import java.net.URL;
import java.util.Collections;
import java.util.List;

public class CompileErrorException extends AmebaExceptionWithJavaSource {

    private List<Diagnostic> diagnostics;

    public CompileErrorException(String message, Throwable cause,
                                 Integer line, Integer lineIndex,
                                 URL sourceUrl, List<String> source,
                                 List<Diagnostic> diagnostics) {
        super(message, cause, line, lineIndex, sourceUrl, source);
        this.diagnostics = diagnostics;
    }

    public List<Diagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }
}
