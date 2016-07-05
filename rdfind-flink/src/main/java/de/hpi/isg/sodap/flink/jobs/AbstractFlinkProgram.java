package de.hpi.isg.sodap.flink.jobs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.core.fs.Path;

import de.hpi.isg.sodap.flink.util.FileUtils;
import de.hpi.isg.sodap.util.configuration.StratosphereParameters;
import de.hpi.isg.sodap.util.gp.CollectionUtils;

/**
 * This class gives a template for jobs that profile with CSV files. In particular, it takes care of resolving input
 * directories to files and indexing the columns in all files with unique IDs.
 * 
 * @author Sebastian Kruse
 */
public abstract class AbstractFlinkProgram<TParameters> extends AbstractProgram<TParameters> {

    /**
     * This degree of parallelism is returned by {@link #getMaxDop()} if no degree was specified by the user.
     */
    protected static final int DEFAULT_DOP = 10;
    
    /**
     * Represents an environment that is defined by the configuration of this job.
     * 
     * @see #getStratosphereParameters()
     */
    protected ExecutionEnvironment executionEnvironment;

    /**
     * A list that keeps track of job execution results. The order of job execution is preserved.
     */
    protected final List<JobMeasurement> jobMeasurements = new ArrayList<JobMeasurement>();

    /**
     * The path for the temp folder that can be created with {@link #prepareTempFolder(List)}.
     */
    protected Path tempFolder;

    /**
     * Creates a new {@link AbstractFlinkProgram} instance.
     * 
     * @param args
     *        are, e.g., command line parameters to configure this job.
     */
    public AbstractFlinkProgram(final String... args) {
        super(args);
    }

    /**
     * Executes the plan that was created on the {@link #executionEnvironment} and also prints out some measurement
     * informations.
     * 
     * @param planName
     *        is the name of the plan to be executed
     * @throws Exception
     */
    protected void executePlan(final String planName) throws Exception {
        getLogger().info("Execute plan \"{}\".", planName);
        
        final long startTime = System.currentTimeMillis();
        final JobExecutionResult result = this.executionEnvironment.execute(planName);
        final long endTime = System.currentTimeMillis();
        final JobMeasurement jobMeasurement = new JobMeasurement(planName, startTime, endTime, result);
        
        getLogger().info("Finished plan {}.", planName);
        getLogger().info("Plan runtime: {} ms (net {} ms)", jobMeasurement.getDuration(), result.getNetRuntime());

        this.jobMeasurements.add(jobMeasurement);
    }

    /**
     * Subclasses must configure {@link StratosphereParameters} in the {@link #initialize(String...)} method and provide
     * them via this method.
     * 
     * @return the configured {@link StratosphereParameters}
     */
    abstract protected StratosphereParameters getStratosphereParameters();

    /**
     * Creates a temporary folder located near one of the given files. This folder will be automatically deleted, unless
     * no clean-up is desired.
     * 
     * @param files
     *        are a bunch of files that give a hint where to create the temp folder
     * @throws IOException
     */
    protected void prepareTempFolder(final List<Path> files) throws IOException {

        // Prepare temp folder.
        final Path arbitraryFile = CollectionUtils.getAny(files);
        final Path parent = arbitraryFile.getParent();
        this.tempFolder = FileUtils.ensureEmptyDirectory(parent, "temp", null);
    }
    
    @Override
    protected void prepareProgramLogic() throws Exception {
        super.prepareProgramLogic();

        if (this.getStratosphereParameters() != null) {
            this.executionEnvironment = getStratosphereParameters().createExecutionEnvironment();
            int waitTime = getStratosphereParameters().waitTime;
            if (waitTime > 0) {
                getLogger().info("Waiting {} seconds before execution.", waitTime);
                Thread.sleep(1000 * waitTime);
                getLogger().info("Waiting is over.");
            }
        }
//        if (this.executionEnvironment instanceof LocalEnvironment) {
        // TODO: Remove when stable Flink version does not need this anymore
        // LogUtils.initializeDefaultConsoleLogger();
        // ((LocalEnvironment) this.executionEnvironment).enableLogging();
//        }

    }
    
    @Override
    protected void cleanUp() throws Exception {
        if (this.tempFolder != null) {
            if (!FileUtils.remove(this.tempFolder, true)) {
                System.err.format("Could not remove temporary folder %s.", this.tempFolder);
            }
        }
    }

    @Override
    protected void printProgramStatistics() {
        super.printProgramStatistics();
        
        // Log accumulator results.
        getLogger().info("Accumulator results:");
        int jobNum = 0;
        for (final JobMeasurement jobMeasurement : this.jobMeasurements) {
            getLogger().info("Plan {}:", jobNum);
            for (final Map.Entry<String, Object> entry : new TreeMap<>(jobMeasurement.getFlinkResults().getAllAccumulatorResults()).entrySet()) {
                getLogger().info("* \"{}\": {}", entry.getKey(), entry.getValue());
            }
            jobNum++;
        }
        
        // Log detailed runtimes.
        getLogger().info("Execution time summary:");
        long runtimeSum = 0;
        final long overallRuntime = this.programEndTime - this.programStartTime;
        final long preparationRuntime = this.preparationEndTime - this.programStartTime;
        final long programRuntime = this.programEndTime - this.preparationEndTime;
        long lastPlanEndTime = -1;
        for (int planNumber = 0; planNumber < this.jobMeasurements.size(); planNumber++) {
            JobMeasurement jobMeasurement = this.jobMeasurements.get(planNumber);
            if (lastPlanEndTime != -1) {
                final double inBetweenPlansTime = (jobMeasurement.getStartTime() - lastPlanEndTime) / 1000d;
                getLogger().info(
                        String.format("Break:     %7.3f s ", inBetweenPlansTime));
            }
            lastPlanEndTime = jobMeasurement.getEndTime();
            
            final double internalPlanTime = jobMeasurement.getFlinkResults().getNetRuntime() / 1000d;
            final double externalPlanTime = jobMeasurement.getDuration() / 1000d;
            runtimeSum += jobMeasurement.getDuration();

            getLogger().info(
                    String.format("Plan %2d:     %7.3f s | %7.3f s (%s)", planNumber, internalPlanTime, externalPlanTime, jobMeasurement.getName()));
        }
        getLogger().info(String.format("Preparation: %7.3f s", preparationRuntime / 1000d));
        getLogger().info(String.format("Remainder:   %7.3f s", (programRuntime - runtimeSum) / 1000d));
        getLogger().info(String.format("Overall:     %7.3f s", overallRuntime / 1000d));

        getLogger().info("Runtimes as CSV");
        getLogger().info("overall;preparation;remainder;n=1;n=2;...");
        final StringBuilder sb = new StringBuilder();
        sb.append(String.format("%7.3f;%7.3f;%7.3f", overallRuntime / 1000d, preparationRuntime / 1000d, (programRuntime - runtimeSum) / 1000d));
        for (JobMeasurement jobMeasurement : this.jobMeasurements) {
            sb.append(String.format(";%7.3f", jobMeasurement.getDuration() / 1000d));
        }
        getLogger().info(sb.toString());
    }
    
    @Override
    protected boolean isCleanUpRequested() {
        return getStratosphereParameters() != null && getStratosphereParameters().shallCleanUp();
    }

    @Override
    protected void onExit() {
        super.onExit();
        if (this.getStratosphereParameters() != null) {
            this.getStratosphereParameters().closeMiniClusterIfExists();
        }
    }

    /**
     * Measures the execution time of a job. Also captures Flink's profiling results.
     *
     * @author Sebastian Kruse
     */
    public static class JobMeasurement {
        
        private final long startTime, endTime;
        
        private final String name;
        
        private final JobExecutionResult flinkResults;

        public JobMeasurement(String name, long startTime, long endTime, JobExecutionResult flinkResults) {
            super();
            this.name = name;
            this.startTime = startTime;
            this.endTime = endTime;
            this.flinkResults = flinkResults;
        }

        @Override
        public String toString() {
            return "JobMeasurement [name=" + name + ", " + getDuration() + " ms]";
        }

        /**
         * @return the flinkResults
         */
        public JobExecutionResult getFlinkResults() {
            return flinkResults;
        }
        
        public long getStartTime() {
            return startTime;
        }

        public long getEndTime() {
            return endTime;
        }

        public String getName() {
            return name;
        }

        public long getDuration() {
            return this.endTime - this.startTime;
        }
        
    }
}