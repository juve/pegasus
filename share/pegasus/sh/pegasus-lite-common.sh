#!/bin/bash

##
#  Copyright 2007-2011 University Of Southern California
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing,
#  software distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
##

#
# This file contains a set of common bash funtions to be used by 
# Pegasus Lite jobs
#
# Author: Mats Rynge <rynge@isi.edu>
#


function pegasus_lite_log()
{
    TS=`/bin/date +'%F %H:%M:%S'`
    echo "$TS: $1" 1>&2
}


function pegasus_lite_worker_package()
{
    # many ways of providing worker package
    if pegasus_lite_internal_wp_shipped || pegasus_lite_internal_wp_in_env || pegasus_lite_internal_wp_download; then
        return 0
    fi
    return 1
}


function pegasus_lite_internal_wp_shipped()
{
    # was the job shipped with a Pegasus worker package?
    if ls $pegasus_lite_start_dir/pegasus-worker-*.tar.gz >/dev/null 2>&1; then
        pegasus_lite_log "The job contained a Pegasus worker package"
        tar xzf $pegasus_lite_start_dir/pegasus-worker-*.tar.gz
        rm -f $pegasus_lite_start_dir/pegasus-worker-*.tar.gz
        unset PEGASUS_HOME
        export PATH=${pegasus_lite_work_dir}/pegasus-${pegasus_lite_full_version}/bin:$PATH
        return 0
    fi
    return 1
}


function pegasus_lite_internal_wp_in_env()
{
    old_path=$PATH

    # use PEGASUS_HOME if set
    if [ "x$PEGASUS_HOME" != "x" ]; then
        PATH="$PEGASUS_HOME/bin:$PATH"
        export PATH
    fi

    # is there already a pegasus install in our path?
    detected_pegasus_bin=`which pegasus-config 2>/dev/null || /bin/true`
    if [ "x$detected_pegasus_bin" != "x" ]; then
        detected_pegasus_bin=`dirname $detected_pegasus_bin`

        # does the version match?
        if $detected_pegasus_bin/pegasus-config --version 2>/dev/null | grep -E "^${pegasus_lite_version_major}\.${pegasus_lite_version_minor}\." >/dev/null 2>/dev/null; then
            pegasus_lite_log "Using existing Pegasus binaries in $detected_pegasus_bin"
            return 0
        else
            pegasus_lite_log "Pegasus binaries in $detected_pegasus_bin do not match Pegasus version used for current workflow"
        fi
    fi

    # back out env changes
    unset PEGASUS_HOME
    PATH=$old_path
    export PATH

    return 1
}


function pegasus_lite_internal_wp_download() 
{
    # fall back - download a worker package from download.pegasus.isi.edu

    system=$(pegasus_lite_get_system)
    if [ $? != 0 ]; then
        # not sure what system we are on - try the default package
        system="x86_64_rhel_6"
    fi
    
    url="http://download.pegasus.isi.edu/pegasus/${pegasus_lite_version_major}"
    url="${url}.${pegasus_lite_version_minor}.${pegasus_lite_version_patch}"
    url="${url}/pegasus-worker"
    url="${url}-${pegasus_lite_version_major}.${pegasus_lite_version_minor}.${pegasus_lite_version_patch}"
    url="${url}-${system}.tar.gz"
    pegasus_lite_log "Downloading Pegasus worker package from $url"
    curl -s -S --insecure -o pegasus-worker.tar.gz "$url" || wget -q -O pegasus-worker.tar.gz "$url"
    tar xzf pegasus-worker.tar.gz
    rm -f pegasus-worker.tar.gz

    unset PEGASUS_HOME
    export PATH="${pegasus_lite_work_dir}/pegasus-${pegasus_lite_full_version}/bin:$PATH"
}


function pegasus_lite_setup_work_dir()
{
    # remember where we started from
    pegasus_lite_start_dir=`pwd`

    if [ "x$pegasus_lite_work_dir" != "x" ]; then
        pegasus_lite_log "Not creating a new work directory as it is already set to $pegasus_lite_work_dir"
        return
    fi

    targets="$PEGASUS_WN_TMP $_CONDOR_SCRATCH_DIR $OSG_WN_TMP $TG_NODE_SCRATCH $TG_CLUSTER_SCRATCH $SCRATCH $TMPDIR $TMP /tmp"
    unset TMPDIR

    if [ "x$PEGASUS_WN_TMP_MIN_SPACE" = "x" ]; then
        PEGASUS_WN_TMP_MIN_SPACE=1000000
    fi

    for d in $targets; do

        pegasus_lite_log "Checking $d for potential use as work space... " 

        # does the target exist?
        if [ ! -e $d ]; then
            pegasus_lite_log "  Workdir: $d does not exist"
            continue
        fi

        # make sure there is enough available diskspace
        cd $d
        free=`df -kP . | awk '{if (NR==2) print $4}'`
        if [ "x$free" == "x" -o $free -lt $PEGASUS_WN_TMP_MIN_SPACE ]; then
            pegasus_lite_log "  Workdir: not enough disk space available in $d"
            continue
        fi

        if touch $d/.dirtest.$$ >/dev/null 2>&1; then
            rm -f $d/.dirtest.$$ >/dev/null 2>&1
            d=`mktemp -d $d/pegasus.XXXXXX`
            export pegasus_lite_work_dir=$d
            export pegasus_lite_work_dir_created=1
            pegasus_lite_log "  Work dir is $d - $free kB available"

            # PM-968 if provided, copy lof files from the HTCondor iwd to the PegasusLite work dir
            find $pegasus_lite_start_dir -name \*.lof -exec cp {} $pegasus_lite_work_dir/ \; >/dev/null 2>&1

            cd $pegasus_lite_work_dir
            return 0
        fi
        pegasus_lite_log "  Workdir: not allowed to write to $d"
    done
    return 1
}


function pegasus_lite_init()
{
    pegasus_lite_full_version=${pegasus_lite_version_major}.${pegasus_lite_version_minor}.${pegasus_lite_version_patch}

    # announce version - we do this so pegasus-exitcode and other tools
    # can tell the job was a PegasusLite job
    pegasus_lite_log "PegasusLite: version ${pegasus_lite_full_version}" 1>&2

    # for staged credentials, expand the paths and set strict permissions
    for base in X509_USER_PROXY S3CFG BOTO_CONFIG SSH_PRIVATE_KEY irodsEnvFile GOOGLE_PKCS12 ; do
        for key in `(env | grep -i ^$base | sed 's/=.*//') 2>/dev/null`; do
            eval val="\$$key"
            # expand the path
            if ! (echo $val | grep "^/") >/dev/null 2>&1; then
                eval $key=`pwd`/"$val"
                eval val="\$$key"
                pegasus_lite_log "Expanded \$$key to $val"
            fi
            chmod 0600 $val
        done
    done

}


function pegasus_lite_exit()
{
    rc=$?
    if [ "x$rc" = "x" ]; then
        rc=0
    fi

    if [ "x$job_ec" != "x" ];then
	if [ $job_ec != 0 ];then
	    pegasus_lite_log "Job failed with exitcode $job_ec"
	    rc=$job_ec
	fi
    fi
	

    if [ $rc != 0 ]; then
        pegasus_lite_log "FAILURE: Last command exited with $rc"
    fi

    if [ "x$pegasus_lite_work_dir_created" = "x1" ]; then
        cd /
        rm -rf $pegasus_lite_work_dir
        pegasus_lite_log "$pegasus_lite_work_dir cleaned up"
    fi

    echo "PegasusLite: exitcode $rc" 1>&2

    exit $rc
}


function pegasus_lite_get_system()
{
    # PM-781
    # This function is a replacement of the old release-tools/getsystem
    # and was moved here because we need the getsystem functionallity not
    # only at build time, but at runtime fromt he jobs so that the jobs
    # can determine what worker package is required.

    # The goal is to get a triple identify the system:
    # arch _ osname _ osversion
    # for example: x86_64_deb_7

    arch=`uname -m 2>&1` || arch="UNKNOWN"
    osname=`uname -s 2>&1` || osname="UNKNOWN"
    osversion=`uname -r 2>&1` || osversion="UNKNOWN"
        
    if (echo $arch | grep -E '^i[0-9]86$') >/dev/null 2>&1; then
        arch="x86" 
    fi

    if [ "$osname" = "Linux" ]; then

        # /etc/issue works most of the time, but there are exceptions
        osname=`cat /etc/issue | head -n1 | awk '{print $1;}' | tr '[:upper:]' '[:lower:]'`

        if [ "X$osname" = "Xubuntu" ]; then
            osversion=`cat /etc/issue | head -n1 | awk '{print $2;}'` 
        elif [ -e /etc/debian_version ]; then
            osname="deb"
            osversion=`cat /etc/debian_version`
        elif [ "X$osname" = "Xfedora" ]; then
            osname="fc"
            osversion=`cat /etc/issue | head -n1 | awk '{print $3;}'`
        elif [ -e /etc/redhat-release ]; then
            osname="rhel"
            osversion=`cat /etc/redhat-release | grep -o -E ' [0-9]+.[0-9]+'`
        elif [ -e /etc/rocks-release ]; then
            osname="rhel"
            osversion=`cat /etc/rocks-release | grep -o -E ' [0-9]+.[0-9]+'`
        elif [ -e /etc/SuSE-release ]; then
            osname="suse"
            osversion=`cat /etc/SuSE-release | grep VERSION | grep -o -E ' [0-9]+'`
        fi
        
        # remove spaces/tabs in the version
        osversion=`echo $osversion | sed 's/[ \t]//g'`

        # remove / in the version
        osversion=`echo $osversion | sed 's/\//_/g'`

        # we only want major version numbers
        osversion=`echo $osversion | sed 's/[\.-].*//'`

        echo "${arch}_${osname}_${osversion}"
        return 0
    fi

    if [ "$osname" = "Darwin" ]; then
        osname="macos"
        osversion=`/usr/bin/sw_vers -productVersion`
        
        # we only want major version numbers
        osversion=`echo $osversion | sed 's/[\.-].*//'`

        echo "${arch}_${osname}_${osversion}"
        return 0
    fi
    
    if [ "$osname" = "FreeBSD" ]; then
        osname="freebsd"
        
        # we only want major version numbers
        osversion=`echo $osversion | sed 's/[\.-].*//'`

        echo "${arch}_${osname}_${osversion}"
        return 0
    fi
        
    # unable to determine detailed system information
    echo "${arch}_${osname}_${osversion}"
    return 1
}

