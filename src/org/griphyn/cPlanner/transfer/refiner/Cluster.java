/*
 * 
 *   Copyright 2007-2008 University Of Southern California
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 */

package org.griphyn.cPlanner.transfer.refiner;

import org.griphyn.cPlanner.classes.ADag;
import org.griphyn.cPlanner.classes.PlannerOptions;
import org.griphyn.cPlanner.classes.SubInfo;
import org.griphyn.cPlanner.classes.FileTransfer;

import org.griphyn.cPlanner.common.PegasusProperties;
import org.griphyn.cPlanner.common.LogManager;

import org.griphyn.cPlanner.namespace.VDS;

import org.griphyn.common.catalog.TransformationCatalogEntry;

import org.griphyn.cPlanner.transfer.Refiner;

import org.griphyn.cPlanner.engine.ReplicaCatalogBridge;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.Vector;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;


import java.util.Map;
import java.util.HashMap;
import org.griphyn.cPlanner.code.gridstart.GridStartFactory;


/**
 * A cluster refiner that builds upon the Bundle Refiner. It clusters the stage-in 
 * jobs and stage-out jobs per level of the workflow. The difference from the 
 * Bundle refiner beings
 * 
 * <pre>
 *        - stagein is also clustered/bundled per level. In Bundle it was for the 
 *          whole workflow.
 *        - keys that control the clustering ( old name bundling are ) 
 *          cluster.stagein and cluster.stageout
 * </pre>
 * 
 * In order to use the transfer refiner implemented by this class,
 * <pre>
 *        - the property pegasus.transfer.refiner  must be set to value Cluster
 * </pre>
 *
 * 
 * @version $Revision$
 * @author Karan Vahi
 */
public class Cluster extends Bundle {
    
    /**
     * A short description of the transfer refinement.
     */
    public static final String DESCRIPTION =
                      "Cluster Transfers: Stagein and Stageout TX jobs are clustered per level";


    /**
     * The default clustering factor that identifies the number of transfer jobs
     * that are being created per execution pool for stageing in data for
     * the workflow.
     */
    public static final String DEFAULT_STAGE_IN_CLUSTER_FACTOR = "1";

    /**
     * The default bundling factor that identifies the number of transfer jobs
     * that are being created per execution pool while stageing data out.
     */
    public static final String DEFAULT_STAGE_OUT_CLUSTER_FACTOR = "1";


    /**
     * A map indexed by site name, that contains the pointer to the stage in
     * PoolTransfer objects for that site. This is per level of the workflow.
     */
    protected Map<String,PoolTransfer> mStageInMapPerLevel;
    
    /**
     * The current level of the jobs being traversed.
     */
    private int mCurrentSILevel;

    /**
     * Maps the site name to the current synch job
     */
    private Map< String, SubInfo > mSyncJobMap;
    
     /**
     * The overloaded constructor.
     *
     * @param dag        the workflow to which transfer nodes need to be added.
     * @param properties the <code>PegasusProperties</code> object containing all
     *                   the properties required by Pegasus.
     * @param options    the options passed to the planner.
     *
     */
    public Cluster( ADag dag, PegasusProperties properties, PlannerOptions options ){
        super( dag, properties, options );
        mCurrentSILevel = -1;
        mSyncJobMap = new HashMap< String, SubInfo >();
    }

    /**
     * Adds the stage in transfer nodes which transfer the input files for a job,
     * from the location returned from the replica catalog to the job's execution
     * pool.
     *
     * @param job   <code>SubInfo</code> object corresponding to the node to
     *              which the files are to be transferred to.
     * @param files Collection of <code>FileTransfer</code> objects containing the
     *              information about source and destURL's.
     */
    public  void addStageInXFERNodes( SubInfo job,
                                      Collection files ){
        

        //sanity check
        if( files.isEmpty() ){
            return;
        }

        String jobName = job.getName();

        mLogMsg = "Adding stagin transfer nodes for job " + jobName;

        //separate the files for transfer
        //and for registration
        List txFiles = new ArrayList();
        List stagedExecFiles = new ArrayList();
        //to prevent duplicate dependencies
        Set tempSet = new HashSet();
        
        //iterate through all the files
        for ( Iterator it = files.iterator(); it.hasNext(); ) {
            FileTransfer ft = ( FileTransfer ) it.next();
            String lfn = ft.getLFN();
            
            //check for transfer flag to see if we need to transfer the file.
            if ( !ft.getTransientTransferFlag() ) {
                String key = this.constructFileKey( ft.getLFN(), job.getSiteHandle() );
                //check to see if the file is already being transferred by
                //some other stage in job to that site
                String existingSiTX = (String) mFileTable.get( key );
            
                if ( existingSiTX == null) {
                    //schedule the file for transfer
                    txFiles.add( ft );
                    
                    if( ft.isTransferringExecutableFile() ){
                        stagedExecFiles.add( ft );
                        
                    }
                }
                else{
                    //there is an existing tx job that is transferring the file.
                    
                    //check if tempSet does not contain the parent
                    //fix for sonal's bug
                    if ( tempSet.contains( existingSiTX )) {
                        StringBuffer msg  = new StringBuffer();
                        msg.append( "IGNORING TO ADD rc pull relation from rc tx node: " ).
                            append( existingSiTX ).append( " -> " ).append( jobName ).
                            append( " for transferring file " ).append( lfn ).append( " to site " ).
                            append( job.getSiteHandle() );

                        mLogger.log( msg.toString(), LogManager.DEBUG_MESSAGE_LEVEL );

                    } else {
                        
                        mLogger.log( " For transferring file " + lfn, LogManager.DEBUG_MESSAGE_LEVEL );
                        addRelation( existingSiTX, jobName, job.getSiteHandle(), false );
                        tempSet.add( existingSiTX );
                    }
                }
                
            }
            
            
        }

        boolean makeTNode = !txFiles.isEmpty();
        
        int level   = job.getLevel();
        String site = job.getSiteHandle();
        int bundleValue = getSISiteBundleValue( site,
                                                job.vdsNS.getStringValue( VDS.CLUSTER_STAGE_IN_TX_KEY ) );

        mLogger.log( "The Cluster value for site " + site + " is " + bundleValue,
                     LogManager.DEBUG_MESSAGE_LEVEL
                     );
        
        if ( level != mCurrentSILevel ){
            mCurrentSILevel = level;
            //we are starting on a new level of the workflow.
            //reinitialize stuff
            this.resetStageInMap();
        }


        TransferContainer siTC = null;
        if ( makeTNode ) {

            //get the appropriate pool transfer object for the site
            PoolTransfer pt = this.getStageInPoolTransfer(  site, bundleValue );
            //we add all the file transfers to the pool transfer
            siTC = pt.addTransfer( txFiles, level, SubInfo.STAGE_IN_JOB );
            String siJob = siTC.getTXName();

            //traverse through all files to be staged
            int staged = 0;
            for( Iterator it = txFiles.iterator(); it.hasNext(); ){
                FileTransfer ft = ( FileTransfer)it.next();
                String key = this.constructFileKey( ft.getLFN(), job.getSiteHandle() );
                
                if( ft.isTransferringExecutableFile() ){
                    //the staged execution file should be having the setup
                    //job as parent if it does not preserve x bit
                    if(mTXStageInImplementation.doesPreserveXBit()){
                        mFileTable.put( key, siJob );
                    }
                    else{
                        mFileTable.put( key,
                                        mTXStageInImplementation.getSetXBitJobName( jobName,staged++) );
                    }
                }
                else{
                    //make a new entry into the table
                    mFileTable.put( key, siJob);
                }
                
                
                
                //add the newJobName to the tempSet so that even
                //if the job has duplicate input files only one instance
                //of transfer is scheduled. This came up during collapsing
                //June 15th, 2004
                tempSet.add( siJob );
            }
            
            if( !stagedExecFiles.isEmpty() ){
                //create en-mass the setXBit jobs
                //if there were any staged files
               mTXStageInImplementation.addSetXBitJobs( job, 
                                                        siJob,
                                                        stagedExecFiles,
                                                        SubInfo.STAGE_IN_JOB );
            }
            
           
            
        }


        
       
    }
       
    /**
     * Resets the stage in map.
     */
    protected void resetStageInMap(){
        if ( this.mStageInMapPerLevel != null ){
            
            SubInfo job = new SubInfo();
            //before flushing add the stage in nodes to the workflow
            for( Iterator it = mStageInMapPerLevel.values().iterator(); it.hasNext(); ){
                PoolTransfer pt = ( PoolTransfer ) it.next();
                String site = pt.getPoolName() ;
                job.setSiteHandle( site );

                SubInfo parentSyncJob = this.getSyncJob( site );
                //add a child synch job for this level
                SubInfo childSyncJob = createSyncJobBetweenLevels( getSyncJobBetweenLevelsName( site,
                                                                                        mCurrentSILevel - 1 ) );
                addJob( childSyncJob );
                mLogger.log( "Added synch job " + childSyncJob.getName(), LogManager.DEBUG_MESSAGE_LEVEL );
                
                mLogger.log( "Adding jobs for staging in data to site " + pt.getPoolName(),
                             LogManager.DEBUG_MESSAGE_LEVEL );

                //traverse through all the TransferContainers
                for( Iterator tcIt = pt.getTransferContainerIterator(); tcIt.hasNext(); ){
                    TransferContainer tc = ( TransferContainer ) tcIt.next();
                    if(tc == null){
                        //break out
                        break;
                    }

                    //add the stagein job if required
                    SubInfo siJob = null;
                    if( !tc.getFileTransfers().isEmpty() ){
                        mLogger.log( "Adding stage-in job " + tc.getTXName(),
                                     LogManager.DEBUG_MESSAGE_LEVEL);
                        siJob = mTXStageInImplementation.createTransferJob(
                                                             job, tc.getFileTransfers(), null,
                                                             tc.getTXName(), SubInfo.STAGE_IN_JOB );
                        addJob( siJob );
                    }
                    
                    //add the dependency to parent synch 
                    if( parentSyncJob != null ){
                        addRelation( parentSyncJob.getName(), siJob.getName() );
                    }
                    //stagein job is parent to child synch
                    addRelation( siJob.getName(), childSyncJob.getName() );

                }//end of traversal thru all transfer containers
                
                //update the synch job map
                mSyncJobMap.put( site, childSyncJob );
                
            }//end of traversal thru all pool transfers
        }
        mStageInMapPerLevel = new HashMap< String, PoolTransfer >();
    }

       
    /**
     * Returns the appropriate stagein pool transfer for a particular site.
     *
     * @param site  the site for which the PT is reqd.
     * @param num   the number of stage in jobs required for that Pool.
     *
     * @return the PoolTransfer
     */
    protected PoolTransfer getStageInPoolTransfer( String site, int num  ){

        if ( this.mStageInMapPerLevel.containsKey( site ) ){
            return ( PoolTransfer ) this.mStageInMapPerLevel.get( site );
        }
        else{
            PoolTransfer pt = new PoolTransfer( site, num );
            this.mStageInMapPerLevel.put( site, pt );
            return pt;
        }
    }

    /**
     * Signals that the traversal of the workflow is done. At this point the
     * transfer nodes are actually constructed traversing through the transfer
     * containers and the stdin of the transfer jobs written.
     */
    public void done(){    
        //increment the level counter
        this.mCurrentSILevel++;
        
        //reset the stageout stagein map too
        this.resetStageInMap();
        this.resetStageOutMap();
    }

    /**
     * Returns a textual description of the transfer mode.
     *
     * @return a short textual description
     */
    public  String getDescription(){
        return Cluster.DESCRIPTION;
    }


    /**
     * Determines the bundle factor for a particular site on the basis of the
     * stage in bundle value associcated with the underlying transfer
     * transformation in the transformation catalog. If the key is not found,
     * then the default value is returned. In case of the default value being
     * null the global default is returned.
     *
     * @param site    the site at which the value is desired.
     * @param deflt   the default value.
     *
     * @return the bundle factor.
     *
     * @see #DEFAULT_BUNDLE_STAGE_IN_FACTOR
     */
    protected int getSISiteBundleValue(String site,  String deflt){
        //this should be parameterised Karan Dec 20,2005
        TransformationCatalogEntry entry  =
            mTXStageInImplementation.getTransformationCatalogEntry(site);
        SubInfo sub = new SubInfo();
        String value = (deflt == null)?
                        this.DEFAULT_STAGE_IN_CLUSTER_FACTOR:
                        deflt;

        if(entry != null){
            sub.updateProfiles(entry);
            value = (sub.vdsNS.containsKey( VDS.CLUSTER_STAGE_IN_TX_KEY ))?
                     sub.vdsNS.getStringValue( VDS.CLUSTER_STAGE_IN_TX_KEY ):
                     value;
        }

        return Integer.parseInt(value);
    }


    /**
     * Determines the bundle factor for a particular site on the basis of the
     * stage out bundle value associcated with the underlying transfer
     * transformation in the transformation catalog. If the key is not found,
     * then the default value is returned. In case of the default value being
     * null the global default is returned.
     *
     * @param site    the site at which the value is desired.
     * @param deflt   the default value.
     *
     * @return the bundle factor.
     *
     * @see #DEFAULT_STAGE_OUT_BUNDLE_FACTOR
     */
    protected int getSOSiteBundleValue( String site,  String deflt ){
        //this should be parameterised Karan Dec 20,2005
        TransformationCatalogEntry entry  =
            mTXStageInImplementation.getTransformationCatalogEntry(site);
        SubInfo sub = new SubInfo();
        String value = (deflt == null)?
                        this.DEFAULT_STAGE_OUT_CLUSTER_FACTOR:
                        deflt;

        if(entry != null){
            sub.updateProfiles(entry);
            value = (sub.vdsNS.containsKey( VDS.CLUSTER_STAGE_OUT_TX_KEY ))?
                     sub.vdsNS.getStringValue( VDS.CLUSTER_STAGE_OUT_TX_KEY ):
                     value;
        }

        return Integer.parseInt(value);
    }

    /**
     * Returns the name of the job that acts as a synchronization node in
     * between stage in jobs of different levels.
     * 
     * @param site  the site of the transfer job.
     * 
     * @param level the level of the job
     * 
     * @return name of synce job
     */
    protected String getSyncJobBetweenLevelsName( String site, int level ){
       
            StringBuffer sb = new StringBuffer();
            sb.append( "sync_tx_noop_" );

            //append the job prefix if specified in options at runtime
            if ( mJobPrefix != null ) { sb.append( mJobPrefix ); }

            if( site != null ){
                sb.append( site ).append( "_" );
            }
            sb.append( level );

           return sb.toString();
    }

    /**
     * It creates a NoOP synch job that runs on the submit host.
     *
     * @name of the job
     *
     * @return  the noop job.
     */

    private SubInfo createSyncJobBetweenLevels( String name ) {

        SubInfo newJob = new SubInfo();
        
        List entries = null;
        String execPath =  null;

        //jobname has the dagname and index to indicate different
        //jobs for deferred planning
        newJob.setName( name );
        newJob.setTransformation( "pegasus", "noop", "1.0" );
        newJob.setDerivation( "pegasus", "noop", "1.0" );

        newJob.setUniverse( "vanilla" );
        //the noop job does not get run by condor
        //even if it does, giving it the maximum
        //possible chance
        newJob.executable = "/bin/true";

        //construct noop keys
        newJob.setSiteHandle( "local" );
        newJob.setJobType( SubInfo.CREATE_DIR_JOB );
        constructCondorKey( newJob, "noop_job", "true" );
        constructCondorKey( newJob, "noop_job_exit_code", "0" );

        //we do not want the job to be launched
        //by kickstart, as the job is not run actually
        newJob.vdsNS.checkKeyInNS( VDS.GRIDSTART_KEY,
                                   GridStartFactory.GRIDSTART_SHORT_NAMES[GridStartFactory.NO_GRIDSTART_INDEX] );

        return newJob;
    }
    
    /**
     * Constructs a condor variable in the condor profile namespace
     * associated with the job. Overrides any preexisting key values.
     *
     * @param job   contains the job description.
     * @param key   the key of the profile.
     * @param value the associated value.
     */
    protected void constructCondorKey(SubInfo job, String key, String value){
        job.condorVariables.checkKeyInNS(key,value);
    }
    
    /**
     * Returns the current synch job for a site.
     * 
     * @param site
     * 
     * @return synch job if exists else null
     */
    public SubInfo getSyncJob( String site ){
        return (SubInfo)mSyncJobMap.get( site );
    }
    
}
