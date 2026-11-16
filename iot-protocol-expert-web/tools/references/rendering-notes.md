# 实物结构与实时展示修订

## 参考依据

1. [Tesla 经典款 Model Y 官方充电说明](https://www.tesla.cn/ownersmanual/2020_2024_modely/zh_cn/GUID-BEE08D47-0CE0-4BDD-83F2-9854FB3D578F.html)：充电口位于左后尾灯总成盖板后；插枪前开盖，拔出连接器后自动关盖。停止供电而枪仍插着时，盖板保持打开。不同地区/年份的端子略有不同，当前插座为可视化示意。
2. [Khronos Real-time Asset Creation Guidelines 2.0](https://github.com/KhronosGroup/3DC-Asset-Creation/blob/main/asset-creation-guidelines/RealtimeAssetCreationGuidelines.md)：参考其中实际尺寸、PBR 材质、灯光、动画与交付优化的实践。
3. [Epic 汽车影棚布光指南](https://www.unrealengine.com/tech-blog/how-to-set-up-automotive-studio-lighting-in-unreal-engine?lang=en-US)：简洁环境、可控曝光、大面积灯卡反射、适当粗糙度的地面。
4. [Epic Automotive Materials Pack 文档](https://dev.epicgames.com/documentation/en-us/unreal-engine/automotive-materials-pack-in-unreal-engine)：采用物理材质的金属/非金属区分与粗糙度控制。

这是针对 WebGL 浏览器的适度改进，未移植 Unreal 程序/材质包，也不代表获得上述机构的认证。

## 实施

- 背景改为浅灰汽车展厅式连续地面；移除悬浮圆盘。保留真实尺度停车位、限位块、柔光环境反射、轮胎与柜体接触阴影。
- 车漆和喷粉柜体按非金属表面处理，车漆保留清漆层；轮毂等裸露金属使用金属材质。采用 sRGB 输出、ACES 色调映射和固定曝光。
- 沿原网格的曲面和 UV 分离 4 个左侧尾灯盖板部件，关闭位置不额外外移；盖板透明灯罩增加不透明背衬，插座内缩。模型生成会断言盖板关闭位置与原曲面偏差小于 0.01 mm，实测值写入 `model-manifest.json`。
- 动作分为开盖、从枪座直线拔出、移枪并转向、沿插座轴线插入。拔枪时逆序执行，枪完全退出后才关盖。停充时恢复车身外观，枪和盖板仍遵循插拔状态。
- 桩体与汽车保持同一纵向方向，不附加斜转角。两根枪线采用 Ammo.js/Bullet 软体绳索，考虑固定长度、重力、摩擦和刚体碰撞；正常余线贴地，插拔会拖动和回落。详见 [枪线物理方案](cable-physics-notes.md)。
- 充电到位后默认自动渐变虚化车身与座舱，保留车轮，显示有真实深度遮挡的底盘电池框、16 个模块、车轴及高压线束；显示移动能流、SOC 浮标、功率和进度。结束供电后平滑恢复。可通过“充电自动透视”关闭该效果。
- 电池框、模块与车轴为可视化示意，不是 Tesla 电池包工程 CAD。SOC、功率及状态使用当前所选枪的数据，不推算或改变后端业务状态。
- 保留 Draco 压缩和本地解码器，按浏览器像素比上限 2 渲染；枪线复用动态顶点缓冲，纹理只在显示数据变化时更新，卸载时释放渲染和物理资源。

## 验证

运行 `node --test tools/cable-physics.test.mjs tools/twin-geometry.test.mjs`：验证实际软体节点和管状蒙皮的落地、长度、惯性、碰撞与网格缓冲复用；并检查枪离座前盖板已打开、连接器归位后才闭合。

`/tools/preview-simulator.html` 使用固定测试状态，提供待插枪、插枪、充电、停止、拔枪、故障、离线充电、切枪及 SOC 滑块，供视觉验收，不发送设备控制请求。
