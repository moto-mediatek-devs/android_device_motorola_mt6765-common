#!/vendor/bin/sh
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

echo 1 > /proc/hw/reload

dualsim=$(cat /proc/hw/dualsim/ascii 2> /dev/null)
[ -z "$dualsim" ] && dualsim=$(getprop ro.boot.dualsim)
[ "$dualsim" = "true" ] || dualsim=false
setprop ro.vendor.hw.dualsim "$dualsim"
