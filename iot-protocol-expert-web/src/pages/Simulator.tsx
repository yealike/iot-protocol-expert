import { useEffect, useRef } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import markup from '../../public/simulator/index.html?raw'
import controller from '../../public/simulator/app.js?raw'
import './simulator-legacy.css'
import './simulator.css'

const actions = [
  '$',
  'post',
  'selectDevice',
  'startFleet',
  'stopFleet',
  'fleetAction',
  'chooseGun',
  'gunAction',
  'remote',
  'setSpeed',
  'fillRate',
  'loadPlatformRate',
  'saveRate',
  'applyClock',
  'crossPeriod',
  'refreshConfig',
  'simulateUpgrade',
  'renderLogs',
  'renderSegments',
  'refresh',
  'loadCatalog',
  'showCatalog',
  'decodeWire',
  'detail'
]
const pages = [
  { key: 'home', to: '/simulator', label: '3D 模拟器' },
  { key: 'workbench', to: '/simulator/workbench', label: '仿真工作台' },
  { key: 'billing', to: '/simulator/billing', label: '计费明细' },
  { key: 'rates', to: '/rates', label: '费率管理' },
  { key: 'configuration', to: '/simulator/configuration', label: '设备参数' },
  { key: 'messages', to: '/simulator/messages', label: '协议报文' }
]
const pageKeys = new Set(pages.map((page) => page.key))

export default function Simulator() {
  const host = useRef<HTMLDivElement>(null)
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const route = pathname.split('/')[2] || 'home'
  const activePage = pageKeys.has(route) ? route : 'home'

  useEffect(() => {
    if (pathname === '/simulator/tariff') navigate('/rates', { replace: true })
  }, [navigate, pathname])

  useEffect(() => {
    const container = host.current
    if (!container) return
    const parsed = new DOMParser().parseFromString(markup, 'text/html')
    const main = parsed.querySelector('main')!
    const labGrid = main.querySelector('.lab-grid')!
    labGrid.append(main.querySelector('.fleet-panel')!)

    const quickControls = document.createElement('div')
    quickControls.className = 'simulator-quick-controls'
    quickControls.innerHTML =
      '<div class="simulator-quick-title"><strong>充电操作</strong><span>选择充电枪并控制当前订单</span></div><div class="simulator-quick-actions"><button class="primary" onclick="gunAction(\'plug\')">插入充电枪</button><button onclick="remote(\'start\')">启动充电</button><button class="danger" onclick="gunAction(\'stop\')">停止充电</button><button class="quiet" onclick="gunAction(\'unplug\')">拔出充电枪</button></div>'
    quickControls.append(main.querySelector('#gunTabs')!)
    const homeSidebar = document.createElement('aside')
    homeSidebar.className = 'simulator-home-sidebar'
    homeSidebar.setAttribute('aria-label', '充电操作与实时状态')
    homeSidebar.append(quickControls, main.querySelector('.metrics')!)
    const energyPanel = document.createElement('section')
    energyPanel.className = 'panel twin-panel simulator-energy-panel'
    energyPanel.dataset.presentation = 'studio'
    energyPanel.append(main.querySelector('.twin-energy')!, main.querySelector('.twin-caption')!)

    const addPage = (key: string, nodes: (Element | null)[]) => {
      const page = document.createElement('div')
      page.className = 'simulator-page'
      page.dataset.simulatorPage = key
      nodes.forEach((node) => {
        if (node) page.append(node)
      })
      main.append(page)
    }
    addPage('home', [main.querySelector('.twin-panel'), homeSidebar])
    addPage('workbench', [energyPanel, labGrid])
    addPage('billing', [
      main.querySelector('#billing'),
      main.querySelector('#history')?.closest('section') ?? null
    ])
    // 保留原模拟器脚本需要的 DOM，但不再作为可见费率编辑入口。
    addPage('legacy-tariff', [main.querySelector('#tariff')])
    addPage('configuration', [main.querySelector('#configuration')])
    addPage('messages', [main.querySelector('#messages')])
    const footer = main.querySelector('footer')
    if (footer) main.append(footer)
    main.insertAdjacentHTML(
      'beforeend',
      '<span id="sideProtocol" hidden></span><span id="sideTransport" hidden></span>'
    )
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
    const scopedController = controller.replace(
      'document.getElementById(id)',
      "root.querySelector('#' + CSS.escape(id))"
    )
    const run = new Function(
      'setInterval',
      'root',
      `${scopedController}\n${expose}\nfor (const [key, get, set] of [['rateDraft', () => rateDraft, value => { rateDraft = value }], ['rateDirty', () => rateDirty, value => { rateDirty = value }], ['live', () => live, value => { live = value }], ['logs', () => logs, value => { logs = value }], ['selectedGun', () => selectedGun, value => { selectedGun = value }]]) Object.defineProperty(window, key, { configurable: true, get, set });`
    )
    run(interval, root)

    let disposeTwin: (() => void) | undefined
    let disposed = false
    import('../simulator/twin.js')
      .then((module) => {
        if (!disposed) disposeTwin = module.mountTwin(container)
      })
      .catch(() => {
        const message = container.querySelector('#twin-message')
        if (message) message.textContent = '三维场景加载失败，请检查浏览器 WebGL 支持'
      })
    return () => {
      disposed = true
      timers.forEach(window.clearInterval)
      disposeTwin?.()
      actions.forEach((name) => {
        delete (window as unknown as Record<string, unknown>)[name]
      })
      ;['rateDraft', 'rateDirty', 'live', 'logs', 'selectedGun'].forEach((name) => {
        delete (window as unknown as Record<string, unknown>)[name]
      })
      container.replaceChildren()
    }
  }, [])

  useEffect(() => {
    const container = host.current
    if (!container) return
    container.dataset.activePage = activePage
    container.querySelectorAll<HTMLElement>('[data-simulator-page]').forEach((page) => {
      page.hidden = page.dataset.simulatorPage !== activePage
    })
  }, [activePage])

  return (
    <div className="simulator-shell">
      <nav className="simulator-section-nav" aria-label="模拟器页面导航">
        {pages.map((page) => (
          <NavLink
            key={page.key}
            to={page.to}
            end={page.key === 'home'}
            className={({ isActive }) => (isActive ? 'active' : undefined)}
          >
            {page.label}
          </NavLink>
        ))}
      </nav>
      <div ref={host} className="simulator-workspace" />
    </div>
  )
}
