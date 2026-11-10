import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { RoundedBoxGeometry } from './vendor/three/RoundedBoxGeometry.js';
import { RoomEnvironment } from './vendor/three/RoomEnvironment.js';
import { createModelY, MODEL_Y_SPECS } from './model-y.js';

// Visual state is driven exclusively by simulator-state. Animation time never changes charging or billing.
const host = document.getElementById('scene');
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
const ui = Object.fromEntries(['sceneMotion','sceneBattery','sceneBatteryValue','sceneBatteryEnergy','scenePowerValue','sceneEnergyFlow','sceneFlowStatus','sceneGridLabel','sceneChargerLabel','sceneBatteryLabel','scenePackSoc'].map(id => [id, document.getElementById(id)]));
let renderer;
try {
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
} catch (error) {
    document.getElementById('sceneFallback').hidden = false;
    document.getElementById('sceneFallback').textContent = '此设备无法启动 WebGL。充电控制与分时账单仍可正常使用。';
    throw error;
}
renderer.setPixelRatio(Math.min(devicePixelRatio, 1.8));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.shadowMap.autoUpdate = false;
renderer.shadowMap.needsUpdate = true;
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.0;
renderer.domElement.setAttribute('aria-label', '电网、直流充电桩与电动汽车底盘电池的三维充电场景');
host.prepend(renderer.domElement);

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x0c1725);
scene.fog = new THREE.Fog(0x0c1725, 22, 54);
const camera = new THREE.PerspectiveCamera(35, 1, 0.1, 100);
camera.position.set(10.5, 8.5, 12.8);
const controls = new OrbitControls(camera, renderer.domElement);
controls.target.set(-1.0, 0.6, -0.15);
controls.enableDamping = true;
controls.enablePan = false;
controls.minDistance = 9;
controls.maxDistance = 24;
controls.minPolarAngle = 0.28;
controls.maxPolarAngle = Math.PI / 2.08;
const pmrem = new THREE.PMREMGenerator(renderer);
const room = new RoomEnvironment();
const environment = pmrem.fromScene(room, 0.04);
scene.environment = environment.texture;
room.dispose();
pmrem.dispose();
scene.add(new THREE.HemisphereLight(0xc8e9ff, 0x172337, 0.75));
function areaLight(color, intensity, position) {
    const light = new THREE.DirectionalLight(color, intensity);
    light.position.set(...position);
    scene.add(light);
    return light;
}
const keyLight = areaLight(0xf1f7ff, 2.7, [1, 10, 5]);
keyLight.castShadow = true;
keyLight.shadow.mapSize.set(2048, 2048);
Object.assign(keyLight.shadow.camera, { left: -10, right: 10, top: 9, bottom: -9 });
keyLight.shadow.normalBias = 0.025;
areaLight(0x72b7ff, 1.4, [-4, 4, -5]);
areaLight(0x80efdc, 1.0, [5, 3, 1]);

const V = (x, y, z) => new THREE.Vector3(x, y, z);
const clamp = THREE.MathUtils.clamp;
const lerp = THREE.MathUtils.lerp;
const smooth = t => t * t * (3 - 2 * t);
const materials = {
    pearl: new THREE.MeshPhysicalMaterial({ color: 0x9eb8c9, metalness: 0.55, roughness: 0.28, clearcoat: 1, clearcoatRoughness: 0.17 }),
    white: new THREE.MeshPhysicalMaterial({ color: 0xd9e3ec, metalness: 0.42, roughness: 0.28, clearcoat: 0.8 }),
    glass: new THREE.MeshPhysicalMaterial({ color: 0x122637, metalness: 0.4, roughness: 0.12, clearcoat: 1 }),
    graphite: new THREE.MeshStandardMaterial({ color: 0x14222e, metalness: 0.65, roughness: 0.42 }),
    rubber: new THREE.MeshStandardMaterial({ color: 0x11171c, roughness: 0.87 }),
    silver: new THREE.MeshStandardMaterial({ color: 0xd2dee8, metalness: 0.92, roughness: 0.23 }),
    mint: new THREE.MeshStandardMaterial({ color: 0x2bc7a8, metalness: 0.45, roughness: 0.25 }),
    blue: new THREE.MeshStandardMaterial({ color: 0x368ce5, emissive: 0x287fd1, emissiveIntensity: 0.8, metalness: 0.3, roughness: 0.28 }),
    light: new THREE.MeshStandardMaterial({ color: 0xd6f5ff, emissive: 0xc4edff, emissiveIntensity: 2 }),
    red: new THREE.MeshStandardMaterial({ color: 0xd4384f, emissive: 0xe32c43, emissiveIntensity: 1.2 })
};
function mesh(geometry, material, parent = scene, position = [0, 0, 0]) {
    const object = new THREE.Mesh(geometry, material);
    object.position.set(...position);
    object.castShadow = true;
    object.receiveShadow = true;
    parent.add(object);
    return object;
}
function rounded(w, h, d, radius, material, position, parent = scene) {
    return mesh(new RoundedBoxGeometry(w, h, d, 4, Math.min(radius, w / 2, h / 2, d / 2)), material, parent, position);
}
function cylinder(radius, height, material, position, parent = scene) {
    return mesh(new THREE.CylinderGeometry(radius, radius, height, 40), material, parent, position);
}
function path(points) { return new THREE.CatmullRomCurve3(points.map(p => Array.isArray(p) ? V(...p) : p), false, 'centripetal'); }
function tube(curve, radius, material, parent = scene, segments = 72) {
    return mesh(new THREE.TubeGeometry(curve, segments, radius, 8, false), material, parent);
}
function glow(color, opacity) {
    const canvas = document.createElement('canvas'); canvas.width = canvas.height = 128;
    const context = canvas.getContext('2d');
    const gradient = context.createRadialGradient(64, 64, 0, 64, 64, 64);
    gradient.addColorStop(0, '#ffffff'); gradient.addColorStop(0.25, '#ffffff88'); gradient.addColorStop(1, '#ffffff00');
    context.fillStyle = gradient; context.fillRect(0, 0, 128, 128);
    return new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(canvas), color, transparent: true, opacity, blending: THREE.AdditiveBlending, depthWrite: false });
}

// A raised presentation deck with separate AC and DC pathways.
const floor = mesh(new THREE.PlaneGeometry(180, 180), new THREE.MeshStandardMaterial({ color: 0x07111d, roughness: 0.85 }));
floor.rotation.x = -Math.PI / 2;
const deck = rounded(10.8, 0.16, 6.1, 0.12, new THREE.MeshStandardMaterial({ color: 0x122232, metalness: 0.35, roughness: 0.65 }), [-1.1, 0.02, -0.3]);
const grid = new THREE.GridHelper(60, 80, 0x29435a, 0x1c2e40); grid.position.y = -0.063; scene.add(grid);
const whiteLine = new THREE.MeshBasicMaterial({ color: 0x456074 });
for (const z of [-1.5, 1.5]) rounded(5.1, 0.008, 0.024, 0.005, whiteLine, [1.0, 0.108, z]);
rounded(0.025, 0.008, 3.02, 0.005, whiteLine, [3.55, 0.108, 0]);

// The grid-side cabinet provides an explicit source, rather than energy appearing at the connector.
const gridNode = new THREE.Group(); gridNode.position.set(-5.0, 0.11, 0.8); scene.add(gridNode);
rounded(1.05, 0.10, 0.7, 0.05, materials.graphite, [0, 0.05, 0], gridNode);
rounded(0.83, 1.16, 0.42, 0.08, new THREE.MeshPhysicalMaterial({ color: 0x24496b, metalness: 0.45, roughness: 0.22, clearcoat: 1 }), [0, 0.67, 0], gridNode);
const gridFace = new THREE.MeshBasicMaterial({ color: 0x5eb9ff, transparent: true, opacity: 0.65 });
for (let phase = 0; phase < 3; phase++) {
    const wave = [];
    for (let i = 0; i <= 50; i++) wave.push([-0.31 + i / 50 * 0.62, 0.5 + phase * 0.15 + Math.sin(i / 50 * Math.PI * 3) * 0.05, 0.222]);
    tube(path(wave), 0.008, gridFace, gridNode, 50);
}
rounded(0.55, 0.025, 0.015, 0.005, materials.blue, [0, 1.18, 0.22], gridNode);

// Industrial cabinet: curved shell, recessed screen, converter drawers, vents and a separate connector dock.
const charger = new THREE.Group(); charger.position.set(-2.85, 0.11, -0.72); scene.add(charger);
rounded(1.25, 0.12, 0.92, 0.06, materials.graphite, [0, 0.06, 0], charger);
rounded(1.04, 2.30, 0.74, 0.11, materials.white, [0, 1.26, 0], charger);
rounded(0.92, 1.99, 0.04, 0.07, materials.graphite, [0, 1.29, 0.387], charger);
rounded(0.94, 0.13, 0.72, 0.05, materials.mint, [0, 2.43, 0], charger);
const ledMaterial = new THREE.MeshStandardMaterial({ color: 0x4ce9c3, emissive: 0x2cdcae, emissiveIntensity: 1.5 });
rounded(0.61, 0.025, 0.02, 0.008, ledMaterial, [0, 2.14, 0.42], charger);
const screenCanvas = document.createElement('canvas'); screenCanvas.width = 512; screenCanvas.height = 640;
const screenContext = screenCanvas.getContext('2d');
const screenTexture = new THREE.CanvasTexture(screenCanvas); screenTexture.colorSpace = THREE.SRGBColorSpace;
rounded(0.64, 0.87, 0.027, 0.015, new THREE.MeshBasicMaterial({ map: screenTexture }), [0, 1.65, 0.422], charger);
for (let i = 0; i < 3; i++) {
    rounded(0.67, 0.19, 0.035, 0.015, new THREE.MeshStandardMaterial({ color: 0x263a4c, metalness: 0.65, roughness: 0.5 }), [0, 0.91 - i * 0.235, 0.415], charger);
    rounded(0.055, 0.035, 0.009, 0.005, ledMaterial, [-0.24, 0.91 - i * 0.235, 0.437], charger);
    for (let j = 0; j < 7; j++) rounded(0.02, 0.10, 0.008, 0.003, materials.graphite, [-0.09 + j * 0.052, 0.91 - i * 0.235, 0.438], charger);
}
const stopButton = cylinder(0.049, 0.032, materials.red, [0.35, 1.08, 0.44], charger); stopButton.rotation.x = Math.PI / 2;
rounded(0.15, 0.22, 0.10, 0.04, materials.graphite, [0.62, 1.42, 0.11], charger);

// Model Y rebuilt from the published China five-seat dimensions and exterior reference views.
const car = new THREE.Group(); car.position.set(1.0, 0.11, 0); scene.add(car);
const carModel = createModelY(); car.add(carModel);
// Battery pack is a separate solid assembly. Forty modules display the actual SOC, including a partial module.
const battery = new THREE.Group(); battery.position.set(.08, .237, 0); car.add(battery);
rounded(2.12, 0.16, 1.50, 0.07, materials.graphite, [0, 0, 0], battery);
rounded(2.19, 0.025, 1.57, 0.03, new THREE.MeshStandardMaterial({ color: 0x39c7ae, emissive: 0x1f9a86, emissiveIntensity: 0.6, metalness: 0.6, roughness: 0.3 }), [0, -0.06, 0], battery);
const modules = [];
const cellMaterial = new THREE.MeshBasicMaterial({ color: 0x35dfb0 });
const emptyCellMaterial = new THREE.MeshStandardMaterial({ color: 0x10343c, metalness: 0.35, roughness: 0.5 });
const emptyCells = new THREE.InstancedMesh(new RoundedBoxGeometry(0.183, 0.135, 0.297, 3, 0.025), emptyCellMaterial, 40);
const filledCells = new THREE.InstancedMesh(new RoundedBoxGeometry(0.171, 0.14, 0.281, 3, 0.02), cellMaterial, 40);
const terminals = new THREE.InstancedMesh(new THREE.BoxGeometry(0.08, 0.012, 0.035), materials.silver, 40);
filledCells.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
battery.add(emptyCells, filledCells, terminals);
const instanceTransform = new THREE.Object3D();
for (let row = 0; row < 4; row++) for (let column = 0; column < 10; column++) {
    const x = -.9225 + column * .205, z = -.51 + row * .34, slot = modules.length;
    instanceTransform.position.set(x, 0.11, z); instanceTransform.updateMatrix(); emptyCells.setMatrixAt(slot, instanceTransform.matrix);
    instanceTransform.position.set(x, 0.195, z + 0.12); instanceTransform.updateMatrix(); terminals.setMatrixAt(slot, instanceTransform.matrix);
    modules.push({ x, z, index: column * 4 + row });
}
emptyCells.instanceMatrix.needsUpdate = true; terminals.instanceMatrix.needsUpdate = true;
const batteryHalo = new THREE.Sprite(glow(0x29edb7, 0.25)); batteryHalo.position.set(0, 0.18, 0); batteryHalo.scale.set(5, 3.0, 1); battery.add(batteryHalo);
const packEdges = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(2.23, 0.27, 1.60)), new THREE.LineBasicMaterial({ color: 0x5fffd3, transparent: true, opacity: 0.75 })); battery.add(packEdges);
const port = new THREE.Vector3(...carModel.userData.chargingPort).add(car.position).add(V(0, 0, .015));
// Connector orientation uses a local +Z insertion axis, with a separately modeled grip and locking collar.
const connector = new THREE.Group(); scene.add(connector);
rounded(0.15, 0.17, 0.24, 0.045, materials.graphite, [0, 0, 0], connector);
rounded(0.11, 0.11, 0.13, 0.03, materials.silver, [0, 0.025, 0.16], connector);
rounded(0.045, 0.035, 0.14, 0.012, materials.mint, [0, 0.10, -0.015], connector);
const grip = rounded(0.115, 0.29, 0.12, 0.035, materials.graphite, [0, -0.17, -0.04], connector); grip.rotation.x = -0.32;
const dock = V(-2.22, 1.51, -0.53);
const approach = port.clone().add(V(0, 0.08, 0.70));
let plugProgress = 0, plugFrom = 0, plugTarget = 0, motionStarted = 0, motionDuration = reducedMotion ? 400 : 3600;
let connectorMoving = false, hasState = false, selectedGun = null, motionWaiters = [], motionTimer = null;
const cableMaterial = new THREE.MeshStandardMaterial({ color: 0x182c3a, metalness: 0.3, roughness: 0.45 });
let cableCurve = path([[-2.27, 1.18, -0.5], [-2.06, 0.30, 0.3], [-2.2, 0.30, 0.7], dock]);
const cable = tube(cableCurve, 0.041, cableMaterial);
function placeConnector(progress) {
    let point;
    if (progress < 0.76) {
        const t = smooth(progress / 0.76);
        point = new THREE.QuadraticBezierCurve3(dock, V(-1.6, 2.06, 1.78), approach).getPoint(t);
    } else point = approach.clone().lerp(port, smooth((progress - 0.76) / 0.24));
    connector.position.copy(point);
    connector.rotation.set(lerp(-0.18, 0, smooth(progress)), lerp(-0.68, Math.PI - .12, smooth(Math.min(1, progress / 0.78))), lerp(-0.28, 0, smooth(progress)));
    const tail = V(0, -0.22, -0.08).applyEuler(connector.rotation).add(point);
    cableCurve = path([[-2.27, 1.18, -0.5], [-2.08, 0.40, 0.26], [-1.75, 0.21, 1.46], [point.x - 0.30, Math.max(0.25, point.y - 0.53), point.z + 0.12], tail]);
    const previous = cable.geometry;
    cable.geometry = new THREE.TubeGeometry(cableCurve, 64, 0.041, 8, false);
    previous.dispose();
}
placeConnector(0);
function finishConnectorMotion() {
    clearTimeout(motionTimer); motionTimer = null;
    plugProgress = plugTarget; connectorMoving = false; placeConnector(plugProgress);renderer.shadowMap.needsUpdate = true;
    motionWaiters.splice(0).forEach(resolve => resolve());
}
function moveConnector(target, snap = false) {
    if (snap) {
        clearTimeout(motionTimer); motionTimer = null;
        plugProgress = target; plugTarget = target; connectorMoving = false; placeConnector(target);
        motionWaiters.splice(0).forEach(resolve => resolve()); return;
    }
    if (target === plugTarget) return;
    plugFrom = plugProgress; plugTarget = target; motionStarted = performance.now(); connectorMoving = true;
    clearTimeout(motionTimer); motionTimer = setTimeout(finishConnectorMotion, motionDuration);
}
window.chargingScene = {
    whenConnectorSettled: () => connectorMoving ? new Promise(resolve => motionWaiters.push(resolve)) : Promise.resolve(),
    getState: () => ({ model: 'Model Y 2025+ / parameter reconstruction', specification: MODEL_Y_SPECS, connectorMoving, plugProgress, plugTarget, viewMode, cutaway, active: energyActive, soc: displayedSoc, selectedGun, triangles: renderer.info.render.triangles, drawCalls: renderer.info.render.calls }),
    inspectVehicle: () => {const box=new THREE.Box3().setFromObject(carModel);return {specification:MODEL_Y_SPECS,boundsMeters:box.getSize(new THREE.Vector3()).toArray(),wheelCenters:carModel.userData.wheelCenters,chargingPort:carModel.userData.chargingPort,parts:carModel.userData.parts};},
    setCameraView: view => { const positions={overview:[10.5,8.5,12.8],side:[1,3.0,12.8],front:[12,3.0,0],rear:[-11,3.0,0],rearQuarter:[-10,5.0,10]};if(positions[view]){camera.position.set(...positions[view]);controls.target.set(view==='overview'?-1:1,.75,0);controls.update();}},
    setView: mode => { if (['auto', 'exterior', 'battery'].includes(mode)) viewMode = mode; }
};

// The same directed energy path is visible in the physical scene and the semantic flow diagram.
const acCurve = path([[-5, 0.34, 1.0], [-4.6, 0.18, 1.35], [-3.7, 0.18, 0.15], [-2.84, 0.20, -0.75], [-2.84, 0.7, -0.72]]);
const batteryCurve = path([port, [-.90, .50, .62], [.10, .49, .39], [1.08, .49, .0]]);
const acTrace = tube(acCurve, 0.019, new THREE.MeshBasicMaterial({ color: 0x3280b3, transparent: true, opacity: 0.5 }));
const dcTrace = tube(batteryCurve, 0.022, new THREE.MeshBasicMaterial({ color: 0x29d2a6, transparent: true, opacity: 0.6 }));
function flowParticles(color, count) {
    const particleGeometry = new THREE.ConeGeometry(0.040, 0.14, 8);
    const material = new THREE.MeshBasicMaterial({ color });
    const glowMaterial = glow(color, 0.40);
    return Array.from({ length: count }, () => {
        const object = mesh(particleGeometry, material); object.castShadow = false;
        const halo = new THREE.Sprite(glowMaterial); halo.scale.set(0.20, 0.20, 1); object.add(halo); return object;
    });
}
const acParticles = flowParticles(0x69bfff, 8), cableParticles = flowParticles(0x75ffe0, 13), packParticles = flowParticles(0x7dffce, 5);
const yAxis = V(0, 1, 0);
function animateFlow(particles, curve, time, duration) {
    particles.forEach((particle, index) => {
        particle.visible = energyActive;
        if (!energyActive) return;
        const u = (time / duration + index / particles.length) % 1;
        particle.position.copy(curve.getPointAt(u));
        particle.quaternion.setFromUnitVectors(yAxis, curve.getTangentAt(u).normalize());
    });
}


let live = null, viewMode = 'auto', cutaway = 0, displayedSoc = 20, socGoal = 20, energyActive = false, lastFrame = 0, elapsed = 0;
const fadeMaterials = [];
// Fading the shell never affects the independent battery pack or the protocol state.
carModel.traverse(object => {
    if (!object.isMesh) return;
    const material = object.material.clone(); material.transparent = true;
    material.userData.baseOpacity = material.opacity;
    material.userData.baseDepthWrite = material.depthWrite;
    object.material = material;
    fadeMaterials.push({ object, material, wheel: /wheel|tire|rim|aeroblade|lugnut|tread/i.test(object.name) });
});
host.dataset.model = 'model-y-2025';renderer.shadowMap.needsUpdate = true;
const viewButtons = [...document.querySelectorAll('[data-scene-view]')];
viewButtons.forEach(button => button.addEventListener('click', () => {
    viewMode = button.dataset.sceneView;
    viewButtons.forEach(b => { b.classList.toggle('active', b === button); b.setAttribute('aria-pressed', String(b === button)); });
}));
function updateScreen() {
    if (!live) return;
    const c = screenContext;
    c.fillStyle = '#071724'; c.fillRect(0, 0, 512, 640);
    c.fillStyle = '#6a8b9e'; c.font = '20px sans-serif'; c.fillText('PROTOCOL / DC CHARGER', 32, 48);
    c.fillStyle = live.fault ? '#ff8c8c' : '#68f8cc'; c.font = '600 103px sans-serif'; c.fillText(Math.round(socGoal) + '%', 30, 204);
    c.fillStyle = '#afc6d3'; c.font = '22px sans-serif'; c.fillText(energyActive ? 'ENERGY TRANSFER' : live.plugged ? 'CONNECTED' : 'READY TO CONNECT', 32, 257);
    c.fillStyle = '#1a3645'; c.fillRect(32, 298, 448, 10); c.fillStyle = '#4be4b2'; c.fillRect(32, 298, 448 * socGoal / 100, 10);
    c.fillStyle = '#e7f6ff'; c.font = '500 38px sans-serif'; c.fillText(Number(live.voltage || 0).toFixed(0) + ' V', 32, 391); c.fillText(Number(live.current || 0).toFixed(1) + ' A', 274, 391);
    c.strokeStyle = '#1d3443'; c.beginPath(); c.moveTo(32, 436); c.lineTo(480, 436); c.stroke();
    c.fillStyle = '#7b9cab'; c.font = '22px sans-serif'; c.fillText('AC INPUT  →  DC OUTPUT', 32, 491); c.fillText('GUN ' + live.gunNo + ' / YKC 1.6', 32, 556);
    screenTexture.needsUpdate = true;
}
window.addEventListener('simulator-state', event => {
    const next = event.detail.guns.find(g => g.gunNo === event.detail.selectedGun);
    if (!next) return;
    const differentGun = selectedGun !== event.detail.selectedGun;
    selectedGun = event.detail.selectedGun; live = next;
    socGoal = clamp(Number(next.soc) || 0, 0, 100);
    if (!hasState || differentGun) displayedSoc = socGoal;
    moveConnector(next.plugged ? 1 : 0, !hasState || differentGun);
    hasState = true;
    updateScreen();
});
const projection = new THREE.Vector3();
function positionLabel(element, point) {
    if (!element) return;
    projection.copy(point).project(camera);
    const x = (projection.x * 0.5 + 0.5) * host.clientWidth, y = (-projection.y * 0.5 + 0.5) * host.clientHeight;
    element.style.left = `${clamp(x, 65, host.clientWidth - 65)}px`;
    element.style.top = `${clamp(y, 135, host.clientHeight - 125)}px`;
    element.hidden = projection.z > 1;
}
function resize() {
    const w = host.clientWidth, h = host.clientHeight;
    renderer.setSize(w, h); camera.aspect = w / h;
    camera.fov = Math.max(35, Math.min(72, 35 * 1.28 / (w / h))); camera.updateProjectionMatrix();
}
new ResizeObserver(resize).observe(host); resize();
let sceneInView = true, lastShadowCutaway = -1;
new IntersectionObserver(entries => { sceneInView = entries[0].isIntersecting; }, { rootMargin: '100px' }).observe(host);
const putText = (element, value) => { if (element && element.textContent !== value) element.textContent = value; };
function animate(time) {
    requestAnimationFrame(animate);
    if (document.hidden || !sceneInView || time - lastFrame < 30) return;
    const dt = Math.min((time - lastFrame) / 1000 || 0.03, 0.5); lastFrame = time; elapsed += dt;
    if (connectorMoving) {
        const u = clamp((time - motionStarted) / motionDuration, 0, 1);
        plugProgress = lerp(plugFrom, plugTarget, smooth(u)); placeConnector(plugProgress);
        if (u === 1) finishConnectorMotion();
    }
    energyActive = !!live && live.stateCode === 3 && !live.fault && Number(live.current) > 0 && Number(live.voltage) > 0 && plugProgress > 0.995 && !connectorMoving;
    const targetCutaway = viewMode === 'battery' || viewMode === 'auto' && energyActive ? 1 : 0;
    cutaway = lerp(cutaway, targetCutaway, 1 - Math.exp(-dt * (reducedMotion ? 20 : 2.0)));
    if (Math.abs(cutaway - lastShadowCutaway) > 0.01 || connectorMoving) { renderer.shadowMap.needsUpdate = true; lastShadowCutaway = cutaway; }
    displayedSoc = lerp(displayedSoc, socGoal, 1 - Math.exp(-dt * 3.5));
    for (const { object, material, wheel } of fadeMaterials) {
        material.opacity = lerp(material.userData.baseOpacity, wheel ? 0.18 : 0.075, cutaway);
        const depthWrite = cutaway < 0.1 && material.userData.baseDepthWrite !== false;
        if (material.depthWrite !== depthWrite) { material.depthWrite = depthWrite; material.needsUpdate = true; }
        object.castShadow = cutaway < 0.3;
    }
    batteryHalo.material.opacity = cutaway * (energyActive ? 0.17 + Math.sin(elapsed * 2) * 0.025 : 0.11);
    packEdges.material.opacity = lerp(0.13, 0.8, cutaway);
    modules.forEach(({ x, z, index }, slot) => {
        const fraction = clamp(displayedSoc / 100 * 40 - index, 0, 1);
        instanceTransform.position.set(x - (1 - fraction) * .0855, 0.115, z);
        instanceTransform.scale.set(Math.max(fraction, 0.001), 1, 1); instanceTransform.updateMatrix();
        filledCells.setMatrixAt(slot, instanceTransform.matrix);
    });
    filledCells.instanceMatrix.needsUpdate = true;
    cellMaterial.color.set(energyActive ? 0x35dfb0 : 0x218968);
    ledMaterial.color.set(live?.fault ? 0xf06e7b : energyActive ? 0x4ce9c3 : 0x70a8d4);
    ledMaterial.emissive.set(live?.fault ? 0xd5344b : energyActive ? 0x2cdcae : 0x376f9d);
    dcTrace.visible = cutaway > 0.25;
    acTrace.material.opacity = energyActive ? 0.78 : 0.25;
    animateFlow(acParticles, acCurve, reducedMotion ? 0 : elapsed, 3.3);
    animateFlow(cableParticles, cableCurve, reducedMotion ? 0 : elapsed, 3.0);
    animateFlow(packParticles, batteryCurve, reducedMotion ? 0 : elapsed, 2.0);
    if (ui.sceneEnergyFlow) ui.sceneEnergyFlow.classList.toggle('active', energyActive && !reducedMotion);
    if (ui.sceneMotion) {
        putText(ui.sceneMotion, connectorMoving ? `${plugTarget ? '插枪' : '拔枪'}中 · ${Math.round((plugTarget ? plugProgress : 1 - plugProgress) * 100)}%` : live?.plugged ? '连接器已锁止' : '连接器待机');
        ui.sceneMotion.classList.toggle('moving', connectorMoving);
    }
    if (ui.sceneBattery) ui.sceneBattery.classList.toggle('visible', cutaway > 0.35);
    putText(ui.sceneBatteryValue, Math.round(displayedSoc) + '%');
    putText(ui.scenePackSoc, '底盘电池 · SOC ' + Math.round(displayedSoc) + '%');
    putText(ui.sceneBatteryEnergy, `${(displayedSoc / 100 * Number(live?.capacityKwh || 60)).toFixed(1)} / ${Number(live?.capacityKwh || 60)} kWh`);
    putText(ui.scenePowerValue, `${(Number(live?.voltage || 0) * Number(live?.current || 0) / 1000).toFixed(1)} kW`);
    putText(ui.sceneFlowStatus, energyActive ? '电网向动力电池供能' : connectorMoving ? '连接器缓慢就位中' : live?.fault ? '故障保护 · 能流已切断' : '待机 · 无能量传输');
    if (ui.sceneBatteryLabel) ui.sceneBatteryLabel.style.opacity = String(cutaway);
    controls.update();
    positionLabel(ui.sceneGridLabel, V(-5, 1.53, 0.8));
    positionLabel(ui.sceneChargerLabel, V(-2.85, 2.83, -0.72));
    positionLabel(ui.sceneBatteryLabel, V(1.4, 1.38, 0.25));
    renderer.render(scene, camera);
}
requestAnimationFrame(animate);
