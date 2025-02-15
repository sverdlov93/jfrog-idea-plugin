package com.jfrog.ide.idea.inspections;

import com.goide.vgo.mod.psi.VgoModuleSpec;
import com.goide.vgo.mod.psi.VgoRequireDirective;
import com.goide.vgo.mod.psi.VgoVisitor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.jfrog.ide.idea.scan.ScanManager;
import com.jfrog.ide.idea.scan.ScanManagersFactory;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

/**
 * Created by Bar Belity on 17/02/2020.
 */

public class GoInspection extends AbstractInspection {

    public GoInspection() {
        super("go.mod");
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new VgoVisitor() {
            @Override
            public void visitModuleSpec(@NotNull VgoModuleSpec element) {
                super.visitPsiElement(element);
                GoInspection.this.visitElement(holder, element, isOnTheFly);
            }
        };
    }

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof VgoModuleSpec) {
            GoInspection.this.visitElement(holder, element);
        }
    }

    @Override
    PsiElement[] getTargetElements(PsiElement element) {
        return new PsiElement[]{element};
    }

    @Override
    boolean isDependency(PsiElement element) {
        PsiElement parentElement = element.getParent();
        return parentElement instanceof VgoRequireDirective;
    }

    @Override
    ScanManager getScanManager(Project project, String path) {
        return ScanManagersFactory.getScanManagers(project).stream()
                .filter(manager -> StringUtils.equals(manager.getProjectPath(), path))
                .findAny()
                .orElse(null);
    }


    @Override
    String createComponentName(PsiElement element) {
        return element.getFirstChild().getText();
    }
}
