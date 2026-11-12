# Extract the editable EV and charger from the original Blender campus scene.
# Usage: blender --background <source.blend> --python tools/build-simulator-model.py
import bpy
from pathlib import Path

root = Path(__file__).resolve().parent.parent
names = ('EV', 'Charger', 'Charger_Gun')
keep = set()
def collect(obj):
    keep.add(obj)
    for child in obj.children:
        collect(child)
for name in names:
    collect(bpy.data.objects[name])
for obj in list(bpy.data.objects):
    if obj not in keep:
        bpy.data.objects.remove(obj, do_unlink=True)
# Model Y-style rear charge port; dimensions are illustrative, not CAD-accurate.
bpy.data.objects['EV_Port'].location.y = -1.5
bpy.data.objects['EV'].scale = (1.0, 1.16, 1.05)
bpy.data.objects['Charger'].scale = (1.69, 1.41, 1.12)
bpy.context.preferences.filepaths.save_version = 0
bpy.ops.wm.save_as_mainfile(filepath=str(root / 'tools' / 'simulator-model.blend'))
bpy.ops.export_scene.gltf(filepath=str(root / 'public' / 'simulator' / 'simulator-model.glb'), export_format='GLB', export_apply=True, export_animations=False, export_yup=True)
