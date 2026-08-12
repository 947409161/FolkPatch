#!/system/bin/sh

ARCH=$(getprop ro.product.cpu.abi)

IS_INSTALL_NEXT_SLOT=$1
IS_LKM=$2

# Load utility functions
. ./util_functions.sh

if [ "$IS_INSTALL_NEXT_SLOT" = "true" ]; then
  get_next_slot
else
  get_current_slot
fi

if [ "$IS_LKM" = "true" ]; then
  find_lkm_boot_image
else
  find_boot_image
fi

[ -e "$BOOTIMAGE" ] || { >&2 echo "- can't find boot.img!"; exit 1; }

true
