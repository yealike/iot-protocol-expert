import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import {connectorPose} from '../public/simulator/scene-geometry.js';

const home=new THREE.Vector3(-2.206,.84,-1.02);
const destination=new THREE.Vector3(-.677,1.03,-1.98);
test('lid is fully open before connector leaves its holster; it closes after withdrawal',()=>{
  for(let i=0;i<=100;i++) {
    const p=connectorPose(i/100,home,destination);
    if(p.position.distanceTo(home)>.001)assert.equal(p.lid,1);
  }
  assert(connectorPose(0,home,destination).position.equals(home));
  assert.equal(connectorPose(0,home,destination).lid,0);
  assert(connectorPose(1,home,destination).position.equals(destination));
});
