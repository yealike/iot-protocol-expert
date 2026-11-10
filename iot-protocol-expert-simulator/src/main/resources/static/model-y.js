import * as THREE from 'three';
import { RoundedBoxGeometry } from './vendor/three/RoundedBoxGeometry.js';

// Original visualization geometry. Millimetre constraints: Tesla China, 2025+ Model Y Premium, five seats.
// Shape stations are artist-authored approximations traced from the official exterior/dimension views, not OEM CAD.
export const MODEL_Y_SPECS = Object.freeze({
    reference: 'Tesla Model Y 2025+ Premium / China / 5 seats',
    lengthMm: 4797, bodyWidthMm: 1920, widthWithMirrorsMm: 2129, heightMm: 1624,
    wheelbaseMm: 2890, frontOverhangMm: 896, rearOverhangMm: 1011,
    trackMm: 1636, groundClearanceMm: 167, tire: '255/40R20', tireWidthMm: 255,
    rimDiameterMm: 508, tireDiameterMm: 712,
    dimensionsSource: 'https://www.tesla.cn/ownersmanual/modely/zh_cn/GUID-1E76B638-7B12-4D9A-8767-94B7F1E92A0E.html',
    tiresSource: 'https://www.tesla.cn/ownersmanual/modely/zh_cn/GUID-FDDB10EF-FFA9-46EB-B8CC-03614AE92B6B.html',
    exteriorSource: 'https://www.tesla.cn/ownersmanual/modely/zh_cn/GUID-6C6C3944-9674-4E81-A0E8-94D60B6D87B9.html'
});
const L = 4.797, W = 1.920, H = 1.624, rear = -L / 2, front = L / 2;
const rearAxle = rear + 1.011, frontAxle = rearAxle + 2.890, tireRadius = 0.356;
const v3 = (x, y, z) => new THREE.Vector3(x, y, z);
const clamp = THREE.MathUtils.clamp;
const mix = THREE.MathUtils.lerp;

// Monotone cubic Hermite interpolation avoids overshooting published width/height constraints.
function profile(points) {
    const n = points.length, slopes = [], tangent = [];
    for (let i = 0; i < n - 1; i++) slopes.push((points[i + 1][1] - points[i][1]) / (points[i + 1][0] - points[i][0]));
    tangent.push(slopes[0]);
    for (let i = 1; i < n - 1; i++) tangent.push(slopes[i - 1] * slopes[i] <= 0 ? 0 : 2 / (1 / slopes[i - 1] + 1 / slopes[i]));
    tangent.push(slopes[n - 2]);
    return x => {
        if (x <= points[0][0]) return points[0][1];
        if (x >= points[n - 1][0]) return points[n - 1][1];
        let i = 0; while (i < n - 2 && x > points[i + 1][0]) i++;
        const h = points[i + 1][0] - points[i][0], t = (x - points[i][0]) / h;
        return (2 * t ** 3 - 3 * t * t + 1) * points[i][1] + (t ** 3 - 2 * t * t + t) * h * tangent[i]
            + (-2 * t ** 3 + 3 * t * t) * points[i + 1][1] + (t ** 3 - t * t) * h * tangent[i + 1];
    };
}
function gridGeometry(us, vs, evaluate, reverse = false) {
    const positions = [], uv = [], indices = [], stride = vs.length;
    us.forEach((u, i) => vs.forEach((v, j) => {
        positions.push(...evaluate(u, v)); uv.push(i / (us.length - 1), j / (vs.length - 1));
        if (i < us.length - 1 && j < vs.length - 1) {
            const a = i * stride + j, b = a + stride;
            if (reverse) indices.push(a, b, a + 1, a + 1, b, b + 1);
            else indices.push(a, a + 1, b, a + 1, b + 1, b);
        }
    }));
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); g.setIndex(indices); g.computeVertexNormals(); return g;
}
const samples = (a, b, n) => Array.from({ length: n + 1 }, (_, i) => mix(a, b, i / n));
function arcCurve(points) { return new THREE.CatmullRomCurve3(points.map(p => v3(...p)), false, 'centripetal'); }

export function createModelY() {
    const root = new THREE.Group(); root.name = 'ModelY_2025_Premium_ParameterModel';
    root.userData.specification = MODEL_Y_SPECS;
    root.userData.chargingPort = [-2.13, 1.015, 0.919];
    root.userData.wheelCenters = [rearAxle, frontAxle].flatMap(x => [-1, 1].map(side => [x, tireRadius, side * 0.818]));
    const paint = new THREE.MeshPhysicalMaterial({ color: 0x9caeb9, metalness: 0.65, roughness: 0.27, clearcoat: 1, clearcoatRoughness: 0.17 });
    const black = new THREE.MeshStandardMaterial({ color: 0x111920, metalness: 0.45, roughness: 0.38 });
    const glass = new THREE.MeshPhysicalMaterial({ color: 0x10222f, metalness: 0.30, roughness: 0.12, clearcoat: 1, clearcoatRoughness: 0.08 });
    const roofGlass = new THREE.MeshPhysicalMaterial({ color: 0x0b1721, metalness: 0.42, roughness: 0.14, clearcoat: 1 });
    const rubber = new THREE.MeshStandardMaterial({ color: 0x11151a, roughness: 0.88, metalness: 0.0 });
    const wheelMetal = new THREE.MeshStandardMaterial({ color: 0x303b45, metalness: 0.83, roughness: 0.28 });
    const machined = new THREE.MeshStandardMaterial({ color: 0x687b8b, metalness: 0.90, roughness: 0.23 });
    const brake = new THREE.MeshStandardMaterial({ color: 0x3d4247, metalness: 0.8, roughness: 0.56 });
    const led = new THREE.MeshStandardMaterial({ color: 0xdeefff, emissive: 0xbddeff, emissiveIntensity: 1.15, roughness: 0.2 });
    const redLed = new THREE.MeshStandardMaterial({ color: 0x971125, emissive: 0xc70b27, emissiveIntensity: 0.8, roughness: 0.25 });
    const lampGlass = new THREE.MeshPhysicalMaterial({ color: 0x111e28, metalness: 0.38, roughness: 0.16, clearcoat: 1 });
    function part(name, geometry, material, parent = root, position = [0, 0, 0]) {
        const object = new THREE.Mesh(geometry, material); object.name = name;
        object.position.set(...position); object.castShadow = true; object.receiveShadow = true; parent.add(object); return object;
    }
    function box(name, w, h, d, radius, material, position, parent = root) {
        return part(name, new RoundedBoxGeometry(w, h, d, 3, Math.min(radius, w / 2, h / 2, d / 2)), material, parent, position);
    }
    function line(name, points, radius, material, parent = root, segments = 72) {
        return part(name, new THREE.TubeGeometry(arcCurve(points), segments, radius, 6, false), material, parent);
    }
    const width = profile([[rear, .807], [-2.25, .902], [-1.8, .947], [rearAxle, .960], [-.65, .936], [.40, .931], [frontAxle, .958], [1.94, .921], [2.23, .865], [front, .799]]);
    const belt = profile([[rear, 1.047], [-2.18, 1.122], [-1.65, 1.130], [-1.1, 1.095], [-.25, 1.04], [.55, 1.00], [1.16, .958], [1.65, .871], [2.12, .817], [front, .778]]);
    const centerTop = profile([[rear, 1.07], [-2.20, 1.15], [-1.75, 1.18], [-1.10, 1.10], [-.2, 1.025], [.75, 1.015], [1.15, .99], [1.65, .895], [2.14, .802], [front, .782]]);
    const lower = profile([[rear, .27], [-2.05, .233], [-1.0, .214], [0.5, .214], [1.95, .212], [front, .230]]);
    function cornerX(x, zFraction) {
        const f = clamp((x - 2.10) / (front - 2.10), 0, 1), r = clamp((-2.10 - x) / (-2.10 - rear), 0, 1);
        return x - .118 * f * Math.abs(zFraction) ** 4 + .065 * r * Math.abs(zFraction) ** 4;
    }
    function archBoundary(x) {
        let y = lower(x);
        for (const axle of [rearAxle, frontAxle]) {
            const dx = x - axle, radius = .398;
            if (Math.abs(dx) < radius) y = Math.max(y, tireRadius + Math.sqrt(Math.max(0, radius * radius - dx * dx)));
        }
        return y;
    }
    const sideWidthFactor = profile([[0, .995], [.16, 1], [.64, .986], [1, .950]]);
    function sidePoint(x, t, side) {
        const y = mix(belt(x), archBoundary(x), t);
        let z = width(x) * sideWidthFactor(t);
        const doors = Math.exp(-(((x - .05) / 1.1) ** 6));
        z += doors * (.004 * Math.exp(-(((y - .76) / .06) ** 2)) - .010 * Math.exp(-(((y - .46) / .11) ** 2)));
        return [cornerX(x, 1), y, side * Math.min(z, .960)];
    }
    const xSamples = [...samples(rear, front, 300), rearAxle, frontAxle, -.4];
    for (const axle of [rearAxle, frontAxle]) for (const angle of samples(0, Math.PI, 56)) xSamples.push(axle + .398 * Math.cos(angle));
    const xs = [...new Set(xSamples)].sort((a, b) => a - b);
    part('Body_SmoothHoodAndShoulders', gridGeometry(xs, samples(-1, 1, 56), (x, u) => {
        const q = Math.abs(u), w = width(x), y = mix(centerTop(x), belt(x), q ** 3) + .010 * Math.sin(q * Math.PI);
        return [cornerX(x, u), y, u * w * .995];
    }), paint);
    for (const side of [-1, 1]) {
        part('Body_TrueWheelCutout_' + side, gridGeometry(xs, [...samples(0, 1, 20), .16].sort((a,b)=>a-b), (x, t) => sidePoint(x, t, side), side < 0), paint);
        // Actual open wheel arches, with a black molded lip rather than wheels intersecting a solid body.
        for (const axle of [rearAxle, frontAxle]) {
            const points = samples(0, Math.PI, 90).map(t => [axle + .398 * Math.cos(t), tireRadius + .398 * Math.sin(t), side * .947]);
            line('WheelArch_MoldedTrim_' + axle + '_' + side, points, .012, black, root, 96);
            line('WheelArch_LowerLegA_' + axle + '_' + side, [[axle + .398, .356, side * .947], [axle + .402, .286, side * .930]], .011, black);
            line('WheelArch_LowerLegB_' + axle + '_' + side, [[axle - .398, .356, side * .947], [axle - .402, .286, side * .930]], .011, black);
        }
        box('Rocker_Cladding_' + side, 2.10, .050, .030, .012, black, [.058, .228, side * .901]);
    }
    box('Floorpan_Clearance167mm', 2.20, .050, 1.33, .02, black, [.058, .192, 0]);
    // Front and rear fascia follow the same corner curves as the body; they remain smoothly connected to the shoulders.
    for (const frontEnd of [true, false]) {
        const x = frontEnd ? front : rear;
        part(frontEnd ? 'Front_ClosedFascia' : 'Rear_HatchFascia', gridGeometry(samples(-1, 1, 64), samples(0, 1, 20), (u, t) => {
            const top = mix(centerTop(x), belt(x), Math.abs(u) ** 3) + .010 * Math.sin(Math.abs(u) * Math.PI);
            const y = mix(top, lower(x), t), z = u * width(x) * sideWidthFactor(t);
            const bulge = Math.sin(t * Math.PI) * .013 * (1 - u * u);
            return [cornerX(x, u) + (frontEnd ? -bulge : bulge), y, z];
        }, frontEnd), paint);
    }

    // Model Y's taller cabin, forward-raked windscreen and rear fastback are independently profiled.
    const roofY = profile([[-2.17, 1.155], [-1.85, 1.32], [-1.36, 1.517], [-.95, 1.595], [-.40, H], [.03, 1.605], [.30, 1.559], [.58, 1.421], [.86, 1.208], [1.12, 1.056]]);
    const roofWidth = profile([[-2.17, .744], [-1.40, .712], [-.40, .710], [.27, .706], [.65, .738], [1.12, .806]]);
    const windowLower = profile([[-2.01, 1.140], [-1.50, 1.133], [-.65, 1.085], [.35, 1.045], [1.08, 1.034]]);
    const windowUpper = profile([[-2.01, 1.151], [-1.67, 1.340], [-1.18, 1.484], [-.65, 1.559], [-.28, 1.567], [.15, 1.507], [.53, 1.313], [1.08, 1.047]]);
    const windowWidth = profile([[-2.01, .773], [-1.4, .822], [-.3, .849], [.5, .829], [1.08, .806]]);
    for (const [name, a, b, bend] of [['Rear_HatchGlass', -2.17, -1.33, .035], ['Roof_PanoramicGlass', -1.33, .27, .035], ['Front_Windshield', .27, 1.12, .105]]) {
        const roofXs = [...samples(a, b, 68), ...[-.4].filter(x => x >= a && x <= b)].sort((a,b)=>a-b);
        part(name, gridGeometry(roofXs, samples(-1, 1, 40), (x, u) => [x, roofY(x) - bend * Math.abs(u) ** 3.7, u * roofWidth(x)]), name.startsWith('Roof') ? roofGlass : glass);
    }
    for (const side of [-1, 1]) {
        part('Side_FramelessGlazing_' + side, gridGeometry(samples(-2.01, 1.08, 130), samples(0, 1, 26), (x, t) => [x, mix(windowLower(x), windowUpper(x), t), side * (windowWidth(x) - .125 * t + .008 * Math.sin(t * Math.PI))], side > 0), glass);
        const upperRail = samples(-2.13, 1.105, 85).map(x => {
            const drop = x > .27 ? mix(.045, .102, clamp((x - .27) / .83, 0, 1)) : .045;
            return [x, roofY(x) - drop, side * (roofWidth(x) + .022)];
        });
        line('Painted_RoofRail_' + side, upperRail, .022, paint, root, 96);
        line('Black_Beltline_' + side, samples(-2.01, 1.08, 100).map(x => [x, windowLower(x) - .004, side * (windowWidth(x) + .008)]), .012, black, root, 100);
        // B pillar and rear quarter divider have their own framing, rather than painted lines on one bubble canopy.
        for (const x of [-.27, -1.33]) {
            const a = windowLower(x), b = windowUpper(x);
            const points = samples(0, 1, 20).map(t => [x + (x < -1 ? .13 * t : -.025 * t), mix(a, b, t), side * (windowWidth(x) - .125 * t + .011)]);
            line('Window_Pillar_' + x + '_' + side, points, x < -1 ? .013 : .027, black, root, 24);
        }
        // C-pillar sail, following the liftgate shoulder.
        part('Rear_PaintedCPillar_' + side, gridGeometry(samples(0, 1, 48), samples(0, 1, 10), (u, t) => {
            const x = mix(-2.17, -1.34, u), y = roofY(x) - .043 - .057 * t;
            return [x + .08 * t, y, side * (roofWidth(x) + .015 + .10 * t)];
        }, side < 0), paint);
        // Door cut lines sit on the body surface and follow the lower side sculpting.
        for (const [id, topX, bottomX, topY] of [['Front', 1.035, .83, .997], ['Center', -.30, -.39, 1.065], ['Rear', -1.44, -1.02, 1.121]]) {
            const points = samples(0, 1, 30).map(t => {
                const x = mix(topX, bottomX, t), y = mix(topY, .28, t);
                const sideT = clamp((belt(x) - y) / Math.max(.001, belt(x) - archBoundary(x)), 0, 1);
                const p = sidePoint(x, sideT, side); p[2] += side * .0025; return p;
            });
            line('DoorSeam_' + id + '_' + side, points, .0022, black, root, 36);
        }
        for (const [x, y] of [[.19, .986], [-.99, 1.047]]) {
            const t = clamp((belt(x) - y) / (belt(x) - archBoundary(x)), 0, 1);
            const surface = sidePoint(x, t, side);
            box('FlushDoorHandle_' + x + '_' + side, .155, .017, .008, .006, black, [x, y, surface[2] + side * .004]);
        }
        // Small side repeater camera and a folded-body mirror stalk.
        const cameraPod = new THREE.Shape(); cameraPod.moveTo(-.075,.035);cameraPod.lineTo(.055,.025);cameraPod.lineTo(-.040,-.045);cameraPod.closePath();
        const pod = part('RepeaterCamera_' + side, new THREE.ExtrudeGeometry(cameraPod,{depth:.008,bevelEnabled:false}), black, root, [.93,.884,side * .930]); if(side<0)pod.rotation.y=Math.PI;
        line('MirrorStalk_' + side, [[.78,1.065,side*.82],[.76,1.11,side*.91],[.73,1.14,side*.96]], .018, black);
        const mirror = part('Mirror_Housing_' + side, new THREE.SphereGeometry(1,32,20), paint, root, [.73,1.159,side*.9945]);mirror.scale.set(.135,.054,.070);
        const mirrorLower = part('Mirror_BlackBase_' + side,new THREE.SphereGeometry(1,28,16),black,root,[.73,1.133,side*.9945]);mirrorLower.scale.set(.131,.026,.069);
        const mirrorFace = part('Mirror_Glass_' + side,new THREE.SphereGeometry(1,24,16),machined,root,[.624,1.16,side*.996]);mirrorFace.scale.set(.010,.038,.055);
    }
    line('Windshield_Cowl', samples(-1,1,40).map(u=>[1.124,1.035-.023*u*u,u*.796]),.014,black);
    for (const sign of [-1,1]) line('Windshield_Wiper_'+sign,[[1.11,1.058,sign*.60],[1.05,1.098,sign*.35],[.99,1.145,sign*.08]],.005,black);
    // Hood perimeter and shoulder creases.
    for (const side of [-1,1]) {
        line('Hood_Cutline_'+side,[[2.32,.792,side*.57],[1.96,.837,side*.65],[1.49,.93,side*.68],[1.14,1.001,side*.74]],.0025,black);
        line('Shoulder_Crease_'+side,[[-2.08,1.112,side*.894],[-1.35,1.103,side*.950],[-.2,1.018,side*.932],[.75,.979,side*.928],[1.67,.866,side*.923]],.0017,black);
    }

    // 2025+ front signature: narrow continuous DRL, separate recessed headlamps and a clean closed nose.
    line('Front_DRL_DarkHousing',[ [2.22,.819,-.852],[2.345,.790,-.60],[front-.019,.785,0],[2.345,.790,.60],[2.22,.819,.852] ],.019,black);
    line('Front_ContinuousDRL',[ [2.225,.825,-.850],[2.354,.797,-.60],[front-.011,.792,0],[2.354,.797,.60],[2.225,.825,.850] ],.008,led);
    for(const side of [-1,1]) {
        const headlamp = box('Split_Headlamp_Recess_'+side,.054,.068,.31,.021,lampGlass,[2.305,.586,side*.656]);headlamp.rotation.y=side*-.12;
        box('Split_Headlamp_Lens_'+side,.058,.015,.255,.006,machined,[2.329,.590,side*.656]);
        line('AirCurtain_'+side,[[2.247,.535,side*.835],[2.269,.405,side*.842],[2.245,.332,side*.825]],.014,black);
    }
    box('Front_LowerIntake',.012,.100,1.28,.026,black,[front-.008,.295,0]);
    for(let i=0;i<4;i++)box('Front_Grille_Slat_'+i,.010,.006,1.15,.002,wheelMetal,[front-.006,.258+i*.023,0]);
    // Model Y rear: broad liftgate, spoiler lip, full-width red signature and individual outer lens wings.
    line('Rear_Spoiler_Lip',[[-2.28,1.126,-.79],[-2.35,1.13,-.48],[-2.37,1.132,0],[-2.35,1.13,.48],[-2.28,1.126,.79]],.023,paint);
    line('Rear_Light_DarkBase',[[-2.254,1.012,-.874],[-2.365,1.017,-.59],[rear+.034,1.012,0],[-2.365,1.017,.59],[-2.254,1.012,.874]],.034,black);
    line('Rear_ContinuousLight',[[-2.260,1.018,-.874],[-2.375,1.025,-.59],[rear+.012,1.020,0],[-2.375,1.025,.59],[-2.260,1.018,.874]],.010,redLed);
    for(const side of [-1,1]){
        line('Rear_OuterLens_'+side,[[-2.371,1.021,side*.57],[-2.23,1.014,side*.873],[-2.13,1.008,side*.906]],.024,redLed);
        line('Hatch_Seam_'+side,[[-2.31,1.09,side*.63],[-2.33,.91,side*.64],[-2.31,.63,side*.65]],.0024,black);
        box('Rear_Reflector_'+side,.022,.016,.22,.006,redLed,[-2.323,.401,side*.65]);
    }
    box('Rear_Diffuser',.045,.110,1.36,.025,black,[-2.35,.282,0]);
    box('Rear_Plate_Recess',.023,.148,.42,.016,black,[rear+.012,.650,0]);
    function badge(text,w,h,x,y){
        const canvas=document.createElement('canvas');canvas.width=1024;canvas.height=128;const c=canvas.getContext('2d');c.font='500 68px Arial';c.textAlign='center';c.textBaseline='middle';c.fillStyle='#bcc8d0';c.fillText(text,512,64);
        const tex=new THREE.CanvasTexture(canvas);tex.colorSpace=THREE.SRGBColorSpace;
        const plate=part('Badge_'+text,new THREE.PlaneGeometry(w,h),new THREE.MeshBasicMaterial({map:tex,transparent:true,depthWrite:false}),root,[x,y,0]);plate.rotation.y=-Math.PI/2;
    }
    badge('T  E  S  L  A',.40,.055,rear+.001,.878);
    // Left-rear charge door occupies the outer rear-lamp region, consistent with the owner's manual.
    const [px,py,pz]=root.userData.chargingPort;
    box('ChargePort_Door_RearLeft',.155,.121,.020,.025,black,[px,py,pz]);
    const portRing=part('ChargePort_Socket',new THREE.TorusGeometry(.038,.007,8,40),machined,root,[px,py,pz+.015]);portRing.rotation.y=-.12;

    // Official 255/40R20 envelope: 712mm outside diameter, 508mm rim, 255mm width; 1636mm wheel-center track.
    const tireSection=[[-.1275,.258],[-.128,.281],[-.122,.322],[-.094,.348],[-.065,.356],[.065,.356],[.094,.348],[.122,.322],[.128,.281],[.1275,.258]];
    const tireProfile = new THREE.CatmullRomCurve3(tireSection.map(([z,r])=>v3(r,z,0)));
    const tireGeometry = new THREE.LatheGeometry(tireProfile.getPoints(36).map(p=>new THREE.Vector2(Math.min(.356,p.x),clamp(p.y,-.1275,.1275))),112);
    // Lathe's axis is Y. Rotate onto wheel-local Z; all wheel centers remain exact.
    tireGeometry.rotateX(Math.PI/2);
    const rimGeometry = new THREE.CylinderGeometry(.254,.254,.040,80);rimGeometry.rotateX(Math.PI/2);
    const discGeometry = new THREE.CylinderGeometry(.207,.207,.012,64);discGeometry.rotateX(Math.PI/2);
    const bladeShape = new THREE.Shape();bladeShape.moveTo(-.024,.070);bladeShape.bezierCurveTo(-.050,.15,-.050,.212,-.016,.246);bladeShape.lineTo(.023,.244);bladeShape.bezierCurveTo(.002,.198,.036,.126,.034,.080);bladeShape.closePath();
    const bladeGeometry = new THREE.ExtrudeGeometry(bladeShape,{depth:.015,bevelEnabled:true,bevelSize:.003,bevelThickness:.002,bevelSegments:2,curveSegments:14,steps:1});
    for(const axle of [rearAxle,frontAxle])for(const side of [-1,1]){
        const wheel=new THREE.Group();wheel.name='Wheel_'+(axle===frontAxle?'Front':'Rear')+'_'+(side>0?'Left':'Right');wheel.position.set(axle,tireRadius,side*.818);root.add(wheel);
        part('Tire_255_40R20',tireGeometry,rubber,wheel);
        const outward=side;
        part('Rim_20inch',rimGeometry,wheelMetal,wheel,[0,0,outward*.090]);
        part('Brake_Disc',discGeometry,brake,wheel,[0,0,outward*.063]);
        const rimLip = part('Rim_OuterLip',new THREE.TorusGeometry(.250,.005,8,80),machined,wheel,[0,0,outward*.115]);
        for(let i=0;i<10;i++){
            const blade=part('AeroBlade_'+i,bladeGeometry,wheelMetal,wheel,[0,0,outward*.104]);blade.rotation.z=i*Math.PI/5;if(side<0)blade.rotation.y=Math.PI;
        }
        const hub=part('Wheel_Hub',new THREE.CylinderGeometry(.065,.065,.028,48),black,wheel,[0,0,outward*.123]);hub.rotation.x=Math.PI/2;
        for(let i=0;i<5;i++){
            const a=i*Math.PI*2/5;const bolt=part('LugNut',new THREE.CylinderGeometry(.008,.008,.008,8),machined,wheel,[Math.cos(a)*.043,Math.sin(a)*.043,outward*.141]);bolt.rotation.x=Math.PI/2;
        }
        // Three fine circumferential channels keep the tire from reading as a featureless torus.
        for(const z of [-.048,0,.048])part('Tread_Groove',new THREE.TorusGeometry(.3548,.0012,4,112),black,wheel,[0,0,z]);
    }
    root.userData.parts = {body:'smooth longitudinal patches with real wheel cutouts',glazing:'separate windshield, panorama, rear hatch and side windows',wheels:'20-inch ten-blade aero wheels',lights:'2025+ split front lamps and full-width light signatures'};
    root.userData.paintMaterial = paint;
    return root;
}
