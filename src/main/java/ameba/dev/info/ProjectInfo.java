package ameba.dev.info;

import com.google.common.collect.Lists;

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
    private Path baseDirectory;
    private ProjectInfo parent;
    private List<ProjectInfo> modules = Lists.newArrayList();

    protected ProjectInfo(ProjectInfo parent, Path baseDirectory, Path sourceDirectory) {
        this.parent = parent;
        this.baseDirectory = baseDirectory;
        this.sourceDirectory = sourceDirectory;
    }

    protected ProjectInfo(Path baseDirectory, Path sourceDirectory) {
        this(null, baseDirectory, sourceDirectory);
    }

    public static ProjectInfo root() {
        return ROOT;
    }

    public static ProjectInfo createRoot(Path baseDirectory, Path sourceDirectory) {
        ROOT = new ProjectInfo(null, baseDirectory, sourceDirectory);
        return root();
    }

    public static ProjectInfo create(ProjectInfo parent, Path baseDirectory, Path sourceDirectory) {
        ProjectInfo info = new ProjectInfo(baseDirectory, sourceDirectory);
        if (parent != null) {
            parent.addModule(info);
        }
        return info;
    }

    public List<Path> getAllSourceDirectories() {
        List<Path> sds = Lists.newArrayList();
        if (getSourceDirectory() != null) {
            sds.add(getSourceDirectory());
        }
        if (hasModule()) {
            for (ProjectInfo info : getModules()) {
                sds.addAll(info.getAllSourceDirectories());
            }
        }
        return sds;
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
}
