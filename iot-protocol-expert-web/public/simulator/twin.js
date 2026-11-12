import * as THREE from 'three';
import { GLTFLoader } from './vendor/GLTFLoader.js';
import { OrbitControls } from './vendor/OrbitControls.js';

export function mountTwin() {
const container = document.querySelector('#twin-view');
const message = document.querySelector('#twin-message');
const badge = document.querySelector('#twin-status');
let state = { connected: false, guns: [] };
const onState = event => { state = event.detail; };
window.addEventListener('simulator-state', onState);
let disposed = false, animationFrame, observer, renderer, controls;

async function init() {
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x0c1825);
  scene.fog = new THREE.Fog(0x0c1825, 12, 25);
  const camera = new THREE.PerspectiveCamera(38, 1, 0.1, 100);
  camera.position.set(-5.8, 4.2, 6.8);
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.2;
  container.prepend(renderer.domElement);
  controls = new OrbitControls(camera, renderer.domElement);
  controls.target.set(-0.6, 0.7, 0);
  controls.enableDamping = true;
  controls.minDistance = 4; controls.maxDistance = 14;
  controls.maxPolarAngle = Math.PI / 2.05;
  scene.add(new THREE.HemisphereLight(0xd9f6ff, 0x263e3a, 2.2));
  const key = new THREE.DirectionalLight(0xffffff, 3.4); key.position.set(-4, 8, 5); scene.add(key);
  const fill = new THREE.DirectionalLight(0x63dabb, 1.3); fill.position.set(5, 3, -5); scene.add(fill);
  const ground = new THREE.Mesh(new THREE.CircleGeometry(7, 80), new THREE.MeshStandardMaterial({color: 0x1a2a32, roughness: 0.95}));
  ground.rotation.x = -Math.PI / 2; ground.position.y = -0.03; scene.add(ground);
  const grid = new THREE.GridHelper(12, 24, 0x2b6359, 0x203b3d); grid.position.y = -0.01; scene.add(grid);
  const model = (await new GLTFLoader().loadAsync('/simulator/simulator-model.glb')).scene;
  if (disposed) return;
  scene.add(model);
  const car = model.getObjectByName('EV');
  const charger = model.getObjectByName('Charger');
  const gun = model.getObjectByName('Charger_Gun');
  const port = car?.getObjectByName('EV_Port');
  let screen;
  charger?.traverse(object => { if (object.isMesh && /^Charger[ _]screen/.test(object.name)) screen = object; });
  if (!car || !charger || !gun || !port || !screen) throw new Error(`三维模型缺少必要节点: ${[car, charger, gun, port, screen].map(Boolean).join(',')}`);
  car.position.set(0, 0.05, 0);
  charger.position.set(-2.65, 0, 1.15);
  gun.position.set(-2.08, 0.92, 1.15);
  model.updateMatrixWorld(true);
  const home = gun.position.clone();
  const homeRotation = gun.quaternion.clone();
  const screenCanvas = document.createElement('canvas'); screenCanvas.width = 512; screenCanvas.height = 512;
  const screenTexture = new THREE.CanvasTexture(screenCanvas); screenTexture.colorSpace = THREE.SRGBColorSpace;
  screen.material = new THREE.MeshBasicMaterial({ map: screenTexture });
  const materialCopies = [];
  car.traverse(obj => {
    if (!obj.isMesh) return;
    const clone = mat => { const m = mat.clone(); materialCopies.push({m, opacity: m.opacity, transparent: m.transparent, depthWrite: m.depthWrite}); return m; };
    obj.material = Array.isArray(obj.material) ? obj.material.map(clone) : clone(obj.material);
  });
  const battery = new THREE.Group(); scene.add(battery);
  const pack = new THREE.Mesh(new THREE.BoxGeometry(1.45, 0.12, 2.9), new THREE.MeshBasicMaterial({color:0x17493b})); pack.position.y = 0.35; battery.add(pack);
  const cells = [];
  for (let i = 0; i < 10; i++) {
    const cell = new THREE.Mesh(new THREE.BoxGeometry(1.28, 0.04, 0.25), new THREE.MeshBasicMaterial({color:0x69efb9}));
    cell.position.set(0, 0.43, -1.25 + i * .275); battery.add(cell); cells.push(cell);
  }
  const labelCanvas = document.createElement('canvas'); labelCanvas.width = 512; labelCanvas.height = 128;
  const labelTexture = new THREE.CanvasTexture(labelCanvas); labelTexture.colorSpace = THREE.SRGBColorSpace;
  const label = new THREE.Sprite(new THREE.SpriteMaterial({map:labelTexture, transparent:true, depthTest:false}));
  label.position.set(0, 1.9, 0); label.scale.set(2.4, .6, 1); battery.add(label);
  const cableStart = new THREE.Vector3(-2.2, .8, 1.15);
  let cable;
  const cableMaterial = new THREE.MeshStandardMaterial({color:0x0a1012, roughness:.8});
  const dots = Array.from({length: 9}, () => { const dot = new THREE.Mesh(new THREE.SphereGeometry(.045, 10, 8), new THREE.MeshBasicMaterial({color:0x75ffbe})); scene.add(dot); return dot; });
  let amount = 0, lastTime = performance.now(), lastScreen = '', batteryVisible = null;
  const portPosition = new THREE.Vector3();
  const homeAxis = new THREE.Vector3(1,0,0);
  const destinationRotation = new THREE.Quaternion().setFromUnitVectors(homeAxis, new THREE.Vector3(1,0,0));
  function drawScreen(status, power, soc) {
    const signature = `${status}|${power}|${soc}`; if (signature === lastScreen) return; lastScreen = signature;
    const ctx = screenCanvas.getContext('2d');
    ctx.fillStyle = '#0b2428'; ctx.fillRect(0,0,512,512);
    ctx.fillStyle = '#7bd6bb'; ctx.font = '28px sans-serif'; ctx.fillText('DC FAST CHARGER',32,60);
    ctx.fillStyle = '#e8fff5'; ctx.font = 'bold 57px sans-serif'; ctx.fillText(status,32,155);
    ctx.fillStyle = '#a8c9c2'; ctx.font = '26px sans-serif';
    if (status === '充电中') { ctx.fillText('实时功率',32,230); ctx.fillText('车辆 SOC',32,350); ctx.fillStyle = '#73f4bd'; ctx.font='bold 52px sans-serif'; ctx.fillText(`${power} kW`,32,290); ctx.fillText(`${soc}%`,32,410); }
    else { ctx.fillText('设备仿真 · 实时状态',32,230); }
    screenTexture.needsUpdate = true;
  }
  function resize() { const w=container.clientWidth, h=container.clientHeight; if (!w || !h) return; camera.aspect=w/h; camera.updateProjectionMatrix(); renderer.setSize(w,h,false); }
  observer = new ResizeObserver(resize); observer.observe(container); resize();
  message.style.display = 'none';
  function animate(now) {
    if (disposed) return;
    animationFrame = requestAnimationFrame(animate);
    const dt = Math.min((now-lastTime)/1000, .1); lastTime=now;
    const g = state.guns?.find(item => item.gunNo === state.selectedGun);
    const connected = Boolean(state.connected);
    const plugged = Boolean(g?.plugged);
    const charging = connected && g?.stateCode === 3;
    const soc = Math.round(Math.max(0,Math.min(100,Number(g?.soc) || 0)));
    const power = (Number(g?.voltage || 0) * Number(g?.current || 0) / 1000).toFixed(1);
    const status = !connected ? '离线' : charging ? '充电中' : plugged ? '已插枪' : '空闲';
    badge.textContent = `${status}${charging ? ` · ${power} kW · SOC ${soc}%` : ''}`;
    drawScreen(status,power,soc);
    amount = THREE.MathUtils.clamp(amount + (plugged ? 1 : -1) * dt / 2.8, 0, 1);
    port.getWorldPosition(portPosition);
    const end = portPosition.clone().add(new THREE.Vector3(-.12,0,0));
    const plugPath = new THREE.CatmullRomCurve3([home, home.clone().add(new THREE.Vector3(.2,-.25,0)), end.clone().add(new THREE.Vector3(-.4,-.18,0)), end]);
    gun.position.copy(plugPath.getPoint(THREE.MathUtils.smoothstep(amount,0,1)));
    gun.quaternion.copy(homeRotation).slerp(destinationRotation, THREE.MathUtils.smoothstep(amount,0,1));
    const mid = cableStart.clone().lerp(gun.position,.5); mid.y = Math.max(.09,Math.min(cableStart.y,gun.position.y)-.42);
    const cableCurve = new THREE.CatmullRomCurve3([cableStart,mid,gun.position]);
    if (cable) cable.geometry.dispose(); else { cable = new THREE.Mesh(new THREE.BufferGeometry(),cableMaterial); scene.add(cable); }
    cable.geometry = new THREE.TubeGeometry(cableCurve,20,.025,6,false);
    dots.forEach((dot,i) => { dot.visible=Boolean(charging && amount > .95); if(dot.visible) dot.position.copy(cableCurve.getPoint((now*.00045+i/dots.length)%1)); });
    if (batteryVisible !== charging) {
      batteryVisible = charging; battery.visible = Boolean(charging);
      materialCopies.forEach(({m,opacity,transparent,depthWrite}) => {m.transparent=charging || transparent; m.opacity=charging ? Math.min(opacity,.16) : opacity; m.depthWrite=charging ? false : depthWrite; m.needsUpdate=true;});
    }
    if (charging) {
      cells.forEach((cell,i)=>cell.material.color.setHex(i < soc/10 ? 0x63efaf : 0x28584d));
      const ctx=labelCanvas.getContext('2d'); ctx.clearRect(0,0,512,128); ctx.fillStyle='rgba(8,34,29,.92)'; ctx.beginPath(); ctx.roundRect(2,2,508,124,22); ctx.fill(); ctx.fillStyle='#a0ffcf'; ctx.textAlign='center'; ctx.font='bold 54px sans-serif'; ctx.fillText(`电池 SOC  ${soc}%`,256,82); labelTexture.needsUpdate=true;
    }
    controls.update(); renderer.render(scene,camera);
  }
  animationFrame = requestAnimationFrame(animate);
}
init().catch(error => { if (!disposed) { console.error(error); message.textContent = '三维场景加载失败，请检查浏览器 WebGL 支持'; } });
return () => { disposed = true; window.removeEventListener('simulator-state', onState); cancelAnimationFrame(animationFrame); observer?.disconnect(); controls?.dispose(); renderer?.dispose(); renderer?.domElement.remove(); };
}
