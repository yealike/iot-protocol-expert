import * as THREE from 'three';
import { GLTFLoader } from './vendor/GLTFLoader.js';
import { OrbitControls } from './vendor/OrbitControls.js';
import { DRACOLoader } from './vendor/DRACOLoader.js';
import { connectorPose } from './scene-geometry.js';
import { loadCablePhysics, CablePhysicsWorld, createCableSkin } from './cable-physics.js';

// Shared by the React workspace and the standalone simulator. All animations
// consume the selected gun's state; the scene never sends device commands.
export function mountTwin(root = document) {
  const container = root.querySelector('#twin-view');
  const panel = container.closest('.twin-panel');
  const message = root.querySelector('#twin-message');
  const badge = root.querySelector('#twin-status');
  const find = id => root.querySelector(`#${id}`);
  let state = { connected: false, guns: [] };
  let disposed = false, frame, observer, renderer, controls, environment;
  const scene = new THREE.Scene();
  const cleanups = [];
  const onState = event => { state = event.detail || {}; };
  window.addEventListener('simulator-state', onState);
  // React mounts the renderer asynchronously; don't lose the first state event.
  if (window.live?.guns) state = window.live;

  function disposeObjects(object) {
    const geometries = new Set(), materials = new Set(), textures = new Set();
    object.traverse(item => {
      if (item.geometry) geometries.add(item.geometry);
      for (const m of Array.isArray(item.material) ? item.material : [item.material]) {
        if (!m) continue;
        materials.add(m);
        for (const v of Object.values(m)) if (v?.isTexture) textures.add(v);
      }
    });
    textures.forEach(t => t.dispose());
    materials.forEach(m => m.dispose());
    geometries.forEach(g => g.dispose());
  }

  async function init() {
    scene.background = new THREE.Color(0xe6ebec);
    scene.fog = new THREE.Fog(0xe6ebec, 14, 35);
    const camera = new THREE.PerspectiveCamera(36, 1, .1, 80);
    renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.0;
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.domElement.setAttribute('aria-label', '一台直流充电桩与一台新能源汽车的交互三维场景');
    container.prepend(renderer.domElement);
    controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.enablePan = false;
    controls.minDistance = 3.5;
    controls.maxDistance = 14;
    controls.maxPolarAngle = Math.PI / 2.12;

    // Large softboxes supply clear reflections on paint and glass without
    // downloading an HDR map. PMREM is created once and released on unmount.
    const studio = new THREE.Scene();
    studio.background = new THREE.Color(0x7f868c);
    for (const [x,y,z,sx,sy,sz] of [[-4,5,2,1,3,7],[4,4,-3,1,3,7],[0,7,0,7,.1,3]]) {
      const softbox = new THREE.Mesh(new THREE.BoxGeometry(sx,sy,sz), new THREE.MeshBasicMaterial({ color: 0xffffff }));
      softbox.position.set(x,y,z); studio.add(softbox);
    }
    const pmrem = new THREE.PMREMGenerator(renderer);
    environment = pmrem.fromScene(studio, .02);
    scene.environment = environment.texture;
    pmrem.dispose(); disposeObjects(studio);
    scene.add(new THREE.HemisphereLight(0xf5f9ff, 0x737d7c, 1.0));
    const key = new THREE.DirectionalLight(0xfffaf2, 2.6);
    key.position.set(-3,7,5); key.castShadow = true;
    key.shadow.mapSize.set(2048,2048);
    Object.assign(key.shadow.camera,{ left:-5, right:5, top:5, bottom:-5, near:.5, far:18 });
    key.shadow.bias = -.0002; key.shadow.normalBias = .008; key.shadow.radius=4;
    scene.add(key);
    cleanups.push(() => key.shadow.dispose());
    const rim = new THREE.DirectionalLight(0xe4f4ff, 1.0); rim.position.set(4,4,-5); scene.add(rim);
    // Continuous architectural floor, measured parking bay and soft contact
    // shadows replace the floating primitive turntable.
    const ground = new THREE.Mesh(new THREE.PlaneGeometry(160,160), new THREE.MeshStandardMaterial({ color:0xb8c2c4, roughness:.73, metalness:0 }));
    ground.rotation.x=-Math.PI/2; ground.position.y=-.008; ground.receiveShadow=true; scene.add(ground);
    const marking = new THREE.MeshStandardMaterial({color:0xf5f7f7,roughness:.85});
    for (const x of [-1.05,1.65]) {
      const line = new THREE.Mesh(new THREE.BoxGeometry(.045,.004,5.65),marking);
      line.position.set(x,0,0); scene.add(line);
    }
    const endLine = new THREE.Mesh(new THREE.BoxGeometry(2.74,.004,.045),marking);
    endLine.position.set(.30,0,-2.82); scene.add(endLine);
    const bayAccent=new THREE.Mesh(new THREE.PlaneGeometry(2.64,.07),new THREE.MeshStandardMaterial({color:0x3b8f81,roughness:.85}));
    bayAccent.rotation.x=-Math.PI/2; bayAccent.position.set(.30,.003,2.83); scene.add(bayAccent);
    for(const x of [-.43,1.03]) {
      const stop=new THREE.Mesh(new THREE.BoxGeometry(.58,.075,.14),new THREE.MeshStandardMaterial({color:0x555f63,roughness:.9}));
      stop.position.set(x,.036,-2.52); stop.castShadow=true; stop.receiveShadow=true; scene.add(stop);
    }
    // Radial contact falloff anchors the tires and cabinet to the floor.
    const contactCanvas=document.createElement('canvas'); contactCanvas.width=128; contactCanvas.height=128;
    const contactContext=contactCanvas.getContext('2d');
    const gradient=contactContext.createRadialGradient(64,64,8,64,64,64);
    gradient.addColorStop(0,'rgba(18,30,34,.33)'); gradient.addColorStop(1,'rgba(18,30,34,0)');
    contactContext.fillStyle=gradient; contactContext.fillRect(0,0,128,128);
    const contactTexture=new THREE.CanvasTexture(contactCanvas);
    for(const [x,z,w,d] of [[-.66,-1.43,.70,.95],[1.26,-1.43,.70,.95],[-.66,1.43,.70,.95],[1.26,1.43,.70,.95],[-2.3,-1.4,1.35,1.0]]) {
      const shadow=new THREE.Mesh(new THREE.PlaneGeometry(w,d),new THREE.MeshBasicMaterial({map:contactTexture,transparent:true,depthWrite:false}));
      shadow.rotation.x=-Math.PI/2; shadow.position.set(x,.001,z); scene.add(shadow);
    }

    const draco=new DRACOLoader().setDecoderPath('/simulator/vendor/draco/').setDecoderConfig({type:'wasm'});
    cleanups.push(()=>draco.dispose());
    const [gltf, Ammo] = await Promise.all([
      new GLTFLoader().setDRACOLoader(draco).loadAsync('/simulator/simulator-model.glb'),
      loadCablePhysics(),
    ]);
    const model = gltf.scene;
    if (disposed) { disposeObjects(model); return; }
    scene.add(model);
    const car = model.getObjectByName('EV');
    const charger = model.getObjectByName('Charger');
    const gun = model.getObjectByName('Charger_Gun');
    const port = model.getObjectByName('EV_Port');
    const door = model.getObjectByName('Port_Door');
    const tip = model.getObjectByName('Gun_Tip');
    const gunCable = model.getObjectByName('Gun_Cable');
    const anchor = model.getObjectByName('Cable_Anchor');
    const holster = model.getObjectByName('Gun_Home');
    let screen;
    charger?.traverse(o => { if (o.isMesh && /^Charger[ _]screen/i.test(o.name)) screen = o; });
    if ([car,charger,gun,port,door,tip,gunCable,anchor,holster,screen].some(o => !o)) throw new Error('三维模型缺少动画节点');
    const socketParts=[];
    port.traverse(object=>{
      if(!object.isMesh)return;
      for(let parent=object.parent;parent && parent!==port;parent=parent.parent)if(parent===door)return;
      socketParts.push(object);
    });
    car.position.set(.30,0,0);
    charger.position.set(-2.30,0,-1.40);
    charger.rotation.y=0;
    model.traverse(o => { if(o.isMesh) { o.castShadow=true; o.receiveShadow=true; } });
    model.updateMatrixWorld(true);
    gun.position.copy(gun.parent.worldToLocal(holster.getWorldPosition(new THREE.Vector3())));
    model.updateMatrixWorld(true);
    const home = gun.position.clone();
    const baseRotation = gun.quaternion.clone();
    const parkedRotation = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0,1,0),Math.PI/2).multiply(baseRotation);
    const doorRotation = door.quaternion.clone();
    const portPosition = port.getWorldPosition(new THREE.Vector3());
    // Use the physical tip anchor, preserving Blender's Z-up conversion.
    const tipOffset = tip.getWorldPosition(new THREE.Vector3()).sub(gun.getWorldPosition(new THREE.Vector3()));
    const destination = portPosition.clone().add(new THREE.Vector3(-.008,0,0)).sub(tipOffset);
    gun.parent.worldToLocal(destination);
    const cableStart = anchor.getWorldPosition(new THREE.Vector3());
    const cabinet=new THREE.Box3(new THREE.Vector3(-.425,0,-.275).add(charger.position),new THREE.Vector3(.425,1.55,.345).add(charger.position));
    const carBounds=new THREE.Box3(new THREE.Vector3(-.66,.20,-2.35),new THREE.Vector3(1.26,1.55,2.35));
    const wheelStops=[-.43,1.03].map(x=>new THREE.Box3(new THREE.Vector3(x-.29,0,-2.59),new THREE.Vector3(x+.29,.074,-2.45)));
    const cablePhysics=new CablePhysicsWorld(Ammo,cabinet,[carBounds,...wheelStops]);
    cleanups.push(()=>cablePhysics.dispose());
    const cableMaterial = new THREE.MeshStandardMaterial({ color:0x090f16, roughness:.55, metalness:.1 });
    gun.quaternion.copy(parkedRotation); gun.updateMatrixWorld(true);
    const physicalCable=cablePhysics.createCable(cableStart,gunCable.getWorldPosition(new THREE.Vector3()));
    const cableSkin=createCableSkin(physicalCable.curve,cableMaterial); scene.add(cableSkin.mesh);
    let spareSkin;
    const spare=model.getObjectByName('Spare_Gun');
    const spareHome=model.getObjectByName('Spare_Home');
    const spareAnchor=model.getObjectByName('Spare_Anchor');
    if(spare && spareHome && spareAnchor) {
      spare.position.copy(spare.parent.worldToLocal(spareHome.getWorldPosition(new THREE.Vector3())));
      spare.quaternion.copy(parkedRotation); spare.updateMatrixWorld(true);
      const start=spareAnchor.getWorldPosition(new THREE.Vector3());
      const end=model.getObjectByName('Spare_Cable').getWorldPosition(new THREE.Vector3());
      const physicalSpare=cablePhysics.createCable(start,end,-1);
      spareSkin=createCableSkin(physicalSpare.curve,cableMaterial); scene.add(spareSkin.mesh);
    }
    cablePhysics.settle(); cableSkin.update(); spareSkin?.update();
    const cableCurve=physicalCable.curve;
    const gripPosition=new THREE.Vector3();
    const glowMaterial = new THREE.MeshBasicMaterial({ color:0x67ffd0 });
    const dots = Array.from({length:14},() => {
      const o = new THREE.Mesh(new THREE.SphereGeometry(.031,10,8),glowMaterial); scene.add(o); return o;
    });
    const portHalo = new THREE.Mesh(new THREE.TorusGeometry(.054,.003,8,64),glowMaterial.clone());
    portHalo.rotation.y = Math.PI/2; portHalo.position.copy(portPosition).x -= .012; scene.add(portHalo);

    // Real depth-tested chassis presentation. The exterior fades while the
    // wheels, axles, battery frame and modules stay solid and correctly placed.
    const fadedMeshes=[], fadeMaterials=new Map();
    car.traverse(object=>{
      if(!object.isMesh || object.name.startsWith('Port_'))return;
      let parent=object.parent;
      while(parent && parent!==car) {if(parent===port)return; parent=parent.parent;}
      const materials=Array.isArray(object.material)?object.material:[object.material];
      if(materials.some(m=>/Pneu|Freio|Cromado|Metal_Preto/.test(m.name)))return;
      object.geometry.computeBoundingBox();
      if(object.geometry.boundingBox.max.y<.82 && object.geometry.boundingBox.min.y<.08)return;
      const cloned=materials.map(source=>{
        if(!fadeMaterials.has(source)) {
          const material=source.clone();
          fadeMaterials.set(source,{material,opacity:source.opacity,transparent:source.transparent,depthWrite:source.depthWrite,target:/Vidro|Interno/.test(source.name)?.035:.12});
        }
        return fadeMaterials.get(source).material;
      });
      object.material=Array.isArray(object.material)?cloned:cloned[0];
      fadedMeshes.push(object);
    });
    const battery = new THREE.Group(); scene.add(battery);
    const packMat = new THREE.MeshStandardMaterial({ color:0x27363c, metalness:.8,roughness:.34 });
    const pack = new THREE.Mesh(new THREE.BoxGeometry(1.48,.14,2.88),packMat);
    pack.position.set(.30,.28,0); pack.castShadow=true; battery.add(pack);
    const edge=new THREE.LineSegments(new THREE.EdgesGeometry(pack.geometry),new THREE.LineBasicMaterial({color:0x70a3ab}));
    edge.position.copy(pack.position); battery.add(edge);
    const cells=[];
    for(let i=0;i<16;i++) {
      const cell=new THREE.Mesh(new THREE.BoxGeometry(.61,.055,.30),new THREE.MeshStandardMaterial({ color:0x37bda0,metalness:.25,roughness:.36,emissive:0x123b30,emissiveIntensity:.3 }));
      cell.position.set(.30+(i%2 ? .34 : -.34),.38,-1.225+Math.floor(i/2)*.35); battery.add(cell); cells.push(cell);
    }
    for(const z of [-1.44,1.44]) {
      const axle=new THREE.Mesh(new THREE.CylinderGeometry(.045,.045,1.78,12),new THREE.MeshStandardMaterial({color:0x61717c,metalness:.8,roughness:.35}));
      axle.rotation.z=Math.PI/2; axle.position.set(.30,.37,z); battery.add(axle);
    }
    const innerCurve = new THREE.CatmullRomCurve3([portPosition.clone(),portPosition.clone().add(new THREE.Vector3(.18,-.24,0)),new THREE.Vector3(-.30,.40,-1.30),new THREE.Vector3(.30,.43,-1.12),new THREE.Vector3(.30,.43,.65)]);
    const innerFlow = new THREE.Mesh(new THREE.TubeGeometry(innerCurve,48,.019,8,false),new THREE.MeshStandardMaterial({color:0xf18c34,emissive:0xc75a0f,emissiveIntensity:.25,roughness:.4}));
    battery.add(innerFlow);
    const innerDots=Array.from({length:5},()=>{
      const o=new THREE.Mesh(new THREE.SphereGeometry(.035,8,6),new THREE.MeshBasicMaterial({color:0xafffe0})); battery.add(o); return o;
    });
    const socCanvas=document.createElement('canvas'); socCanvas.width=512; socCanvas.height=192;
    const socTexture=new THREE.CanvasTexture(socCanvas); socTexture.colorSpace=THREE.SRGBColorSpace;
    const socLabel=new THREE.Sprite(new THREE.SpriteMaterial({map:socTexture,depthTest:false,depthWrite:false,transparent:true}));
    socLabel.position.set(.30,1.35,.05); socLabel.scale.set(1.85,.694,1); socLabel.renderOrder=20; scene.add(socLabel);
    let lastSoc='', ghost=0;
    let batteryEnabled=true;
    const batteryButton=find('twin-battery-toggle');
    const toggleBattery=()=>{ batteryEnabled=!batteryEnabled; batteryButton?.setAttribute('aria-pressed',String(batteryEnabled)); };
    batteryButton?.addEventListener('click',toggleBattery);
    cleanups.push(()=>batteryButton?.removeEventListener('click',toggleBattery));

    const screenCanvas=document.createElement('canvas'); screenCanvas.width=512; screenCanvas.height=512;
    const screenTexture=new THREE.CanvasTexture(screenCanvas); screenTexture.colorSpace=THREE.SRGBColorSpace;
    // Blender cube UVs cover only part of an atlas. Use a full-UV display plane
    // so the live text is legible on the cabinet instead of a cropped stripe.
    screen.visible=false;
    const display=new THREE.Mesh(new THREE.PlaneGeometry(.19,.12),new THREE.MeshBasicMaterial({map:screenTexture}));
    display.position.copy(screen.getWorldPosition(new THREE.Vector3())).add(new THREE.Vector3(0,0,.009).applyQuaternion(charger.quaternion));
    display.quaternion.copy(charger.quaternion);
    scene.add(display);
    let lastScreen='', lastHud='', amount=0, lastTime=performance.now(), chosen=null;
    const setText=(id,text)=>{ const o=find(id); if(o) o.textContent=text; };
    function drawScreen(status,power,soc) {
      const signature=`${status}|${power}|${soc}`; if(signature===lastScreen)return; lastScreen=signature;
      const ctx=screenCanvas.getContext('2d'); ctx.fillStyle='#0a1d29'; ctx.fillRect(0,0,512,512);
      ctx.fillStyle='#75e6c8'; ctx.font='24px sans-serif'; ctx.fillText('PROTOCOL / DC',30,49);
      ctx.fillStyle='#f0fcff'; ctx.font='bold 51px sans-serif'; ctx.fillText(status,30,133);
      ctx.fillStyle='#829ba8'; ctx.font='24px sans-serif'; ctx.fillText('OUTPUT POWER',30,205);
      ctx.fillStyle='#80f2d1'; ctx.font='bold 65px sans-serif'; ctx.fillText(`${power}`,30,277);
      ctx.font='24px sans-serif'; ctx.fillText('kW',375,277);
      ctx.fillStyle='#829ba8'; ctx.fillText('BATTERY SOC',30,350);
      ctx.fillStyle='#ecfffb'; ctx.font='bold 48px sans-serif'; ctx.fillText(`${soc}%`,30,410);
      ctx.fillStyle='#254a54'; ctx.fillRect(30,451,452,12); ctx.fillStyle='#68e7bd'; ctx.fillRect(30,451,452*soc/100,12);
      screenTexture.needsUpdate=true;
    }

    const framingCorners=[];
    for(const entity of [car,charger]) entity.traverse(object=>{
      if(!object.isMesh)return;
      object.geometry.computeBoundingBox();
      const bounds=object.geometry.boundingBox;
      for(const x of [bounds.min.x,bounds.max.x])
        for(const y of [bounds.min.y,bounds.max.y])
          for(const z of [bounds.min.z,bounds.max.z]) framingCorners.push(new THREE.Vector3(x,y,z).applyMatrix4(object.matrixWorld));
    });
    let view='overview';
    function resetView() {
      const aspect=container.clientWidth/Math.max(container.clientHeight,1);
      if(view==='port') {
        controls.target.copy(portPosition).add(new THREE.Vector3(-.22,0,0));
        // View from the rear quarter so the cabinet cannot hide the socket.
        camera.position.copy(controls.target).add(new THREE.Vector3(-2.7,.95,-2.5));
      } else {
        controls.target.set(-.65,.65,0);
        const direction=new THREE.Vector3(-.77,.33,.55).normalize();
        camera.position.copy(controls.target).add(direction);
        camera.lookAt(controls.target);
        const inverse=camera.quaternion.clone().invert();
        const tangent=Math.tan(THREE.MathUtils.degToRad(camera.fov)/2);
        let distance=6.4;
        for(const corner of framingCorners) {
          const local=corner.clone().sub(controls.target).applyQuaternion(inverse);
          distance=Math.max(distance,local.z+Math.abs(local.x)/(tangent*aspect*.90),local.z+Math.abs(local.y)/(tangent*.92));
        }
        camera.position.copy(controls.target).addScaledVector(direction,distance);
      }
      controls.update();
    }
    for(const button of panel.querySelectorAll('[data-twin-view]')) {
      const handler=()=>{view=button.dataset.twinView; resetView();};
      button.addEventListener('click',handler); cleanups.push(()=>button.removeEventListener('click',handler));
    }
    function resize() {
      const w=container.clientWidth,h=container.clientHeight; if(!w||!h)return;
      camera.aspect=w/h; camera.updateProjectionMatrix(); renderer.setSize(w,h,false);
      resetView();
    }
    observer=new ResizeObserver(resize); observer.observe(container); resize();
    message.style.display='none';

    function animate(now) {
      if(disposed)return;
      frame=requestAnimationFrame(animate);
      const dt=Math.min((now-lastTime)/1000,.1); lastTime=now;
      const g=state.guns?.find(item=>String(item.gunNo)===String(state.selectedGun)) || (!state.selectedGun ? state.guns?.[0] : undefined);
      const plugged=Boolean(g?.plugged);
      // Offline-card charging is still a physical energy transfer.
      const charging=plugged && !g?.fault && Number(g?.stateCode)===3;
      const soc=Math.round(THREE.MathUtils.clamp(Number(g?.soc)||0,0,100));
      const voltage=Math.max(0,Number(g?.voltage)||0), current=Math.max(0,Number(g?.current)||0);
      const power=(charging ? voltage*current/1000 : 0).toFixed(1);
      const selection=`${state.deviceId ?? state.pileCode ?? ''}/${g?.gunNo ?? ''}`;
      const changedSelection=chosen!==null && chosen!==selection;
      if(changedSelection) amount=plugged ? 1 : 0;
      chosen=selection;
      amount=THREE.MathUtils.clamp(amount+(plugged ? 1 : -1)*dt/3.2,0,1);
      const moving=amount>0 && amount<1;
      const status=g?.fault ? '设备故障' : !g ? '等待设备' : moving ? (plugged ? '正在插枪' : '正在拔枪') : charging ? '充电中' : plugged ? '已插枪' : '待插枪';
      const badgeText=`${g ? `${g.gunNo} 枪 · ` : ''}${status}${g && !state.connected ? ' · 平台离线' : ''}`;
      if(badge.textContent!==badgeText) badge.textContent=badgeText;
      drawScreen(status,power,soc);

      // First open the cover, then carry the connector, then insert straight
      // along the socket axis. Reversing the same timeline safely withdraws it.
      const pose=connectorPose(amount,home,destination);
      door.quaternion.copy(doorRotation).multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0,0,-1),pose.lid*1.72));
      // The backed lens conceals the inlet when shut, including coplanar
      // contacts that would otherwise show through the source lamp's layers.
      socketParts.forEach(object=>{object.visible=pose.lid>.55;});
      gun.position.copy(pose.position);
      gun.quaternion.copy(parkedRotation).slerp(baseRotation,pose.rotation);
      gun.updateMatrixWorld(true);
      gunCable.getWorldPosition(gripPosition);
      if(changedSelection) {physicalCable.reset(gripPosition); cablePhysics.settle();}
      else {physicalCable.setEnd(gripPosition); cablePhysics.step(dt);}
      cableSkin.update(); spareSkin?.update();
      const flowing=charging && amount===1;
      if(panel.dataset.flowing!==String(flowing)) panel.dataset.flowing=String(flowing);
      const seated=String(plugged && amount===1);
      if(panel.dataset.plugged!==seated) panel.dataset.plugged=seated;
      portHalo.visible=pose.lid>.8;
      portHalo.material.color.setHex(g?.fault?0xe9594c:flowing?0x27ca86:plugged?0x4ba7f0:0xc9eaf4);
      portHalo.scale.setScalar(flowing?1+Math.sin(now*.004)*.025:1);
      dots.forEach((dot,i)=>{dot.visible=flowing; if(flowing)dot.position.copy(cableCurve.getPoint((now*.00022+i/dots.length)%1));});
      ghost=THREE.MathUtils.damp(ghost,flowing && batteryEnabled?1:0,5,dt);
      if(ghost<.002)ghost=0;
      for(const saved of fadeMaterials.values()) {
        const {material,opacity,target,transparent,depthWrite}=saved;
        const nextTransparent=ghost>0 || transparent;
        if(material.transparent!==nextTransparent){material.transparent=nextTransparent; material.needsUpdate=true;}
        material.opacity=opacity*THREE.MathUtils.lerp(1,target,ghost);
        material.depthWrite=ghost>.01?false:depthWrite;
      }
      fadedMeshes.forEach(o=>{o.castShadow=ghost<.3;});
      battery.visible=ghost>.1;
      socLabel.visible=flowing && batteryEnabled;
      if(battery.visible) {
        cells.forEach((cell,i)=>{cell.material.color.setHex(i/16<soc/100 ? 0x45c6a0 : 0x526368);});
        innerDots.forEach((dot,i)=>dot.position.copy(innerCurve.getPoint((now*.00035+i/innerDots.length)%1)));
      }
      const socSignature=`${soc}/${power}`;
      if(socSignature!==lastSoc) {
        lastSoc=socSignature;
        const ctx=socCanvas.getContext('2d'); ctx.clearRect(0,0,512,192);
        ctx.fillStyle='rgba(255,255,255,.95)'; ctx.beginPath();ctx.roundRect(4,4,504,184,22);ctx.fill();
        ctx.strokeStyle='#c5dfdc';ctx.lineWidth=3;ctx.stroke();
        ctx.fillStyle='#637a80';ctx.font='25px sans-serif';ctx.fillText('动力电池 / BATTERY SOC',28,44);
        ctx.fillStyle='#087e66';ctx.font='bold 64px sans-serif';ctx.fillText(`${soc}%`,28,118);
        ctx.fillStyle='#536c75';ctx.font='27px sans-serif';ctx.fillText(`${power} kW`,300,108);
        ctx.fillStyle='#e2eceb';ctx.fillRect(28,148,456,12);ctx.fillStyle='#28b18e';ctx.fillRect(28,148,456*soc/100,12);
        socTexture.needsUpdate=true;
      }
      const hud=`${status}|${soc}|${power}|${voltage}|${current}|${g?.energy}|${flowing}`;
      if(hud!==lastHud) {
        lastHud=hud;
        setText('twin-stage',!g ? '选择设备，开始充电仿真' : g.fault ? '设备故障 · 能量传输已停止' : moving ? (plugged ? '充电口已打开 · 枪头对准插入' : '枪头退出 · 充电枪归位') : flowing ? '能量正在从充电桩流向车辆电池' : plugged ? '连接已就绪 · 等待启动充电' : '点击「插入充电枪」查看连接动画');
        setText('twin-flow-status',g?.fault ? '故障 · 能流已停止' : flowing ? '能量传输中' : moving ? status : plugged ? '已连接 · 待充电' : '等待连接');
        setText('twin-flow-power',`${power} kW`);
        setText('twin-flow-electrical',`${voltage.toFixed(0)} V / ${current.toFixed(1)} A`);
        setText('twin-flow-soc',g ? `${soc}%` : '—');
        setText('twin-flow-energy',`${Math.max(0,Number(g?.energy)||0).toFixed(2)} kWh`);
        setText('twin-gun-label',`星星充电 · 双子座 120 kW${g ? ` · ${g.gunNo} 枪` : ''}`);
        setText('twin-soc-label',`Tesla Model Y${g ? ` · ${soc}% SOC` : ''}`);
      }
      controls.update(); renderer.render(scene,camera);
    }
    frame=requestAnimationFrame(animate);
  }
  init().catch(error=>{
    if(!disposed) {
      console.error('Digital twin:',error);
      message.style.display='grid'; message.textContent='三维场景加载失败，请刷新页面并检查 WebGL 支持';
      badge.textContent='场景不可用';
    }
  });
  return ()=>{
    disposed=true;
    window.removeEventListener('simulator-state',onState);
    cancelAnimationFrame(frame); observer?.disconnect(); controls?.dispose();
    cleanups.forEach(fn=>fn()); disposeObjects(scene); environment?.dispose();
    renderer?.dispose(); renderer?.domElement.remove();
  };
}
