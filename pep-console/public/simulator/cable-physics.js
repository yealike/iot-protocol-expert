import * as THREE from 'three';
import { CABLE_RADIUS } from './scene-geometry.js';
import {resolveCableContacts} from './cable-contact.js';

// Bullet/Ammo soft-body setup follows the Three.js r180 official rope example.
// The actual engine is vendored unchanged; this module supplies charger-specific
// anchors, cable length, friction, collision bodies and the render skin.
export const CABLE_LENGTH = 5; // StarCharge Gemini V3.2 standard cable, metres.
const STEP = 1 / 120;
let ammoPromise;

export function loadCablePhysics() {
  if (!ammoPromise) ammoPromise = new Promise((resolve, reject) => {
    const initialize = () => window.Ammo({
      locateFile: file => `/simulator/vendor/ammo/${file}`,
    }).then(resolve, reject);
    if (typeof window.Ammo === 'function') { initialize(); return; }
    const script = document.createElement('script');
    script.src = '/simulator/vendor/ammo/ammo.wasm.js';
    script.onload = initialize;
    script.onerror = () => { script.remove(); ammoPromise = undefined; reject(new Error('枪线物理引擎加载失败')); };
    document.head.append(script);
  });
  return ammoPromise;
}

// Start with the correct amount of slack on the floor. This is only the initial
// condition: after reset the soft-body solver, not this path, moves the cable.
function initialCable(anchor, end, cabinet, side, length, count) {
  const floor = CABLE_RADIUS + .002;
  const outside = side > 0 ? cabinet.max.x + .23 : cabinet.min.x - .23;
  const front = cabinet.max.z + .34;
  const safe = cabinet.clone().expandByScalar(CABLE_RADIUS + .006);
  const make = depth => {
    const spline = new THREE.CatmullRomCurve3([anchor,
      anchor.clone().add(new THREE.Vector3(side * .09, -.025, 0)),
      new THREE.Vector3(outside, floor, front),
      new THREE.Vector3(outside + side * .28, floor, front + depth * .72),
      new THREE.Vector3((outside + end.x) / 2 + side * .15, floor, front + depth),
      new THREE.Vector3(end.x + side * .20, floor, front + depth * .72),
      new THREE.Vector3(end.x, floor, Math.max(end.z, cabinet.max.z + .14)),
      end.clone().add(new THREE.Vector3(0, -.12, 0)), end],false,'centripetal');
    return spline.getPoints(240).map(p => {
      p.y = Math.max(floor,p.y);
      if(safe.containsPoint(p)) {
        if(p.z>cabinet.max.z)p.z=safe.max.z+.001;
        else p.x=side>0?safe.max.x+.001:safe.min.x-.001;
      }
      return p;
    });
  };
  const total = points => points.slice(1).reduce((sum, p, i) => sum + p.distanceTo(points[i]), 0);
  let low = 0, high = 3;
  for (let i = 0; i < 24; i++) {
    const mid = (low + high) / 2;
    if (total(make(mid)) < length) low = mid; else high = mid;
  }
  const path = make((low + high) / 2), distances = [0];
  for (let i = 1; i < path.length; i++) distances.push(distances[i - 1] + path[i].distanceTo(path[i - 1]));
  return Array.from({length: count}, (_, i) => {
    const distance = distances.at(-1) * i / (count - 1);
    let segment = 1;
    while (segment < distances.length - 1 && distances[segment] < distance) segment++;
    const t = (distance - distances[segment - 1]) / Math.max(1e-8, distances[segment] - distances[segment - 1]);
    return path[segment - 1].clone().lerp(path[segment], t);
  });
}

export class CablePhysicsWorld {
  constructor(Ammo, cabinet, otherBoxes = []) {
    this.Ammo = Ammo;
    this.cabinet = cabinet.clone();
    this.ropes = []; this.rigid = []; this.accumulator = 0;
    this.configuration = new Ammo.btSoftBodyRigidBodyCollisionConfiguration();
    this.dispatcher = new Ammo.btCollisionDispatcher(this.configuration);
    this.broadphase = new Ammo.btDbvtBroadphase();
    this.solver = new Ammo.btSequentialImpulseConstraintSolver();
    this.softSolver = new Ammo.btDefaultSoftBodySolver();
    this.world = new Ammo.btSoftRigidDynamicsWorld(this.dispatcher, this.broadphase, this.solver, this.configuration, this.softSolver);
    const gravity = new Ammo.btVector3(0, -9.81, 0);
    this.world.setGravity(gravity); this.world.getWorldInfo().set_m_gravity(gravity); Ammo.destroy(gravity);
    this.helpers = new Ammo.btSoftBodyHelpers();
    this.addBox(new THREE.Box3(new THREE.Vector3(-12, -1, -12), new THREE.Vector3(12, 0, 12)));
    this.addBox(cabinet);
    otherBoxes.forEach(box => this.addBox(box));
  }

  addBox(box) {
    const A = this.Ammo, size = box.getSize(new THREE.Vector3()).multiplyScalar(.5), center = box.getCenter(new THREE.Vector3());
    const extent = new A.btVector3(size.x, size.y, size.z), shape = new A.btBoxShape(extent);
    A.destroy(extent); shape.setMargin(.004);
    const transform = new A.btTransform(); transform.setIdentity();
    const origin = new A.btVector3(center.x, center.y, center.z); transform.setOrigin(origin); A.destroy(origin);
    const motion = new A.btDefaultMotionState(transform), inertia = new A.btVector3(0, 0, 0);
    const info = new A.btRigidBodyConstructionInfo(0, motion, shape, inertia), body = new A.btRigidBody(info);
    body.setFriction(.9); body.setRestitution(0); this.world.addRigidBody(body);
    this.rigid.push({body, motion, shape}); A.destroy(info); A.destroy(inertia); A.destroy(transform);
  }

  createCable(anchor, end, side = 1) {
    const cable = new PhysicalCable(this, anchor, end, side);
    this.ropes.push(cable); return cable;
  }

  step(dt) {
    this.accumulator += Math.min(Math.max(dt, 0), .1);
    const steps = Math.min(12, Math.floor((this.accumulator + 1e-9) / STEP));
    if (!steps) {
      this.ropes.forEach(rope=>{rope.pin(1); rope.read();});
      return false;
    }
    this.accumulator -= steps * STEP;
    for (let i = 1; i <= steps; i++) {
      this.ropes.forEach(rope => rope.pin(i / steps));
      this.world.stepSimulation(STEP, 0);
      this.ropes.forEach(rope=>rope.read()); resolveCableContacts(this.ropes);
    }
    this.ropes.forEach(rope => {rope.previousEnd.copy(rope.end); rope.read();});
    return true;
  }

  settle(seconds = 3) {
    for (let i = 0; i < Math.ceil(seconds / STEP); i++) {
      this.ropes.forEach(rope => rope.pin(1));
      this.world.stepSimulation(STEP, 0);
      this.ropes.forEach(rope=>rope.read()); resolveCableContacts(this.ropes);
    }
    this.ropes.forEach(rope => {rope.previousEnd.copy(rope.end); rope.read();});
  }

  dispose() {
    const A = this.Ammo;
    for (const rope of this.ropes) {this.world.removeSoftBody(rope.body); A.destroy(rope.body);}
    for (const {body, motion, shape} of this.rigid) {this.world.removeRigidBody(body); A.destroy(body); A.destroy(motion); A.destroy(shape);}
    [this.helpers, this.world, this.softSolver, this.solver, this.broadphase, this.dispatcher, this.configuration].forEach(o => A.destroy(o));
    this.ropes.length = 0; this.rigid.length = 0;
  }
}

class PhysicalCable {
  constructor(physics, anchor, end, side) {
    const A = physics.Ammo;
    this.physics = physics; this.anchor = anchor.clone(); this.end = end.clone(); this.previousEnd = end.clone();
    this.side = side; this.length = CABLE_LENGTH; this.segments = 64; this.segment = this.length / this.segments;
    const start = new A.btVector3(anchor.x, anchor.y, anchor.z), finish = new A.btVector3(anchor.x, anchor.y - this.length, anchor.z);
    this.body = physics.helpers.CreateRope(physics.world.getWorldInfo(), start, finish, this.segments - 1, 0);
    A.destroy(start); A.destroy(finish);
    const config = this.body.get_m_cfg();
    config.set_viterations(12); config.set_piterations(32); config.set_diterations(8);
    config.set_kDP(.025); config.set_kDF(.85); config.set_collisions(0x11);
    this.body.get_m_materials().at(0).set_m_kLST(.99);
    const bending = this.body.appendMaterial(); bending.set_m_kLST(.18);
    this.body.generateBendingConstraints(2, bending);
    this.body.setTotalMass(8, false);
    A.castObject(this.body, A.btCollisionObject).getCollisionShape().setMargin(CABLE_RADIUS);
    this.body.setActivationState(4);
    const array = this.body.get_m_nodes(); this.count = array.size();
    this.nodes = Array.from({length: this.count}, (_, i) => {
      const node = array.at(i);
      return {position: node.get_m_x(), previous: node.get_m_q(), velocity: node.get_m_v()};
    });
    this.pins = [0, 1, this.count - 2, this.count - 1];
    this.pins.forEach(i => this.body.setMass(i, 0));
    this.points = Array.from({length: this.count}, () => new THREE.Vector3());
    this.contactBefore = Array.from({length: this.count}, () => new THREE.Vector3());
    const spline = new THREE.CatmullRomCurve3(this.points, false, 'centripetal');
    const safe = physics.cabinet.clone().expandByScalar(CABLE_RADIUS + .003);
    class CableCurve extends THREE.Curve {
      getPoint(t, target = new THREE.Vector3()) {
        spline.getPoint(t, target);
        // The finite-radius render skin follows the solved nodes. Guard the
        // small interpolation overshoot at floor contacts/cabinet corners.
        target.y = Math.max(CABLE_RADIUS, target.y);
        if (safe.containsPoint(target)) {
          if (target.z > physics.cabinet.max.z) target.z = safe.max.z + .001;
          else target.x = side > 0 ? safe.max.x + .001 : safe.min.x - .001;
        }
        return target;
      }
    }
    this.curve = new CableCurve();
    this.reset(end);
    physics.world.addSoftBody(this.body, 1, -1);
  }

  reset(end) {
    this.end.copy(end); this.previousEnd.copy(end);
    const points = initialCable(this.anchor, end, this.physics.cabinet, this.side, this.length, this.count);
    this.nodes.forEach((node, i) => {
      const p = points[i]; node.position.setValue(p.x, p.y, p.z); node.previous.setValue(p.x, p.y, p.z); node.velocity.setValue(0, 0, 0);
    });
    this.pin(1); this.read();
  }

  setEnd(end) { this.end.copy(end); }

  pin(fraction) {
    const end = this.previousEnd.clone().lerp(this.end, fraction);
    const targets = [this.anchor,
      this.anchor.clone().add(new THREE.Vector3(this.side * this.segment, 0, 0)),
      end.clone().add(new THREE.Vector3(0, -this.segment, 0)), end];
    this.pins.forEach((index, i) => {
      const node = this.nodes[index], p = targets[i];
      node.position.setValue(p.x, p.y, p.z); node.previous.setValue(p.x, p.y, p.z); node.velocity.setValue(0, 0, 0);
    });
  }

  read() {
    this.nodes.forEach((node, i) => this.points[i].set(node.position.x(), node.position.y(), node.position.z()));
    this.curve.needsUpdate = true;
  }
}

// A persistent tube buffer avoids reallocating TubeGeometry every moving frame.
export function createCableSkin(curve, material) {
  const segments = 128, radial = 8;
  const geometry = new THREE.TubeGeometry(curve, segments, CABLE_RADIUS, radial, false);
  geometry.attributes.position.setUsage(THREE.DynamicDrawUsage); geometry.attributes.normal.setUsage(THREE.DynamicDrawUsage);
  const mesh = new THREE.Mesh(geometry, material); mesh.castShadow = true;
  const centers = Array.from({length: segments + 1}, () => new THREE.Vector3());
  const tangent = new THREE.Vector3(), normal = new THREE.Vector3(), binormal = new THREE.Vector3();
  return {mesh, update() {
    for (let i = 0; i <= segments; i++) curve.getPoint(i / segments, centers[i]);
    normal.set(0, 0, 1);
    const positions = geometry.attributes.position, normals = geometry.attributes.normal;
    for (let i = 0; i <= segments; i++) {
      tangent.subVectors(centers[Math.min(i + 1, segments)], centers[Math.max(0, i - 1)]).normalize();
      normal.addScaledVector(tangent, -normal.dot(tangent));
      if (normal.lengthSq() < 1e-8) normal.set(1, 0, 0).addScaledVector(tangent, -tangent.x);
      normal.normalize(); binormal.crossVectors(tangent, normal).normalize();
      for (let j = 0; j <= radial; j++) {
        const angle = j / radial * Math.PI * 2, c = -Math.cos(angle), s = Math.sin(angle);
        const x = normal.x * c + binormal.x * s, y = normal.y * c + binormal.y * s, z = normal.z * c + binormal.z * s;
        const index = i * (radial + 1) + j;
        positions.setXYZ(index, centers[i].x + x * CABLE_RADIUS, Math.max(0, centers[i].y + y * CABLE_RADIUS), centers[i].z + z * CABLE_RADIUS);
        normals.setXYZ(index, x, y, z);
      }
    }
    positions.needsUpdate = true; normals.needsUpdate = true; geometry.computeBoundingSphere();
  }};
}
