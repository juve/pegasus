/**
 *  Copyright 2007-2008 University Of Southern California
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package edu.isi.pegasus.planner.code.generator.condor.style;

import edu.isi.pegasus.planner.code.generator.condor.CondorStyleException;

import edu.isi.pegasus.common.logging.LogManager;

import edu.isi.pegasus.planner.classes.Job;

import edu.isi.pegasus.planner.namespace.Condor;

import edu.isi.pegasus.planner.code.generator.condor.CondorQuoteParser;
import edu.isi.pegasus.planner.code.generator.condor.CondorQuoteParserException;

import edu.isi.pegasus.planner.classes.TransferJob;
import edu.isi.pegasus.planner.code.generator.condor.CondorEnvironmentEscape;
import edu.isi.pegasus.planner.namespace.Globus;
import edu.isi.pegasus.planner.namespace.Pegasus;

/**
 * This implementation enables a job to be submitted via gLite to a
 * grid sites. This is the style applied when job has a pegasus profile style key
 * with value GLite associated with it.
 *
 *
 * <p>
 * This style should only be used when the condor on the submit host can directly
 * talk to scheduler running on the cluster. In Pegasus there should  be a separate
 * compute site that has this style associated with it. This style should not be
 * specified for the local site.
 *
 * As part of applying the style to the job, this style adds the following
 * classads expressions to the job description
 * <pre>
 *      batch_queue  - value picked up from globus profile queue or can be
 *                     set directly as a Condor profile named batch_queue
 *      +remote_cerequirements - See below
 * </pre>
 *
 * <p>
 * The remote CE requirements are constructed from the following profiles
 * associated with the job.The profiles for a job are derived from various
 * sources
 *  - user properties
 *  - transformation catalog
 *  - site catalog
 *  - DAX
 *
 * Note it is upto the user to specify these or a subset of them.
 *
 * The following globus profiles if associated with the job are picked up and
 * translated to +remote_cerequirements key in the job submit files.
 * <pre>
 *
 * hostcount    -> NODES
 * xcount       -> PROCS
 * maxwalltime  -> WALLTIME
 * totalmemory  -> TOTAL_MEMORY
 * maxmemory    -> PER_PROCESS_MEMORY
 * </pre>
 *
 *
 * The following condor profiles if associated with the job are picked up
 * <pre>
 * priority  -> PRIORITY
 * </pre>
 *
 * All the env profiles are translated to MYENV
 *
 * For e.g. the expression in the submit file may look as
 * <pre>
 * +remote_cerequirements = "PROCS==18 && NODES==1 && PRIORITY==10 && WALLTIME==3600
 *   && PASSENV==1 && JOBNAME==\"TEST JOB\" && MYENV ==\"MONTAGE_HOME=/usr/montage,JAVA_HOME=/usr\""
 * </pre>
 *
 * The pbs_local_attributes.sh file in share/pegasus/htcondor/glite picks up
 * these values and translated to appropriate PBS parameters
 *
 * <pre>
 * NODES                 -> nodes
 * PROCS                 -> ppn
 * WALLTIME              -> walltime
 * TOTAL_MEMORY          -> mem
 * PER_PROCESS_MEMORY    -> pmem
 * </pre>
 *
 *
 *
 * All the jobs that have this style applied dont have a remote directory
 * specified in the submit directory. They rely on kickstart to change to the
 * working directory when the job is launched on the remote node.
 *
 * @author Karan Vahi
 * @version $Revision$
 */

public class GLite extends Abstract {
    /**
     * The name of the style being implemented.
     */
    public static final String STYLE_NAME = "GLite";


    /**
     * The Condor remote directory classad key to be used with Glite
     */
    public static final String CONDOR_REMOTE_DIRECTORY_KEY = "+remote_iwd";
    
    /**
     * The condor key to set the remote environment via BLAHP
     */
    public static final String CONDOR_REMOTE_ENVIRONMENT_KEY = "+remote_environment";
    
    
    public static final String SGE_GRID_RESOURCE = "sge";
    
    public static final String PBS_GRID_RESOURCE = "pbs";
    
    /**
     * Handle to escaping class for environment variables
     */
    private CondorEnvironmentEscape mEnvEscape;
    
    private CondorG mCondorG;

    /**
     * The default Constructor.
     */
    public GLite() {
        super();
        mEnvEscape = new CondorEnvironmentEscape();
        mCondorG = new CondorG();
    }



    /**
     * Applies the gLite style to the job.
     *
     * @param job  the job on which the style needs to be applied.
     *
     * @throws CondorStyleException in case of any error occuring code generation.
     */
    public void apply( Job job ) throws CondorStyleException {

        String workdir = job.getDirectory();

        /* universe is always set to grid*/
        job.condorVariables.construct( Condor.UNIVERSE_KEY,Condor.GRID_UNIVERSE );

        /* figure out the remote scheduler. should be specified with the job*/
        String gridResource = (String) job.condorVariables.get( Condor.GRID_RESOURCE_KEY );
        if( gridResource == null  ){
            throw new CondorStyleException( missingKeyError( job, Condor.GRID_RESOURCE_KEY ) );
        }


        job.condorVariables.construct( GLite.CONDOR_REMOTE_DIRECTORY_KEY,
                                       workdir == null ? null : quote(workdir) );

        //also set it as an environment variable, since for MPI jobs
        //glite and BLAHP dont honor +remote_iwd and we cannot use kickstart
        //the only way to get it to work is for the wrapper around the mpi
        //executable to a cd to the directory pointed to by this variable.
        if( workdir != null ){
            job.envVariables.construct( "_PEGASUS_SCRATCH_DIR", workdir);
            //PM-961 also associate the value as an environment variable
            job.envVariables.construct( edu.isi.pegasus.planner.namespace.ENV.PEGASUS_SCRATCH_DIR_KEY, 
                                        workdir);
        }

        /* transfer_executable does not work with gLite
         * Explicitly set to false */
        //PM-950 looks like it works now. for pegasus lite modes we need
        //the planner to be able to set it to true
        //job.condorVariables.construct( Condor.TRANSFER_EXECUTABLE_KEY, "false" );

        /* retrieve some keys from globus rsl and convert to gLite format */
        if( job.globusRSL.containsKey( "queue" ) ){
            job.condorVariables.construct(  "batch_queue" , (String)job.globusRSL.get( "queue" ) );
        }

        /* convert some condor keys and globus keys to remote ce requirements
         +remote_cerequirements = blah */
        job.condorVariables.construct( "+remote_cerequirements", getCERequirementsForJob( job, gridResource) );
        
        /*
         PM-934 construct environment accordingly
        */
        job.condorVariables.construct( GLite.CONDOR_REMOTE_ENVIRONMENT_KEY, 
                                       mEnvEscape.escape( job.envVariables ) );
        job.envVariables.reset();

        /* do special handling for jobs scheduled to local site
         * as condor file transfer mechanism does not work
         * Special handling for the JPL cluster */
        if( job.getSiteHandle().equals( "local" ) && job instanceof TransferJob ){
                /* remove the change dir requirments for the
                 * third party transfer on local host */
                job.condorVariables.removeKey( GLite.CONDOR_REMOTE_DIRECTORY_KEY );
        }

        /* similar handling for registration jobs */
        if( job.getSiteHandle().equals( "local" ) && job.getJobType() == Job.REPLICA_REG_JOB ){
                /* remove the change dir requirments for the
                 * third party transfer on local host */
                job.condorVariables.removeKey( GLite.CONDOR_REMOTE_DIRECTORY_KEY );
        }

        if ( job.getSiteHandle().equals("local") ) {
            applyCredentialsForLocalExec(job);
        }
        else {
            applyCredentialsForRemoteExec(job);
        }
    }



    /**
     * Constructs the value for remote CE requirements expression for the job .
     *
     * For e.g. the expression in the submit file may look as
     * <pre>
     * +remote_cerequirements = "PROCS==18 && NODES==1 && PRIORITY==10 && WALLTIME==3600
     *   && PASSENV==1 && JOBNAME==\"TEST JOB\" && MYENV ==\"GAURANG=MEHTA,KARAN=VAHI\""
     *
     * </pre>
     *
     * The requirements are generated on the basis of certain profiles associated
     * with the jobs.
     * The following globus profiles if associated with the job are picked up
     * <pre>
     * hostcount    -> NODES
     * xcount       -> PROCS
     * maxwalltime  -> WALLTIME
     * totalmemory  -> TOTAL_MEMORY
     * maxmemory    -> PER_PROCESS_MEMORY
     * </pre>
     *
     * The following condor profiles if associated with the job are picked up
     * <pre>
     * priority  -> PRIORITY
     * </pre>
     *
     * All the env profiles are translated to MYENV
     *
     * @param job
     * @param gridResource
     *
     * @return the value to the expression and it is condor quoted
     *
     * @throws CondorStyleException in case of condor quoting error
     */
    protected String getCERequirementsForJob( Job job, String gridResource ) throws CondorStyleException {
        StringBuffer value = new StringBuffer();

        /* append the job name */
        /* job name cannot have - or _ */
        String id = job.getID().replace( "-", "" );
        id = id.replace( "_", "" );
        //the jobname in case of pbs can only be 15 characters long
        id = ( id.length() > 15 )? id.substring( 0, 15 ) : id;

        //add the jobname so that it appears when we do qstat
        addSubExpression( value, "JOBNAME" , id   );
	    value.append( " && ");

        /* always have PASSENV to true */
        //value.append( " && ");
        addSubExpression( value, "PASSENV", 1 );

        /* specifically pass the queue in the requirement since
           some versions dont handle +remote_queue correctly */
        if( job.globusRSL.containsKey( "queue" ) ){
            value.append( " && ");
            addSubExpression( value, "QUEUE", (String)job.globusRSL.get( "queue" ) );
        }

        this.handleResourceRequirements( job , gridResource );

        /* the globus key hostCount is NODES */
        if( job.globusRSL.containsKey( "hostcount" ) ){
            value.append( " && " );
            addSubExpression( value, "NODES" ,  (String)job.globusRSL.get( "hostcount" ) )  ;
        }

        /* the globus key count is CORES */
        if( job.globusRSL.containsKey( "count" ) ){
            value.append( " && " );
            addSubExpression( value, "CORES" ,  (String)job.globusRSL.get( "count" ) )  ;
        }
        
        /* the globus key xcount is PROCS */
        if( job.globusRSL.containsKey( "xcount" ) ){
            value.append( " && " );
            addSubExpression( value, "PROCS" ,  (String)job.globusRSL.get( "xcount" )  );
        }

        /* the globus key maxwalltime is WALLTIME */
        if( job.globusRSL.containsKey( "maxwalltime" ) ){
            value.append( " && " );
            addSubExpression( value,"WALLTIME" , pbsFormattedTimestamp(   (String)job.globusRSL.get( "maxwalltime" ) ) );
        }

        /* the globus key maxmemory is PER_PROCESS_MEMORY */
        if( job.globusRSL.containsKey( "maxmemory" ) ){
            value.append( " && " );
            addSubExpression( value, "PER_PROCESS_MEMORY" ,  (String)job.globusRSL.get( "maxmemory" )  );
        }

        /* the globus key totalmemory is TOTAL_MEMORY */
        if( job.globusRSL.containsKey( "totalmemory" ) ){
            value.append( " && " );
            addSubExpression( value, "TOTAL_MEMORY" ,  (String)job.globusRSL.get( "totalmemory" )  );
        }

        /* the condor key priority is PRIORITY */
        if( job.condorVariables.containsKey( "priority" ) ){
            value.append( " && " );
            addSubExpression( value, "PRIORITY" , Integer.parseInt( (String)job.condorVariables.get( "priority" ) ) );
        }
        
        /* the pegasus key glite.arguments is EXTRA_ARGUMENTS */
        if( job.vdsNS.containsKey( Pegasus.GLITE_ARGUMENTS_KEY ) ){
            value.append( " && " );
            addSubExpression( value, "EXTRA_ARGUMENTS" , (String)job.vdsNS.get( Pegasus.GLITE_ARGUMENTS_KEY   ) );
        }

        return value.toString();
    }


    /**
     * Adds a sub expression to a string buffer
     *
     * @param sb    the StringBuffer
     * @param key   the key
     * @param value the value
     */
    protected void addSubExpression( StringBuffer sb, String key, String value ) {
        //PM-802
        sb.append( key ).append( "==" )
           .append( "\"" )
           .append( value )
           .append( "\"" );
    }


    /**
     * Adds a sub expression to a string buffer
     *
     * @param sb    the StringBuffer
     * @param key   the key
     * @param value the value
     */
    protected void addSubExpression( StringBuffer sb, String key, Integer value ) {
        sb.append( key ).append( "==" ).append( value );
    }

    /**
     * Constructs an error message in case of invalid combination of cores, nodes and ppn
     *
     * @param job      the job object.
     * @param cores
     * @param nodes
     * @param ppn
     * 
     * @return 
     */
    protected String invalidCombinationError( Job job, Integer cores, Integer nodes, Integer ppn  ){
        StringBuffer sb = new StringBuffer();
        StringBuilder comb = new StringBuilder();
        sb.append( "Invalid combination of ");
        comb.append( "(" );
        if( cores != null ){
            sb.append( " cores " ); 
            comb.append( cores ).append( "," );
        }
        if( nodes != null ){
            sb.append( " nodes " ); 
            comb.append( nodes ).append( "," );
        }
        if( ppn  != null ){
            sb.append( " ppn " ); 
            comb.append( ppn ).append( "," );
        }
        comb.append( ")" );
        sb.append( " ").append( comb );
        sb.append( " for job ").append(job.getID() );

         return sb.toString();
    }
    
    /**
     * Constructs an error message in case of style mismatch.
     *
     * @param job      the job object.
     * @param key      the missing key
     */
    protected String missingKeyError( Job job, String key ){
        StringBuffer sb = new StringBuffer();
        sb.append( "( " ).
             append( "Missing key " ).append( key ).
             append( " for job " ).append( job.getName() ).
             append( "with style " ).append( STYLE_NAME );

         return sb.toString();
    }

    /**
     * Condor Quotes a string
     *
     * @param string   the string to be quoted.
     *
     * @return quoted string.
     *
     * @throws CondorStyleException in case of condor quoting error
     */
    private String quote( String string ) throws CondorStyleException{
        String result;
        try{
            mLogger.log("Unquoted string is  " + string, LogManager.TRACE_MESSAGE_LEVEL);
            result = CondorQuoteParser.quote( string, true );
            mLogger.log("Quoted string is  " + result, LogManager.TRACE_MESSAGE_LEVEL );
        }
        catch (CondorQuoteParserException e) {
            throw new CondorStyleException("CondorQuoting Problem " +
                                       e.getMessage());
        }
        return result;

    }

    /**
     * Converts minutes into hh:dd:ss for PBS formatting purposes
     * 
     * @param minutes
     * 
     * @return 
     */
    public String pbsFormattedTimestamp(String minutes ) {
        int minutesValue = Integer.parseInt(minutes);
        
        if( minutesValue < 0 ){
            throw new IllegalArgumentException( "Invalid value for minutes provided for conversion " + minutes );
        }
        
        int hours = minutesValue/60;
        int mins   = minutesValue%60;
        
        StringBuffer result = new StringBuffer();
        if( hours < 10 ){
            result.append( "0" ).append( hours );
        }
        else{
            result.append( hours );
        }
        result.append(":");
        if( mins < 10 ){
            result.append( "0" ).append( mins );
        }
        else{
            result.append( mins );
        }
        result.append( ":00" );//we don't have second precision
        
        return result.toString();
        
    }


    /**
     * This translates the Pegasus resource profiles to corresponding globus 
     * profiles that are used to set the shell parameters for
     * local attributes shell script for the LRMS.
     * 
     * @param job 
     * @param gridResource   the grid resource associated with the job. 
     *                       can be pbs | sge.
     */
    protected void handleResourceRequirements( Job job, String gridResource) throws CondorStyleException {
        //PM-962 we update the globus RSL keys on basis
        //of Pegasus profile keys before doing any translation
        
        mCondorG.handleResourceRequirements(job);
        
        //sanity check
        if( (!(gridResource.equals( "pbs") || gridResource.equals( "sge") ))){
            //if it is not pbs or sge . log a warning.
            mLogger.log( "Glite mode supports only pbs or sge submission. Will use PBS style attributes for job " + 
                          job.getID() + " with grid resource " + gridResource,
                         LogManager.WARNING_MESSAGE_LEVEL );
            gridResource = "pbs";
            
        }
        
        if ( gridResource.equals( "pbs" ) ){
            //check for cores / count if set
            boolean coresSet = job.globusRSL.containsKey( Globus.COUNT_KEY );
            boolean nodesSet = job.globusRSL.containsKey( Globus.HOST_COUNT_KEY );
            boolean ppnSet   = job.globusRSL.containsKey( Globus.XCOUNT_KEY );
            
            if( coresSet ){
                //we need to arrive at PPN which is cores/nodes
                int cores = Integer.parseInt((String) job.globusRSL.get( Globus.COUNT_KEY));    
                //sanity check
                if ( !( nodesSet || ppnSet ) ){
                    //neither nodes or ppn are set
                    //cannot do any translation
                    throw new CondorStyleException( "Cannot translate to PBS attributes. Only cores " + 
                                                     cores + " specified for job " + job.getID());
                }
                
                //need to do some arithmetic to arrive at nodes and ppn
                if( nodesSet ){
                    
                    int nodes = Integer.parseInt((String) job.globusRSL.get( Globus.HOST_COUNT_KEY));
                    int ppn = cores/nodes;
                    //sanity check
                    if( cores%nodes != 0 ){
                        throw new CondorStyleException( invalidCombinationError ( job, cores, nodes, null) );
                    }
                    if( ppnSet ){
                        //all three were set . check if derived value is same as
                        //existing
                        int existing = Integer.parseInt((String) job.globusRSL.get( Globus.XCOUNT_KEY) );
                        if( existing != ppn ){
                            throw new CondorStyleException( invalidCombinationError ( job, cores, nodes, ppn) );
                        }
                    }
                    else{
                        job.globusRSL.construct( Globus.XCOUNT_KEY, Integer.toString(ppn) );
                    }
                }
                else{
                    //we need to arrive at nodes which is cores/ppn
                    int ppn = Integer.parseInt((String) job.globusRSL.get( Globus.XCOUNT_KEY));
                    int nodes = cores/ppn;
                    //sanity check
                    if( cores%ppn != 0 ){
                        throw new CondorStyleException( invalidCombinationError ( job, cores, null, ppn));
                    }
                    
                    if( nodesSet ){
                        //all three were set . check if derived value is same as
                        //existing
                        int existing = Integer.parseInt((String) job.globusRSL.get( Globus.HOST_COUNT_KEY) );
                        if( existing != nodes ){
                            throw new CondorStyleException( invalidCombinationError ( job, cores, nodes, ppn) );
                        }
                    }
                    else{
                        job.globusRSL.construct( Globus.HOST_COUNT_KEY, Integer.toString( nodes ) );
                    }
                }
            }
            else {
                //usually for PBS users specify nodes and ppn
                //globus rsl keys are already set appropriately for translation
               //FIXME:  should we complain if nothing is associated or
               //set some default value?
            }
            
        }
        else if ( gridResource.equals( "sge" )){
            //for SGE case
            boolean coresSet = job.globusRSL.containsKey( Globus.COUNT_KEY );
            boolean nodesSet = job.globusRSL.containsKey( Globus.HOST_COUNT_KEY );
            boolean ppnSet   = job.globusRSL.containsKey( Globus.XCOUNT_KEY );
            
            if( coresSet ){
                //then that is what SGE really needs. 
                //ignore other values.
            }
            else{
                //we need to attempt to arrive at a value or specify a default value
                if( nodesSet && ppnSet ){
                    //set cores to multiple
                    int nodes = Integer.parseInt((String) job.globusRSL.get( Globus.HOST_COUNT_KEY));
                    int ppn   = Integer.parseInt((String) job.globusRSL.get( Globus.XCOUNT_KEY));
                    job.globusRSL.construct( Globus.COUNT_KEY, Integer.toString( nodes*ppn ) );
                }
                else if( nodesSet || ppnSet ){ 
                    throw new CondorStyleException( "Either cores or ( nodes and ppn) need to be set for SGE submission for job " + job.getID() );
                    
                }
                //default case nothing specified 
            }

        }
        else{
            //unreachable code
            throw new CondorStyleException( "Invalid grid resource associated for job " + job.getID() + " " + gridResource );
        }
    }
    
    public static void main(String[] args ){
        GLite gl = new GLite();
        
        System.out.println( "0 mins is " + gl.pbsFormattedTimestamp( "0") );
        System.out.println( "11 mins is " + gl.pbsFormattedTimestamp( "11") );
        System.out.println( "60 mins is " + gl.pbsFormattedTimestamp( "60") );
        System.out.println( "69 mins is " + gl.pbsFormattedTimestamp( "69") );
        System.out.println( "169 mins is " + gl.pbsFormattedTimestamp( "169") );
        System.out.println( "1690 mins is " + gl.pbsFormattedTimestamp( "1690") );
    }

}
