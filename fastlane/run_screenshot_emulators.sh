#!/bin/bash

ANDROID_PLATFORM=${ANDROID_PLATFORM:-35}

echo "create emulators"
avdmanager create avd -n Screenshot_Phone -k "system-images;android-${ANDROID_PLATFORM};google_apis;x86_64" -d "pixel_6"
avdmanager create avd -n Screenshot_Tablet_7 -k "system-images;android-${ANDROID_PLATFORM};google_apis;x86_64" -d "Nexus 7"
avdmanager create avd -n Screenshot_Tablet_10 -k "system-images;android-${ANDROID_PLATFORM};google_apis;x86_64" -d "Nexus 10"

echo "start emulators"
mkdir "emulator_logs"
emulator -avd Screenshot_Phone -port 5564 -no-window -no-audio -no-boot-anim &
emulator -avd Screenshot_Tablet_7 -port 5566 -no-window -no-audio -no-boot-anim &
emulator -avd Screenshot_Tablet_10 -port 5568 -no-window -no-audio -no-boot-anim -prop persist.sys.orientation=landscape &

explain() {
	if [[ "$1" =~ "not found" ]]; then
		printf "device not found"
	elif [[ "$1" =~ "offline" ]]; then
		printf "device offline"
	elif [[ "$1" =~ "running" ]]; then
		printf "booting"
	else
		printf "$1"
	fi
}

wait_for_emulator() {
    local adb_port=$1
    local sec=0
    local timeout=120

    adb -s "emulator-${adb_port}" wait-for-device
    adb -s "emulator-${adb_port}" devices

    while true; do
        if [[ $sec -ge $timeout ]]; then
            echo "Timeout (${timeout} seconds) reached - Failed to start emulator on port ${adb_port}"
            exit 1
        fi
        out=$(adb -s "emulator-${adb_port}" shell getprop init.svc.bootanim 2>&1 | grep -v '^\*')
        if [[ "$out" =~ "command not found" ]]; then
            echo "$out"
            exit 1
        fi
        if [ "$(adb -s "emulator-${adb_port}" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; then
            break
        fi
        let "r = sec % 5"
        if [[ $r -eq 0 ]]; then
            echo "Waiting for emulator on port ${adb_port} to start: $(explain "$out")"
        fi
        sleep 1
        let "sec++"
    done
    echo "Emulator on port ${adb_port} is ready (took ${sec} seconds)"
}

echo "wait for emulators to fully start"
wait_for_emulator 5564
wait_for_emulator 5566
wait_for_emulator 5568

#echo "unlock emulator screens"
#adb -s "emulator-5554" shell input keyevent 82
#adb -s "emulator-5556" shell input keyevent 82
#adb -s "emulator-5558" shell input keyevent 82

echo "All emulators are ready."
wait