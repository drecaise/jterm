#!/bin/sh
# Installs what build-freerdp-linux.sh and build-linux.sh need. Meant for the CI build containers
# (run as root); on a workstation, read it as the package list.
#
# curl is deliberately not installed on Rocky/RHEL: the images ship curl-minimal, which provides
# /usr/bin/curl and conflicts with the full curl package (the el9 build failed on that).
#
# Rocky/RHEL use pkgconfig(...) provides so one line serves EL9 and EL10 (whose zlib packages
# differ); cJSON and patchelf come from EPEL, ninja from CRB on EL9.
set -eu
. /etc/os-release
case "$ID" in
    rocky|rhel|almalinux|centos)
        dnf -y install dnf-plugins-core epel-release
        dnf config-manager --set-enabled crb
        dnf -y install git cmake ninja-build gcc gcc-c++ make patchelf \
            'pkgconfig(openssl)' 'pkgconfig(zlib)' 'pkgconfig(libcjson)'
        ;;
    ubuntu|debian)
        export DEBIAN_FRONTEND=noninteractive
        apt-get update
        apt-get install -y --no-install-recommends ca-certificates curl git cmake ninja-build \
            gcc g++ make pkg-config patchelf libssl-dev zlib1g-dev libcjson-dev
        ;;
    *)
        echo "install-build-deps-linux.sh: unsupported distribution '$ID'" >&2
        exit 1
        ;;
esac
