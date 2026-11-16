// Finite-radius contact for the rope render skin. Bullet's vertex/face soft
// collision has no faces on a 1D rope, so handle the segment capsules here.
import {CABLE_RADIUS} from './scene-geometry.js';

export function segmentContact(a,b,c,d,diameter=CABLE_RADIUS*2+.010) {
  if(Math.min(a.x,b.x)>Math.max(c.x,d.x)+diameter || Math.max(a.x,b.x)<Math.min(c.x,d.x)-diameter ||
     Math.min(a.y,b.y)>Math.max(c.y,d.y)+diameter || Math.max(a.y,b.y)<Math.min(c.y,d.y)-diameter ||
     Math.min(a.z,b.z)>Math.max(c.z,d.z)+diameter || Math.max(a.z,b.z)<Math.min(c.z,d.z)-diameter)return null;
  const ux=b.x-a.x,uy=b.y-a.y,uz=b.z-a.z,vx=d.x-c.x,vy=d.y-c.y,vz=d.z-c.z;
  const wx=a.x-c.x,wy=a.y-c.y,wz=a.z-c.z;
  const uu=ux*ux+uy*uy+uz*uz,vv=vx*vx+vy*vy+vz*vz,uv=ux*vx+uy*vy+uz*vz;
  const uw=ux*wx+uy*wy+uz*wz,vw=vx*wx+vy*wy+vz*wz,den=uu*vv-uv*uv;
  if(uu<1e-10||vv<1e-10)return null;
  const clamp=x=>Math.max(0,Math.min(1,x));
  let s=den>1e-10?clamp((uv*vw-vv*uw)/den):0;
  let t=(uv*s+vw)/vv;
  if(t<0){t=0;s=clamp(-uw/uu);}else if(t>1){t=1;s=clamp((uv-uw)/uu);}
  const x=wx+s*ux-t*vx,y=wy+s*uy-t*vy,z=wz+s*uz-t*vz,dist=Math.hypot(x,y,z);
  if(dist>=diameter)return null;
  return {s,t,x,y,z,dist,diameter};
}

export function resolveCableContacts(ropes) {
  for(const rope of ropes)rope.contactBefore.forEach((p,i)=>p.copy(rope.points[i]));
  for(let pass=0;pass<3;pass++)for(let first=0;first<ropes.length;first++)for(let second=first;second<ropes.length;second++) {
    const A=ropes[first],B=ropes[second];
    for(let i=1;i<A.count-2;i++)for(let j=first===second?i+3:1;j<B.count-2;j++) {
      const a=A.points[i],b=A.points[i+1],c=B.points[j],d=B.points[j+1];
      const hit=segmentContact(a,b,c,d);if(!hit)continue;
      const {s,t,x,y,z,dist,diameter}=hit;
      let nx,ny,nz,depth;
      if(Math.max(a.y,b.y,c.y,d.y)<CABLE_RADIUS*2+.026 && Math.abs(y)<.025) {
        // Near a flat floor, let the later segment lie on top. Pushing the
        // lower cable through the ground would violate its contact constraint.
        nx=0;ny=-1;nz=0;depth=Math.sqrt(Math.max(0,diameter*diameter-x*x-z*z))-Math.max(0,-y);
      } else {
        nx=dist>1e-8?x/dist:0;ny=dist>1e-8?y/dist:-1;nz=dist>1e-8?z/dist:0;depth=diameter-dist;
      }
      if(depth<=0)continue;
      let wa=A.pins.includes(i)?0:1-s,wb=A.pins.includes(i+1)?0:s;
      let wc=B.pins.includes(j)?0:1-t,wd=B.pins.includes(j+1)?0:t;
      if(ny<0){if(a.y<CABLE_RADIUS+.003)wa=0;if(b.y<CABLE_RADIUS+.003)wb=0;}
      if(ny>0){if(c.y<CABLE_RADIUS+.003)wc=0;if(d.y<CABLE_RADIUS+.003)wd=0;}
      const weight=wa*wa+wb*wb+wc*wc+wd*wd;if(weight<1e-8)continue;
      const correction=Math.min(.035,depth)/weight;
      a.x+=nx*correction*wa;a.y+=ny*correction*wa;a.z+=nz*correction*wa;
      b.x+=nx*correction*wb;b.y+=ny*correction*wb;b.z+=nz*correction*wb;
      c.x-=nx*correction*wc;c.y-=ny*correction*wc;c.z-=nz*correction*wc;
      d.x-=nx*correction*wd;d.y-=ny*correction*wd;d.z-=nz*correction*wd;
      for(const p of [a,b,c,d])p.y=Math.max(CABLE_RADIUS,p.y);
    }
  }
  for(const rope of ropes)rope.points.forEach((p,i)=>{
    const old=rope.contactBefore[i],dx=p.x-old.x,dy=p.y-old.y,dz=p.z-old.z;
    if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)<1e-9)return;
    const node=rope.nodes[i];node.position.setValue(p.x,p.y,p.z);
    // Move the previous position equally: contact projection must not inject
    // an artificial velocity/impulse into the Bullet soft body.
    node.previous.setValue(node.previous.x()+dx,node.previous.y()+dy,node.previous.z()+dz);
  });
}
