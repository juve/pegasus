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


package edu.isi.pegasus.planner.common;

import edu.isi.pegasus.common.logging.LogManager;
import edu.isi.pegasus.planner.classes.PlannerOptions;
import edu.isi.pegasus.planner.transfer.sls.SLSFactory;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

/**
 * A utility class that returns JAVA Properties that need to be set based on
 * a configuration value
 * 
 * @author Karan Vahi
 * @version $Revision$
 */
public class PegasusConfiguration {
    
    /**
     * The property key for pegasus configuration.
     */
    public static final String PEGASUS_CONFIGURATION_PROPERTY_KEY = "pegasus.data.configuration";

    /**
     * The value for the S3 configuration.
     */
    public static final String DEPRECATED_S3_CONFIGURATION_VALUE = "S3";

       /**
     * The value for the non shared filesystem configuration.
     */
    public static final String SHARED_FS_CONFIGURATION_VALUE = "sharedfs";

    /**
     * The value for the non shared filesystem configuration.
     */
    public static final String NON_SHARED_FS_CONFIGURATION_VALUE = "nonsharedfs";

    /**
     * The value for the condor configuration.
     */
    public static final String CONDOR_CONFIGURATION_VALUE = "condorio";

    /**
     * The value for the condor configuration.
     */
    public static final String DEPRECATED_CONDOR_CONFIGURATION_VALUE = "Condor";
    
    /**
     * The logger to use.
     */
    private LogManager mLogger;
    
    /**
     * Overloaded Constructor
     * 
     * @param logger   the logger to use.
     */
    public PegasusConfiguration( LogManager logger ){
        mLogger = logger;
    }

    /**
     * Loads configuration specific properties into PegasusProperties,
     * and adjusts planner options accordingly.
     *
     * @param properties   the Pegasus Properties
     * @param options      the PlannerOptions .
     */
    public void loadConfigurationPropertiesAndOptions( PegasusProperties properties,
                                                       PlannerOptions options ){

        this.loadConfigurationProperties( properties );

        //sanitize on the planner options
        if( properties.executeOnWorkerNode() ){
            String slsImplementor = properties.getSLSTransferImplementation();
            if( slsImplementor == null ){
                slsImplementor = SLSFactory.DEFAULT_SLS_IMPL_CLASS;
            }
            
            //check for the sls implementation
            if( slsImplementor.equalsIgnoreCase( DEPRECATED_CONDOR_CONFIGURATION_VALUE ) ){

                for( String site : (Set<String>)options.getExecutionSites() ){
                    //sanity check to make sure staging site is set to local
                    String stagingSite = options.getStagingSite( site );
                    if( stagingSite == null ){
                        stagingSite = "local";
                        //set it to local site
                        mLogger.log( "Setting staging site for " + site + " to " + stagingSite ,
                                      LogManager.CONFIG_MESSAGE_LEVEL );
                        options.addToStagingSitesMappings( site , stagingSite );

                    }
                    else if (!( stagingSite.equalsIgnoreCase( "local" ) )){
                        StringBuffer sb = new StringBuffer();

                        sb.append( "Mismatch in the between execution site ").append( site ).
                           append( " and staging site " ).append( stagingSite ).
                           append( " . For Condor IO staging site should be set to local . " );

                        throw new RuntimeException( sb.toString() );
                    }
                }

            }
        }
    }
    
    /**
     * Loads configuration specific properties into PegasusProperties
     * 
     * @param properties   the Pegasus Properties.
     */
    private void loadConfigurationProperties( PegasusProperties properties ){
        String configuration  = properties.getProperty( PEGASUS_CONFIGURATION_PROPERTY_KEY ) ;
        
        Properties props = this.getConfigurationProperties(configuration);
        for( Iterator it = props.keySet().iterator(); it.hasNext(); ){
            String key = (String) it.next();
            String value = props.getProperty( key );
            this.checkAndSetProperty( properties, key, value );
        }
        
    }
    
    /**
     * Returns Properties corresponding to a particular configuration.
     * 
     * @param configuration  the configuration value.
     * 
     * @return Properties
     */
    public Properties getConfigurationProperties( String configuration ){
        //sanity check
        if( configuration == null ){
            //default is the sharedfs
            configuration = SHARED_FS_CONFIGURATION_VALUE;
        }        
        
        Properties p = new Properties( );
        if( configuration.equalsIgnoreCase( DEPRECATED_S3_CONFIGURATION_VALUE ) || configuration.equalsIgnoreCase( NON_SHARED_FS_CONFIGURATION_VALUE ) ){

            //throw warning for deprecated value
            if( configuration.equalsIgnoreCase( DEPRECATED_S3_CONFIGURATION_VALUE ) ){
                mLogger.log( deprecatedValueMessage( PEGASUS_CONFIGURATION_PROPERTY_KEY,DEPRECATED_S3_CONFIGURATION_VALUE ,NON_SHARED_FS_CONFIGURATION_VALUE ),
                             LogManager.WARNING_MESSAGE_LEVEL );
            }

            p.setProperty( "pegasus.execute.*.filesystem.local", "true" );
            p.setProperty( "pegasus.gridstart", "PegasusLite" );

            //we want the worker package to be staged, unless user sets it to false explicitly
            p.setProperty( PegasusProperties.PEGASUS_TRANSFER_WORKER_PACKAGE_PROPERTY, "true" );
        }
        else if ( configuration.equalsIgnoreCase( CONDOR_CONFIGURATION_VALUE ) || configuration.equalsIgnoreCase( DEPRECATED_CONDOR_CONFIGURATION_VALUE ) ){

            //throw warning for deprecated value
            if( configuration.equalsIgnoreCase( DEPRECATED_CONDOR_CONFIGURATION_VALUE ) ){
                mLogger.log( deprecatedValueMessage( PEGASUS_CONFIGURATION_PROPERTY_KEY,DEPRECATED_CONDOR_CONFIGURATION_VALUE ,CONDOR_CONFIGURATION_VALUE ),
                             LogManager.WARNING_MESSAGE_LEVEL );
            }

            p.setProperty( "pegasus.transfer.sls.*.impl", "Condor" );
            p.setProperty( "pegasus.execute.*.filesystem.local", "true" );
            p.setProperty( "pegasus.gridstart", "PegasusLite" );
            
            //we want the worker package to be staged, unless user sets it to false explicitly
            p.setProperty( PegasusProperties.PEGASUS_TRANSFER_WORKER_PACKAGE_PROPERTY, "true" );

        }
        else if( configuration.equalsIgnoreCase( SHARED_FS_CONFIGURATION_VALUE ) ){
            //PM-624
            //we should not explicitly set it to false. false is default value
            //in Pegasus Properties.
            //p.setProperty( "pegasus.execute.*.filesystem.local", "false" );
        }
        
        return p;
    }
    
    /**
     * Checks for a property, if it does not exist then sets the property to 
     * the value passed
     * 
     * @param key   the property key
     * @param value the value to set to 
     */
    protected void checkAndSetProperty( PegasusProperties properties, String key, String value ) {
        String propValue = properties.getProperty( key );
        if( propValue == null ){
            //set the value
            properties.setProperty( key, value );
        }
        else{
            //log a warning 
            StringBuffer sb = new StringBuffer();
            sb.append( "Property Key " ).append( key ).append( " already set to " ).
               append( propValue ).append( ". Will not be set to - ").append( value );
            mLogger.log( sb.toString(), LogManager.WARNING_MESSAGE_LEVEL );
        }
    }

    /**
     * Returns the deperecated value message
     *
     * @param property              the property
     * @param deprecatedValue       the deprecated value
     * @param updatedValue           the updated value
     *
     * @return message
     */
    protected String deprecatedValueMessage(String property, String deprecatedValue, String updatedValue) {
        StringBuffer sb = new StringBuffer();
        sb.append( " The property " ).append(  property ) .append( " = " ).append( deprecatedValue ).
           append( " is deprecated. Replace with ").append( property ) .append( " = " ).
           append( updatedValue );

        return sb.toString();
    }

}