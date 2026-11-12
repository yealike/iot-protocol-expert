import { useEffect, useRef } from 'react'
import markup from '../../public/simulator/index.html?raw'
import controller from '../../public/simulator/app.js?raw'
import './simulator-legacy.css'
import './simulator.css'

// The simulator controller is mounted with the page and disposed on navigation.
const actions = ['$', 'post', 'selectDevice', 'startFleet', 'stopFleet', 'fleetAction', 'chooseGun', 'gunAction', 'remote', 'setSpeed', 'fillRate', 'loadPlatformRate', 'saveRate', 'applyClock', 'crossPeriod', 'refreshConfig', 'simulateUpgrade', 'renderLogs', 'refresh', 'loadCatalog', 'showCatalog', 'decodeWire', 'detail']

export default function Simulator() {
  const host = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const container = host.current
    if (!container) return
    const parsed = new DOMParser().parseFromString(markup, 'text/html')
    const main = parsed.querySelector('main')!
    const twin = main.querySelector('.twin-panel')!
    twin.after(main.querySelector('.lab-grid')!)
    main.querySelector('header')?.insertAdjacentHTML('afterend', '<nav class="simulator-section-nav" aria-label="模拟器页面导航"><a href="#lab">仿真工作台</a><a href="#billing">计费明细</a><a href="#tariff">费率模型</a><a href="#configuration">设备参数</a><a href="#messages">协议报文</a></nav>')
    main.insertAdjacentHTML('beforeend', '<span id="sideProtocol" hidden></span><span id="sideTransport" hidden></span>')
    const root = document.createElement('div')
    root.append(main, ...Array.from(parsed.querySelectorAll('#toast, #detailDialog')))
    container.replaceChildren(root)

    const timers: number[] = []
    const interval = (callback: TimerHandler, delay?: number) => {
      const id = window.setInterval(callback, delay)
      timers.push(id)
      return id
    }
    const expose = actions.map((name) => `window[${JSON.stringify(name)}] = ${name};`).join('\n')
    const scopedController = controller.replace('document.getElementById(id)', "root.querySelector('#' + CSS.escape(id))")
    const run = new Function('setInterval', 'root', `${scopedController}\n${expose}\nfor (const [key, get, set] of [['rateDraft', () => rateDraft, value => { rateDraft = value }], ['rateDirty', () => rateDirty, value => { rateDirty = value }], ['live', () => live, value => { live = value }], ['logs', () => logs, value => { logs = value }]]) Object.defineProperty(window, key, { configurable: true, get, set });`)
    run(interval, root)

    let disposeTwin: (() => void) | undefined
    let disposed = false
    const twinModule = '/simulator/twin.js'
    import(/* @vite-ignore */ twinModule).then((module) => {
      if (!disposed) disposeTwin = module.mountTwin()
    }).catch(() => {
      const message = container.querySelector('#twin-message')
      if (message) message.textContent = '三维场景加载失败，请检查浏览器 WebGL 支持'
    })
    return () => {
      disposed = true
      timers.forEach(window.clearInterval)
      disposeTwin?.()
      actions.forEach((name) => { delete (window as unknown as Record<string, unknown>)[name] })
      ;['rateDraft', 'rateDirty', 'live', 'logs'].forEach((name) => { delete (window as unknown as Record<string, unknown>)[name] })
      container.replaceChildren()
    }
  }, [])

  return <div ref={host} className="simulator-workspace" />
}
