package ameba.dev;

import ameba.dev.sun.tools.attach.BsdVirtualMachine;
import ameba.dev.sun.tools.attach.LinuxVirtualMachine;
import ameba.dev.sun.tools.attach.SolarisVirtualMachine;
import ameba.dev.sun.tools.attach.WindowsVirtualMachine;
import ameba.i18n.Messages;
import com.google.common.collect.Lists;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.sun.tools.attach.spi.AttachProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * @author icode
 */
public class AgentLoader {

    private static final Logger logger = LoggerFactory.getLogger(AgentLoader.class);

    private static final List<String> loaded = Lists.newArrayList();
    private static VirtualMachine vm;
    private static final AttachProvider ATTACH_PROVIDER = new AttachProvider() {
        @Override
        public String name() {
            return "AmebaAttachProvider";
        }

        @Override
        public String type() {
            return getClass().getName();
        }

        @Override
        public VirtualMachine attachVirtualMachine(String id) {
            return vm;
        }

        @Override
        public List<VirtualMachineDescriptor> listVirtualMachines() {
            return Lists.newArrayList(new VirtualMachineDescriptor(this, type(), name()));
        }
    };

    private static String discoverPid() {
        String nameOfRunningVM = ManagementFactory.getRuntimeMXBean().getName();
        int p = nameOfRunningVM.indexOf('@');
        return nameOfRunningVM.substring(0, p);
    }

    /**
     * Load an agent providing the full file path.
     *
     * @param jarFilePath jar file path
     */
    public static void loadAgent(String jarFilePath) {
        loadAgent(jarFilePath, "");
    }

    /**
     * Load an agent providing the full file path with parameters.
     *
     * @param jarFilePath jar file path
     * @param params      params
     */
    public static void loadAgent(String jarFilePath, String params) {

        logger.info(Messages.get("dev.loading.jvm.angent", jarFilePath));
        try {

            String pid = discoverPid();

            if (AttachProvider.providers().isEmpty()) {
                vm = getVirtualMachineImplementationFromEmbeddedOnes(pid);
            } else {
                vm = VirtualMachine.attach(pid);
            }

            if (vm == null) {
                logger.warn("VirtualMachine or OS Platform not support JVM Agent");
                return;
            }
            vm.loadAgent(jarFilePath, params);
            vm.detach();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Load the agent from the classpath using its name.
     *
     * @param agentName agent name
     */
    public static void loadAgentFromClasspath(String agentName) {
        loadAgentFromClasspath(agentName, "");
    }

    /**
     * Load the agent from the classpath using its name and passing params.
     *
     * @param agentName agent name
     * @param params    params
     * @return boolean
     */
    public synchronized static boolean loadAgentFromClasspath(String agentName, String params) {

        if (loaded.contains(agentName)) {
            // the agent is already loaded
            return true;
        }
        try {
            // Search for the agent jar in the classpath
            if (AgentLoader.class.getClassLoader() instanceof URLClassLoader) {
                URLClassLoader cl = (URLClassLoader) (AgentLoader.class.getClassLoader());
                for (URL url : cl.getURLs()) {
                    if (isMatch(url, agentName)) {
                        // We have found the agent jar in the classpath
                        String fullName = url.toURI().getPath();
                        if (fullName.startsWith("/") && isWindows()) {
                            fullName = fullName.substring(1);
                        }
                        loadAgent(fullName, params);
                        loaded.add(fullName);
                        return true;
                    }
                }
            }

            // Agent not found and not loaded
            return false;

        } catch (URISyntaxException use) {
            throw new RuntimeException(use);
        }
    }

    /**
     * Check to see if this url/jar matches our agent name.
     *
     * @param url     url
     * @param partial partial
     */
    private static boolean isMatch(URL url, String partial) {
        String fullPath = url.getFile();
        int lastSlash = fullPath.lastIndexOf('/');
        if (lastSlash < 0) {
            return false;
        }
        String jarName = fullPath.substring(lastSlash + 1);
        // Use startsWith so ignoring the version of the agent
        return jarName.startsWith(partial);
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }

    private static VirtualMachine getVirtualMachineImplementationFromEmbeddedOnes(String pid) {
        try {
            if (isWindows()) {
                return new WindowsVirtualMachine(ATTACH_PROVIDER, pid);
            }

            String osName = System.getProperty("os.name");

            if (osName.startsWith("Linux") || osName.startsWith("LINUX")) {
                return new LinuxVirtualMachine(ATTACH_PROVIDER, pid);

            } else if (osName.startsWith("Mac OS X")) {
                return new BsdVirtualMachine(ATTACH_PROVIDER, pid);

            } else if (osName.startsWith("Solaris")) {
                return new SolarisVirtualMachine(ATTACH_PROVIDER, pid);
            }

        } catch (AttachNotSupportedException | IOException e) {
            throw new RuntimeException(e);
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException("Native library for Attach API not available in this JRE", e);
        }

        return null;
    }
}
