"""Reusable Blender primitives for the reference-based charging scene."""
import bpy
import math
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
bpy.ops.wm.read_factory_settings(use_empty=True)

def mat(name,color,metallic=0,roughness=.35,emission=0):
    m=bpy.data.materials.new(name); m.diffuse_color=(*color,1); m.use_nodes=True
    p=m.node_tree.nodes.get('Principled BSDF'); p.inputs['Base Color'].default_value=(*color,1)
    p.inputs['Metallic'].default_value=metallic; p.inputs['Roughness'].default_value=roughness
    if emission:
        p.inputs['Emission Color'].default_value=(*color,1); p.inputs['Emission Strength'].default_value=emission
    return m

black=mat('Satin black',(.012,.017,.022),.1,.38)
glass=mat('Display glass',(.025,.045,.06),.2,.18)
chrome=mat('Aluminium',(.52,.59,.64),.85,.25)
rim_dark=mat('Cabinet plinth',(.16,.18,.20),.45,.4)
white=mat('Powder coated white',(.74,.76,.78),0,.38)
charcoal=mat('Graphite fascia',(.035,.04,.045),0,.42)
green=mat('Green indicator',(.04,.75,.12),.1,.28,1)
red=mat('Red lamp',(.5,.009,.015),.2,.25,1)

def group(name,parent=None,pos=(0,0,0)):
    o=bpy.data.objects.new(name,None); bpy.context.collection.objects.link(o)
    o.parent=parent; o.location=pos; return o

def finish(o,name,material,parent):
    o.name=name; o.parent=parent
    if material:o.data.materials.append(material)
    return o

def box(name,pos,size,material,parent,bevel=.02):
    bpy.ops.mesh.primitive_cube_add(size=1,location=pos); o=bpy.context.object
    o.dimensions=size; bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    if bevel:
        b=o.modifiers.new('Machined edges','BEVEL'); b.width=bevel; b.segments=3
        o.modifiers.new('Weighted normals','WEIGHTED_NORMAL')
    return finish(o,name,material,parent)

def mesh(name,verts,faces,material,parent,smooth=False):
    data=bpy.data.meshes.new(name); data.from_pydata(verts,[],faces); data.update()
    o=bpy.data.objects.new(name,data); bpy.context.collection.objects.link(o)
    for f in data.polygons:f.use_smooth=smooth
    return finish(o,name,material,parent)

def cylinder(name,pos,radius,depth,material,parent,vertices=48):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices,radius=radius,depth=depth,
        location=pos,rotation=(0,math.pi/2,0)); o=bpy.context.object
    for f in o.data.polygons:f.use_smooth=True
    o.modifiers.new('Weighted normals','WEIGHTED_NORMAL')
    return finish(o,name,material,parent)

def torus(name,pos,major,minor,material,parent):
    bpy.ops.mesh.primitive_torus_add(major_segments=48,minor_segments=10,location=pos,
        rotation=(0,math.pi/2,0),major_radius=major,minor_radius=minor)
    o=bpy.context.object
    for f in o.data.polygons:f.use_smooth=True
    return finish(o,name,material,parent)
