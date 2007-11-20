/**
 * This file or a portion of this file is licensed under the terms of
 * the Globus Toolkit Public License, found at $PEGASUS_HOME/GTPL or
 * http://www.globus.org/toolkit/download/license.html.
 * This notice must appear in redistributions of this file
 * with or without modification.
 *
 * Redistributions of this Software, with or without modification, must reproduce
 * the GTPL in:
 * (1) the Software, or
 * (2) the Documentation or
 * some other similar material which is provided with the Software (if any).
 *
 * Copyright 1999-2004
 * University of Chicago and The University of Southern California.
 * All rights reserved.
 */

package org.griphyn.cPlanner.engine;

import java.util.Iterator;
import java.util.Set;
import java.util.Vector;

import org.griphyn.cPlanner.classes.ADag;
import org.griphyn.cPlanner.classes.PlannerOptions;
import org.griphyn.cPlanner.classes.PegasusBag;

import org.griphyn.cPlanner.common.LogManager;
import org.griphyn.cPlanner.common.PegasusProperties;

import org.griphyn.common.catalog.transformation.TCMode;

/**
 * The central class that calls out to the various other components of Pegasus.
 *
 * @author Karan Vahi
 * @author Gaurang Mehta
 * @version $Revision$
 *
 * @see org.griphyn.cPlanner.classes.ReplicaLocations
 */
public class MainEngine
    extends Engine {

    /**
     * The Original Dag object which is constructed by parsing the dag file.
     */
    private ADag mOriginalDag;

    /**
     * The reduced Dag object which is got from the Reduction Engine.
     */
    private ADag mReducedDag;

    /**
     * The cleanup dag for the final concrete dag.
     */
    private ADag mCleanupDag;

    /**
     * The pools on which the Dag should be executed as specified by the user.
     */
    private Set mExecPools;

    /**
     * The pool on which all the output data should be transferred.
     */
    private String mOutputPool;

    /**
     * The bridge to the Replica Catalog.
     */
    private ReplicaCatalogBridge mRCBridge;

    /**
     * The handle to the InterPool Engine that calls out to the Site Selector
     * and maps the jobs.
     */
    private InterPoolEngine mIPEng;

    /**
     * The handle to the Reduction Engine that performs reduction on the graph.
     */
    private ReductionEngine mRedEng;

    /**
     * The handle to the Transfer Engine that adds the transfer nodes in the
     * graph to transfer the files from one site to another.
     */
    private TransferEngine mTransEng;

    /**
     * The engine that ends up creating random directories in the remote
     * execution pools.
     */
    private CreateDirectory mCreateEng;

    /**
     * The engine that ends up creating the cleanup dag for the dag.
     */
    private RemoveDirectory mRemoveEng;

    /**
     * The handle to the Authentication Engine that performs the authentication
     * with the various sites.
     */
    private AuthenticateEngine mAuthEng;

    /**
     * The handle to the node collapser.
     */
    private NodeCollapser mNodeCollapser;

    /**
     * The bag of objects that is populated as planner is run.
     */
    private PegasusBag mBag;


    /**
     * This constructor initialises the class variables to the variables
     * passed. The pool names specified should be present in the pool.config file
     *
     * @param orgDag    the dag to be worked on.
     * @param props   the properties to be used.
     * @param options   The options specified by the user to run the planner.
     */

    public MainEngine( ADag orgDag, PegasusProperties props, PlannerOptions options) {

        super( props );
        mOriginalDag = orgDag;
        this.mPOptions = options;
        mExecPools = (Set)mPOptions.getExecutionSites();
        mOutputPool = mPOptions.getOutputSite();
        mTCHandle = TCMode.loadInstance();

        if (mOutputPool != null && mOutputPool.length() > 0) {
            Engine.mOutputPool = mOutputPool;
        }

    }

    /**
     * The main function which calls the other engines and does the necessary work.
     *
     * @return the planned worflow.
     */
    public ADag runPlanner() {
        //do the authentication against the pools
        if (mPOptions.authenticationSet()) {
            mAuthEng = new AuthenticateEngine( mProps,
                          new java.util.HashSet(mPOptions.getExecutionSites()));

            mLogger.log("Authenticating Sites", LogManager.INFO_MESSAGE_LEVEL);
            Set authenticatedSet = mAuthEng.authenticate();
            if (authenticatedSet.isEmpty()) {
                StringBuffer error = new StringBuffer( );
                error.append( "Unable to authenticate against any site. ").
                      append( "Probably your credentials were not generated" ).
                      append( " or have expired" );
                throw new RuntimeException( error.toString() );
            }
            mLogger.log("Sites authenticated are " +
                        setToString(authenticatedSet, ","),
                        LogManager.DEBUG_MESSAGE_LEVEL);
            mLogger.logCompletion("Authenticating Sites",
                                  LogManager.INFO_MESSAGE_LEVEL);
            mPOptions.setExecutionSites(authenticatedSet);
        }

        Vector vDelLeafJobs = new Vector();
        String message = null;
        mRCBridge = new ReplicaCatalogBridge( mOriginalDag, mProps, mPOptions );


        mRedEng = new ReductionEngine( mOriginalDag, mProps, mPOptions);
        mReducedDag = mRedEng.reduceDag( mRCBridge );
        vDelLeafJobs = mRedEng.getDeletedLeafJobs();
        mRedEng = null;

        //unmark arg strings
        //unmarkArgs();
        message = "Doing site selection" ;
        mLogger.log(message, LogManager.INFO_MESSAGE_LEVEL);
        mIPEng = new InterPoolEngine( mReducedDag, mProps, mPOptions );
        mIPEng.determineSites();
        mBag = mIPEng.getPegasusBag();
        mIPEng = null;
        mLogger.logCompletion(message,LogManager.INFO_MESSAGE_LEVEL);

        //do the node cluster
        if( mPOptions.getClusteringTechnique() != null ){
            message = "Clustering the jobs in the workflow";
            mLogger.log(message,LogManager.INFO_MESSAGE_LEVEL);
            mNodeCollapser = new NodeCollapser( mProps, mPOptions );

            try{
                mReducedDag = mNodeCollapser.cluster( mReducedDag );
            }
            catch ( Exception e ){
                throw new RuntimeException( message, e );
            }

            mNodeCollapser = null;
            mLogger.logCompletion(message,LogManager.INFO_MESSAGE_LEVEL);
        }

        message = "Grafting transfer nodes in the workflow";
        mLogger.log(message,LogManager.INFO_MESSAGE_LEVEL);
        mTransEng = new TransferEngine( mReducedDag, vDelLeafJobs, mProps, mPOptions );
        mTransEng.addTransferNodes( mRCBridge );
        mTransEng = null;
        mLogger.logCompletion(message,LogManager.INFO_MESSAGE_LEVEL);

        //close the connection to RLI explicitly
        mRCBridge.closeConnection();


        if (mPOptions.generateRandomDirectory()) {
            //add the nodes to that create
            //random directories at the remote
            //execution pools.
            message = "Grafting the remote workdirectory creation jobs " +
                        "in the workflow";
            mLogger.log(message,LogManager.INFO_MESSAGE_LEVEL);
            mCreateEng = CreateDirectory.loadCreateDirectoryInstance(mProps.
                getCreateDirClass(),
                mReducedDag,
                mProps );
            mCreateEng.addCreateDirectoryNodes();
            mCreateEng = null;
            mLogger.logCompletion(message,LogManager.INFO_MESSAGE_LEVEL);

            //create the cleanup dag
            message = "Generating the cleanup workflow";
            mLogger.log(message,LogManager.INFO_MESSAGE_LEVEL);
            mRemoveEng = new RemoveDirectory( mReducedDag, mProps );
            mCleanupDag = mRemoveEng.generateCleanUPDAG();
            mLogger.logCompletion(message,LogManager.INFO_MESSAGE_LEVEL);
        }

        //add the cleanup nodes in place
        if ( mPOptions.getCleanup() ){ /* should be exposed via command line option */
            message = "Adding cleanup jobs in the workflow";
            mLogger.log( message, LogManager.INFO_MESSAGE_LEVEL );
            CleanupEngine cEngine = new CleanupEngine( mProps, mPOptions );
            mReducedDag = cEngine.addCleanupJobs( mReducedDag );
            mLogger.logCompletion( message, LogManager.INFO_MESSAGE_LEVEL );
        }

        return mReducedDag;
    }

    /**
     * Returns the cleanup dag for the concrete dag.
     *
     * @return the cleanup dag if the random dir is given.
     *         null otherwise.
     */
    public ADag getCleanupDAG(){
        return mCleanupDag;
    }

    /**
     * Returns the bag of intialization objects.
     *
     * @return PegasusBag
     */
    public PegasusBag getPegasusBag(){
        return mBag;
    }

    /**
     * Unmarks the arguments , that are tagged in the DaxParser. At present there are
     * no tagging.
     *
     * @deprecated
     */
    private void unmarkArgs() {
        /*Enumeration e = mReducedDag.vJobSubInfos.elements();
                 while(e.hasMoreElements()){
            SubInfo sub = (SubInfo)e.nextElement();
            sub.strargs = new String(removeMarkups(sub.strargs));
                 }*/
    }

    /**
     * A small helper method that displays the contents of a Set in a String.
     *
     * @param s      the Set whose contents need to be displayed
     * @param delim  The delimited between the members of the set.
     * @return  String
     */
    public String setToString(Set s, String delim) {
        StringBuffer sb = new StringBuffer();
        for( Iterator it = s.iterator(); it.hasNext(); ) {
            sb.append( (String) it.next() ).append( delim );
        }
        String result = sb.toString();
        result = (result.length() > 0) ?
                 result.substring(0, result.lastIndexOf(delim)) :
                 result;
        return result;
    }

}
