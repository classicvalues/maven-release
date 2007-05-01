package org.apache.maven.shared.release.phase;

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

import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.release.ReleaseExecutionException;
import org.apache.maven.shared.release.ReleaseFailureException;
import org.apache.maven.shared.release.ReleaseResult;
import org.apache.maven.shared.release.config.ReleaseDescriptor;
import org.codehaus.plexus.util.FileUtils;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * @author Edwin Punzalan
 * @plexus.component role="org.apache.maven.shared.release.phase.ReleasePhase" role-hint="create-backup-poms"
 */
public class CreateBackupPomsPhase
    extends AbstractBackupPomsPhase
{
    public ReleaseResult execute( ReleaseDescriptor releaseDescriptor, Settings settings, List reactorProjects )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        ReleaseResult result = new ReleaseResult();

        //remove previous backups, if any
        clean( reactorProjects );

        for ( Iterator projects = reactorProjects.iterator(); projects.hasNext(); )
        {
            MavenProject project = (MavenProject) projects.next();

            createPomBackup( project );
        }

        result.setResultCode( ReleaseResult.SUCCESS );

        return result;
    }

    public ReleaseResult clean( List reactorProjects )
    {
        ReleaseResult result = new ReleaseResult();

        for ( Iterator projects = reactorProjects.iterator(); projects.hasNext(); )
        {
            MavenProject project = (MavenProject) projects.next();

            deletePomBackup( project );
        }

        result.setResultCode( ReleaseResult.SUCCESS );

        return result;
    }

    public ReleaseResult simulate( ReleaseDescriptor releaseDescriptor, Settings settings, List reactorProjects )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        return execute( releaseDescriptor, settings, reactorProjects );
    }

    private void createPomBackup( MavenProject project )
        throws ReleaseExecutionException
    {
        //delete any existing backup first
        deletePomBackup( project );

        try
        {
            FileUtils.copyFile( project.getFile(), getPomBackup( project ) );
        }
        catch ( IOException e )
        {
            throw new ReleaseExecutionException( "Error creating backup POM: " + e.getMessage(), e );
        }
    }
}