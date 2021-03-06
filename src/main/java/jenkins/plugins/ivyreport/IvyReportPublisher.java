/*
 * The MIT License
 *
 * Copyright (c) 2012, Cedric Chabanois
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE. 
 */
package jenkins.plugins.ivyreport;

import hudson.Extension;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.Util;
import hudson.ivy.ModuleName;
import hudson.ivy.IvyModule;
import hudson.ivy.IvyModuleSet;
import hudson.ivy.IvyModuleSetBuild;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Publish the ivy dependency report
 * 
 * @author Cedric Chabanois (cchabanois at gmail.com)
 * 
 */
public class IvyReportPublisher extends Recorder {
    @SuppressWarnings("unused")
    @Deprecated
    private transient String resolveId;

    private final String ivyReportConfigurations;

    @DataBoundConstructor
    public IvyReportPublisher(String ivyReportConfigurations) {
        this.ivyReportConfigurations = ivyReportConfigurations;
    }

    public String getIvyReportConfigurations() {
        return ivyReportConfigurations;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public Collection<? extends Action> getProjectActions(
            AbstractProject<?, ?> project) {
        return Collections.singletonList(new IvyReportProjectAction(
                (IvyModuleSet) project));
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener) throws InterruptedException, IOException {
        if (build.getResult().isWorseThan(Result.UNSTABLE)) {
            listener.getLogger()
                    .println(
                            "Skipping ivy report as build was not UNSTABLE or better ...");
            return true;
        }
        try {
            listener.getLogger().println("Publishing ivy report...");
            IvyModuleSetBuild ivyModuleSetBuild = (IvyModuleSetBuild) build;
            FilePath resolutionCacheRoot = getResolutionCacheRoot(
                    ivyModuleSetBuild, listener);
            if (resolutionCacheRoot == null) {
                return true;
            }
            final File reportsDir = new File(ivyModuleSetBuild.getRootDir(),
                    "ivyreport");
            reportsDir.mkdirs();
            final List<IvyReport> reports = buildPerModuleReports(
                    ivyModuleSetBuild, reportsDir, resolutionCacheRoot);

            build.addAction(new IvyReportBuildAction(reportsDir, reports));
            return true;
        } catch (IOException e) {
            listener.getLogger().println(
                    "Could not generate ivy reports : " + e.getMessage());
            return true;
        }
    }

    private List<IvyReport> buildPerModuleReports(IvyModuleSetBuild build,
            File reportsDir, FilePath resolutionCacheRoot) throws IOException,
            InterruptedException {
        List<IvyReport> result = new ArrayList<IvyReport>();
        for (IvyModule module : build.getProject().getModules()) {
            ModuleName moduleName = module.getModuleName();
            String resolveId = moduleName.organisation + "-" + moduleName.name;

            String[] confs = new IvyAccess(build, module)
                    .expandConfs(getConfs());
            copyIvyReportFilesToMaster(resolutionCacheRoot, resolveId, confs,
                    reportsDir);
            IvyReportGenerator ivyReportGenerator = new IvyReportGenerator(
                    Hudson.getInstance(), resolveId, confs, reportsDir,
                    reportsDir);
            File htmlReport = ivyReportGenerator.generateReports();
            result.add(new IvyReport(module.getModuleName(), new FilePath(
                    htmlReport)));
        }
        return result;
    }

    private String[] getConfs() {
        String condensed = getIvyReportConfigurations().replace(" ", "");
        return condensed.isEmpty() ? new String[] { "*" } : condensed
                .split(",");
    }

    private FilePath getConfigurationResolveReportInCache(
            FilePath resolutionCacheRoot, String resolveId, String conf) {
        return new FilePath(resolutionCacheRoot, resolveId + "-" + conf
                + ".xml");
    }

    private FilePath getResolutionCacheRoot(IvyModuleSetBuild build,
            BuildListener listener) {
        try {
            FilePath resolutionCacheRoot = build.getModuleRoot().act(
                    new GetResolutionCacheRootCallable(listener, build));
            return resolutionCacheRoot;
        } catch (Throwable e) {
            listener.getLogger().println(
                    "Cannot get the ivy resolution cache root : "
                            + e.getMessage());
            return null;
        }
    }

    private void copyIvyReportFilesToMaster(FilePath resolutionCacheRoot,
            String resolveId, String[] confs, File targetDir)
            throws IOException, InterruptedException {
        for (String conf : confs) {
            FilePath report = getConfigurationResolveReportInCache(
                    resolutionCacheRoot, resolveId, conf);
            if (!report.exists()) {
                throw new IOException("Report file does not exist : "
                        + report.getRemote());
            }
            report.copyTo(new FilePath(new File(targetDir, report.getName())));
        }
    }

    @Override
    public BuildStepDescriptor<Publisher> getDescriptor() {
        return DESCRIPTOR;
    }

    /**
     * Descriptor should be singleton.
     */
    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    /**
     * Descriptor for {@link IvyReportPublisher}. Used as singleton.
     * 
     * 
     */
    public static final class DescriptorImpl extends
            BuildStepDescriptor<Publisher> {

        private String dotExe;

        public DescriptorImpl() {
            super(IvyReportPublisher.class);
            load();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json)
                throws hudson.model.Descriptor.FormException {
            dotExe = Util.fixEmptyAndTrim(json.getString("dotExe"));
            save();

            return true;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return IvyModuleSet.class.isAssignableFrom(jobType);
        }

        @Override
        public String getDisplayName() {
            return "Publish ivy dependency report";
        }

        public String getDotExe() {
            return dotExe;
        }

        /**
         * @return configured dot executable or a default
         */
        public String getDotExeOrDefault() {
            if (Util.fixEmptyAndTrim(dotExe) == null) {
                return getDefaultDotExe();
            } else {
                return dotExe;
            }
        }

        public static String getDefaultDotExe() {
            return Functions.isWindows() ? "dot.exe" : "dot";
        }

        public FormValidation doCheckDotExe(@QueryParameter final String value) {
            return FormValidation.validateExecutable(value);
        }

    }

}
