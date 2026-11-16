import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import * as THREE from 'three';
import {CablePhysicsWorld, CABLE_LENGTH, createCableSkin} from '../public/simulator/cable-physics.js';
import {connectorPose, CABLE_RADIUS} from '../public/simulator/scene-geometry.js';

const vendor=fileURLToPath(new URL('../public/simulator/vendor/ammo/',import.meta.url));
const context={module:{exports:{}},exports:{},require:createRequire(import.meta.url),__dirname:vendor,
  __filename:path.join(vendor,'ammo.wasm.js'),process,console,WebAssembly,TextDecoder,TextEncoder,setTimeout,clearTimeout};
vm.runInNewContext(fs.readFileSync(path.join(vendor,'ammo.wasm.js'),'utf8'),context);
const Ammo=await context.module.exports({wasmBinary:fs.readFileSync(path.join(vendor,'ammo.wasm.wasm'))});
const cabinet=new THREE.Box3(new THREE.Vector3(-2.725,0,-1.675),new THREE.Vector3(-1.875,1.55,-1.055));
const home=new THREE.Vector3(-2.206,.84,-1.02), destination=new THREE.Vector3(-.677,1.03,-1.98);
const parked=new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0,1,0),Math.PI/2);
const obstacles=[new THREE.Box3(new THREE.Vector3(-.66,.20,-2.35),new THREE.Vector3(1.26,1.55,2.35)),
  ...[-.43,1.03].map(x=>new THREE.Box3(new THREE.Vector3(x-.29,0,-2.59),new THREE.Vector3(x+.29,.074,-2.45)))];
const cableEnd=pose=>new THREE.Vector3(-.055,-.22,0).applyQuaternion(parked.clone().slerp(new THREE.Quaternion(),pose.rotation)).add(pose.position);
const length=rope=>rope.points.slice(1).reduce((sum,p,i)=>sum+p.distanceTo(rope.points[i]),0);

test('Ammo soft-body: fixed 5m cable settles on the floor and keeps both attachments',()=>{
  const physics=new CablePhysicsWorld(Ammo,cabinet,obstacles);
  try {
    const end=cableEnd(connectorPose(0,home,destination));
    const rope=physics.createCable(new THREE.Vector3(-1.83,1.4,-1.51),end);
    physics.settle(4);
    const contact=rope.points.filter(p=>p.y<.065).length;
    console.log(JSON.stringify({contact,length:length(rope),minY:Math.min(...rope.points.map(p=>p.y))}));
    assert(contact>12,'idle slack must rest on the floor, not hang in a floating arc');
    assert(Math.abs(length(rope)-CABLE_LENGTH)<.08,'cable must retain physical length');
    assert(rope.points[0].distanceTo(rope.anchor)<1e-5);
    assert(rope.points.at(-1).distanceTo(end)<1e-5);
  } finally {physics.dispose();}
});

test('moving the gun drags a continuous cable; return settles again without cabinet/floor penetration',()=>{
  const physics=new CablePhysicsWorld(Ammo,cabinet,obstacles);
  try {
    const rope=physics.createCable(new THREE.Vector3(-1.83,1.4,-1.51),cableEnd(connectorPose(0,home,destination)));
    const spare=physics.createCable(new THREE.Vector3(-2.77,1.4,-1.51),new THREE.Vector3(-2.394,.62,-.965),-1);
    physics.settle(3);
    const skin=createCableSkin(rope.curve,new THREE.MeshBasicMaterial());
    const buffer=skin.mesh.geometry.attributes.position.array;
    let maxLength=0,maxMotion=0,motionWhileGunStill=0;
    const cabinetInterior=cabinet.clone().expandByScalar(CABLE_RADIUS-.008);
    for(let frame=0;frame<420;frame++) {
      const amount=frame<192?frame/192:frame<228?1:Math.max(0,1-(frame-228)/192);
      const end=cableEnd(connectorPose(amount,home,destination));
      const old=rope.points.map(p=>p.clone());
      rope.setEnd(end); physics.step(1/60); skin.update();
      assert.equal(skin.mesh.geometry.attributes.position.array,buffer,'rendering must reuse the vertex buffer');
      assert(rope.points.at(-1).distanceTo(end)<1e-5,'gun attachment detaches');
      maxLength=Math.max(maxLength,length(rope));
      maxMotion=Math.max(maxMotion,...rope.points.map((p,i)=>p.distanceTo(old[i])));
      if(frame>194 && frame<220)motionWhileGunStill=Math.max(motionWhileGunStill,...rope.points.slice(2,-2).map((p,i)=>p.distanceTo(old[i+2])));
      for(const cable of [rope,spare])for(const p of cable.points) {
        assert(p.y>CABLE_RADIUS-.007,'physical nodes penetrate the ground');
        assert(!cabinetInterior.containsPoint(p),'physical nodes penetrate cabinet before rendering');
      }
      for(const cable of [rope,spare]) for(let i=0;i<=128;i++) {
        const p=cable.curve.getPoint(i/128);
        assert(Number.isFinite(p.x)&&Number.isFinite(p.y)&&Number.isFinite(p.z));
        assert(p.y>=CABLE_RADIUS-1e-6,'cable skin must remain above floor');
        assert(!cabinet.clone().expandByScalar(CABLE_RADIUS).containsPoint(p),'cable penetrates cabinet');
      }
    }
    physics.settle(4);
    console.log(JSON.stringify({maxLength,maxMotion,motionWhileGunStill,returnContact:rope.points.filter(p=>p.y<.065).length}));
    assert(maxLength<CABLE_LENGTH*1.06,'gun motion cannot stretch cable like elastic');
    assert(maxMotion<.18,'cable must drag continuously instead of snapping to a new curve');
    assert(motionWhileGunStill>.0001,'cable inertia must continue after the gun stops moving');
    assert(rope.points.filter(p=>p.y<.065).length>12,'returned slack must fall back to floor');
    // Independently measure separation with Three.js point/line queries.
    // Recovered loops may overlap in plan view, but their solid tubes must
    // stack vertically rather than occupy the same volume.
    const cables=[rope,spare],probe=new THREE.Vector3(),closest=new THREE.Vector3(),line=new THREE.Line3();
    for(let a=0;a<cables.length;a++)for(let b=a;b<cables.length;b++)
      for(let i=1;i<cables[a].count-2;i++)for(let j=a===b?i+3:1;j<cables[b].count-2;j++) {
        line.set(cables[b].points[j],cables[b].points[j+1]);
        for(let sample=0;sample<=4;sample++) {
          probe.copy(cables[a].points[i]).lerp(cables[a].points[i+1],sample/4);
          line.closestPointToPoint(probe,true,closest);
          assert(probe.distanceTo(closest)>CABLE_RADIUS*2-.01,'settled cable segments interpenetrate each other');
        }
      }
    const position=skin.mesh.geometry.attributes.position, normal=skin.mesh.geometry.attributes.normal, index=skin.mesh.geometry.index;
    const a=new THREE.Vector3(), b=new THREE.Vector3(), c=new THREE.Vector3(), face=new THREE.Vector3(), n=new THREE.Vector3();
    let inward=0,faces=0;
    for(let i=0;i<index.count;i+=3) {
      a.fromBufferAttribute(position,index.getX(i));b.fromBufferAttribute(position,index.getX(i+1));c.fromBufferAttribute(position,index.getX(i+2));
      face.subVectors(b,a).cross(c.sub(a)); n.fromBufferAttribute(normal,index.getX(i));
      if(face.lengthSq()<1e-12)continue;
      faces++; if(face.dot(n)<0)inward++;
    }
    assert(inward/faces<.02,'dynamic cable mesh has inverted winding');
    skin.mesh.geometry.dispose(); skin.mesh.material.dispose();
  } finally {physics.dispose();}
});
