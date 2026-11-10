# 3D落地方案

## 本项目采用Three.js

现有前端是Spring Boot静态HTML/JS，直接使用Three.js无需引入React、构建服务或独立3D后端。当前固定版本0.180.0，核心和OrbitControls均置于 `iot-protocol-expert-simulator/src/main/resources/static/vendor/three/`，MIT许可文件一起保留。

当前视觉实现及模型许可详见 [3D视觉升级](3d-visual-upgrade.md)。

场景实现：`iot-protocol-expert-simulator/src/main/resources/static/scene.js`。

- 充电柜、触摸屏、按钮、充电口、停车位、线缆和分组电芯由代码生成；整车使用本地化、已署名的Car Concept外观几何。
- 订阅 `simulator-state` 事件，按当前枪的插枪、SOC、充电、故障状态更新场景；不在3D代码中计算账单。
- 线缆使用CatmullRomCurve3 + TubeGeometry；插拔枪缓动约3.6秒。电网→充电桩→线缆→底盘电池按方向显示能流，仅在实际有电压、电流且连接器就位时启用。
- OrbitControls支持鼠标/触控旋转和缩放；设备像素比限制1.8，动画上限约30fps，页面隐藏或场景滚出视野后暂停渲染。电芯使用InstancedMesh批量渲染，阴影按需更新。
- 无需网络模型资源，避免展示时CDN或模型服务不可用。

## 可选方向

| 方案 | 适用情形 | 对本项目的判断 |
|---|---|---|
| Three.js + 原生JavaScript | 独立可视化、已有静态页面 | 当前直接采用 |
| React Three Fiber | 已有React前端，需要组件化场景 | 将来迁移React时再考虑；它是Three.js的React渲染器 |
| Babylon.js | 更完整的场景/游戏引擎体系 | 可作为重建复杂园区场景时的备选；本次不额外引入第二个引擎 |

## 换成真实外观GLB

有正式授权的车型/充电桩资产后，可以使用Three.js官方GLTFLoader载入glTF 2.0/GLB，替换 `car` / `charger` 的子网格，继续保留组的位置、线缆连接点和状态事件。引入时需要把GLTFLoader及相关依赖同版本本地化；当前已打包0.180.0版本GLTFLoader和BufferGeometryUtils，并使用本地neutral-ev.glb。

建议模型以米为单位，单车控制面数与贴图体积，保留充电口节点；厂商Logo与车型资产另确认资产本身的使用许可。当前Car Concept几何采用CC BY 4.0，署名、修改说明、完整许可和资产SHA256已保存在static/assets/car-concept中。加载失败时保留简化几何回退。

## 官方资料

- Three.js及MIT许可：<https://github.com/mrdoob/three.js/blob/dev/LICENSE>
- OrbitControls：<https://threejs.org/docs/pages/OrbitControls.html>
- GLTFLoader：<https://threejs.org/docs/pages/GLTFLoader.html>
- React Three Fiber：<https://r3f.docs.pmnd.rs/getting-started/introduction>
- Babylon.js许可：<https://github.com/BabylonJS/Babylon.js/blob/master/license.md>
