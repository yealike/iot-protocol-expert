import { useEffect, useRef } from 'react'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

/**
 * ECharts 按需引入。
 *
 * 直接 import 'echarts' 会把全部图表类型与渲染器打进包（约 1 MB）。
 * 这里只注册项目用到的两种图，gzip 后只剩三分之一。
 */
echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

interface Props {
  option: Record<string, unknown>
  height: number
}

export default function Chart({ option, height }: Props) {
  const holder = useRef<HTMLDivElement>(null)
  const instance = useRef<echarts.ECharts | null>(null)

  useEffect(() => {
    if (!holder.current) return
    const chart = echarts.init(holder.current, undefined, { renderer: 'canvas' })
    instance.current = chart
    const observer = new ResizeObserver(() => chart.resize())
    observer.observe(holder.current)
    return () => {
      observer.disconnect()
      chart.dispose()
      instance.current = null
    }
  }, [])

  useEffect(() => {
    instance.current?.setOption(option, true)
  }, [option])

  return <div ref={holder} style={{ width: '100%', height }} />
}
