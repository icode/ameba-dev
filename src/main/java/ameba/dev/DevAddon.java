package ameba.dev;

import ameba.core.Addon;
import ameba.core.Application;
import ameba.dev.classloading.EnhanceClassEvent;
import ameba.dev.classloading.EnhancerListener;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.dev.exception.DevException;
import ameba.dev.info.ProjectInfo;
import ameba.i18n.Messages;
import ameba.util.ClassUtils;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * @author icode
 */
public class DevAddon extends Addon {
    private static final Logger logger = LoggerFactory.getLogger(DevAddon.class);
    private static final String POM_FILE_NAME = "pom.xml";
    private static final String POM_ENDS_WITH_FILE_NAME = "/" + POM_FILE_NAME;
    private static final String DEFAULT_SOURCE_DIR = "src/main/java";

    @Override
    public void setup(final Application app) {
        if (!app.getMode().isDev()) {
            return;
        }

        logger.warn(Messages.get("dev.mode.enabled"));

        subscribeEvent(EnhanceClassEvent.class, new EnhancerListener());

        logger.info(Messages.get("dev.find.package.dir"));
        ProjectInfo info = readMavenModel();

        logger.info(Messages.get("dev.app.base.dir", ProjectInfo.root().getBaseDirectory()));

        List<Path> packages = info.getAllSourceDirectories();
        logger.info(Messages.get("dev.app.package.dirs", StringUtils.join(
                Collections2.transform(packages,
                        new Function<Path, String>() {
                            @NotNull
                            @Override
                            public String apply(@NotNull Path path) {
                                return path.toString();
                            }
                        }), ',' + System.getProperty("line.separator", "\n"))
                )
        );

        Enhancing.loadEnhancers(app.getSrcProperties());

        ClassLoader classLoader = ClassUtils.getContextClassLoader();

        if (!(classLoader instanceof ReloadClassLoader)) {
            classLoader = new ReloadClassLoader(packages);
            app.setClassLoader(classLoader);
        }
        Thread.currentThread().setContextClassLoader(classLoader);

        HotswapJvmAgent.initialize();
    }

    private ProjectInfo readMavenModel(ProjectInfo parent, String file) {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        File pomFile;
        if (StringUtils.isBlank(file)) file = POM_FILE_NAME;
        else if (!file.endsWith(POM_ENDS_WITH_FILE_NAME) && !file.equals(POM_FILE_NAME)) {
            file += POM_ENDS_WITH_FILE_NAME;
        }
        if (parent != null) {
            pomFile = parent.getBaseDirectory().resolve(file).toFile();
        } else {
            pomFile = new File(file);
        }

        Model mavenModel;
        try (FileInputStream in = new FileInputStream(pomFile)) {
            mavenModel = reader.read(in);
            mavenModel.setPomFile(pomFile);
        } catch (IOException | XmlPullParserException e) {
            throw new DevException(Messages.get("dev.read.pom.error"), e);
        }

        File baseDirFile = mavenModel.getProjectDirectory();
        Path baseDir = baseDirFile.toPath(), sourceDir;
        String sourceDirFile = mavenModel.getBuild().getSourceDirectory();

        if (sourceDirFile == null) {
            sourceDirFile = DEFAULT_SOURCE_DIR;
        }

        sourceDir = baseDir.resolve(sourceDirFile);
        ProjectInfo info;
        if (parent == null)
            info = ProjectInfo.createRoot(
                    baseDir,
                    sourceDir
            );
        else
            info = ProjectInfo.create(parent, baseDir, sourceDir);

        for (String module : mavenModel.getModules()) {
            readMavenModel(info, module);
        }

        return info;
    }

    private ProjectInfo readMavenModel() {
        return readMavenModel(null, null);
    }
}
