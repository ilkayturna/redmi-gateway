# Fastboot Baseline Verification

- **Timestamp:** 2026-09-18 02:38:12
- **Fastboot Devices:**
```
17df6357	 fastboot
```

## Hardware & Lock State
- **Product:**
```
fastboot.exe : product: ginkgo
At C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\watch_fastboot.ps1:9 char:20
+         $product = & $fastboot getvar product 2>&1 | Out-String
+                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    + CategoryInfo          : NotSpecified: (product: ginkgo:String) [], RemoteException
    + FullyQualifiedErrorId : NativeCommandError
 
Finished. Total time: 0.002s
```
- **Unlocked:**
```
fastboot.exe : unlocked: no
At C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\watch_fastboot.ps1:10 char:21
+         $unlocked = & $fastboot getvar unlocked 2>&1 | Out-String
+                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    + CategoryInfo          : NotSpecified: (unlocked: no:String) [], RemoteException
    + FullyQualifiedErrorId : NativeCommandError
 
Finished. Total time: 0.002s
```
- **Token Nonce:**
```
fastboot.exe : token: VQEBIAEQCxT-sgAAAAAAAAAAAAAAAAMGZ2lua2dvAgTuOlLK
At C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\watch_fastboot.ps1:11 char:18
+         $token = & $fastboot getvar token 2>&1 | Out-String
+                  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    + CategoryInfo          : NotSpecified: (token: VQEBIAEQ...2lua2dvAgTuOlLK:String) [], RemoteException
    + FullyQualifiedErrorId : NativeCommandError
 
Finished. Total time: 0.002s
```
