package org.apache.maven.shared.release;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmTag;
import org.apache.maven.scm.command.checkout.CheckOutScmResult;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.release.config.ReleaseDescriptor;
import org.apache.maven.shared.release.config.ReleaseDescriptorStore;
import org.apache.maven.shared.release.config.ReleaseDescriptorStoreException;
import org.apache.maven.shared.release.exec.MavenExecutor;
import org.apache.maven.shared.release.exec.MavenExecutorException;
import org.apache.maven.shared.release.phase.ReleasePhase;
import org.apache.maven.shared.release.scm.ReleaseScmCommandException;
import org.apache.maven.shared.release.scm.ReleaseScmRepositoryException;
import org.apache.maven.shared.release.scm.ScmRepositoryConfigurator;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the release manager.
 *
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 */
public class DefaultReleaseManager
    extends AbstractLogEnabled
    implements ReleaseManager
{
    /**
     * The phases of release to run, and in what order.
     */
    private List preparePhases;

    //todo implement
    private List performPhases;

    /**
     * The phases of release to run to rollback changes
     */
    private List rollbackPhases;

    /**
     * The available phases.
     */
    private Map releasePhases;

    /**
     * The configuration storage.
     */
    private ReleaseDescriptorStore configStore;

    /**
     * Tool for configuring SCM repositories from release configuration.
     */
    private ScmRepositoryConfigurator scmRepositoryConfigurator;

    /**
     * Tool to execute Maven.
     */
    private MavenExecutor mavenExecutor;

    private static final int PHASE_SKIP = 0, PHASE_START = 1, PHASE_END = 2, GOAL_START = 11, GOAL_END = 12, ERROR = 99;

    public void prepare( ReleaseDescriptor releaseDescriptor, Settings settings, List reactorProjects )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        prepare( releaseDescriptor, settings, reactorProjects, true, false, null );
    }

    public void prepare( ReleaseDescriptor releaseDescriptor, Settings settings, List reactorProjects, boolean resume,
                         boolean dryRun )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        prepare( releaseDescriptor, settings, reactorProjects, resume, dryRun, null );
    }

    public ReleaseResult prepareWithResult( ReleaseDescriptor releaseDescriptor, Settings settings,
                                            List reactorProjects, boolean resume, boolean dryRun,
                                            ReleaseManagerListener listener )
    {
        ReleaseResult result = new ReleaseResult();

        result.setStartTime( System.currentTimeMillis() );

        try
        {
            prepare( releaseDescriptor, settings, reactorProjects, resume, dryRun, listener, result );

            result.setResultCode( ReleaseResult.SUCCESS );
        }
        catch ( ReleaseExecutionException e )
        {
            captureException( result, listener, e );
        }
        catch ( ReleaseFailureException e )
        {
            captureException( result, listener, e );
        }
        finally
        {
            result.setEndTime( System.currentTimeMillis() );
        }

        return result;
    }

    public void prepare( ReleaseDescriptor releaseDescriptor, Settings settings, List reactorProjects, boolean resume,
                         boolean dryRun, ReleaseManagerListener listener )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        prepare( releaseDescriptor, settings, reactorProjects, resume, dryRun, listener, null );
    }

    private void prepare( ReleaseDescriptor releaseDescriptor, Settings settings, List reactorProjects, boolean resume,
                          boolean dryRun, ReleaseManagerListener listener, ReleaseResult result )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        updateListener( listener, "prepare", GOAL_START );

        ReleaseDescriptor config;
        if ( resume )
        {
            config = loadReleaseDescriptor( releaseDescriptor, listener );
        }
        else
        {
            config = releaseDescriptor;
        }

        // Later, it would be a good idea to introduce a proper workflow tool so that the release can be made up of a
        // more flexible set of steps.

        String completedPhase = config.getCompletedPhase();
        int index = preparePhases.indexOf( completedPhase );

        for ( int idx = 0; idx <= index; idx++ )
        {
            updateListener( listener, preparePhases.get( idx ).toString(), PHASE_SKIP );
        }

        if ( index == preparePhases.size() - 1 )
        {
            logInfo( result, "Release preparation already completed. You can now continue with release:perform, " +
                "or start again using the -Dresume=false flag" );
        }
        else if ( index >= 0 )
        {
            logInfo( result, "Resuming release from phase '" + preparePhases.get( index + 1 ) + "'" );
        }

        // start from next phase
        for ( int i = index + 1; i < preparePhases.size(); i++ )
        {
            String name = (String) preparePhases.get( i );

            ReleasePhase phase = (ReleasePhase) releasePhases.get( name );

            if ( phase == null )
            {
                throw new ReleaseExecutionException( "Unable to find phase '" + name + "' to execute" );
            }

            updateListener( listener, name, PHASE_START );

            ReleaseResult phaseResult = null;
            try
            {
                if ( dryRun )
                {
                    phaseResult = phase.simulate( config, settings, reactorProjects );
                }
                else
                {
                    phaseResult = phase.execute( config, settings, reactorProjects );
                }
            }
            finally
            {
                if ( result != null && phaseResult != null )
                {
                    result.getOutputBuffer().append( phaseResult.getOutput() );
                }
            }

            config.setCompletedPhase( name );
            try
            {
                configStore.write( config );
            }
            catch ( ReleaseDescriptorStoreException e )
            {
                // TODO: rollback?
                throw new ReleaseExecutionException( "Error writing release properties after completing phase", e );
            }

            updateListener( listener, name, PHASE_END );
        }

        updateListener( listener, "prepare", GOAL_END );
    }

    public void rollback( ReleaseDescriptor releaseDescriptor, Settings settings, List reactorProjects )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        rollback( releaseDescriptor, settings, reactorProjects, null );
    }

    public void rollback( ReleaseDescriptor releaseDescriptor, Settings settings, List reactorProjects,
                          ReleaseManagerListener listener )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        updateListener( listener, "rollback", GOAL_START );

        releaseDescriptor = loadReleaseDescriptor( releaseDescriptor, null );

        for ( Iterator phases = rollbackPhases.iterator(); phases.hasNext(); )
        {
            String name = (String) phases.next();

            ReleasePhase phase = (ReleasePhase) releasePhases.get( name );

            if ( phase == null )
            {
                throw new ReleaseExecutionException( "Unable to find phase '" + name + "' to execute" );
            }

            updateListener( listener, name, PHASE_START );
            phase.execute( releaseDescriptor, settings, reactorProjects );
            updateListener( listener, name, PHASE_END );
        }

        //call release:clean so that resume will not be possible anymore after a rollback
        clean( releaseDescriptor, reactorProjects );
        updateListener( listener, "prepare", GOAL_END );
    }

    public void perform( ReleaseDescriptor releaseDescriptor, Settings settings, List reactorProjects,
                         File checkoutDirectory, String goals, boolean useReleaseProfile )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        perform( releaseDescriptor, settings, reactorProjects, checkoutDirectory, goals, useReleaseProfile, null );
    }

    public void perform( ReleaseDescriptor releaseDescriptor, Settings settings, List reactorProjects,
                         File checkoutDirectory, String goals, boolean useReleaseProfile,
                         ReleaseManagerListener listener )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        perform( releaseDescriptor, settings, reactorProjects, checkoutDirectory, goals, useReleaseProfile, listener,
                 new ReleaseResult() );
    }

    public ReleaseResult performWithResult( ReleaseDescriptor releaseDescriptor, Settings settings,
                                            List reactorProjects, File checkoutDirectory, String goals,
                                            boolean useReleaseProfile, ReleaseManagerListener listener )
    {
        ReleaseResult result = new ReleaseResult();

        try
        {
            result.setStartTime( System.currentTimeMillis() );

            perform( releaseDescriptor, settings, reactorProjects, checkoutDirectory, goals, useReleaseProfile,
                     listener, result );

            result.setResultCode( ReleaseResult.SUCCESS );
        }
        catch ( ReleaseExecutionException e )
        {
            captureException( result, listener, e );
        }
        catch ( ReleaseFailureException e )
        {
            captureException( result, listener, e );
        }
        finally
        {
            result.setEndTime( System.currentTimeMillis() );
        }

        return result;
    }

    private void perform( ReleaseDescriptor releaseDescriptor, Settings settings, List reactorProjects,
                          File checkoutDirectory, String goals, boolean useReleaseProfile,
                          ReleaseManagerListener listener, ReleaseResult result )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        updateListener( listener, "perform", GOAL_START );

        logInfo( result, "Checking out the project to perform the release ..." );

        ReleaseDescriptor config = loadReleaseDescriptor( releaseDescriptor, listener );

        updateListener( listener, "verify-completed-prepare-phases", PHASE_START );

        // if we stopped mid-way through preparation - don't perform
        if ( config.getCompletedPhase() != null && !"end-release".equals( config.getCompletedPhase() ) )
        {
            String message = "Cannot perform release - the preparation step was stopped mid-way. Please re-run " +
                "release:prepare to continue, or perform the release from an SCM tag.";

            updateListener( listener, message, ERROR );

            throw new ReleaseFailureException( message );
        }

        if ( config.getScmSourceUrl() == null )
        {
            String message = "No SCM URL was provided to perform the release from";

            updateListener( listener, message, ERROR );

            throw new ReleaseFailureException( message );
        }

        updateListener( listener, "verify-completed-prepare-phases", PHASE_END );

        updateListener( listener, "configure-repositories", PHASE_START );

        ScmRepository repository;
        ScmProvider provider;
        try
        {
            repository = scmRepositoryConfigurator.getConfiguredRepository( config, settings );

            provider = scmRepositoryConfigurator.getRepositoryProvider( repository );
        }
        catch ( ScmRepositoryException e )
        {
            updateListener( listener, e.getMessage(), ERROR );

            throw new ReleaseScmRepositoryException( e.getMessage(), e.getValidationMessages() );
        }
        catch ( NoSuchScmProviderException e )
        {
            updateListener( listener, e.getMessage(), ERROR );

            throw new ReleaseExecutionException( "Unable to configure SCM repository: " + e.getMessage(), e );
        }

        // TODO: sanity check that it is not . or .. or lower

        updateListener( listener, "configure-repositories", PHASE_END );
        updateListener( listener, "checkout-project-from-scm", PHASE_START );

        if ( checkoutDirectory.exists() )
        {
            try
            {
                FileUtils.deleteDirectory( checkoutDirectory );
            }
            catch ( IOException e )
            {
                updateListener( listener, e.getMessage(), ERROR );

                throw new ReleaseExecutionException( "Unable to remove old checkout directory: " + e.getMessage(), e );
            }
        }
        checkoutDirectory.mkdirs();

        CheckOutScmResult scmResult;
        try
        {
            scmResult = provider.checkOut( repository, new ScmFileSet( checkoutDirectory ),
                                           new ScmTag( config.getScmReleaseLabel() ) );
        }
        catch ( ScmException e )
        {
            updateListener( listener, e.getMessage(), ERROR );

            throw new ReleaseExecutionException( "An error is occurred in the checkout process: " + e.getMessage(), e );
        }
        if ( !scmResult.isSuccess() )
        {
            updateListener( listener, scmResult.getProviderMessage(), ERROR );

            throw new ReleaseScmCommandException( "Unable to checkout from SCM", scmResult );
        }

        updateListener( listener, "checkout-project-from-scm", PHASE_END );
        updateListener( listener, "build-project", PHASE_START );

        String additionalArguments = config.getAdditionalArguments();

        if ( useReleaseProfile )
        {
            if ( !StringUtils.isEmpty( additionalArguments ) )
            {
                additionalArguments = additionalArguments + " -DperformRelease=true";
            }
            else
            {
                additionalArguments = "-DperformRelease=true";
            }
        }

        try
        {
            File workingDirectory =
                determineWorkingDirectory( checkoutDirectory, scmResult.getRelativePathProjectDirectory() );
            mavenExecutor.executeGoals( workingDirectory, goals, config.isInteractive(), additionalArguments,
                                        config.getPomFileName(), result );
        }
        catch ( MavenExecutorException e )
        {
            updateListener( listener, e.getMessage(), ERROR );

            throw new ReleaseExecutionException( "Error executing Maven: " + e.getMessage(), e );
        }

        updateListener( listener, "build-project", PHASE_END );

        updateListener( listener, "cleanup", PHASE_START );
        clean( config, reactorProjects );
        updateListener( listener, "cleanup", PHASE_END );

        updateListener( listener, "perform", GOAL_END );
    }

    /**
     * Determines the path of the working directory. By default, this is the
     * checkout directory. For some SCMs, the project root directory is not the
     * checkout directory itself, but a SCM-specific subdirectory.
     *
     * @param checkoutDirectory            The checkout directory as java.io.File
     * @param relativePathProjectDirectory The relative path of the project directory within the checkout
     *                                     directory or ""
     * @return The working directory
     */
    protected File determineWorkingDirectory( File checkoutDirectory, String relativePathProjectDirectory )
    {
        if ( StringUtils.isNotEmpty( relativePathProjectDirectory ) )
        {
            return new File( checkoutDirectory, relativePathProjectDirectory );
        }
        else
        {
            return checkoutDirectory;
        }
    }

    private ReleaseDescriptor loadReleaseDescriptor( ReleaseDescriptor releaseDescriptor,
                                                     ReleaseManagerListener listener )
        throws ReleaseExecutionException
    {
        try
        {
            updateListener( listener, "verify-release-configuration", PHASE_START );
            ReleaseDescriptor descriptor = configStore.read( releaseDescriptor );
            updateListener( listener, "verify-release-configuration", PHASE_END );
            return descriptor;
        }
        catch ( ReleaseDescriptorStoreException e )
        {
            updateListener( listener, e.getMessage(), ERROR );

            throw new ReleaseExecutionException( "Error reading stored configuration: " + e.getMessage(), e );
        }
    }

    public void clean( ReleaseDescriptor releaseDescriptor, List reactorProjects )
    {
        getLogger().info( "Cleaning up after release..." );

        configStore.delete( releaseDescriptor );

        for ( Iterator i = preparePhases.iterator(); i.hasNext(); )
        {
            String name = (String) i.next();

            ReleasePhase phase = (ReleasePhase) releasePhases.get( name );

            phase.clean( reactorProjects );
        }
    }

    void setConfigStore( ReleaseDescriptorStore configStore )
    {
        this.configStore = configStore;
    }

    void setMavenExecutor( MavenExecutor mavenExecutor )
    {
        this.mavenExecutor = mavenExecutor;
    }

    void updateListener( ReleaseManagerListener listener, String name, int state )
    {
        if ( listener != null )
        {
            switch ( state )
            {
                case GOAL_START:
                    listener.goalStart( name, getGoalPhases( name ) );
                    break;
                case GOAL_END:
                    listener.goalEnd();
                    break;
                case PHASE_SKIP:
                    listener.phaseSkip( name );
                    break;
                case PHASE_START:
                    listener.phaseStart( name );
                    break;
                case PHASE_END:
                    listener.phaseEnd();
                    break;
                default:
                    listener.error( name );
            }
        }
    }

    private List getGoalPhases( String name )
    {
        List phases = new ArrayList();

        if ( "prepare".equals( name ) )
        {
            phases.addAll( this.preparePhases );
        }
        else if ( "perform".equals( name ) )
        {
            phases.addAll( this.performPhases );
        }
        else if ( "rollback".equals( name ) )
        {
            phases.addAll( this.rollbackPhases );
        }

        return Collections.unmodifiableList( phases );
    }

    private void logInfo( ReleaseResult result, String message )
    {
        if ( result != null )
        {
            result.appendInfo( message );
        }

        getLogger().info( message );
    }

    private void captureException( ReleaseResult result, ReleaseManagerListener listener, Exception e )
    {
        updateListener( listener, e.getMessage(), ERROR );

        result.appendError( e );

        result.setResultCode( ReleaseResult.ERROR );
    }
}