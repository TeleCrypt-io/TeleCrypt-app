#!/bin/bash

ANDROID_PLATFORM=${ANDROID_PLATFORM:-35}

echo "create emulators"
avdmanager create avd -n Nexus_5 -k "system-images;android-${ANDROID_PLATFORM};google_apis;x86_64" -d "Nexus 5"
avdmanager create avd -n Nexus_7 -k "system-images;android-${ANDROID_PLATFORM};google_apis;x86_64" -d "Nexus 7"
avdmanager create avd -n Nexus_10 -k "system-images;android-${ANDROID_PLATFORM};google_apis;x86_64" -d "Nexus 10"

echo "start emulators"
mkdir "emulator_logs"
emulator -avd Nexus_5 -port 5554 -no-window -no-audio -no-accel >> emulator_logs/nexus_5.log 2>&1 &
emulator -avd Nexus_7 -port 5556 -no-window -no-audio -no-accel >> emulator_logs/nexus_7.log 2>&1 &
emulator -avd Nexus_10 -port 5558 -no-window -no-audio -no-accel -prop persist.sys.orientation=landscape >> emulator_logs/nexus_10.log 2>&1 &

wait_for_emulator() {
    local adb_port=$1
    local sec=0
    local timeout=300

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
wait_for_emulator 5554
wait_for_emulator 5556
wait_for_emulator 5558

echo "All emulators are ready."