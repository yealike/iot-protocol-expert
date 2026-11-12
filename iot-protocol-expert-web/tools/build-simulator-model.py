"""Generate the simulator's close-up EV and DC charger.

Run: D:/tools/blender/blender.exe --background --python-exit-code 1 --python tools/build-simulator-model.py
Coordinates are in metres; EV, EV_Port, Charger, Charger screen and
Charger_Gun are the animation interfaces consumed by the web scene.
"""
import bpy
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
bpy.ops.wm.read_factory_settings(use_empty=True)

def mat(name, color, metallic=0, roughness=.35, emission=0):
    m = bpy.data.materials.new(name); m.diffuse_color = (*color, 1); m.use_nodes = True
    p = m.node_tree.nodes.get('Principled BSDF'); p.inputs['Base Color'].default_value = (*color, 1)
    p.inputs['Metallic'].default_value = metallic; p.inputs['Roughness'].default_value = roughness
    if emission:
        p.inputs['Emission Color'].default_value = (*color, 1); p.inputs['Emission Strength'].default_value = emission
    return m

paint = mat('Pearl graphite blue', (.28, .38, .43), .72, .23)
black = mat('Satin black trim', (.018, .027, .032), .15, .4)
rubber = mat('Tire rubber', (.012, .016, .019), 0, .82)
glass = mat('Tinted glazing', (.025, .065, .082), .48, .13)
chrome = mat('Machined alloy', (.48, .58, .61), .85, .22)
rim_dark = mat('Wheel graphite', (.055, .075, .083), .72, .28)
white = mat('Charger porcelain', (.76, .83, .83), .24, .27)
charcoal = mat('Charger graphite', (.025, .045, .055), .22, .36)
green = mat('Status light', (.12, .8, .54), .1, .25, 2)
red = mat('Rear lamps', (.58, .008, .015), .2, .23, 1.2)
headlight = mat('Head lamps', (.75, .92, 1), .1, .18, 1.5)

def group(name, parent=None, pos=(0,0,0)):
    o=bpy.data.objects.new(name,None); bpy.context.collection.objects.link(o); o.parent=parent; o.location=pos; return o

def finish(o,name,material,parent):
    o.name=name; o.parent=parent
    if material: o.data.materials.append(material)
    return o

def box(name, pos, size, material, parent, bevel=.02):
    bpy.ops.mesh.primitive_cube_add(size=1, location=pos); o=bpy.context.object
    o.dimensions=size; bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel:
        b=o.modifiers.new('Soft machined edges','BEVEL'); b.width=bevel; b.segments=3
        o.modifiers.new('Corner normals','WEIGHTED_NORMAL')
    return finish(o,name,material,parent)

def mesh(name, verts, faces, material, parent, smooth=False):
    data=bpy.data.meshes.new(name); data.from_pydata(verts,[],faces); data.update()
    o=bpy.data.objects.new(name,data); bpy.context.collection.objects.link(o); finish(o,name,material,parent)
    for f in data.polygons: f.use_smooth=smooth
    return o

def cylinder(name,pos,radius,depth,material,parent,vertices=64):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=pos, rotation=(0,math.pi/2,0))
    o=bpy.context.object
    for f in o.data.polygons: f.use_smooth=True
    o.modifiers.new('Weighted normals','WEIGHTED_NORMAL')
    return finish(o,name,material,parent)

def torus(name,pos,major,minor,material,parent):
    bpy.ops.mesh.primitive_torus_add(major_segments=64, minor_segments=16, location=pos, rotation=(0,math.pi/2,0), major_radius=major, minor_radius=minor)
    o=bpy.context.object
    for f in o.data.polygons: f.use_smooth=True
    return finish(o,name,material,parent)

car=group('EV')
# Longitudinal stations form a continuously tapered, rounded body. Eight points
# per section define the sill, shoulder and bonnet instead of a rectangular box.
stations=[(-2.22,.65,.62),(-2.12,.80,.79),(-1.85,.91,.91),(-1.4,.96,.98),(-.85,.98,1.01),(.35,.98,1.00),(1.15,.95,.94),(1.78,.87,.80),(2.12,.72,.67),(2.19,.61,.60)]
verts=[]
for y,w,h in stations:
    verts += [(-w*.78,y,.24),(w*.78,y,.24),(w*.98,y,.34),(w,y,h-.13),(w*.85,y,h),(-w*.85,y,h),(-w,y,h-.13),(-w*.98,y,.34)]
faces=[tuple(reversed(range(8))),tuple(range((len(stations)-1)*8,len(stations)*8))]
for i in range(len(stations)-1):
    for j in range(8): faces.append((i*8+j,i*8+(j+1)%8,(i+1)*8+(j+1)%8,(i+1)*8+j))
body=mesh('Sculpted body',verts,faces,paint,car,True)
sub=body.modifiers.new('Continuous bodywork','SUBSURF'); sub.levels=2
# The greenhouse is a gently rounded dark glass canopy, with body-coloured pillars.
profiles=[(-1.65,.72,1.00),(-1.35,.78,1.32),(-.95,.78,1.48),(.48,.77,1.49),(1.08,.72,1.32),(1.48,.67,.96)]
verts=[]
for y,w,h in profiles: verts += [(-w,y,.98),(w,y,.98),(w*.96,y,h-.055),(w*.77,y,h),(-w*.77,y,h),(-w*.96,y,h-.055)]
faces=[tuple(reversed(range(6))),tuple(range((len(profiles)-1)*6,len(profiles)*6))]
for i in range(len(profiles)-1):
    for j in range(6): faces.append((i*6+j,i*6+(j+1)%6,(i+1)*6+(j+1)%6,(i+1)*6+j))
canopy=mesh('Panoramic glass canopy',verts,faces,glass,car,True); s=canopy.modifiers.new('Curved glass','SUBSURF'); s.levels=2
for side in (-1,1):
    # The windows sit on the outside of the body shoulder and slope inward
    # towards the roof, so the cabin remains legible from a low side view.
    for name, outline in (
        ('Rear side window', [(-1.42,1.02),(-.14,1.02),(-.14,1.39),(-.93,1.38),(-1.25,1.25)]),
        ('Front side window', [(-.08,1.02),(1.30,1.02),(.97,1.27),(.47,1.40),(-.08,1.39)]),
    ):
        points=[(side*(.945-(z-1.02)*.43), y, z) for y,z in outline]
        mesh(name, points, [tuple(range(len(points)))], glass, car)
    # Trim and recessed flush handles provide scale and readable side detail.
    box('Lower rocker', (side*.935,-.02,.31),(.055,2.65,.09),black,car,.025)
    for y in (-.75,.48): box('Flush door handle',(side*.97,y,.86),(.018,.18,.025),black,car,.008)
    box('Window divider',(side*.758,-.08,1.20),(.018,.045,.40),black,car,.008)
    box('Side mirror',(side*.99,.84,1.05),(.18,.24,.105),paint,car,.045)
    box('Mirror indicator',(side*1.078,.84,1.05),(.008,.14,.018),headlight,car,.003)
    for y in (-1.38,1.38):
        # Complete wheel layers, including tire shoulder, rim and ten machined spokes.
        x=side*.90
        cylinder('Tire',(x,y,.39),.395,.245,rubber,car)
        torus('Tire sidewall',(side*1.035,y,.39),.315,.055,rubber,car)
        cylinder('Alloy rim',(side*1.04,y,.39),.29,.025,rim_dark,car)
        torus('Rim edge',(side*1.058,y,.39),.27,.012,chrome,car)
        for k in range(10):
            a=k*math.tau/10
            spoke=box('Alloy spoke',(side*1.06,y+math.sin(a)*.16,.39+math.cos(a)*.16),(.018,.038,.23),chrome,car,.009)
            spoke.rotation_euler.x=-a
        cylinder('Wheel hub',(side*1.075,y,.39),.065,.03,chrome,car,32)
        for k in range(5):
            a=k*math.tau/5
            cylinder('Wheel bolt',(side*1.094,y+math.sin(a)*.039,.39+math.cos(a)*.039),.008,.008,black,car,12)
    box('Front LED',(side*.58,2.01,.71),(.43,.04,.055),headlight,car,.02)
    box('Rear LED',(side*.61,-2.05,.75),(.43,.035,.06),red,car,.02)
box('Front lower intake',(0,2.105,.39),(1.05,.035,.095),black,car,.025)
box('Rear lower diffuser',(0,-2.105,.34),(1.15,.04,.11),black,car,.025)
box('Rear light bridge',(0,-2.075,.75),(.86,.025,.022),red,car,.008)
port=group('EV_Port',car,(-.965,-1.35,.83))
cylinder('Charging port bezel',(-.012,0,0),.10,.026,black,port,48)
cylinder('Charging port inset',(-.03,0,0),.073,.03,chrome,port,48)

charger=group('Charger')
box('Foundation',(0,0,.07),(.82,.65,.14),charcoal,charger,.04)
box('Lower cabinet',(0,0,.76),(.67,.48,1.35),white,charger,.10)
box('Upper cabinet',(0,0,1.47),(.72,.51,.47),white,charger,.12)
box('Front fascia',(0,-.254,1.13),(.59,.035,1.13),charcoal,charger,.055)
box('Screen surround',(0,-.281,1.37),(.47,.025,.50),black,charger,.025)
box('Charger screen',(0,-.298,1.38),(.39,.014,.40),glass,charger,.012)
box('Status LED',(0,-.289,1.02),(.37,.014,.018),green,charger,.006)
box('Reader panel',(.12,-.29,.85),(.19,.016,.16),rim_dark,charger,.015)
for i in range(3): box('Reader signal',(0.12,-.302,.83+i*.025),(.07+i*.018,.006,.006),chrome,charger,.002)
box('Emergency stop',(-.16,-.30,.85),(.09,.045,.09),red,charger,.025)
for i in range(8): box('Cooling vent',(.337,0,.47+i*.06),(.012,.26,.012),black,charger,.004)
box('Gun holster',(.38,.02,.86),(.14,.25,.35),charcoal,charger,.04)
box('Top signature',(0,-.257,1.67),(.24,.012,.025),green,charger,.008)

gun=group('Charger_Gun')
box('Gun housing',(0,0,0),(.24,.11,.16),charcoal,gun,.04)
box('Gun grip',(-.065,.01,-.13),(.095,.10,.18),black,gun,.025)
cylinder('Gun nozzle',(.16,0,.015),.052,.18,chrome,gun,32)
box('Gun status',(-.04,-.058,.045),(.07,.008,.018),green,gun,.005)

bpy.context.preferences.filepaths.save_version=0
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'tools'/'simulator-model.blend'))
bpy.ops.export_scene.gltf(filepath=str(ROOT/'public'/'simulator'/'simulator-model.glb'),export_format='GLB',export_apply=True,export_animations=False,export_yup=True)
