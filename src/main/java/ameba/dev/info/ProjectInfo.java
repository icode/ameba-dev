package ameba.dev.info;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.BooleanUtils;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;

/**
 * 项目信息
 *
 * @author icode
 */
public class ProjectInfo implements Serializable {

    private static ProjectInfo ROOT;
    private Path sourceDirectory;
    private Path outputDirectory;
    private Path baseDirectory;
    private ProjectInfo parent;
    private List<ProjectInfo> modules = Lists.newArrayList();

    protected ProjectInfo(ProjectInfo parent, Path baseDirectory, Path sourceDirectory, Path outputDirectory) {
        this.parent = parent;
        this.baseDirectory = baseDirectory;
        this.sourceDirectory = sourceDirectory;
        this.outputDirectory = outputDirectory;
    }

    protected ProjectInfo(Path baseDirectory, Path sourceDirectory, Path outputDirectory) {
        this(null, baseDirectory, sourceDirectory, outputDirectory);
    }

    public static ProjectInfo root() {
        return ROOT;
    }

    public static ProjectInfo createRoot(Path baseDirectory, Path sourceDirectory, Path classesDirectory) {
        ROOT = new ProjectInfo(null, baseDirectory, sourceDirectory, classesDirectory);
        return root();
    }

    public static ProjectInfo create(ProjectInfo parent, Path baseDirectory,
                                     Path sourceDirectory, Path classesDirectory) {
        ProjectInfo info = new ProjectInfo(baseDirectory, sourceDirectory, classesDirectory);
        if (parent != null) {
            parent.addModule(info);
        }
        return info;
    }

    public List<Path> getAllSourceDirectories() {
        final List<Path> sds = Lists.newArrayList();
        forEach(new InfoVisitor<ProjectInfo, Boolean>() {
            @Override
            public Boolean visit(ProjectInfo projectInfo) {
                sds.add(projectInfo.getSourceDirectory());
                return true;
            }
        });
        return sds;
    }

    public void forEach(InfoVisitor<ProjectInfo, Boolean> visitor) {
        if (visitor.visit(this) && hasModule()) {
            visit(this, visitor);
        }
    }

    private void visit(ProjectInfo projectInfo, InfoVisitor<ProjectInfo, Boolean> visitor) {
        if (!(visitor instanceof Visitor)) {
            visitor = new Visitor(visitor);
        }
        Visitor vtor = (Visitor) visitor;
        if (vtor.isContinue() && projectInfo.hasModule()) {
            for (ProjectInfo info : projectInfo.getModules()) {
                if (!vtor.visit(info)) {
                    return;
                }
            }
            for (ProjectInfo info : projectInfo.getModules()) {
                visit(info, vtor);
                if (!vtor.isContinue()) {
                    return;
                }
            }
        }
    }

    public boolean isRoot() {
        return getParent() == null;
    }

    public boolean hasModule() {
        return getModules() != null && !getModules().isEmpty();
    }

    public void addModule(ProjectInfo... modules) {
        for (ProjectInfo info : modules) {
            if (info != null) {
                info.setParent(this);
                getModules().add(info);
            }
        }
    }

    public Path getSourceDirectory() {
        return sourceDirectory;
    }

    public void setSourceDirectory(Path sourceDirectory) {
        this.sourceDirectory = sourceDirectory;
    }

    public Path getBaseDirectory() {
        return baseDirectory;
    }

    public void setBaseDirectory(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    public ProjectInfo getParent() {
        return parent;
    }

    public void setParent(ProjectInfo parent) {
        this.parent = parent;
    }

    public List<ProjectInfo> getModules() {
        return modules;
    }

    public void setModules(List<ProjectInfo> modules) {
        this.modules = modules;
    }

    public Path getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    private static class Visitor implements InfoVisitor<ProjectInfo, Boolean> {
        private Boolean _continue = true;
        private InfoVisitor<ProjectInfo, Boolean> visitor;

        public Visitor(InfoVisitor<ProjectInfo, Boolean> visitor) {
            this.visitor = visitor;
        }

        @Override
        public Boolean visit(ProjectInfo projectInfo) {
            return _continue = visitor.visit(projectInfo);
        }

        public boolean isContinue() {
            return BooleanUtils.isTrue(_continue);
        }
    }
}
