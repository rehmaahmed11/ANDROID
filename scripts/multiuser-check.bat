@echo off
rem Personas device probe - read-only queries over adb, output is meant to be pasted into
rem the app under Diagnostics > section 2.
echo # Personas device probe (windows)
adb shell "echo @model; getprop ro.product.model; echo @release; getprop ro.build.version.release; echo @sdk; getprop ro.build.version.sdk; echo @buildtype; getprop ro.build.type; echo @maxusers; pm get-max-users; echo @maxusersprop; getprop fw.max_users; echo @multiui; getprop fw.show_multiuserui; echo @managed; pm list features | grep -c managed_users; echo @userlines; pm list users | grep -c UserInfo{; echo @users; pm list users"
if errorlevel 1 echo adb failed - is USB debugging enabled and this computer authorised?
