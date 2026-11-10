from pathlib import Path
from urllib.request import urlopen
import json,struct,copy,hashlib
base=Path('iot-protocol-expert-simulator/src/main/resources/static/assets/car-concept');base.mkdir(parents=True,exist_ok=True)
data=urlopen('https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/CarConcept/glTF-Binary/CarConcept.glb',timeout=60).read();size=struct.unpack_from('<I',data,12)[0];doc=json.loads(data[20:20+size]);oldbin=data[28+size:]
for n in doc['nodes']:
    name=n.get('name','')
    if name.startswith('Interior') or name in ['Engine','License Plate'] or 'Emblem' in name:n.pop('mesh',None)
# Retain only visible exterior meshes and their position/normal/index accessors.
mesh_ids=sorted({n['mesh'] for n in doc['nodes'] if 'mesh' in n});mesh_map={old:i for i,old in enumerate(mesh_ids)}
meshes=[doc['meshes'][i] for i in mesh_ids]
for n in doc['nodes']:
    if 'mesh' in n:n['mesh']=mesh_map[n['mesh']]
accessor_ids=set()
for m in meshes:
    for p in m['primitives']:
        p['attributes']={k:v for k,v in p['attributes'].items() if k in ['POSITION','NORMAL']}
        p.pop('extensions',None);accessor_ids.update(p['attributes'].values());accessor_ids.add(p['indices'])
accessor_ids=sorted(accessor_ids);amap={old:i for i,old in enumerate(accessor_ids)};accessors=[doc['accessors'][i] for i in accessor_ids]
for m in meshes:
    for p in m['primitives']:
        p['indices']=amap[p['indices']];p['attributes']={k:amap[v] for k,v in p['attributes'].items()}
view_ids=sorted({a['bufferView'] for a in accessors});vmap={old:i for i,old in enumerate(view_ids)};views=[];binary=bytearray()
for i in view_ids:
    v=copy.deepcopy(doc['bufferViews'][i]);begin=v.get('byteOffset',0);raw=oldbin[begin:begin+v['byteLength']]
    while len(binary)%4:binary.append(0)
    v['buffer']=0;v['byteOffset']=len(binary);binary.extend(raw);views.append(v)
for a in accessors:a['bufferView']=vmap[a['bufferView']]
for m in doc['materials']:
    name=m.get('name','Neutral');m.clear();m['name']=name
    color=[.14,.20,.25,1];metal=.5;rough=.4
    if name.startswith('Paint 1'):color=[.50,.66,.77,1];metal=.6;rough=.24;m['extensions']={'KHR_materials_clearcoat':{'clearcoatFactor':1,'clearcoatRoughnessFactor':.18}}
    elif name.startswith('Paint 2'):color=[.04,.065,.085,1];metal=.6;rough=.3
    elif name=='Glass':color=[.015,.035,.055,.88];metal=.28;rough=.12;m['alphaMode']='BLEND';m['doubleSided']=True
    elif name in ['Tireside','Tiretread']:color=[.012,.016,.02,1];metal=0;rough=.85
    elif name.startswith('Rim') or name in ['Disc','Mirror']:color=[.55,.65,.72,1];metal=.9;rough=.2
    elif name=='Headlight':color=[.75,.93,1,1];m['emissiveFactor']=[.55,.8,1]
    elif name=='Brakelight':color=[.55,.015,.035,1];m['emissiveFactor']=[.5,.01,.015]
    elif name=='Signallight':color=[.8,.32,.035,1]
    m['pbrMetallicRoughness']={'baseColorFactor':color,'metallicFactor':metal,'roughnessFactor':rough}
doc['asset']={'version':'2.0','copyright':'Car Concept geometry: Eric Chadwick / Darmstadt Graphics Group GmbH, 2024, CC BY 4.0. Derived neutral EV visualization: exterior only; logos, original textures and combustion engine removed; custom materials and battery pack added. Source and license: see ATTRIBUTION.md.','generator':'iot-protocol-expert offline model preparation'}
doc['meshes']=meshes;doc['accessors']=accessors;doc['bufferViews']=views;doc['buffers']=[{'byteLength':len(binary)}]
for key in ['images','textures','samplers','animations','extensions','extensionsRequired']:doc.pop(key,None)
doc['extensionsUsed']=['KHR_materials_clearcoat']
js=json.dumps(doc,separators=(',',':')).encode();js+=b' '*((-len(js))%4);binary+=b'\0'*((-len(binary))%4)
out=struct.pack('<III',0x46546c67,2,12+8+len(js)+8+len(binary))+struct.pack('<II',len(js),0x4e4f534a)+js+struct.pack('<II',len(binary),0x004e4942)+binary
(base/'neutral-ev.glb').write_bytes(out)
(base/'ASSET-MANIFEST.json').write_text(json.dumps({'name':'Neutral EV exterior derived from Car Concept','source':'https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/CarConcept','author':'Eric Chadwick / Darmstadt Graphics Group GmbH (2024)','license':'CC-BY-4.0','asset':'neutral-ev.glb','bytes':len(out),'sha256':hashlib.sha256(out).hexdigest(),'changes':'Exterior only; source textures, logos, engine and interior removed; neutral PBR materials; custom battery and connector provided separately.'},indent=2),encoding='utf-8')
(base/'SOURCE-LICENSE.md').write_bytes(urlopen('https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/CarConcept/LICENSE.md',timeout=30).read())
(base/'CC-BY-4.0.txt').write_bytes(urlopen('https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/LICENSES/CC-BY-4.0.txt',timeout=30).read())
(base/'ATTRIBUTION.md').write_text('''# Car Concept — attribution and modifications

Geometry: **Eric Chadwick / Darmstadt Graphics Group GmbH (2024)**.
Source: https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/CarConcept
License: **Creative Commons Attribution 4.0 International (CC BY 4.0)**.
License text: CC-BY-4.0.txt / https://creativecommons.org/licenses/by/4.0/

Derived asset `neutral-ev.glb` was modified for iot-protocol-expert:
- Removed source textures, logo-bearing license plate / emblem meshes, interior meshes, and combustion-engine geometry.
- Replaced original paint / glass / tire / lamp materials with neutral materials.
- Kept exterior geometry, normals and indices; stripped unused texture-coordinate / tangent / image data.
- Uses an independently generated EV battery pack and charging-port visualization at runtime.
- The resulting vehicle is a generic EV visualization, not a claim that the source concept is a production EV.

Khronos logos and trademarks are excluded by the source license; the derived asset does not display these logos.
No endorsement by the artist, DGG or Khronos is implied.
''',encoding='utf-8')
vendor=base.parent.parent/'vendor/three'
for remote,name in [('examples/jsm/loaders/GLTFLoader.js','GLTFLoader.js'),('examples/jsm/utils/BufferGeometryUtils.js','BufferGeometryUtils.js')]:
    code=urlopen('https://cdn.jsdelivr.net/npm/three@0.180.0/'+remote,timeout=30).read().decode().replace('../utils/BufferGeometryUtils.js','./BufferGeometryUtils.js');(vendor/name).write_text(code,encoding='utf-8')
print('neutral exterior GLB bytes',len(out),'triangles',sum(accessors[p['indices']]['count']//3 for m in meshes for p in m['primitives']))
