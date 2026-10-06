# KamNG native engines

AmneziaWG source: amnezia-vpn/amneziawg-go
Pinned revision: b5928efb6ca19f0153958460c3d141f04abc5c2e

CottenDNS source: WhiteDNS/CottenDNS
Pinned revision: f4a22770dcbfb72f5332be0bdf28cb9c3e26a9a9

The AmneziaWG adapter uses the upstream userspace netstack. It exposes localhost SOCKS5
TCP CONNECT and UDP ASSOCIATE, retaining the Android VPN/Xray routing pipeline.
CottenDNS is packaged using its upstream Android client builder.

Create a SOCKS profile and choose the connection engine. Paste the complete AmneziaWG
configuration or CottenDNS TOML. CottenDNS additionally needs its resolver list.
The domain, encryption key and resolver endpoints must correspond to your server.
Desktop wg-quick shell hooks are rejected. Local listener ports must be unique.

Engine configuration is stored with the profile. Native processes receive private
temporary files, not secret command-line values. Raw engine output is discarded.
Native profile configuration is included in exports as kamngNativeCores, removed
from the JSON before Xray runs, and restored when imported.

The build copies both upstream licenses into the APK. Source dependencies remain
pinned; native/sources is build-only and must not be committed.
