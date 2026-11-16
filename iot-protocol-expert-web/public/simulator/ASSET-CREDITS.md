# 3D model attribution

Tesla Model Y geometry is adapted from **2021 Tesla Model Y** by
**tonielpro520**, licensed under **Creative Commons Attribution 4.0**.

- Original: https://sketchfab.com/3d-models/2021-tesla-model-y-59e2ead369984b1a85c800ff6cf6789d
- Author: https://sketchfab.com/tonielpro520
- License: https://creativecommons.org/licenses/by/4.0/
- Public GLB mirror: https://shop.americawant.com/wp-content/uploads/2026/08/tesla-model-y.glb

Changes: decompression and re-export, dimension/axis normalization, PBR material
adjustments, added animated charge-port/socket assembly. Original vehicle
bodywork, wheels, glass, lighting and interior meshes are retained.

The StarCharge Gemini 120 kW V3.2 cabinet and charging connectors are project
authored geometry based on the manufacturer's product photograph/datasheet:
https://cn.starcharge.com/products/ev-charging/all-in-one-charger/207

Tesla / Model Y and StarCharge / 星星充电 identify the depicted products.

Cable dynamics use Ammo.js (Bullet Physics), zlib licensed, distributed with
Three.js r180. Runtime files are vendored unchanged under `vendor/ammo/` with
their license. Integration is based on the Three.js official soft-body rope
example: https://github.com/mrdoob/three.js/blob/r180/examples/physics_ammo_rope.html
Ammo.js source: https://github.com/kripken/ammo.js
