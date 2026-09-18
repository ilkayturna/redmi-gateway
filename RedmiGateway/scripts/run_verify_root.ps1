$adb = "C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\adb.exe"
$localOut = "c:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\docs\root_first_boot"

Write-Host "Pushing verify_root.sh to device..."
& $adb push "c:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\scripts\verify_root.sh" /data/local/tmp/verify_root.sh
& $adb shell "chmod 755 /data/local/tmp/verify_root.sh"

Write-Host "Executing root verification on device..."
& $adb shell "/data/local/tmp/verify_root.sh"

Write-Host "Pulling verification logs to $localOut..."
& $adb pull /sdcard/Download/root_first_boot/* "$localOut/"
Write-Host "Root verification logs successfully stored."
