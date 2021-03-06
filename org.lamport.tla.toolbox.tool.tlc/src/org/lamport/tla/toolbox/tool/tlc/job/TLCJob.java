package org.lamport.tla.toolbox.tool.tlc.job;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jface.action.Action;
import org.lamport.tla.toolbox.spec.Spec;
import org.lamport.tla.toolbox.tool.tlc.TLCActivator;
import org.lamport.tla.toolbox.tool.tlc.launch.IModelConfigurationConstants;
import org.lamport.tla.toolbox.tool.tlc.launch.IModelConfigurationDefaults;
import org.lamport.tla.toolbox.tool.tlc.launch.TraceExplorerDelegate;
import org.lamport.tla.toolbox.tool.tlc.model.Model;
import org.lamport.tla.toolbox.tool.tlc.result.IResultPresenter;
import org.lamport.tla.toolbox.tool.tlc.util.ModelHelper;
import org.lamport.tla.toolbox.util.ResourceHelper;
import org.lamport.tla.toolbox.util.ToolboxJob;

import tlc2.TLCGlobals;

/**
 * Abstract TLC job
 * @author Simon Zambrovski
 */
public abstract class TLCJob extends AbstractJob implements IModelConfigurationConstants, IModelConfigurationDefaults
{

    /**
     * Job family identifier for all org.lamport toolbox jobs
     */
    public static final String AllJobsMatcher = ToolboxJob.FAMILY;

    protected static final int STEP = 30;
    /*
     * Number of minutes between printing of coverage data.
     */
    private static final int COVERAGE_INTERVAL = 3;
    
    /*
     * Number of minutes between checkpoints.  It was changed from 20 or 30 to 3,
     * apparently by Simon Z.  Changed to 15 by LL for 10 Apr 2012 release
     * 
     */
    private static final int CHECKPOINT_INTERVAL = 15 ;  
    
    protected long timeout = 1000L;
    protected IFile rootModule;
    protected IFile cfgFile;
    protected IFile outFile;
    protected IFolder launchDir;
    protected int workers = 1;
    protected ILaunch launch;
    protected String modelName;
    protected final String specName;

    protected boolean appendConsole = true;

    public TLCJob(String specName, String modelName, ILaunch launch)
    {
    	this(specName, modelName, launch, 1);
    }
    
    public TLCJob(String specName, String modelName, ILaunch launch, int workers)
    {
    	this(specName, modelName, launch, workers, "");
    }
    
    protected String filePrefix = "";
    
    /**
     * Creates a TLC job for a given spec and model
     * @param workers2 
     * @param rootModule
     * @param cfgFile
     * @param launchDir
     * @param workers number of threads to be run in parallel
     */
    public TLCJob(String specName, String modelName, ILaunch launch, int workers, String filePrefix)
    {
        super("TLC run for " + modelName);
        this.specName = specName;
        this.modelName = modelName;
        this.workers = workers;
        this.filePrefix = filePrefix;

        IProject project = ResourceHelper.getProject(specName);
        Assert.isNotNull(project, "Error accessing the spec project " + specName);

        this.launchDir = project.getFolder(modelName);
        Assert.isNotNull(this.launchDir, "Error accessing the model folder " + modelName);

        this.launch = launch;

        if (launch.getLaunchMode().equals(TraceExplorerDelegate.MODE_TRACE_EXPLORE))
        {
            this.rootModule = this.launchDir.getFolder(filePrefix).getFile(ModelHelper.TE_FILE_TLA);
            this.cfgFile = this.launchDir.getFolder(filePrefix).getFile(ModelHelper.TE_FILE_CFG);
            this.outFile = this.launchDir.getFolder(filePrefix).getFile(ModelHelper.TE_FILE_OUT);
        } else
        {
            this.rootModule = this.launchDir.getFolder(filePrefix).getFile(ModelHelper.FILE_TLA);
            this.cfgFile = this.launchDir.getFolder(filePrefix).getFile(ModelHelper.FILE_CFG);
            this.outFile = this.launchDir.getFolder(filePrefix).getFile(ModelHelper.FILE_OUT);
        }
    }

    /**
     * Reads the model parameters and constructs the corresponding command line arguments
     * @return string array with arguments
     * @throws CoreException
     */
    protected String[] constructProgramArguments() throws CoreException
    {
    	final List<String> arguments = new ArrayList<String>();
        ILaunchConfiguration config = launch.getLaunchConfiguration();

        // deadlock
        boolean checkDeadlock = config.getAttribute(IModelConfigurationConstants.MODEL_CORRECTNESS_CHECK_DEADLOCK,
                IModelConfigurationDefaults.MODEL_CORRECTNESS_CHECK_DEADLOCK_DEFAULT);
        if (!checkDeadlock) /* "!" added by LL on 22 Aug 2009 */
        {
            arguments.add("-deadlock");
        }

        // adjust checkpointing
       	arguments.add("-checkpoint");
       	arguments.add(String.valueOf(CHECKPOINT_INTERVAL));

        boolean hasSpec = config.getAttribute(MODEL_BEHAVIOR_SPEC_TYPE, MODEL_BEHAVIOR_TYPE_DEFAULT) != IModelConfigurationDefaults.MODEL_BEHAVIOR_TYPE_NO_SPEC;

        if (hasSpec)
        {
            boolean runAsModelCheck = config.getAttribute(IModelConfigurationConstants.LAUNCH_MC_MODE,
                    IModelConfigurationDefaults.LAUNCH_MC_MODE_DEFAULT);
            if (runAsModelCheck)
            {
                // look for advanced model checking parameters
                boolean isDepthFirst = config.getAttribute(IModelConfigurationConstants.LAUNCH_DFID_MODE,
                        IModelConfigurationDefaults.LAUNCH_DFID_MODE_DEFAULT);
                if (isDepthFirst)
                {
                    // for depth-first run, look for the depth
                    int dfidDepth = config.getAttribute(IModelConfigurationConstants.LAUNCH_DFID_DEPTH,
                            IModelConfigurationDefaults.LAUNCH_DFID_DEPTH_DEFAULT);
                    arguments.add("-dfid");
                    arguments.add(String.valueOf(dfidDepth));
                }
            } else
            {
                arguments.add("-simulate");

                // look for advanced simulation parameters
                int traceDepth = config.getAttribute(IModelConfigurationConstants.LAUNCH_SIMU_DEPTH,
                        IModelConfigurationDefaults.LAUNCH_SIMU_DEPTH_DEFAULT);
                if (traceDepth != IModelConfigurationDefaults.LAUNCH_SIMU_DEPTH_DEFAULT)
                {
                    arguments.add("-depth");
                    arguments.add(String.valueOf(traceDepth));
                }

                int aril = config.getAttribute(IModelConfigurationConstants.LAUNCH_SIMU_ARIL,
                        IModelConfigurationDefaults.LAUNCH_SIMU_ARIL_DEFAULT);
                int seed = config.getAttribute(IModelConfigurationConstants.LAUNCH_SIMU_SEED,
                        IModelConfigurationDefaults.LAUNCH_SIMU_SEED_DEFAULT);
                if (aril != IModelConfigurationDefaults.LAUNCH_SIMU_ARIL_DEFAULT)
                {
                	arguments.add("-aril");
                	arguments.add(String.valueOf(aril));
                }
                if (seed != IModelConfigurationDefaults.LAUNCH_SIMU_SEED_DEFAULT)
                {
                	arguments.add("-seed");
                	arguments.add(String.valueOf(seed));
                }
            }
        }

        // recover from checkpoint
        if (hasSpec)
        {
            boolean recover = config.getAttribute(IModelConfigurationConstants.LAUNCH_RECOVER,
                    IModelConfigurationDefaults.LAUNCH_RECOVER_DEFAULT);
            if (recover)
            {
                IResource[] checkpoints = config.getAdapter(Model.class).getCheckpoints(false);
                if (checkpoints.length > 0)
                {
                	arguments.add("-recover");
                	arguments.add(checkpoints[0].getName());
                }
            }
        }
        
        // fpBits
        int fpBits = launch.getLaunchConfiguration().getAttribute(LAUNCH_FPBITS, -1);
        if(fpBits >= 0) {
        	arguments.add("-fpbits");
        	arguments.add(String.valueOf(fpBits));
        }
        
        // fp seed offset (decrease by one to map from [1, 64] interval to [0, 63] array address
        final int fpSeedOffset = launch.getLaunchConfiguration().getAttribute(LAUNCH_FP_INDEX, LAUNCH_FP_INDEX_DEFAULT);
        arguments.add("-fp");
        arguments.add(String.valueOf(fpSeedOffset - 1));
        
        // add maxSetSize argument if not equal to the default
        // code added by LL on 9 Mar 2012
        final int maxSetSize = launch.getLaunchConfiguration().getAttribute(
                LAUNCH_MAXSETSIZE, TLCGlobals.setBound);
        if (maxSetSize != TLCGlobals.setBound) {
        	arguments.add("-maxSetSize");
        	arguments.add(String.valueOf(maxSetSize));
        }
        
        arguments.add("-config");
        arguments.add(cfgFile.getName()); // configuration file

        // Should not add a coverage option only if TLC is being run
        // without a spec. This change added 10 Sep 2009 by LL & DR
        if (config.getAttribute(MODEL_BEHAVIOR_SPEC_TYPE, MODEL_BEHAVIOR_TYPE_DEFAULT) != MODEL_BEHAVIOR_TYPE_NO_SPEC)
        {
        	// coverage 0.1 hour
        	arguments.add("-coverage");
        	arguments.add(String.valueOf(COVERAGE_INTERVAL)); 
        }
        
        // number of workers
        arguments.add("-workers");
        arguments.add(String.valueOf(workers));
        
        // debugging only
        //arguments.put("-debug", null); 
        
        // run in tool mode
        arguments.add("-tool");
        
        // running in directory
        arguments.add("-metadir");
        arguments.add(launchDir.getLocation().toOSString()); 
        
		// name of the module to check
        arguments.add(ResourceHelper.getModuleName(rootModule)); 

        // Replace any of the above parameters if explicitly given as extra TLC parameters
		final ILaunchConfiguration launchConfig = launch.getLaunchConfiguration();
		final String tlcParameters = launchConfig.getAttribute(LAUNCH_TLC_PARAMETERS, (String) null);
		if (tlcParameters != null && !"".equals(tlcParameters.trim())) {
			final String[] split = tlcParameters.trim().split("\\s+");
			arguments.addAll(Arrays.asList(split));
		}

        return (String[]) arguments.toArray(new String[arguments.size()]);
    }

	/**
     * Returns the action that tells all registered result
     * presenters to show results. Result presenters are registered
     * using the extension point {@link IResultPresenter#EXTENSION_ID}.
     */
    protected Action getJobCompletedAction()
    {
        return new Action("View job results") {
            public void run()
            {
                IResultPresenter[] registeredResultPresenters = getRegisteredResultPresenters();
                for (int i = 0; i < registeredResultPresenters.length; i++)
                {
                	Model model = launch.getLaunchConfiguration().getAdapter(Model.class);
                    registeredResultPresenters[i].showResults(model);
                }
            }
        };
    }

    /**
     * The run method
     */
    protected abstract IStatus run(IProgressMonitor monitor);

    /**
     * Checks if TLC is still running
     * @return true, if TLC is still running
     */
    protected boolean checkAndSleep()
    {
        try
        {
            // go sleep
            Thread.sleep(timeout);

        } catch (InterruptedException e)
        {
            e.printStackTrace();
        }
        // return true if the TLC is still calculating
        return checkCondition();
    }
    
    protected abstract boolean checkCondition();

    /**
     * Matches the spec (by name) or generic to the AllJobMatcher
     */
    public boolean belongsTo(Object family)
    {
        if (family != null)
        {
        	if (family instanceof Model) {
                return (this.launch.getLaunchConfiguration().getAdapter(Model.class).equals(family));
        	} else if (family instanceof ILaunchConfiguration)
            {
                return (this.launch.getLaunchConfiguration().contentsEqual((ILaunchConfiguration) family));
            } else if (family instanceof Spec)
            {
                Spec spec = (Spec) family;
                return (spec.getName().equals(this.specName));
            } else if (AllJobsMatcher.equals(family))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Retrieves all presenter of TLC job results
     * @return 
     */
    protected static IResultPresenter[] getRegisteredResultPresenters()
    {
        IConfigurationElement[] decls = Platform.getExtensionRegistry().getConfigurationElementsFor(
                IResultPresenter.EXTENSION_ID);

        Vector<IResultPresenter> validExtensions = new Vector<IResultPresenter>();
        for (int i = 0; i < decls.length; i++)
        {
            try
            {
                IResultPresenter extension = (IResultPresenter) decls[i].createExecutableExtension("class");
                validExtensions.add(extension);
            } catch (CoreException e)
            {
                TLCActivator.logError("Error instatiating the IResultPresenter extension", e);
            }
        }
        return validExtensions.toArray(new IResultPresenter[validExtensions.size()]);
    }
}