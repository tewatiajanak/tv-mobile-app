# Device compatibility

TVs differ: storage pickers, USB filesystems, decoders and background limits vary by model. This
file records what was actually observed, instead of assuming every TV behaves the same. Phase 6
starts filling it in.

| Model | Android version | USB | HDD | SAF picker present | Notes |
|---|---|---|---|---|---|
| Xiaomi MiTV-AXSO2 (`dangalUHD`) | 9 (API 28) | not tested | not tested | not tested | Phase 1: app installs over network adb and reaches a LAN backend over http. The first cold start after install took over a minute before the activity appeared; later starts were under a second. |
