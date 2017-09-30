package ameba.dev;

import ameba.core.Addon;
import ameba.core.Application;
import ameba.dev.classloading.EnhanceClassEvent;
import ameba.dev.classloading.EnhancerListener;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.dev.info.MavenProjects;
import ameba.dev.info.ProjectInfo;
import ameba.i18n.Messages;
import ameba.util.ClassUtils;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;
import java.nio.file.Path;
import java.util.List;

/**
 * @author icode
 */
public class DevAddon extends Addon {
    private static final Logger logger = LoggerFactory.getLogger(DevAddon.class);

    @Override
    public void setup(final Application app) {
        if (!app.getMode().isDev()) {
            return;
        }

        logger.warn(Messages.get("dev.mode.enabled"));

        subscribeEvent(EnhanceClassEvent.class, new EnhancerListener(app.getSrcProperties()));

        logger.info(Messages.get("dev.find.package.dir"));

        MavenProjects.load();

        logger.info(Messages.get("dev.app.base.dir", ProjectInfo.root().getBaseDirectory()));

        List<Path> packages = ProjectInfo.root().getAllSourceDirectories();
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

        ClassLoader classLoader = ClassUtils.getContextClassLoader();

        if (!(classLoader instanceof ReloadClassLoader)) {
            classLoader = new ReloadClassLoader(ProjectInfo.root());
            app.setClassLoader(classLoader);
        }
        Thread.currentThread().setContextClassLoader(classLoader);

        Enhancing.loadEnhancers(app.getSrcProperties());

        HotswapJvmAgent.initialize();
    }
}
