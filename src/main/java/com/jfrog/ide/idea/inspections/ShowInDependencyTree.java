package com.jfrog.ide.idea.inspections;

import com.intellij.codeInsight.intention.HighPriorityAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Iconable;
import com.intellij.util.ui.tree.TreeUtil;
import com.jfrog.ide.common.nodes.DependencyNode;
import com.jfrog.ide.idea.ui.LocalComponentsTree;
import com.jfrog.ide.idea.ui.utils.IconUtils;
import com.jfrog.ide.idea.utils.Utils;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Adds the yellow bulb action - "Show in JFrog plugin".
 *
 * @author yahavi
 */
public class ShowInDependencyTree implements LocalQuickFix, Iconable, HighPriorityAction {

    final static String SHOW_IN_TREE_MESSAGE = "Show vulnerability info in JFrog plugin";
    private final DependencyNode node;
    private final boolean isIndirect;

    public ShowInDependencyTree(DependencyNode node, boolean isIndirect) {
        this.node = node;
        this.isIndirect = isIndirect;
    }

    @Override
    public Icon getIcon(int flags) {
        return IconUtils.load("jfrog_icon");
    }

    @NotNull
    @Override
    public String getFamilyName() {
        String description = SHOW_IN_TREE_MESSAGE;
        if (isIndirect) {
            description += " (transitive dependency:  <" + node.getTitle() + ">)";
        }
        return description;
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        Utils.focusJFrogToolWindow(project);
        TreeUtil.selectInTree(project, node, true, LocalComponentsTree.getInstance(project), true);
    }
}