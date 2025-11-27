#!/bin/bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# Build ROM from redroid Docker image for twoyi container
# This script downloads the redroid aarch64 image and extracts the rootfs

set -e

# Configuration
REDROID_IMAGE="${REDROID_IMAGE:-docker.io/redroid/redroid:16.0.0-latest}"
ROOTFS_OUTPUT="${ROOTFS_OUTPUT:-rootfs}"
ROM_7Z_OUTPUT="${ROM_7Z_OUTPUT:-rootfs.7z}"
ROM_INFO_FILE="${ROM_INFO_FILE:-rom.ini}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_dependencies() {
    log_info "Checking dependencies..."
    
    local missing_deps=()
    
    # Check for required tools
    for cmd in docker 7z; do
        if ! command -v "$cmd" &> /dev/null; then
            missing_deps+=("$cmd")
        fi
    done
    
    if [ ${#missing_deps[@]} -ne 0 ]; then
        log_error "Missing dependencies: ${missing_deps[*]}"
        log_info "Please install the missing dependencies:"
        for dep in "${missing_deps[@]}"; do
            case "$dep" in
                docker)
                    echo "  - Docker: https://docs.docker.com/get-docker/"
                    ;;
                7z)
                    echo "  - p7zip: apt-get install p7zip-full OR brew install p7zip"
                    ;;
            esac
        done
        exit 1
    fi
    
    # Check if docker is running
    if ! docker info &> /dev/null; then
        log_error "Docker daemon is not running. Please start Docker first."
        exit 1
    fi
    
    log_info "All dependencies are available."
}

pull_redroid_image() {
    log_info "Pulling redroid image: ${REDROID_IMAGE}"
    
    # Pull the aarch64 version specifically
    docker pull --platform linux/arm64 "${REDROID_IMAGE}"
    
    log_info "Successfully pulled redroid image."
}

extract_rootfs() {
    local temp_container="twoyi-redroid-temp-$$"
    
    log_info "Creating temporary container to extract rootfs..."
    
    # Create a temporary container (without starting it)
    docker create --name "${temp_container}" --platform linux/arm64 "${REDROID_IMAGE}"
    
    # Clean up any existing rootfs directory
    rm -rf "${ROOTFS_OUTPUT}"
    mkdir -p "${ROOTFS_OUTPUT}"
    
    log_info "Exporting container filesystem..."
    docker export "${temp_container}" | tar -xf - -C "${ROOTFS_OUTPUT}"
    
    # Clean up temporary container
    docker rm "${temp_container}"
    
    log_info "Rootfs extracted to: ${ROOTFS_OUTPUT}"
}

customize_rootfs() {
    log_info "Customizing rootfs for twoyi..."
    
    # Create rom.ini file with metadata
    local rom_version="16.0.0"
    local rom_code="$(date +%Y%m%d%H%M)"
    
    cat > "${ROOTFS_OUTPUT}/${ROM_INFO_FILE}" << EOF
# Twoyi ROM built from redroid
author=twoyi
version=${rom_version}
code=${rom_code}
desc=Built from redroid (${REDROID_IMAGE}) for headless scrcpy display
EOF

    # Create necessary directories (only if they don't exist)
    # Use -e for regular files/dirs and -L for symlinks (including broken ones)
    # Skip mkdir if the path exists as any type (file, directory, or symlink)
    if [ ! -e "${ROOTFS_OUTPUT}/dev/input" ] && [ ! -L "${ROOTFS_OUTPUT}/dev/input" ]; then
        mkdir -p "${ROOTFS_OUTPUT}/dev/input"
    fi
    if [ ! -e "${ROOTFS_OUTPUT}/dev/socket" ] && [ ! -L "${ROOTFS_OUTPUT}/dev/socket" ]; then
        mkdir -p "${ROOTFS_OUTPUT}/dev/socket"
    fi
    if [ ! -e "${ROOTFS_OUTPUT}/dev/maps" ] && [ ! -L "${ROOTFS_OUTPUT}/dev/maps" ]; then
        mkdir -p "${ROOTFS_OUTPUT}/dev/maps"
    fi
    if [ ! -e "${ROOTFS_OUTPUT}/sdcard" ] && [ ! -L "${ROOTFS_OUTPUT}/sdcard" ]; then
        mkdir -p "${ROOTFS_OUTPUT}/sdcard"
    fi
    
    # Ensure vendor directory exists
    if [ ! -e "${ROOTFS_OUTPUT}/vendor" ] && [ ! -L "${ROOTFS_OUTPUT}/vendor" ]; then
        mkdir -p "${ROOTFS_OUTPUT}/vendor"
    fi
    
    # Create default.prop for vendor if it doesn't exist (handle symlinks)
    local vendor_prop="${ROOTFS_OUTPUT}/vendor/default.prop"
    if [ ! -e "${vendor_prop}" ] && [ ! -L "${vendor_prop}" ]; then
        # Only create if vendor is a real directory (not a symlink)
        if [ -d "${ROOTFS_OUTPUT}/vendor" ] && [ ! -L "${ROOTFS_OUTPUT}/vendor" ]; then
            touch "${vendor_prop}"
        fi
    fi
    
    # Set up ADB to listen on network by default
    # This is important for scrcpy connectivity
    local default_prop="${ROOTFS_OUTPUT}/default.prop"
    # Handle the case where default.prop might be a symlink
    if [ -L "${default_prop}" ]; then
        # If it's a symlink, resolve it and work with the real file
        local real_prop=$(readlink -f "${default_prop}" 2>/dev/null || echo "${default_prop}")
        if [ -f "${real_prop}" ]; then
            grep -v "^service.adb.tcp.port=" "${real_prop}" > "${real_prop}.tmp" || true
            echo "service.adb.tcp.port=5555" >> "${real_prop}.tmp"
            mv "${real_prop}.tmp" "${real_prop}"
        fi
    elif [ -f "${default_prop}" ]; then
        # Regular file - modify it directly
        grep -v "^service.adb.tcp.port=" "${default_prop}" > "${default_prop}.tmp" || true
        echo "service.adb.tcp.port=5555" >> "${default_prop}.tmp"
        mv "${default_prop}.tmp" "${default_prop}"
    else
        # Create default.prop if it doesn't exist
        echo "service.adb.tcp.port=5555" > "${default_prop}"
        log_info "Created default.prop with ADB network port configuration"
    fi
    
    # Add ADB port configuration to init.rc if possible
    # (This ensures ADB listens on the network for scrcpy)
    
    log_info "Rootfs customization complete."
}

create_rom_archive() {
    log_info "Creating ROM archive: ${ROM_7Z_OUTPUT}"
    
    # Remove existing archive if present
    rm -f "${ROM_7Z_OUTPUT}"
    
    # Create 7z archive with the rootfs
    # Use maximum compression for smaller size
    (cd "${ROOTFS_OUTPUT}" && 7z a -t7z -m0=lzma2 -mx=9 -mfb=64 -md=32m -ms=on "../${ROM_7Z_OUTPUT}" .)
    
    # Calculate MD5 for verification
    local md5sum
    if command -v md5sum &> /dev/null; then
        md5sum=$(md5sum "${ROM_7Z_OUTPUT}" | awk '{print $1}')
    elif command -v md5 &> /dev/null; then
        md5sum=$(md5 -q "${ROM_7Z_OUTPUT}")
    else
        md5sum="unknown"
    fi
    
    # Update rom.ini with MD5
    sed -i "s/^md5=.*/md5=${md5sum}/" "${ROOTFS_OUTPUT}/${ROM_INFO_FILE}" 2>/dev/null || true
    
    log_info "ROM archive created: ${ROM_7Z_OUTPUT}"
    log_info "MD5: ${md5sum}"
    
    # Show archive size
    local size
    size=$(du -h "${ROM_7Z_OUTPUT}" | awk '{print $1}')
    log_info "Archive size: ${size}"
}

copy_rom_info() {
    log_info "Copying rom.ini to output directory..."
    cp "${ROOTFS_OUTPUT}/${ROM_INFO_FILE}" "./${ROM_INFO_FILE}"
}

cleanup() {
    log_info "Cleaning up..."
    # Optionally remove the extracted rootfs to save space
    # rm -rf "${ROOTFS_OUTPUT}"
    log_info "Cleanup complete. Rootfs directory retained for inspection."
}

show_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Build ROM from redroid Docker image for twoyi container."
    echo ""
    echo "Options:"
    echo "  -i, --image IMAGE    Redroid Docker image (default: ${REDROID_IMAGE})"
    echo "  -o, --output DIR     Output directory for rootfs (default: ${ROOTFS_OUTPUT})"
    echo "  -a, --archive FILE   Output 7z archive name (default: ${ROM_7Z_OUTPUT})"
    echo "  -c, --cleanup        Remove rootfs directory after creating archive"
    echo "  -h, --help           Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                                    # Use defaults"
    echo "  $0 -i redroid/redroid:14.0.0-latest  # Use different redroid version"
    echo "  $0 -o /tmp/rootfs -a rom.7z          # Custom output paths"
}

main() {
    local do_cleanup=false
    
    # Parse command line arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            -i|--image)
                REDROID_IMAGE="$2"
                shift 2
                ;;
            -o|--output)
                ROOTFS_OUTPUT="$2"
                shift 2
                ;;
            -a|--archive)
                ROM_7Z_OUTPUT="$2"
                shift 2
                ;;
            -c|--cleanup)
                do_cleanup=true
                shift
                ;;
            -h|--help)
                show_usage
                exit 0
                ;;
            *)
                log_error "Unknown option: $1"
                show_usage
                exit 1
                ;;
        esac
    done
    
    log_info "Building twoyi ROM from redroid image"
    log_info "Image: ${REDROID_IMAGE}"
    log_info "Output: ${ROOTFS_OUTPUT}"
    log_info "Archive: ${ROM_7Z_OUTPUT}"
    echo ""
    
    check_dependencies
    pull_redroid_image
    extract_rootfs
    customize_rootfs
    create_rom_archive
    copy_rom_info
    
    if [ "$do_cleanup" = true ]; then
        rm -rf "${ROOTFS_OUTPUT}"
        log_info "Removed rootfs directory."
    else
        cleanup
    fi
    
    echo ""
    log_info "ROM build complete!"
    log_info "ROM archive: ${ROM_7Z_OUTPUT}"
    log_info "ROM info: ${ROM_INFO_FILE}"
    echo ""
    log_info "To use this ROM with twoyi:"
    log_info "  1. Copy ${ROM_7Z_OUTPUT} to app/src/main/assets/rootfs.7z"
    log_info "  2. Copy ${ROM_INFO_FILE} to app/src/main/assets/rom.ini"
    log_info "  3. Build the app"
}

main "$@"
