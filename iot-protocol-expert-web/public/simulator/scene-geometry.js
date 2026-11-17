import * as THREE from 'three';

export const CABLE_RADIUS = .026;

export function connectorPose(amount, home, destination) {
  const approach = destination.clone().add(new THREE.Vector3(-.30, 0, 0));
  const released = home.clone().add(new THREE.Vector3(0, .06, .42));
  let position, rotation;
  if (amount < .30) {
    const p = THREE.MathUtils.smoothstep(amount, .14, .30);
    position = home.clone().lerp(released, p); rotation = 0;
  } else if (amount < .82) {
    const p = THREE.MathUtils.smoothstep(amount, .30, .82);
    const path = new THREE.CatmullRomCurve3([released,
      new THREE.Vector3(released.x + .65, 1.15, released.z + .04),
      approach.clone().add(new THREE.Vector3(-.18, .06, .08)), approach]);
    position = path.getPoint(p); rotation = p;
  } else {
    position = approach.clone().lerp(destination, THREE.MathUtils.smoothstep(amount, .82, 1));
    rotation = 1;
  }
  return { position, rotation, lid: THREE.MathUtils.smoothstep(amount, 0, .14) };
}
