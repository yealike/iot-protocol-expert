import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'

/**
 * 光储充放园区沙盘。
 *
 * 与原始 demo 的区别：demo 是「时间轴脚本动画」，这里改成「数据驱动状态机」——
 * 外部每次调用 setDevices() 传入真实遥测，场景只负责把状态映射到节点。
 * 时间轴那套（车辆驶入/插枪）属于演示脚本，不适合挂真实数据，已剥离。
 *
 * 节点约定沿用 Blender 导出：PV / Wind / Storage / Charger* / Lock* / GateIn / GateOut /
 * CameraIn / CameraOut。多台同型设备按名称序号依次分配（Charger、Charger1、Charger2...）。
 */
export interface DeviceFeed {
  /** 场景节点名前缀，取自 device.sceneAnchor.model 或产品 sceneModel。 */
  model: string
  deviceId: number
  label: string
  online: boolean
  alarm: boolean
  /** 展示用短文本，最多两行。 */
  lines: string[]
  /** kW，正数为输出，负数为消耗。 */
  power?: number
  soc?: number
  windSpeed?: number
  /** LOCK：true 表示已降锁。 */
  lowered?: boolean
  /** GATE：true 表示抬杆。 */
  open?: boolean
  state?: string
}

export interface CampusHandle {
  setDevices(feeds: DeviceFeed[]): void
  focus(deviceId: number): void
  resetView(): void
  dispose(): void
  onSelect(handler: (deviceId: number | null) => void): void
}

interface Options {
  /** GLB 地址，默认 /assets/energy-campus.glb */
  modelUrl?: string
  /** 降低阴影与像素比，移动端可用 */
  quality?: 'high' | 'low'
}

interface Slot {
  model: string
  node: THREE.Object3D
  feed: DeviceFeed | null
  marker: HTMLButtonElement
  baseColor: THREE.Color | null
  screen?: ScreenSurface
}

interface ScreenSurface {
  canvas: HTMLCanvasElement
  ctx: CanvasRenderingContext2D
  texture: THREE.CanvasTexture
  signature: string
}

const NEGATIVE = /^(PV|Wind|Storage|Charger|Lock|GateIn|GateOut|CameraIn|CameraOut)$/

export async function createCampus(
  container: HTMLElement,
  options: Options = {}
): Promise<CampusHandle> {
  const modelUrl = options.modelUrl ?? '/assets/energy-campus.glb'
  const quality = options.quality ?? 'high'

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, quality === 'high' ? 2 : 1.4))
  renderer.shadowMap.enabled = quality === 'high'
  renderer.shadowMap.type = THREE.PCFSoftShadowMap
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.05
  renderer.setClearColor(0x070d15, 1)
  renderer.domElement.style.width = '100%'
  renderer.domElement.style.height = '100%'
  renderer.domElement.style.display = 'block'
  renderer.domElement.setAttribute('aria-label', '光储充放园区沙盘')
  container.appendChild(renderer.domElement)

  const scene = new THREE.Scene()
  scene.background = new THREE.Color(0x070d15)
  scene.fog = new THREE.Fog(0x070d15, 34, 78)

  const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 260)
  const focus = new THREE.Vector3(0, 0.95, 1.15)
  const direction = new THREE.Vector3(21.5, 19.6, 23.4).normalize()
  camera.position.copy(focus).addScaledVector(direction, 70)

  const controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  controls.enablePan = false
  controls.minZoom = 0.7
  controls.maxZoom = 2.6
  controls.maxPolarAngle = Math.PI * 0.46
  controls.minPolarAngle = Math.PI * 0.16
  controls.target.copy(focus)
  controls.saveState()

  const resize = () => {
    const width = container.clientWidth || 1
    const height = container.clientHeight || 1
    const aspect = width / height
    const halfHeight = Math.max(13.5, 23 / aspect)
    camera.left = -halfHeight * aspect
    camera.right = halfHeight * aspect
    camera.top = halfHeight
    camera.bottom = -halfHeight
    camera.updateProjectionMatrix()
    renderer.setSize(width, height, false)
  }
  resize()
  const observer = new ResizeObserver(resize)
  observer.observe(container)

  // ---------------------------------------------------------------- 灯光

  scene.add(new THREE.HemisphereLight(0xcfe9ff, 0x101c2a, 0.9))
  const key = new THREE.DirectionalLight(0xffffff, 2.4)
  key.position.set(12, 26, 14)
  key.castShadow = quality === 'high'
  if (key.castShadow) {
    key.shadow.mapSize.set(2048, 2048)
    key.shadow.camera.left = -26
    key.shadow.camera.right = 26
    key.shadow.camera.top = 26
    key.shadow.camera.bottom = -26
    key.shadow.bias = -0.0006
    key.shadow.normalBias = 0.03
  }
  scene.add(key)
  const fill = new THREE.DirectionalLight(0x4f7fa8, 0.7)
  fill.position.set(-16, 12, -18)
  scene.add(fill)

  // ---------------------------------------------------------------- 标签层

  const overlay = document.createElement('div')
  overlay.className = 'campus-overlay'
  container.appendChild(overlay)

  // ---------------------------------------------------------------- 载入模型

  const gltf = await new GLTFLoader().loadAsync(modelUrl)
  const campus = gltf.scene
  campus.traverse((object) => {
    if ((object as THREE.Mesh).isMesh) {
      const mesh = object as THREE.Mesh
      mesh.castShadow = true
      mesh.receiveShadow = true
      if (mesh.material) {
        const material = mesh.material as THREE.MeshStandardMaterial
        if (material.map) {
          material.map.colorSpace = THREE.SRGBColorSpace
        }
      }
    }
  })
  scene.add(campus)

  // Highlight the main vehicle only when its charger supplies real power and SOC.
  const vehicle = campus.getObjectByName('EV')
  const vehicleMaterials: {
    material: THREE.Material
    opacity: number
    transparent: boolean
    depthWrite: boolean
  }[] = []
  const battery = new THREE.Group()
  const cells: THREE.Mesh[] = []
  const socCanvas = document.createElement('canvas')
  socCanvas.width = 512
  socCanvas.height = 128
  const socTexture = new THREE.CanvasTexture(socCanvas)
  socTexture.colorSpace = THREE.SRGBColorSpace
  if (vehicle) {
    vehicle.traverse((object) => {
      const mesh = object as THREE.Mesh
      if (!mesh.isMesh) return
      const materials = (Array.isArray(mesh.material) ? mesh.material : [mesh.material]).map(
        (source) => {
          const material = source.clone()
          vehicleMaterials.push({
            material,
            opacity: material.opacity,
            transparent: material.transparent,
            depthWrite: material.depthWrite
          })
          return material
        }
      )
      mesh.material = Array.isArray(mesh.material) ? materials : materials[0]
    })
    vehicle.add(battery)
    const pack = new THREE.Mesh(
      new THREE.BoxGeometry(1.45, 0.12, 2.45),
      new THREE.MeshBasicMaterial({ color: 0x123e34 })
    )
    pack.position.y = 0.31
    battery.add(pack)
    for (let i = 0; i < 10; i++) {
      const cell = new THREE.Mesh(
        new THREE.BoxGeometry(1.26, 0.035, 0.19),
        new THREE.MeshBasicMaterial({ color: 0x63efaf })
      )
      cell.position.set(0, 0.39, -1.04 + i * 0.23)
      battery.add(cell)
      cells.push(cell)
    }
    const label = new THREE.Sprite(
      new THREE.SpriteMaterial({ map: socTexture, transparent: true, depthTest: false })
    )
    label.position.set(0, 1.9, 0)
    label.scale.set(2.8, 0.7, 1)
    label.renderOrder = 10
    battery.add(label)
    battery.visible = false
  }
  let batterySignature = ''
  const updateBattery = (feed: DeviceFeed | null) => {
    const soc = feed?.soc
    const active = Boolean(
      feed?.online &&
      Number.isFinite(soc) &&
      soc! >= 0 &&
      soc! <= 100 &&
      Number.isFinite(feed?.power) &&
      Math.abs(feed!.power!) > 0.01
    )
    const signature = active ? String(Math.round(soc!)) : 'off'
    if (signature === batterySignature) return
    batterySignature = signature
    battery.visible = active
    for (const entry of vehicleMaterials) {
      entry.material.transparent = active || entry.transparent
      entry.material.opacity = active ? Math.min(entry.opacity, 0.16) : entry.opacity
      entry.material.depthWrite = active ? false : entry.depthWrite
      entry.material.needsUpdate = true
    }
    if (!active) return
    const value = Math.round(soc!)
    cells.forEach((cell, i) =>
      (cell.material as THREE.MeshBasicMaterial).color.setHex(i < value / 10 ? 0x63efaf : 0x28584d)
    )
    const ctx = socCanvas.getContext('2d')!
    ctx.clearRect(0, 0, 512, 128)
    ctx.fillStyle = 'rgba(8,34,29,0.92)'
    ctx.beginPath()
    ctx.roundRect(2, 2, 508, 124, 22)
    ctx.fill()
    ctx.fillStyle = '#9bffcc'
    ctx.font = 'bold 58px sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText(`电池 SOC  ${value}%`, 256, 82)
    socTexture.needsUpdate = true
  }

  // ---------------------------------------------------------------- 设备槽位

  const slots: Slot[] = []
  const byModel = new Map<string, THREE.Object3D[]>()

  campus.traverse((object) => {
    if (!NEGATIVE.test(object.name)) return
    const base = object.name.replace(/\d+$/, '')
    const list = byModel.get(base) ?? []
    list.push(object)
    byModel.set(base, list)
  })

  const colorOf = (node: THREE.Object3D): THREE.Color | null => {
    let found: THREE.Color | null = null
    node.traverse((child) => {
      const mesh = child as THREE.Mesh
      if (!found && mesh.isMesh && mesh.material) {
        const material = mesh.material as THREE.MeshStandardMaterial
        if (material.color) {
          found = material.color.clone()
        }
      }
    })
    return found
  }

  const markerFor = (model: string) => {
    const button = document.createElement('button')
    button.className = 'campus-marker'
    button.dataset.model = model
    button.type = 'button'
    button.addEventListener('click', () => select(model))
    overlay.appendChild(button)
    return button
  }

  let selected: string | null = null
  const selectionHandlers: ((deviceId: number | null) => void)[] = []

  // 屏幕贴图：充电桩屏与道闸屏
  const screenSurface = (width: number, height: number): ScreenSurface => {
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const ctx = canvas.getContext('2d')!
    const texture = new THREE.CanvasTexture(canvas)
    texture.colorSpace = THREE.SRGBColorSpace
    texture.anisotropy = 4
    return { canvas, ctx, texture, signature: '' }
  }

  const findScreen = (node: THREE.Object3D, suffix: string) => {
    let target: THREE.Mesh | null = null
    node.traverse((child) => {
      const mesh = child as THREE.Mesh
      if (!target && mesh.isMesh && mesh.name.toLowerCase().endsWith(suffix)) {
        target = mesh
      }
    })
    return target as THREE.Mesh | null
  }

  const attachScreen = (node: THREE.Object3D, suffix: string, width: number, height: number) => {
    const mesh = findScreen(node, suffix)
    if (!mesh) return undefined
    const surface = screenSurface(width, height)
    const material = new THREE.MeshBasicMaterial({ map: surface.texture, toneMapped: false })
    mesh.material = material
    return surface
  }

  for (const [base, nodes] of byModel) {
    nodes.forEach((node, index) => {
      const slot: Slot = {
        model: index === 0 ? base : `${base}#${index}`,
        node,
        feed: null,
        marker: markerFor(index === 0 ? base : `${base}#${index}`),
        baseColor: colorOf(node)
      }
      if (base === 'Charger') {
        slot.screen = attachScreen(node, 'screen', 256, 300)
      }
      slots.push(slot)
    })
  }

  // 道闸屏单独挂
  const display = campus.getObjectByName('GateDisplay')
  const gateScreen = display ? attachScreen(display, 'screen', 256, 112) : undefined

  // ---------------------------------------------------------------- 拾取

  const raycaster = new THREE.Raycaster()
  const pointer = new THREE.Vector2()
  const pointerStart = new THREE.Vector2()
  let dragging = false

  const onPointerDown = (event: PointerEvent) => {
    pointerStart.set(event.clientX, event.clientY)
    dragging = false
  }
  const onPointerMove = (event: PointerEvent) => {
    if (pointerStart.distanceTo(new THREE.Vector2(event.clientX, event.clientY)) > 5) {
      dragging = true
    }
  }
  const onPointerUp = (event: PointerEvent) => {
    if (dragging) return
    const rect = renderer.domElement.getBoundingClientRect()
    pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
    pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1
    raycaster.setFromCamera(pointer, camera)
    const nodes = slots.map((slot) => slot.node)
    const hit = raycaster.intersectObjects(nodes, true)[0]?.object as THREE.Object3D | undefined
    let node: THREE.Object3D | null = hit ?? null
    while (node && !nodes.includes(node)) {
      node = node.parent
    }
    const slot = node ? slots.find((item) => item.node === node) : undefined
    select(slot ? slot.model : null)
  }

  renderer.domElement.addEventListener('pointerdown', onPointerDown)
  renderer.domElement.addEventListener('pointermove', onPointerMove)
  renderer.domElement.addEventListener('pointerup', onPointerUp)

  const select = (model: string | null) => {
    selected = model
    const deviceId = slots.find((slot) => slot.model === model)?.feed?.deviceId ?? null
    selectionHandlers.forEach((handler) => handler(deviceId))
  }

  // ---------------------------------------------------------------- 状态映射

  const rotor = campus.getObjectByName('WindRotor')
  const gateInBar = campus.getObjectByName('GateIn_Bar')
  const gateOutBar = campus.getObjectByName('GateOut_Bar')
  const smooth = new Map<string, number>()
  const approach = (key: string, target: number, dt: number, rate = 6) => {
    const current = smooth.get(key) ?? target
    const next = current + (target - current) * Math.min(1, dt * rate)
    smooth.set(key, next)
    return next
  }

  const tint = (slot: Slot, feed: DeviceFeed | null) => {
    const accent = !feed ? null : feed.alarm ? 0xff5d5d : feed.online ? 0x38d39f : 0x3c5670
    slot.node.traverse((child) => {
      const mesh = child as THREE.Mesh
      if (!mesh.isMesh || !mesh.material) return
      const material = mesh.material as THREE.MeshStandardMaterial
      if (!material.emissive) return
      if (accent === null) {
        material.emissive.setHex(0x000000)
        material.emissiveIntensity = 0
      } else {
        material.emissive.setHex(accent)
        material.emissiveIntensity = feed?.alarm ? 0.7 : feed?.online ? 0.28 : 0.12
      }
    })
  }

  const drawChargerScreen = (surface: ScreenSurface, feed: DeviceFeed | null) => {
    const signature = feed ? `${feed.label}|${feed.lines.join('|')}|${feed.state ?? ''}` : 'empty'
    if (surface.signature === signature) return
    surface.signature = signature
    const { ctx, canvas } = surface
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    ctx.fillStyle = '#07131f'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.fillStyle = feed?.alarm ? '#ff5d5d' : '#68d49a'
    ctx.font = '600 22px "Segoe UI", system-ui'
    ctx.fillText(feed?.label?.slice(0, 9) ?? '充电桩', 18, 44)
    ctx.font = '600 46px "Segoe UI", system-ui'
    ctx.fillText(feed?.lines[0]?.slice(0, 8) ?? '待机', 18, 118)
    ctx.font = '500 24px "Segoe UI", system-ui'
    ctx.fillStyle = '#9fb4cb'
    ctx.fillText(feed?.lines[1]?.slice(0, 12) ?? '', 18, 164)
    ctx.fillStyle = feed?.online ? '#38d39f' : '#3c5670'
    ctx.beginPath()
    ctx.arc(30, 240, 9, 0, Math.PI * 2)
    ctx.fill()
    ctx.font = '500 20px "Segoe UI", system-ui'
    ctx.fillStyle = '#9fb4cb'
    ctx.fillText(feed?.state ?? (feed?.online ? '在线' : '离线'), 48, 247)
    surface.texture.needsUpdate = true
  }

  const drawGateScreen = (surface: ScreenSurface | undefined, feed: DeviceFeed | null) => {
    if (!surface) return
    const signature = feed ? `${feed.label}|${feed.lines.join('|')}` : 'empty'
    if (surface.signature === signature) return
    surface.signature = signature
    const { ctx, canvas } = surface
    ctx.fillStyle = '#061019'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.fillStyle = '#8df0b6'
    ctx.font = '600 28px "Segoe UI", system-ui'
    ctx.fillText(feed?.label ?? '道闸', 16, 44)
    ctx.fillStyle = '#9fb4cb'
    ctx.font = '500 22px "Segoe UI", system-ui'
    ctx.fillText(feed?.lines.join(' · ') ?? '', 16, 88)
    surface.texture.needsUpdate = true
  }

  // ---------------------------------------------------------------- 帧循环

  const projected = new THREE.Vector3()
  let last = performance.now()
  let disposed = false

  const tick = (now: number) => {
    if (disposed) return
    const dt = Math.min(0.08, Math.max(0, (now - last) / 1000))
    last = now

    const wind = slots.find((slot) => slot.model.startsWith('Wind'))?.feed
    if (rotor) {
      const speed = wind?.online && !wind.alarm ? (wind.windSpeed ?? 0) : 0
      rotor.rotation.z += dt * speed * 0.32
    }

    const gateIn = slots.find((slot) => slot.model.startsWith('GateIn'))?.feed
    const gateOut = slots.find((slot) => slot.model.startsWith('GateOut'))?.feed
    if (gateInBar) gateInBar.rotation.x = approach('gateIn', gateIn?.open ? -1.38 : 0, dt)
    if (gateOutBar) gateOutBar.rotation.x = approach('gateOut', gateOut?.open ? 1.38 : 0, dt)

    slots
      .filter((slot) => slot.model.startsWith('Lock'))
      .forEach((slot, index) => {
        let arm: THREE.Object3D | null = null
        slot.node.traverse((child) => {
          if (!arm && child.name.startsWith('Lock_Arm')) arm = child
        })
        if (arm) {
          ;(arm as THREE.Object3D).rotation.x = approach(
            `lock${index}`,
            slot.feed?.lowered ? 0 : -1.25,
            dt,
            4
          )
        }
      })

    // Cameras retain the model pose until actual PTZ telemetry is available.

    for (const slot of slots) {
      if (slot.screen) drawChargerScreen(slot.screen, slot.feed)
    }
    drawGateScreen(gateScreen, gateIn ?? gateOut ?? null)
    updateBattery(slots.find((slot) => slot.node.name === 'Charger')?.feed ?? null)

    controls.update()
    renderer.render(scene, camera)

    // 标签投影
    const width = container.clientWidth
    const height = container.clientHeight
    for (const slot of slots) {
      const anchor = slot.node.getWorldPosition(projected.clone())
      anchor.y += 2.4
      projected.copy(anchor).project(camera)
      const x = (projected.x * 0.5 + 0.5) * width
      const y = (-projected.y * 0.5 + 0.5) * height
      const marker = slot.marker
      marker.hidden =
        (battery.visible && slot.model.startsWith('Lock')) ||
        projected.z > 1 ||
        x < 40 ||
        x > width - 40 ||
        y < 40 ||
        y > height - 20
      marker.style.transform = `translate(${x}px, ${y}px) translate(-50%, -100%)`
      marker.classList.toggle('alarm', Boolean(slot.feed?.alarm))
      marker.classList.toggle('offline', Boolean(slot.feed && !slot.feed.online))
      marker.classList.toggle('active', selected === slot.model)
      const text = slot.feed ? [slot.feed.label, ...slot.feed.lines].join(' · ') : slot.model
      if (marker.textContent !== text) {
        marker.textContent = text
      }
    }
  }

  renderer.setAnimationLoop(tick)

  // ---------------------------------------------------------------- 对外句柄

  const applyFeeds = (feeds: DeviceFeed[]) => {
    const used = new Map<string, number>()
    for (const slot of slots) {
      slot.feed = null
    }
    for (const feed of feeds) {
      const base = feed.model.replace(/\d+$/, '')
      const index = used.get(base) ?? 0
      used.set(base, index + 1)
      const key = index === 0 ? base : `${base}#${index}`
      const slot = slots.find((item) => item.model === key)
      if (!slot) continue
      slot.feed = feed
    }
    for (const slot of slots) {
      tint(slot, slot.feed)
      slot.marker.style.display = ''
    }
  }

  const dispose = () => {
    disposed = true
    renderer.setAnimationLoop(null)
    renderer.domElement.removeEventListener('pointerdown', onPointerDown)
    renderer.domElement.removeEventListener('pointermove', onPointerMove)
    renderer.domElement.removeEventListener('pointerup', onPointerUp)
    observer.disconnect()
    controls.dispose()
    scene.traverse((object) => {
      const mesh = object as THREE.Mesh
      if (mesh.isMesh) {
        mesh.geometry?.dispose()
        const material = mesh.material as THREE.Material | THREE.Material[]
        if (Array.isArray(material)) material.forEach((item) => item.dispose())
        else material?.dispose()
      }
    })
    socTexture.dispose()
    renderer.dispose()
    if (renderer.domElement.parentElement) {
      renderer.domElement.parentElement.removeChild(renderer.domElement)
    }
    overlay.remove()
  }

  return {
    setDevices: applyFeeds,
    focus: (deviceId: number) => {
      const slot = slots.find((item) => item.feed?.deviceId === deviceId)
      if (!slot) return
      select(slot.model)
      const position = slot.node.getWorldPosition(new THREE.Vector3())
      controls.target.set(position.x, Math.max(0.6, position.y), position.z)
      controls.update()
    },
    resetView: () => {
      controls.reset()
      controls.target.copy(focus)
      controls.update()
    },
    dispose,
    onSelect: (handler) => {
      selectionHandlers.push(handler)
    }
  }
}
