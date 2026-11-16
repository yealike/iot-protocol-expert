# 桩车外观参考与确定参数

本目录是 3D 场景的外观依据，不能用通用小汽车替换 Model Y。

最新结构、布光与充电透视改进见 [实时展示修订](rendering-notes.md)。

## Tesla Model Y（经典款 2020–2024 / 资产为 2021 款）

- 官方尺寸：[Tesla 2020–2024 Model Y owner's manual](https://www.tesla.com/ownersmanual/2020_2024_modely/en_us/GUID-E47C4A6D-528E-419C-8C57-FD3864644C34.html)。长 4751 mm、车身宽 1921 mm、高 1624 mm、轴距 2890 mm；含后视镜宽度 2129 mm。当前资产按车身宽、总长、总高校准，后视镜沿用模型比例，未单独拉伸。
- 外观：白色车漆、独立水滴形前灯、无格栅前脸、溜背玻璃车顶、黑色窗框、隐藏门把手、黑色轮眉和侧裙。不是 2025 Juniper 贯穿灯车型。
- 充电口：车辆左后方尾灯区域；沿侧面向内插入。端口定位见 `model-manifest.json`。
- 实车后侧照片：`tesla-model-y-classic-rear.webp`，来源 [2024 Model Y 页面](https://ksa.yallamotor.com/new-cars/tesla/model-y/2024)。只作为本地外观参考，不在产品页面发布。
- 作者预览：`tesla-model-y-author-reference.jpg`，通过作者模型的 [Sketchfab metadata](https://api.sketchfab.com/v3/models/59e2ead369984b1a85c800ff6cf6789d) 中 thumbnails 保存。
- 原始三维资产：`tesla-model-y-source.glb`，作者 **tonielpro520**，作品 [2021 Tesla Model Y](https://sketchfab.com/3d-models/2021-tesla-model-y-59e2ead369984b1a85c800ff6cf6789d)，**[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)**；作者署名、商业使用与改编许可由 Sketchfab API 元数据及 GLB 内嵌 `asset.extras` 交叉核实，保存为 `tesla-model-y-license.json`。
- 公开文件镜像：[AmericaWant GLB](https://shop.americawant.com/wp-content/uploads/2026/08/tesla-model-y.glb)，[镜像署名页](https://shop.americawant.com/atribuciones/)。未使用该站点程序代码。
- 改动：解压 Draco，坐标归一化，按官方尺寸校准，白色清漆与玻璃等 PBR 材质调整，添加独立充电口与可动盖板。车身、车窗、轮胎、轮毂、内饰、灯组使用原有真实车型网格。

## 星星充电 双子座120kW直流一体充电桩 V3.2

- [官方产品页](https://cn.starcharge.com/products/ev-charging/all-in-one-charger/207)。
- 官方正面产品图：`starcharge-gemini-120kw.png`，[原图](https://cn.starcharge.com/Public/Uploads/uploadfile/images/20261014/Rectangle346243135-38.png)。
- 官方规格书：`starcharge-gemini-120kw.pdf`，[原文件](https://cn.starcharge.com/Public/Uploads/uploadfile/files/20261014/shuangzizuo120kWzhiliuyitichongdianzhuang.pdf)。
- 规格书尺寸 **W850 × D550 × H1550 mm**；120 kW；200–1000 V；额定 250 A / Boost 300 A；枪线 5 m（标配）。这些额定参数只定义外观与设备型号，不改动模拟器后端运行参数。
- 正面比例来自官方产品图：白色方柜、灰底座、中央窄黑面板、横向小 LCD、并列双绿灯、读卡器、二维码面板、前置双枪座、顶部橙色品牌符号及底部竖排“快充300A”。
- 柜体网格为项目自行建模，不是官方 CAD。侧面与背面未获官方工程图，散热孔、检修门等为按产品结构制作的近似细节。
- 图片/PDF 只保存为外观参考，不作为应用纹理或重新发布的营销素材；运行时使用自建几何、文字与材质。

## 生成与查看

从前端目录执行：

```powershell
& D:/tools/blender/blender.exe --background --python-exit-code 1 --python tools/build-simulator-model.py
npm run build
```

输出 `tools/simulator-model.blend` 与 `public/simulator/simulator-model.glb`。
开发服务中打开 `/tools/preview-simulator.html` 可用固定状态验收，页面不请求模拟器控制 API。
本次使用网络实物照片、官方规格与有署名许可的模型；未使用 AI 生成的虚构外观模板。
