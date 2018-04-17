#!/usr/bin/env bash
set -o nounset
set -o errexit
set -o pipefail
set -o errtrace
set -o functrace

cd "$(dirname "${BASH_SOURCE[0]}")"    # Run from the script's root



##############
#  SETTINGS  #
##############
BIN_NAME=                    # Name of binary archive used as a source for package building
IGNITE_VERSION=              # Main product version
PACKAGING_DIR=               # Root directory of packaging script
DEB_WORK_DIR=                # Main directory for building DEB packages
RPM_WORK_DIR=                # Main directory for building RPM packages

BUILD_DEB_FLAG=false         # Whether to build DEB
BUILD_RPM_FLAG=false         # Whether to build RPM
BUILD_FROM_SRC_FLAG=false    # Whether to build packages from sources



###############
#  FUNCTIONS  #
###############

# Usage help
usage () {
    cat <<EOF

######################################################################
#  Build RPM or DEB package from Apache Ignite's sources or binaries #
######################################################################

Prerequisites:
     - RPM: binary archive with name 'apache-ignite-fabric-<version>-bin.zip'
     - DEB: previously built corresponding RPM package

Usage: ./$(basename ${BASH_SOURCE[0]}) --rpm,--deb [--src]

Options:
    --rpm, --deb     select package type for building (multiselect)

    --src            prepare binary artifacts from source before packaging

EOF
}


# Check and prepare build environment
prepEnv () {
    installCmd=""
    packages="unzip curl alien gcc"
    executables="rpmbuild unzip curl alien gcc"

    # Check OS
    name=$(cat /etc/*release | grep ^NAME | sed -r 's|.*"(.*)".*|\1|')
    case ${name} in
        "Ubuntu")
            installCmd='apt --no-install-recommends'
            packages="${packages} rpm"
            ;;
        "CentOS Linux")
            installCmd="yum"
            packages="${packages} rpm-build"
            ;;
        *)
            echo "Unknown or unsupported linux detected"
            echo "Will skip 'Check and prepare build environment' step"
            echo "Please, prepare you environment manually"
            ;;
    esac

    # Install missing software if necessary
    installFlag=false
    if [[ ! -z "${installCmd}" ]]; then
        for executable in ${executables}; do
            command -v ${executable} &>/dev/null || {
                installFlag=true
                break
            }
        done
        if ${installFlag}; then
            echo "[INFO] Software installation required root privileges"
            sudo ${installCmd} install ${packages}
        fi
    fi
}


# Prepare binary artifacts from sources
prepBin () {
    echo "Packaging from sources is not implemented"
}


# Check that binary archive exists and try to download it from Apache Dist Archive is not
getBin () {
    IGNITE_VERSION=$(cat rpm/apache-ignite.spec | grep Version: | head -1 | sed -r 's|.*:\s+(.*)|\1|')
    BIN_NAME="apache-ignite-fabric-${IGNITE_VERSION}-bin.zip"
    binPreparedFlag=false

    if [ -f "${BIN_NAME}" ]; then
    binPreparedFlag=true
    fi
    if ! ${binPreparedFlag}; then
        curl -O https://archive.apache.org/dist/ignite/${IGNITE_VERSION}/${BIN_NAME}
        binPreparedFlag=true
    fi
    if ! ${binPreparedFlag}; then
        echo "[ERROR] Can't find | get Apache Ignite's binary archive"
        exit 1
    fi
}


# Build RPM package
buildRPM () {
    RPM_WORK_DIR="$(mktemp -d)"
    rm -rfv *.rpm

    # Prepare build layout
    mkdir -pv ${RPM_WORK_DIR}/{BUILD,RPMS,SOURCES,SPECS,SRPMS}
    cp -rfv ${BIN_NAME} rpm/{name.service,service.sh} ${RPM_WORK_DIR}/SOURCES
    cp -rfv rpm/apache-ignite.spec ${RPM_WORK_DIR}/SPECS

    # Assemble RPM packages
    rpmbuild -bb -v --define "_topdir ${RPM_WORK_DIR}" ${RPM_WORK_DIR}/SPECS/apache-ignite.spec

    # Gather RPMS
    find ${RPM_WORK_DIR} -name "*.rpm" -exec mv -fv {} ${PACKAGING_DIR} \;
}


# Build DEB package
buildDEB () {
    DEB_WORK_DIR="$(mktemp -d)"
    rm -rfv *.deb

    # Check that RPMs for building exists
    packageVersion="$(cat deb/changelog | head -1 | sed -r 's|.*\((.*)\).*|\1|')"
    if [ ! -f "apache-ignite-${packageVersion}.noarch.rpm" ]; then
        echo "[ERROR] RPM for converting to DEB not found"
        exit 1
    fi

    # Unpack RPMs and prepare DEBs build layout
    cd ${DEB_WORK_DIR}
    cp -rfv ${PACKAGING_DIR}/apache-ignite-${packageVersion}.noarch.rpm ${DEB_WORK_DIR}
    alien --scripts --verbose --keep-version --single apache-ignite-${packageVersion}.noarch.rpm

    # Copy custom DEBs control files and make some modifications on the fly
    buildDirVersion="$(echo ${packageVersion} | cut -f1 -d-)"
    cp -rfv ${PACKAGING_DIR}/deb/{changelog,control,copyright,rules} ${DEB_WORK_DIR}/apache-ignite-${buildDirVersion}/debian
    sed -i -r -e 's|/usr/bin/mkdir|/bin/mkdir|' -e 's|/usr/bin/chown|/bin/chown|' ${DEB_WORK_DIR}/apache-ignite-${buildDirVersion}/etc/systemd/system/apache-ignite@.service

    # Assemble DEB packages
    cd ${DEB_WORK_DIR}/apache-ignite-${buildDirVersion}
    debian/rules binary

    # Gather DEBs
    find ${DEB_WORK_DIR} -name "*.deb" -exec mv -fv {} ${PACKAGING_DIR} \;
}


# Trap function
processTrap () {
    # Removing temporary files
    echo "Removing temporary work directories: ${DEB_WORK_DIR} ${RPM_WORK_DIR}"
    rm -rf ${DEB_WORK_DIR} ${RPM_WORK_DIR}

    # Finish
    echo
    TIME="$(($(date +%s) - START_TIME))"
    echo "=== Run time: $(printf '%dh:%02dm:%02ds\n' $((TIME/3600)) $((TIME%3600/60)) $((TIME%60))) ==="
    echo
}



###########
#  START  #
###########
START_TIME=$(date +%s)
clear


# Parse input options
while [ $# -gt 0 ]; do
    case "$1" in
        --src)
            shift
            BUILD_FROM_SRC_FLAG=true
            ;;
        --rpm)
            shift
            BUILD_RPM_FLAG=true
            ;;
        --deb)
            BUILD_DEB_FLAG=true
            shift
            ;;
        --help)
            usage
            exit 0
        ;;
        *)
            echo "[ERROR] Unknown argument '${1}'"
            usage
            exit 1
        ;;
    esac
done
if [ ${BUILD_RPM_FLAG} == false -a ${BUILD_DEB_FLAG} == false ]; then
    echo "[ERROR] At least one type of package should be specified: RPM or DEB"
    usage
    exit 1
fi

PACKAGING_DIR="$(pwd)"


# Trap
trap 'processTrap' EXIT


# Build packages
prepEnv

if ${BUILD_RPM_FLAG}; then
    if ${BUILD_FROM_SRC_FLAG}; then
        prepBin
    else getBin
    fi
    buildRPM
fi

if ${BUILD_DEB_FLAG}; then buildDEB; fi

