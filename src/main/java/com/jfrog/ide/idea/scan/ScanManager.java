package com.jfrog.ide.idea.scan;

import com.google.common.collect.Sets;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInspection.GlobalInspectionContext;
import com.intellij.codeInspection.InspectionEngine;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.jfrog.ide.common.configuration.ServerConfig;
import com.jfrog.ide.common.log.ProgressIndicator;
import com.jfrog.ide.common.scan.ComponentPrefix;
import com.jfrog.ide.common.scan.ScanLogic;
import com.jfrog.ide.common.tree.DependencyNode;
import com.jfrog.ide.common.tree.FileTreeNode;
import com.jfrog.ide.common.tree.ImpactTreeNode;
import com.jfrog.ide.common.tree.IssueNode;
import com.jfrog.ide.idea.configuration.GlobalSettings;
import com.jfrog.ide.idea.inspections.AbstractInspection;
import com.jfrog.ide.idea.inspections.JFrogSecurityWarning;
import com.jfrog.ide.idea.log.Logger;
import com.jfrog.ide.idea.log.ProgressIndicatorImpl;
import com.jfrog.ide.idea.ui.ComponentsTree;
import com.jfrog.ide.idea.ui.LocalComponentsTree;
import com.jfrog.ide.idea.ui.menus.filtermanager.ConsistentFilterManager;
import com.jfrog.ide.idea.utils.Utils;
import com.jfrog.xray.client.services.summary.Components;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.NotNull;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.extractor.scan.DependencyTree;

import javax.swing.*;
import javax.swing.tree.TreeNode;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jfrog.ide.common.log.Utils.logError;

/**
 * Created by romang on 4/26/17.
 */
public abstract class ScanManager {
    private final ServerConfig serverConfig;
    private final ComponentPrefix prefix;
    private final Log log;
    private ScanLogic scanLogic;
    private ExecutorService executor;
    protected Project project;
    String basePath;

    // Lock to prevent multiple simultaneous scans
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

    protected SourceCodeScannerManager scanner;

    /**
     * @param project  - Currently opened IntelliJ project. We'll use this project to retrieve project based services
     *                 like {@link ConsistentFilterManager} and {@link ComponentsTree}
     * @param basePath - Project base path
     * @param prefix   - Components prefix for xray scan, e.g. gav:// or npm://
     * @param executor - An executor that should limit the number of running tasks to 3
     */
    ScanManager(@NotNull Project project, String basePath, ComponentPrefix prefix, ExecutorService executor) {
        this.serverConfig = GlobalSettings.getInstance().getServerConfig();
        this.prefix = prefix;
        this.log = Logger.getInstance();
        this.executor = executor;
        this.basePath = basePath;
        this.project = project;
        this.scanner = new SourceCodeScannerManager(project, getCodeBaseLanguage());
    }

    void setExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Collect and return {@link Components} to be scanned by JFrog Xray.
     * Implementation should be project type specific.
     */
    protected abstract DependencyTree buildTree() throws IOException;

    /**
     * Return all project descriptors under the scan-manager project, which need to be inspected by the corresponding {@link LocalInspectionTool}.
     *
     * @return all project descriptors under the scan-manager project to be inspected.
     */
    protected abstract PsiFile[] getProjectDescriptors();

    /**
     * Return the Inspection tool corresponding to the scan-manager type.
     * The returned Inspection tool is used to perform the inspection on the project-descriptor files.
     *
     * @return the Inspection tool corresponding to the scan-manager type.
     */
    protected abstract AbstractInspection getInspectionTool();

    protected void sendUsageReport() {
        Utils.sendUsageReport(getPackageManagerName() + "-deps");
    }

    protected abstract String getPackageManagerName();

    /**
     * Groups a collection of DependencyNodes by the descriptor files of the modules that depend on them.
     * The returned DependencyNodes inside the FileTreeNodes might be clones of the ones in depScanResults, but it's not
     * guaranteed.
     *
     * @param depScanResults - collection of DependencyNodes.
     * @param depMap         - a map of DependencyTree objects by their component ID.
     * @return A list of FileTreeNodes (that are all DescriptorFileTreeNodes) having the DependencyNodes as their children.
     */
    protected abstract List<FileTreeNode> groupDependenciesToDescriptorNodes(Collection<DependencyNode> depScanResults, Map<String, List<DependencyTree>> depMap);

    public abstract String getPackageType();

    /**
     * Scan and update dependency components.
     *
     * @param indicator - The progress indicator
     */
    private void scanAndUpdate(ProgressIndicator indicator) {
        try {
            indicator.setText("1/3: Building dependency tree");
            DependencyTree dependencyTree = buildTree();
            indicator.setText("2/3: Xray scanning project dependencies");
            log.debug("Start scan for '" + basePath + "'.");
            Map<String, DependencyNode> results = scanLogic.scanArtifacts(dependencyTree, serverConfig, indicator, prefix, this::checkCanceled);
            indicator.setText("3/3: Finalizing");
            if (results == null || results.isEmpty()) {
                // No violations/vulnerabilities or no components to scan or an error was thrown
                return;
            }
            Map<String, List<DependencyTree>> depMap = new HashMap<>();
            mapDependencyTree(depMap, dependencyTree);

            Map<String, List<IssueNode>> issuesMap = mapIssuesByCve(results);

            createImpactPaths(results, depMap, dependencyTree);
            List<FileTreeNode> fileTreeNodes = groupDependenciesToDescriptorNodes(results.values(), depMap);
            addScanResults(fileTreeNodes);

            // Source Code Scan
            scanner.scanAndUpdate(indicator, List.copyOf(issuesMap.keySet()));
            addScanResults(scanner.getResults(issuesMap));
            fileTreeNodes.forEach(node -> node.sortChildren());

            // Force inspections run due to changes in the displayed tree
            runInspections();

        } catch (ProcessCanceledException e) {
            log.info("Xray scan was canceled");
        } catch (Exception e) {
            logError(log, "Xray Scan failed", e, true);
        } finally {
            scanInProgress.set(false);
            sendUsageReport();
        }
    }

    /**
     * Maps a DependencyTree and its children (direct and indirect) by their component IDs.
     *
     * @param depMap - a Map that the entries will be added to. This Map must be initialized.
     * @param root   - the DependencyTree to map.
     */
    private void mapDependencyTree(Map<String, List<DependencyTree>> depMap, DependencyTree root) {
        depMap.putIfAbsent(root.getComponentId(), new ArrayList<>());
        depMap.get(root.getComponentId()).add(root);
        for (DependencyTree child : root.getChildren()) {
            mapDependencyTree(depMap, child);
        }
    }

    /**
     * Maps all the issues (vulnerabilities and security violations) by their CVE IDs.
     * Issues without a CVE ID are ignored.
     *
     * @param results - scan results mapped by dependencies.
     * @return a map of CVE IDs to lists of issues with them.
     */
    private Map<String, List<IssueNode>> mapIssuesByCve(Map<String, DependencyNode> results) {
        Map<String, List<IssueNode>> issues = new HashMap<>();
        for (DependencyNode dep : results.values()) {
            Enumeration<TreeNode> treeNodeEnumeration = dep.children();
            while (treeNodeEnumeration.hasMoreElements()) {
                TreeNode node = treeNodeEnumeration.nextElement();
                if (!(node instanceof IssueNode)) {
                    continue;
                }
                IssueNode issueNode = (IssueNode) node;
                String cveId = issueNode.getCve().getCveId();
                if (issueNode.getCve() == null || StringUtils.isBlank(cveId)) {
                    continue;
                }
                issues.putIfAbsent(cveId, new ArrayList<>());
                issues.get(cveId).add(issueNode);
            }
        }
        return issues;
    }

    /**
     * Builds impact paths for DependencyNode objects.
     *
     * @param dependencies - a map of component IDs and the DependencyNode object matching each of them.
     * @param depMap       - a map of component IDs and lists of DependencyTree objects matching each of them.
     * @param root         - the DependencyTree object of the root component of the project/module.
     */
    private void createImpactPaths(Map<String, DependencyNode> dependencies, Map<String, List<DependencyTree>> depMap, DependencyTree root) {
        for (Map.Entry<String, DependencyNode> depEntry : dependencies.entrySet()) {
            Map<DependencyTree, ImpactTreeNode> impactTreeNodes = new HashMap<>();
            for (DependencyTree depTree : depMap.get(depEntry.getKey())) {
                addImpactPath(impactTreeNodes, depTree);
            }
            depEntry.getValue().setImpactPaths(impactTreeNodes.get(root));
        }
    }

    private ImpactTreeNode addImpactPath(Map<DependencyTree, ImpactTreeNode> impactTreeNodes, DependencyTree depTreeNode) {
        if (impactTreeNodes.containsKey(depTreeNode)) {
            return impactTreeNodes.get(depTreeNode);
        }
        ImpactTreeNode parentImpactTreeNode = null;
        if (depTreeNode.getParent() != null) {
            parentImpactTreeNode = addImpactPath(impactTreeNodes, (DependencyTree) depTreeNode.getParent());
        }
        ImpactTreeNode currImpactTreeNode = new ImpactTreeNode(depTreeNode.getComponentId());
        if (parentImpactTreeNode != null) {
            parentImpactTreeNode.getChildren().add(currImpactTreeNode);
        }
        impactTreeNodes.put(depTreeNode, currImpactTreeNode);
        return currImpactTreeNode;
    }

    /**
     * Launch async dependency scan.
     */
    void asyncScanAndUpdateResults() {
        if (DumbService.isDumb(project)) { // If intellij is still indexing the project
            return;
        }
        // The tasks run asynchronously. To make sure no more than 3 tasks are running concurrently,
        // we use a count down latch that signals to that executor service that it can get more tasks.
        CountDownLatch latch = new CountDownLatch(1);
        Task.Backgroundable scanAndUpdateTask = new Task.Backgroundable(null, getTaskTitle()) {
            @Override
            public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                if (project.isDisposed()) {
                    return;
                }
                if (!GlobalSettings.getInstance().areXrayCredentialsSet()) {
                    log.warn("Xray server is not configured.");
                    return;
                }
                // Prevent multiple simultaneous scans
                if (!scanInProgress.compareAndSet(false, true)) {
                    log.info("Scan already in progress");
                    return;
                }
                scanAndUpdate(new ProgressIndicatorImpl(indicator));
            }

            @Override
            public void onFinished() {
                latch.countDown();
            }
        };
        if (executor.isShutdown() || executor.isTerminated()) {
            // Scan initiated by a change in the project descriptor
            createRunnable(scanAndUpdateTask, null).run();
        } else {
            // Scan initiated by opening IntelliJ, by user, or by changing the configuration
            executor.submit(createRunnable(scanAndUpdateTask, latch));
        }
    }

    /**
     * Get text to display in the task progress.
     *
     * @return text to display in the task progress.
     */
    private String getTaskTitle() {
        Path projectBasePath = Utils.getProjectBasePath(project);
        Path wsBasePath = Paths.get(basePath);
        String relativePath = "";
        if (projectBasePath.isAbsolute() == wsBasePath.isAbsolute()) {
            // If one of the path is relative and the other one is absolute, the following exception is thrown:
            // IllegalArgumentException: 'other' is different type of Path
            relativePath = projectBasePath.relativize(wsBasePath).toString();
        }
        return "JFrog Xray scanning " + StringUtils.defaultIfBlank(relativePath, project.getName());
    }

    /**
     * Create a runnable to be submitted to the executor service, or run directly.
     *
     * @param scanAndUpdateTask - The task to submit
     * @param latch             - The countdown latch, which makes sure the executor service doesn't get more than 3 tasks.
     *                          If null, the scan was initiated by a change in the project descriptor and the executor
     *                          service is terminated. In this case, there is no requirement to wait.
     */
    private Runnable createRunnable(Task.Backgroundable scanAndUpdateTask, CountDownLatch latch) {
        return () -> {
            // The progress manager is only good for foreground threads.
            if (SwingUtilities.isEventDispatchThread()) {
                scanAndUpdateTask.queue();
            } else {
                // Run the scan task when the thread is in the foreground.
                ApplicationManager.getApplication().invokeLater(scanAndUpdateTask::queue);
            }
            try {
                // Wait for scan to finish, to make sure the thread pool remain full
                if (latch != null) {
                    latch.await();
                }
            } catch (InterruptedException e) {
                logError(log, ExceptionUtils.getRootCauseMessage(e), e, true);
            }
        };
    }

    /**
     * Returns all project modules locations as Paths.
     * Other scanners such as npm will use these paths in order to find modules.
     *
     * @return all project modules locations as Paths
     */
    public Set<Path> getProjectPaths() {
        Set<Path> paths = Sets.newHashSet();
        paths.add(Paths.get(basePath));
        return paths;
    }

    void runInspections() {
        ApplicationManager.getApplication().invokeLater(() -> {
            PsiFile[] projectDescriptors = getProjectDescriptors();
            if (ArrayUtils.isEmpty(projectDescriptors)) {
                return;
            }
            InspectionManagerEx inspectionManagerEx = (InspectionManagerEx) InspectionManager.getInstance(project);
            GlobalInspectionContext context = inspectionManagerEx.createNewGlobalContext(false);
            AbstractInspection localInspectionTool = getInspectionTool();
            localInspectionTool.setAfterScan(true);
            for (PsiFile descriptor : projectDescriptors) {
                // Run inspection on descriptor.
                InspectionEngine.runInspectionOnFile(descriptor, new LocalInspectionToolWrapper(localInspectionTool), context);
                FileEditor[] editors = FileEditorManager.getInstance(project).getAllEditors(descriptor.getVirtualFile());
                if (!ArrayUtils.isEmpty(editors)) {
                    // Refresh descriptor highlighting only if it is already opened.
                    DaemonCodeAnalyzer.getInstance(project).restart(descriptor);
                }
            }
        });
    }

    /**
     * filter scan components tree model according to the user filters and sort the issues tree.
     */
    private void addScanResults(List<FileTreeNode> fileTreeNodes) {
        if (fileTreeNodes.isEmpty()) {
            return;
        }
        LocalComponentsTree componentsTree = LocalComponentsTree.getInstance(project);
        componentsTree.addScanResults(fileTreeNodes);
    }

    protected void checkCanceled() {
        if (project.isOpen()) {
            // The project is closed if we are in test mode.
            // In tests, we can't check if the user canceled the scan, since we don't have the ProgressManager service.
            ProgressManager.checkCanceled();
        }
    }

    boolean isScanInProgress() {
        return this.scanInProgress.get();
    }

    public String getProjectPath() {
        return this.basePath;
    }

    public void setScanLogic(ScanLogic scanLogic) {
        this.scanLogic = scanLogic;
    }

    public Log getLog() {
        return log;
    }

    public List<JFrogSecurityWarning> getSourceCodeScanResults() {
        return this.scanner.getScanResults();
    }

    private String getCodeBaseLanguage() {
        switch (getPackageType().toLowerCase()) {
            case "npm":
                return "js";
            case "pip":
                return "python";
            case "maven":
            case "gradle":
                return "java";
            default:
                return "";
        }
    }
}
