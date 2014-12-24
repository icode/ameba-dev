package ameba.dev;

import ameba.core.AddOn;
import ameba.core.Application;
import ameba.dev.classloading.ReloadClassLoader;
import com.google.common.collect.FluentIterable;
import com.google.common.io.Files;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author icode
 */
public class DevAddOn extends AddOn {
    private static final Logger logger = LoggerFactory.getLogger(DevFeature.class);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^(\\s*(/\\*|\\*|//))");//注释正则
    private static final Pattern PKG_PATTERN = Pattern.compile("^(\\s*package)\\s+([_a-zA-Z][_a-zA-Z0-9\\.]+)\\s*;$");//包名正则

    public static boolean searchPackageRoot(File f, Application application) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(f));

            String line = null;
            while (StringUtils.isBlank(line)) {
                line = reader.readLine();
                //匹配注释
                Matcher m = COMMENT_PATTERN.matcher(line);
                if (m.find()) {
                    line = null;
                }
            }
            if (line == null) return false;
            Matcher m = PKG_PATTERN.matcher(line);

            if (m.find()) {
                String pkg = m.group(2);
                String[] dirs = pkg.split("\\.");
                ArrayUtils.reverse(dirs);
                File pf = f.getParentFile();
                boolean isPkg = true;
                for (String dir : dirs) {
                    if (!pf.getName().equals(dir)) {
                        isPkg = false;
                        break;
                    }
                    pf = pf.getParentFile();
                }
                if (isPkg && pf != null) {
                    if (pf.toURI().normalize().getPath().endsWith("/test/java/")) {
                        pf = new File(pf, "../../main/java").getCanonicalFile();
                    }
                    application.setPackageRoot(pf);
                    return true;
                }
            }

        } catch (FileNotFoundException e) {
            logger.error("find package root dir has error", e);
        } catch (IOException e) {
            logger.error("find package root dir has error", e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    logger.warn("close file input stream error", e);
                }
            }
        }
        return false;
    }

    public static void searchPackageRoot(Application application) {
        FluentIterable<File> iterable = Files.fileTreeTraverser()
                .breadthFirstTraversal(application.getSourceRoot());
        for (File f : iterable) {
            if (f.getName().endsWith(".java") && f.canRead()) {
                if (searchPackageRoot(f, application)) {
                    break;
                }
            }
        }
    }

    @Override
    public void setup(final Application app) {
        logger.warn("当前应用程序为开发模式");
        String sourceRootStr = System.getProperty("app.source.root");

        if (StringUtils.isNotBlank(sourceRootStr)) {
            app.setSourceRoot(new File(sourceRootStr));
        } else {
            app.setSourceRoot(new File("").getAbsoluteFile());
        }

        logger.info("应用源码根路径为：{}", app.getSourceRoot().getAbsolutePath());

        logger.info("查找包根目录...");

        if (app.getSourceRoot().exists() && app.getSourceRoot().isDirectory()) {
            searchPackageRoot(app);
            if (app.getPackageRoot() == null) {
                logger.info("未找到包根目录，很多功能将失效，请确认项目内是否有Java源文件，如果确实存在Java源文件，" +
                        "请设置项目根目录的JVM参数，添加 -Dapp.source.root=${yourAppRootDir}");
                logger.debug("打开文件监听，寻找包根目录...");
                long interval = TimeUnit.SECONDS.toMillis(4);
                final FileAlterationObserver observer = new FileAlterationObserver(
                        app.getSourceRoot(),
                        FileFilterUtils.and(
                                FileFilterUtils.fileFileFilter(),
                                FileFilterUtils.suffixFileFilter(".java")));
                final FileAlterationMonitor monitor = new FileAlterationMonitor(interval, observer);
                observer.addListener(new FileAlterationListenerAdaptor() {
                    @Override
                    public void onFileCreate(File pFile) {
                        if (pFile.getName().endsWith(".java") && pFile.canRead()) {
                            if (searchPackageRoot(pFile, app)) {
                                logger.debug("找到包根目录为：{}，退出监听。", pFile.getAbsolutePath());
                                try {
                                    monitor.stop();
                                } catch (Exception e) {
                                    logger.info("停止监控目录发生错误", e);
                                }
                            }
                        }
                    }
                });
                try {
                    monitor.start();
                } catch (Exception e) {
                    logger.info("监控目录发生错误", e);
                }
            } else {
                logger.info("包根目录为:{}", app.getPackageRoot().getAbsolutePath());
            }
        } else {
            logger.info("未找到项目根目录，很多功能将失效，请设置项JVM参数，添加 -Dapp.source.root=${yourAppRootDir}");
        }

        final ClassLoader classLoader = new ReloadClassLoader(app);
        app.setClassLoader(classLoader);
        Thread.currentThread().setContextClassLoader(classLoader);

        HotswapJvmAgent.initialize();
    }
}
