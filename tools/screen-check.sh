#!/usr/bin/env bash
# Opens the test APK on the CI emulator in Urdu and in English and saves screenshots (fonts check).
set -x
APK=$(ls app/build/outputs/apk/preview/*.apk | head -1)
PKG=com.kodawick.messwise.test
ACT=com.kodawick.messwise.MainActivity
mkdir -p screens
adb install -r "$APK" || exit 0
open_app() {  # $1 = file name
  adb shell wm dismiss-keyguard
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/$ACT
  sleep 15
  adb exec-out screencap -p > "screens/$1.png"
}
# 1. Urdu chosen inside the app (per-app language, as in the app's Settings).
adb shell cmd locale set-app-locales $PKG --locales ur
adb shell cmd locale get-app-locales $PKG
open_app urdu-app-language
# 2. Whole phone in Urdu.
adb shell cmd locale set-app-locales $PKG --locales ""
adb root; sleep 3
adb shell setprop persist.sys.locale ur-PK
adb shell stop; sleep 2; adb shell start
adb wait-for-device
for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 3; done
sleep 25
open_app urdu-phone-language
# 3. Back to English.
adb shell setprop persist.sys.locale en-US
adb shell stop; sleep 2; adb shell start
adb wait-for-device
for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 3; done
sleep 25
open_app english
ls -la screens
