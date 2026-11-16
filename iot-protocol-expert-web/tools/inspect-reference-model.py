import bpy
import json
from pathlib import Path
from mathutils import Vector

root=Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(root/'references'/'tesla-model-y-source.glb'))
meshes=[o for o in bpy.context.scene.objects if o.type=='MESH']
coords=[o.matrix_world@Vector(v) for o in meshes for v in o.bound_box]
low=Vector(tuple(min(v[i] for v in coords) for i in range(3)))
high=Vector(tuple(max(v[i] for v in coords) for i in range(3)))
report={'min':list(low),'max':list(high),'size':list(high-low),'parts':[]}
for o in meshes:
    c=[o.matrix_world@Vector(v) for v in o.bound_box]
    report['parts'].append({'name':o.name,'materials':[m.name for m in o.data.materials],
        'min':[min(v[i] for v in c) for i in range(3)],'max':[max(v[i] for v in c) for i in range(3)]})
(root/'references'/'tesla-model-y-inspection.json').write_text(json.dumps(report,indent=2))
print('REFERENCE_BOUNDS',report['size'],report['min'],report['max'])
