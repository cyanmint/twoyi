#!/system/bin/sh
DIR=$1
rm $DIR/rootfs/data/system.log
rm -rf $DIR/rootfs/dev/socket
mkdir -p $DIR/rootfs/dev/socket
rm -rf $DIR/rootfs/dev/__properties__
mkdir -p $DIR/rootfs/dev/__properties__
rm $DIR/rootfs/dev/kmsg
rm $DIR/rootfs/dev/pmsg0
touch $DIR/rootfs/dev/kmsg
touch $DIR/rootfs/dev/pmsg0
mkdir -p $DIR/rootfs/data/media/0
mkdir -p $DIR/rootfs/data/system_ce/0
mkdir -p $DIR/rootfs/data/misc_ce/0
#export PROOT_NO_SECCOMP=1
export PATH=/sbin:/system/bin:/system/sbin:/system/xbin:/system/vendor/bin 
export ANDROID_ASSETS=/assets 
export ANDROID_DATA=/data 
export ANDROID_ROOT=/system 
export ANDROID_STORAGE=/storage 
export ASEC_MOUNTPOINT=/mnt/asec 
export EXTERNAL_STORAGE=/sdcard 
export PROOT_TMP_DIR=$DIR/tmp
$2 --kill-on-exit -v 255 -r $DIR/rootfs -0 -w / -b /dev -b /proc -b /sys -b $DIR/rootfs/dev/kmsg:/dev/kmsg -b $DIR/rootfs/dev/pmsg0:/dev/pmsg0 -b $DIR/rootfs/system/vendor:/vendor -b $DIR/rootfs/dev/__properties__:/dev/__properties__ -b $DIR/rootfs/dev/socket:/dev/socket -b /dev/binder:/dev/binder -b /dev/ashmem:/dev/ashmem -b $DIR/qemu_pipe:/dev/qemu_pipe -b $DIR/rootfs/dev/input:/dev/input -b $DIR/rootfs/mnt/user/0:/storage/self /init > $DIR/proot.log 2>&1 &
