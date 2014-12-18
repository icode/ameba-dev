package ameba.dev;

import ameba.Ameba;
import ameba.exception.AmebaException;
import ameba.exception.SourceAttachment;
import ameba.mvc.ErrorPageGenerator;
import ameba.mvc.template.internal.Viewables;
import com.google.common.collect.Lists;
import org.glassfish.jersey.server.mvc.Viewable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Response;
import java.io.*;
import java.util.List;

/**
 * @author icode
 */
public class DevErrorPageGenerator extends ErrorPageGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DevErrorPageGenerator.class);
    private static final String DEFAULT_5XX_DEV_ERROR_PAGE = ErrorPageGenerator.DEFAULT_ERROR_PAGE_DIR + "dev_500.html";

    @Override
    public Response toResponse(Throwable exception) {
        ContainerRequestContext request = requestProvider.get();
        int status = 500;
        if (exception instanceof WebApplicationException) {
            status = ((WebApplicationException) exception).getResponse().getStatus();
        }
        if (status >= 500 && Ameba.getApp().getMode().isDev()) {
            //开发模式，显示详细错误信息
            Error error = new Error(
                    request,
                    status,
                    exception);

            Viewable viewable = Viewables.newDefaultViewable(DEFAULT_5XX_DEV_ERROR_PAGE, error);

            if (status == 500)
                logger.error("服务器错误", exception);

            return Response.status(status).entity(viewable).build();
        } else {
            return super.toResponse(exception);
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

        public Error() {
        }

        public Error(ContainerRequestContext request, int status, Throwable exception) {
            this.status = status;
            this.exception = exception;
            this.request = request;
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
                                    lineIndex = source.size();
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
