package ameba.dev.info;

import ameba.i18n.Messages;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author icode
 */
public class MavenProjects {
    private static final Logger logger = LoggerFactory.getLogger(ProjectInfo.class);
    private static final String POM_FILE_NAME = "pom.xml";
    private static final String POM_ENDS_WITH_FILE_NAME = "/" + POM_FILE_NAME;

    private MavenProjects() {
    }

    public static ProjectInfo load(ProjectInfo parent, String file) {
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
        logger.debug(Messages.get("dev.load.maven.module", pomFile.getPath()));
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest()
                .setPomFile(pomFile)
                .setLocationTracking(false)
                .setProcessPlugins(false)
                .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
                .setTwoPhaseBuilding(false);
        try {
            ModelBuildingResult result = (new DefaultModelBuilderFactory()).newInstance().build(request);
            Model model = result.getEffectiveModel();
            Build build = model.getBuild();
            Path baseDir = model.getProjectDirectory().toPath(),
                    sourceDir = Paths.get(build.getSourceDirectory()),
                    outDir = Paths.get(build.getOutputDirectory());

            ProjectInfo info;
            if (parent == null)
                info = ProjectInfo.createRoot(
                        baseDir,
                        sourceDir,
                        outDir
                );
            else
                info = ProjectInfo.create(parent, baseDir, sourceDir, outDir);

            for (String module : model.getModules()) {
                load(info, module);
            }
            return info;
        } catch (ModelBuildingException e) {
            logger.error(Messages.get("dev.read.pom.xml.error"), e);
        }

        return null;
    }

    public static ProjectInfo load() {
        logger.info(Messages.get("dev.load.maven.project"));
        return load(null, null);
    }

}
