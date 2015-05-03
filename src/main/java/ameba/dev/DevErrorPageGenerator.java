package ameba.dev;

import ameba.core.Application;
import ameba.exception.AmebaException;
import ameba.exception.SourceAttachment;
import ameba.message.error.ErrorMessage;
import ameba.mvc.ErrorPageGenerator;
import ameba.mvc.template.internal.Viewables;
import com.google.common.collect.Lists;
import org.glassfish.jersey.server.mvc.Viewable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

/**
 * @author icode
 */
public class DevErrorPageGenerator extends ErrorPageGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DevErrorPageGenerator.class);
    private static final String DEFAULT_5XX_DEV_ERROR_PAGE = ErrorPageGenerator.DEFAULT_ERROR_PAGE_DIR + "dev_500.httl";

    @Inject
    private Application application;

    @Override
    public void writeTo(ErrorMessage errorMessage,
                        Class<?> type, Type genericType,
                        Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        ContainerRequestContext request = requestProvider.get();
        int status = errorMessage.getStatus();

        if (status >= 500 && application.getMode().isDev()) {
            //开发模式，显示详细错误信息
            Error error = new Error(
                    request,
                    status,
                    errorMessage.getThrowable(),
                    errorMessage);

            Viewable viewable = Viewables.newDefaultViewable(DEFAULT_5XX_DEV_ERROR_PAGE, error);
            writeViewable(viewable, mediaType, httpHeaders, entityStream);
        } else {
            super.writeTo(errorMessage, type, genericType, annotations, mediaType, httpHeaders, entityStream);
        }
    }

    public static class Error implements SourceAttachment {
        private int status;
        private ContainerRequestContext request;
        private Throwable exception;
        private File sourceFile;
        private List<String> source;
        private List<UsefulSource> usefulSources;
        private Integer line;
        private Integer lineIndex;
        private String method;
        private ErrorMessage errorMessage;

        public Error() {
        }

        public Error(ContainerRequestContext request, int status, Throwable exception, ErrorMessage errorMessage) {
            this.status = status;
            this.exception = exception;
            this.request = request;
            this.errorMessage = errorMessage;
            if (exception instanceof SourceAttachment) {
                SourceAttachment e = (SourceAttachment) exception;
                sourceFile = e.getSourceFile();
                source = e.getSource();
                line = e.getLineNumber();
                lineIndex = e.getLineIndex();
            } else {
                AmebaException.InterestingSomething something = AmebaException.getInterestingSomething(exception);
                if (something == null) return;
                line = something.getStackTraceElement().getLineNumber();
                File f = something.getSourceFile();
                sourceFile = f;
                method = something.getStackTraceElement().getMethodName();
                source = Lists.newArrayList();
                usefulSources = Lists.newArrayList();
                if (f.exists()) {
                    LineNumberReader reader = null;
                    try {
                        reader = new LineNumberReader(new FileReader(f));
                        int bl = line < 5 ? 0 : line - 5;
                        String l;
                        while ((l = reader.readLine()) != null) {
                            if (bl <= reader.getLineNumber() && reader.getLineNumber() < bl + 11) {

                                if (reader.getLineNumber() == line) {
                                    line = source.size();
                                }

                                source.add(l);
                            }
                        }
                    } catch (FileNotFoundException e) {
                        logger.error("open source file has error", e);
                    } catch (IOException e) {
                        logger.error("read source file has error", e);
                    } finally {
                        if (reader != null)
                            try {
                                reader.close();
                            } catch (IOException e) {
                                logger.error("close source file input stream has error", e);
                            }
                    }
                    int i = 0;
                    for (StackTraceElement el : something.getUsefulStackTraceElements()) {
                        LineNumberReader usefulReader = null;
                        try {
                            File uf = something.getUsefulFiles().get(i);
                            usefulReader = new LineNumberReader(new FileReader(uf));
                            UsefulSource u = new UsefulSource();
                            u.lineNumber = el.getLineNumber();
                            while (u.lineNumber <= usefulReader.getLineNumber()) {
                                if (u.lineNumber == usefulReader.getLineNumber())
                                    u.source = usefulReader.readLine();
                            }
                            u.sourceFile = uf;
                            usefulSources.add(u);
                        } catch (FileNotFoundException e) {
                            logger.error("open source file has error", e);
                        } catch (IOException e) {
                            logger.error("read source file has error", e);
                        } finally {
                            if (usefulReader != null)
                                try {
                                    usefulReader.close();
                                } catch (IOException e) {
                                    logger.error("close source file input stream has error", e);
                                }
                            i++;
                        }
                    }
                }
            }
        }

        public ErrorMessage getErrorMessage() {
            return errorMessage;
        }

        public int getStatus() {
            return status;
        }

        public ContainerRequestContext getRequest() {
            return request;
        }

        public Throwable getException() {
            return exception;
        }

        public boolean isSourceAvailable() {
            return getSourceFile() != null;
        }

        @Override
        public File getSourceFile() {
            return sourceFile;
        }

        @Override
        public File[] getSourceFiles() {
            return new File[]{sourceFile};
        }

        @Override
        public List<String> getSource() {
            return source;
        }

        @Override
        public Integer getLineNumber() {
            return line;
        }

        public String getMethod() {
            return method;
        }

        public Integer getLineIndex() {
            return lineIndex;
        }

        public List<UsefulSource> getUsefulSources() {
            return usefulSources;
        }

        public static class UsefulSource {
            int lineNumber;
            String source;
            File sourceFile;

            public int getLineNumber() {
                return lineNumber;
            }

            public File getSourceFile() {
                return sourceFile;
            }

            public String getSource() {
                return source;
            }
        }
    }
}
