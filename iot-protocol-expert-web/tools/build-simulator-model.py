"""Model Y (tonielpro520, CC BY 4.0) + StarCharge Gemini 120 kW V3.2.

Run: D:/tools/blender/blender.exe -b --python-exit-code 1 --python tools/build-simulator-model.py
Sources, dimensions and provenance are in references/README.md.
Geometry is authored in metres, Z-up, with the EV facing -Y.
"""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from model_primitives import *
from mathutils import Matrix, Vector
import json
import bmesh

# Preserve the actual authored Model Y mesh: no lofted substitute bodywork.
bpy.ops.import_scene.gltf(filepath=str(ROOT/'tools/references/tesla-model-y-source.glb'))
imported=[o for o in bpy.context.scene.objects if o.type=='MESH']
old_groups=[o for o in bpy.context.scene.objects if o.type=='EMPTY']
car=group('EV')
car['identity']='Tesla Model Y · 2021 / classic 2020–2024 body'
car['author']='tonielpro520'
car['license']='CC BY 4.0'
car['source']='https://sketchfab.com/3d-models/2021-tesla-model-y-59e2ead369984b1a85c800ff6cf6789d'
# Measured source bounds: L4.763916, H1.654583, body width1.849941.
# Calibrate to the classic Model Y owner's manual, not the 2025 facelift.
calibration=Matrix.Diagonal((1.921/1.849941,4.751/4.763916,1.624/1.654583,1))
for o in imported:
    transform=calibration@o.matrix_world
    o.parent=None; o.matrix_world=Matrix.Identity(4)
    o.data.transform(transform); o.data.update(); o.parent=car
    o.name='Tesla_Model_Y_'+o.name
for o in old_groups: bpy.data.objects.remove(o,do_unlink=True)

# The source uses diffuse materials. Refinish its existing material slots;
# lamp, tire and interior textures remain attached to their original UVs.
for m in set(m for o in imported for m in o.data.materials):
    p=m.node_tree.nodes.get('Principled BSDF')
    if not p: continue
    name=m.name
    p.inputs['Roughness'].default_value=.4
    if 'Pintura' in name:
        p.inputs['Base Color'].default_value=(.82,.86,.90,1)
        p.inputs['Metallic'].default_value=0; p.inputs['Roughness'].default_value=.24
        p.inputs['Coat Weight'].default_value=.85; p.inputs['Coat Roughness'].default_value=.16
    elif 'Vidros' in name:
        p.inputs['Base Color'].default_value=(.035,.055,.070,.68)
        p.inputs['Alpha'].default_value=.68; p.inputs['Roughness'].default_value=.14
        p.inputs['Metallic'].default_value=0
    elif 'Vidro_Vermelho' in name:
        p.inputs['Base Color'].default_value=(.14,.005,.008,.72)
        p.inputs['Alpha'].default_value=.72; p.inputs['Roughness'].default_value=.19
    elif 'Pneu' in name: p.inputs['Roughness'].default_value=.85
    elif 'Cromado' in name or 'Disco' in name:
        p.inputs['Metallic'].default_value=.78; p.inputs['Roughness'].default_value=.24
    elif 'Metal_Preto' in name:
        p.inputs['Base Color'].default_value=(.09,.11,.13,1)
        p.inputs['Metallic'].default_value=.65
    elif 'Farol' in name:
        p.inputs['Metallic'].default_value=.50; p.inputs['Roughness'].default_value=.18
    elif 'Plastico' in name:
        p.inputs['Base Color'].default_value=(.012,.017,.023,1)
        p.inputs['Roughness'].default_value=.37

# The real Model Y port is integrated into the left REAR lamp. Sample the
# source's side surface so the socket follows the car, rather than a door.
port_y=1.98; port_z=1.04
near=[v.co.x for o in imported for v in o.data.vertices
      if abs(v.co.y-port_y)<.05 and abs(v.co.z-port_z)<.06 and v.co.x<-.5]
# Socket sits BEHIND the original curved lens, not on top of the bodywork.
port_x=(min(near) if near else -.82)+.035
port=group('EV_Port',car,(port_x,port_y,port_z))
port['location']='Left rear tail-lamp charging port'
box('Socket recess',(.025,0,0),(.052,.19,.135),black,port,.025)
cylinder('Socket seal',(.005,0,0),.052,.018,black,port,48)
torus('Port socket gasket',(-.006,0,0),.050,.003,black,port)
for y,z in [(-.021,.017),(.021,.017),(0,-.023)]:
    cylinder('Socket contact',(-.008,y,z),.009,.008,chrome,port,24)
hinge=Vector((port_x,port_y,1.145))
door=group('Port_Door',port,(0,0,hinge.z-port_z))
# Split the actual left wraparound tail-light lens/housing at precise planes.
# BMesh keeps UVs and interpolates boundary vertices. In the closed pose the
# extracted surfaces are exactly coincident with the original curved lamp.
cover_parts=[]
for o in imported:
    if not any(('Vermelho' in m.name or 'Lanterna' in m.name) for m in o.data.materials): continue
    bm=bmesh.new(); bm.from_mesh(o.data)
    planes=[((-.64,0,0),(1,0,0)),((0,1.84,0),(0,1,0)),((0,2.10,0),(0,1,0)),
            ((0,0,.97),(0,0,1)),((0,0,1.145),(0,0,1))]
    for point,normal in planes:
        bmesh.ops.bisect_plane(bm,geom=list(bm.verts)+list(bm.edges)+list(bm.faces),
            dist=.000001,plane_co=Vector(point),plane_no=Vector(normal))
    def is_lid(face):
        c=face.calc_center_median()
        return c.x<-.640001 and 1.839999<c.y<2.100001 and .969999<c.z<1.145001
    selected=[f for f in bm.faces if is_lid(f)]
    if not selected: bm.free(); continue
    lid_bm=bm.copy()
    bmesh.ops.delete(lid_bm,geom=[f for f in lid_bm.faces if not is_lid(f)],context='FACES')
    lid_data=bpy.data.meshes.new('Original curved charge-port lens')
    lid_bm.to_mesh(lid_data); lid_bm.free()
    for m in o.data.materials:
        if 'Vidro_Vermelho' in m.name:
            opaque=m.copy(); opaque.name='Opaque backed charge-port lens'
            shader=opaque.node_tree.nodes.get('Principled BSDF')
            shader.inputs['Alpha'].default_value=1
            color=list(shader.inputs['Base Color'].default_value); color[3]=1
            shader.inputs['Base Color'].default_value=color
            lid_data.materials.append(opaque)
        else: lid_data.materials.append(m)
    lid=bpy.data.objects.new('Port_Lid_Original_'+o.name,lid_data)
    bpy.context.collection.objects.link(lid); lid.parent=door; lid.location=-hinge
    cover_parts.append(lid)
    bmesh.ops.delete(bm,geom=selected,context='FACES')
    bm.to_mesh(o.data); bm.free(); o.data.update()
assert cover_parts, 'No original tail-lamp surfaces selected for the flush charge-port lid'
door['construction']='Original curved tail-lamp surfaces, exact closed pose'

# Official Gemini V3.2 datasheet: W850 x D550 x H1550 mm, 120kW.
# Photo: square white cabinet, narrow central fascia, landscape LCD, dual
# green status strips, two front holsters and orange StarCharge branding.
orange=mat('StarCharge orange',(.98,.23,.035),.05,.36)
charger=group('Charger')
charger['identity']='StarCharge 星星充电 双子座120kW V3.2'
charger['dimensions_mm']='850 x 550 x 1550 (W x D x H)'
box('Grey cabinet plinth',(0,0,.055),(.85,.55,.11),rim_dark,charger,.008)
box('White rectangular cabinet',(0,0,.825),(.85,.55,1.45),white,charger,.012)
box('Central graphite fascia',(0,-.286,1.005),(.31,.022,.76),charcoal,charger,.012)
box('LCD bezel',(0,-.303,1.30),(.257,.015,.19),black,charger,.008)
box('Charger screen',(0,-.314,1.30),(.19,.006,.12),glass,charger,.003)
for x in (-.088,.088):
    box('Green status indicator',(x,-.302,1.165),(.102,.009,.007),green,charger,.002)
box('Card reader',(0,-.302,1.06),(.067,.012,.055),black,charger,.008)
box('Tariff and QR label',(0,-.302,.973),(.267,.009,.06),rim_dark,charger,.004)
for x in (-.105,.105):
    box('QR backing',(x,-.309,.973),(.045,.004,.045),white,charger,.001)
    for i in range(5):
        for j in range(5):
            if (i+j)%3!=0:
                box('QR pattern',(x+(i-2)*.007,-.312,.973+(j-2)*.007),(.005,.003,.005),black,charger,0)
for x in (-.094,.094):
    box('Front gun holster',(x,-.311,.807),(.096,.055,.17),black,charger,.019)
    box('Holster socket',(x,-.345,.83),(.063,.015,.10),charcoal,charger,.017)
for side in (-1,1):
    box('Cabinet side handle',(side*.429,-.17,.74),(.018,.05,.15),chrome,charger,.008)
    for i in range(13):
        box('Side cooling slit',(side*.427,.045,.33+i*.027),(.006,.30,.008),black,charger,.002)
    box('Rear cable outlet',(side*.405,.11,1.40),(.055,.08,.06),black,charger,.015)
    cylinder('Cable exit grommet',(side*.445,.11,1.40),.027,.05,black,charger,32)
box('Emergency button collar',(.43,-.14,1.12),(.018,.075,.075),black,charger,.012)
cylinder('Emergency stop',(.447,-.14,1.12),.028,.024,red,charger,32)
box('Rear maintenance door',(0,.279,.83),(.74,.008,1.30),white,charger,.006)

font=bpy.data.fonts.load('C:/Windows/Fonts/msyh.ttc')
def label(name,body,pos,size,material,parent):
    data=bpy.data.curves.new(name,'FONT'); data.body=body; data.size=size
    data.align_x='CENTER'; data.align_y='CENTER'; data.font=font
    o=bpy.data.objects.new(name,data); bpy.context.collection.objects.link(o)
    o.location=pos; o.rotation_euler=(math.pi/2,0,0)
    finish(o,name,material,parent)
    bpy.context.view_layer.objects.active=o
    bpy.ops.object.select_all(action='DESELECT'); o.select_set(True)
    bpy.ops.object.convert(target='MESH')
    return o
label('StarCharge brand','StarCharge 星星充电',(.025,-.284,1.477),.028,charcoal,charger)
sun=[]
for i in range(24):
    a=i*math.tau/24; r=.025 if i%2==0 else .018
    sun.append((-.215+math.cos(a)*r,-.285,1.477+math.sin(a)*r))
mesh('Orange brand sun',sun,[tuple(range(24))],orange,charger)
for i,c in enumerate('快充300A'):
    label('Fast charge marking',c,(.286,-.284,.54-i*.047),.037,orange,charger)
label('Rating label','120 kW',(0,-.314,.98),.018,white,charger)

# Two physical guns belong to one cabinet. Only the selected gun moves;
# the second remains parked, matching the dual-holster reference photo.
def make_gun(name):
    g=group(name)
    box('Gun housing',(0,0,0),(.20,.09,.12),black,g,.029)
    cylinder('Gun collar',(.115,0,.01),.051,.10,charcoal,g,32)
    cylinder('Gun connector',(.183,0,.01),.043,.06,chrome,g,32)
    box('Gun grip',(-.055,0,-.115),(.075,.085,.20),black,g,.025)
    box('Release latch',(.012,0,.071),(.085,.05,.017),rim_dark,g,.005)
    group('Gun_Tip' if name=='Charger_Gun' else 'Spare_Tip',g,(.213,0,.01))
    group('Gun_Cable' if name=='Charger_Gun' else 'Spare_Cable',g,(-.055,0,-.22))
    return g
gun=make_gun('Charger_Gun')
group('Gun_Home',charger,(.094,-.38,.84))
group('Cable_Anchor',charger,(.47,.11,1.40))
group('Spare_Home',charger,(-.094,-.38,.84))
group('Spare_Anchor',charger,(-.47,.11,1.40))
make_gun('Spare_Gun')

bpy.context.view_layer.update()
closed_surface_error=max((lid.matrix_world@v.co-v.co).length for lid in cover_parts for v in lid.data.vertices)
assert closed_surface_error<.00001, f'Closed lid moved away from original tail lamp: {closed_surface_error}m'
door['closed_surface_error_m']=closed_surface_error
bpy.context.preferences.filepaths.save_version=0
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'tools/simulator-model.blend'))
bpy.ops.export_scene.gltf(filepath=str(ROOT/'public/simulator/simulator-model.glb'),
    export_format='GLB',export_apply=True,export_animations=False,export_yup=True,
    export_draco_mesh_compression_enable=True,export_draco_mesh_compression_level=6,
    export_draco_position_quantization=16,export_draco_normal_quantization=12,
    export_draco_texcoord_quantization=14)
manifest={'car':'Tesla Model Y 2021','car_dimensions_m':[4.751,1.921,1.624],
          'charger':'StarCharge Gemini 120kW V3.2','charger_dimensions_m':[.85,.55,1.55],
          'port_blender_xyz':[port_x,port_y,port_z],
          'lid_original_parts':len(cover_parts),
          'lid_closed_surface_error_m':closed_surface_error,
          'car_license':'CC BY 4.0','car_author':'tonielpro520'}
(ROOT/'tools/references/model-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
