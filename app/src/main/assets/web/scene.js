'use strict';
/* Parameterized miniature, not measured vehicle geometry. No WebGL dependency. */
(function(root){
 const clamp=(x,a=0,b=1)=>Math.min(b,Math.max(a,Number.isFinite(+x)?+x:a));
 const defaults={seat:.08,light:.75,window:0,blind:0,fan:.2,temperature:22,vent:0};
 const V={add:(a,b)=>a.map((v,i)=>v+b[i]),sub:(a,b)=>a.map((v,i)=>v-b[i]),mul:(a,s)=>a.map(v=>v*s),dot:(a,b)=>a.reduce((s,v,i)=>s+v*b[i],0),cross:(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],unit:a=>{const l=Math.hypot(...a)||1;return a.map(v=>v/l);}};
 const lightDir=V.unit([3,6,-4]);
 function rotateX(p,a){return[p[0],p[1]*Math.cos(a)-p[2]*Math.sin(a),p[1]*Math.sin(a)+p[2]*Math.cos(a)];}
 const color=(rgb,k=1,alpha=1)=>`rgba(${rgb.map(v=>Math.round(clamp(v*k,0,255))).join(',')},${alpha})`;
 const palette={leather:[198,168,137],inset:[211,186,157],stitch:[238,216,188],shell:[54,71,88],trim:[113,132,147],floor:[28,44,61],dark:[19,31,46],steel:[145,163,175],blue:[132,205,222],glass:[133,195,207],blind:[222,202,173]};
 function create(canvas){
  const ctx=canvas.getContext('2d',{alpha:true}),host=canvas.parentElement;
  let values={...defaults},target={...defaults},startValues={...defaults},options={motion:false,selected:null,mode:'preview',phase:'awake'},frame=0,startAt=0,duration=650,running=false,lastFrame=0,draws=0,width=0,height=0,faces=[],lines=[],points={},lastFaces=0;
  let stats={seatPivot:[],windowTop:[],blindBottom:[],light:values.light};
  const camera=V.unit([5.2,3.9,-6.5]),right=V.unit(V.cross([0,1,0],camera)),up=V.cross(camera,right),center=[-.08,1.05,.10];
  function project(p){const q=V.sub(p,center),depth=V.dot(q,camera),s=Math.min(width/4.15,height/3.98)*(7.8/(7.8-depth));return[width*.49+V.dot(q,right)*s,height*.41-V.dot(q,up)*s,depth];}
  function face(verts,rgb,alpha=1,emissive=false,group=''){const a=V.sub(verts[1],verts[0]),b=V.sub(verts[2],verts[0]),n=V.unit(V.cross(a,b)),middle=V.mul(verts.reduce((s,p)=>V.add(s,p),[0,0,0]),1/verts.length);const shade=emissive?1:(.46+.54*Math.max(0,V.dot(n,lightDir)))*(.30+.70*values.light);faces.push({verts,rgb,alpha,shade,depth:V.dot(V.sub(middle,center),camera),group});}
  function box(pos,size,rgb,r=.04,rot=null,group='',segments=2){
   const h=size.map(v=>v/2),rad=Math.min(r,...h),cuts=segments===4?[-1,-.8,0,.8,1]:[-1,0,1];
   function vert(a,b,c){const p=[a*h[0],b*h[1],c*h[2]],inner=p.map((v,i)=>clamp(v,-h[i]+rad,h[i]-rad)),delta=V.sub(p,inner),normal=V.unit(delta);let q=V.add(inner,V.mul(normal,rad));q=V.add(q,pos);return rot?rot(q):q;}
   for(let axis=0;axis<3;axis++)for(const sign of[-1,1]){const u=(axis+1)%3,v=(axis+2)%3;for(let i=0;i<cuts.length-1;i++)for(let j=0;j<cuts.length-1;j++){let ps=[[cuts[i],cuts[j]],[cuts[i+1],cuts[j]],[cuts[i+1],cuts[j+1]],[cuts[i],cuts[j+1]]].map(t=>{const p=[0,0,0];p[axis]=sign;p[u]=t[0];p[v]=t[1];return vert(...p);});if(sign<0)ps.reverse();face(ps,rgb,1,false,group);}}
  }
  function line(pts,rgb,weight=1,alpha=1,group=''){lines.push({pts,rgb,weight,alpha,depth:pts.reduce((s,p)=>s+V.dot(V.sub(p,center),camera),0)/pts.length,group});}
  function tube(a,b,r,rgb,group='',count=8){const dir=V.unit(V.sub(b,a)),ax=V.unit(V.cross(dir,Math.abs(dir[1])>.9?[1,0,0]:[0,1,0])),ay=V.cross(dir,ax);for(let i=0;i<count;i++){const t=i/count*Math.PI*2,t2=(i+1)/count*Math.PI*2,offset=q=>V.add(V.mul(ax,Math.cos(q)*r),V.mul(ay,Math.sin(q)*r));face([V.add(a,offset(t)),V.add(a,offset(t2)),V.add(b,offset(t2)),V.add(b,offset(t))],rgb,1,false,group);}}
  function ring(centerP,radius,rgb){let prev;for(let i=0;i<=24;i++){const a=i/24*Math.PI*2,p=[centerP[0]+Math.cos(a)*radius,centerP[1]+Math.sin(a)*radius,centerP[2]+Math.sin(a)*.08];if(prev)tube(prev,p,.035,rgb,'steering',5);prev=p;}}
  function model(t){
   faces=[];lines=[];points={};
   const s=values,angle=(8+s.seat*42)*Math.PI/180,pivot=[.15,.70,.67],seatRot=p=>V.add(pivot,rotateX(p,angle));
   // Low cutaway body lets the model expose the seat hinge and door mechanisms.
   box([0,.08,.03],[2.32,.15,3.36],palette.floor,.09);
   box([-1.08,.54,.05],[.15,.82,3.20],palette.shell,.07);
   box([-1.00,.83,.10],[.13,.22,2.75],[76,85,89],.06);
   box([-.97,.86,.02],[.13,.10,1.05],palette.inset,.035);
   // Slanted window frame, with glass lowering into the door and an independent blind.
   const A=[-1.04,1.03,-1.44],B=[-1.04,2.20,-.94],C=[-1.04,2.20,1.44],D=[-1.04,1.03,1.44];
   tube(A,B,.047,palette.trim);tube(B,C,.047,palette.trim);tube(C,D,.047,palette.trim);tube(D,A,.039,palette.trim);
   const glassHeight=1-s.window,topY=1.03+1.17*glassHeight,blindBottom=2.18-1.11*s.blind;
   // Clip transparent glass to the uncovered opening. Centroid painter sorting alone
   // cannot resolve overlapping translucent panes and a moving opaque shade.
   const visibleTop=Math.min(topY,s.blind>.005?blindBottom:topY),edgeZ=y=>-1.44+.50*(y-1.03)/1.17;
   if(visibleTop>1.045){face([[-1.032,1.03,-1.44],[-1.032,visibleTop,edgeZ(visibleTop)],[-1.032,visibleTop,1.44],[-1.032,1.03,1.44]],palette.glass,.31,true,'window');
    if(topY<=visibleTop+.001)line([[-1.025,topY,edgeZ(topY)],[-1.025,topY,1.44]],[175,224,235],1.8,.9,'window');
    if(visibleTop>1.24){const reflectionTop=Math.min(topY-.08,visibleTop),ratio=clamp((reflectionTop-1.14)/(topY-.08-1.14));line([[-1.026,1.14,-.66],[-1.026,reflectionTop,-.66+1.04*ratio]],palette.glass,2,.22);}
   }
   if(s.blind>.005){const bottom=blindBottom;face([[-.98,2.18,edgeZ(2.18)],[-.98,2.18,1.39],[-.98,bottom,1.39],[-.98,bottom,edgeZ(bottom)]],palette.blind.map(v=>v*(.38+.62*s.light)),1,true,'blind');for(let y=2.14;y>bottom;y-=.115)line([[-.955,y,edgeZ(y)],[-.955,y,1.39]],[128,111,88],1,.76,'blind');tube([-.94,bottom,edgeZ(bottom)],[-.94,bottom,1.39],.038,[216,186,148],'blind');}
   box([-1.00,2.21,.24],[.13,.13,2.40],palette.shell,.04,null,'blind');
   // Front console and ventilation louvers.
   box([0,.88,-1.43],[2.13,.44,.36],palette.shell,.09);
   box([.05,1.13,-1.46],[1.95,.13,.30],palette.dark,.04);
   box([-.22,1.10,-1.18],[.65,.12,.035],[12,24,36],.025);
   for(let x=-.48;x<.10;x+=.08)box([x,1.10,-1.153],[.025,.075,.02],palette.steel,.006);
   box([.64,1.19,-1.39],[.49,.34,.05],palette.dark,.035);
   face([[.43,1.08,-1.355],[.85,1.08,-1.355],[.85,1.31,-1.355],[.43,1.31,-1.355]],[61,93,113],1,true);
   // Ambient strip brightens locally, independently of all other values.
   const ambient=[145+s.light*55,139+s.light*51,108+s.light*35];
   tube([-.96,.99,-1.30],[-.96,.99,1.30],.018,s.light>.02?ambient:[38,50,62],'brightness');
   tube([-.89,1.075,-1.19],[.94,1.075,-1.19],.016,s.light>.02?ambient:[38,50,62],'brightness');
   if(s.light>.01){
    face([[-.94,.46,-1.2],[-.94,.88,-1.2],[-.94,.88,1.3],[-.94,.46,1.3]],[240,191,128],s.light*.26,true,'brightness');
    face([[-.84,.165,-1.25],[.88,.165,-1.25],[.88,.165,-.55],[-.84,.165,-.55]],[240,191,128],s.light*.26,true,'brightness');
    line([[-.89,1.085,-1.18],[.94,1.085,-1.18]],[255,222,169],1+s.light*1.8,s.light,'brightness');
    line([[-.94,1.005,-1.30],[-.94,1.005,1.30]],[255,222,169],1+s.light*1.8,s.light,'brightness');
   }
   // Rail, fixed pan and articulated upholstered back. The hinge is a real transform.
   box([-.12,.22,.31],[.13,.12,1.34],palette.steel,.025);box([.63,.22,.31],[.13,.12,1.34],palette.steel,.025);
   box([.25,.35,.23],[1.03,.24,1.14],palette.dark,.07);
   box([.15,.57,.19],[.96,.29,1.05],palette.leather,.13,null,'seatRecline',4);
   box([.15,.72,.16],[.65,.055,.77],palette.inset,.025,null,'seatRecline',4);
   box([-.30,.73,.17],[.16,.17,.93],palette.leather,.075,null,'seatRecline',4);
   box([.60,.73,.17],[.16,.17,.93],palette.leather,.075,null,'seatRecline',4);
   box([0,.59,.04],[.91,1.18,.30],palette.leather,.12,seatRot,'seatRecline',4);
   box([0,.57,-.125],[.62,.92,.045],palette.inset,.02,seatRot,'seatRecline',4);
   box([-.38,.58,-.09],[.18,1.04,.29],palette.leather,.08,seatRot,'seatRecline',4);
   box([.38,.58,-.09],[.18,1.04,.29],palette.leather,.08,seatRot,'seatRecline',4);
   tube(seatRot([-.17,1.13,.035]),seatRot([-.17,1.32,.035]),.018,palette.steel);tube(seatRot([.17,1.13,.035]),seatRot([.17,1.32,.035]),.018,palette.steel);
   box([0,1.39,.015],[.57,.37,.29],palette.leather,.11,seatRot,'seatRecline',4);
   // Seam lines stay attached to the back, clarifying its articulation.
   for(const x of[-.275,.275])line([seatRot([x,.16,-.155]),seatRot([x,.99,-.155])],palette.stitch,.8,.8,'seatRecline');
   for(const y of[.36,.61,.86])line([seatRot([-.26,y,-.155]),seatRot([.26,y,-.155])],[171,146,121],.75,.7,'seatRecline');
   for(const x of[-.13,.43])line([[x,.751,-.16],[x,.751,.45]],palette.stitch,.8,.8,'seatRecline');
   box([.681,.42,.30],[.045,.065,.24],palette.steel,.018,null,'seatRecline');
   // Steering ring is small enough to expose the instrument panel and seat.
   tube([.15,.77,-1.24],[.15,1.22,-.91],.052,palette.dark);ring([.15,1.30,-.87],.29,palette.dark);
   tube([-.10,1.30,-.87],[.40,1.30,-.87],.028,palette.trim);tube([.15,1.30,-.87],[.15,1.05,-.94],.028,palette.trim);
   // Geometric flow ribbons encode fan/vent strengths without particles or a perpetual loop.
   const flow=(key,origin,strength)=>{if(strength<=.01)return;const phase=running?(t-startAt)/1100:1;for(let j=0;j<5;j++){const ps=[];for(let i=0;i<=18;i++){const u=i/18,shift=j*.13-.26;ps.push([origin[0]+shift+u*.22+Math.sin(u*Math.PI*2-phase*2)*.045,origin[1]+u*(.20+.34*strength),origin[2]+u*(.30+1.02*strength)]);}const heat=clamp((s.temperature-20)/10),air=[161+85*heat,225-64*heat,239-109*heat];line(ps,air,1.3+strength*1.7,.45+strength*.50,key);}};
   flow('fan',[-.18,1.13,-1.12],s.fan);flow('seatVentilation',[.18,.79,.0],s.vent);
   points={seatRecline:seatRot([.45,.65,-.1]),brightness:[-.94,1.0,.70],window:[-1.02,Math.max(1.09,topY),.10],blind:[-.97,2.18-1.11*s.blind,1.25],fan:[-.20,1.1,-1.12],temperature:[.60,1.20,-1.35],seatVentilation:[.20,.77,-.05],hvacPower:[-.20,1.1,-1.12]};
   stats={seatPivot:project(seatRot([0,1.5,0])).slice(0,2),windowTop:project([-1.032,topY,.20]).slice(0,2),blindBottom:project([-.99,2.18-1.11*s.blind,.20]).slice(0,2),light:s.light};
  }
  function paint(t=performance.now()){
   const r=canvas.getBoundingClientRect();width=r.width;height=r.height;if(!width||!height)return;
   const d=Math.min(root.devicePixelRatio||1,1.5),w=Math.round(width*d),h=Math.round(height*d);if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;}ctx.setTransform(d,0,0,d,0,0);ctx.clearRect(0,0,width,height);model(t);
   // Offset soft contact shadow anchors the model rather than adding a colored halo.
   const floor=project([0,.02,.12]);ctx.save();ctx.translate(floor[0],floor[1]+10);ctx.scale(1,.26);ctx.fillStyle='rgba(0,0,0,.17)';ctx.beginPath();ctx.ellipse(0,0,width*.31,width*.19,0,0,Math.PI*2);ctx.fill();ctx.restore();
   const objects=faces.map(f=>({...f,type:'face'})).concat(lines.map(l=>({...l,type:'line'}))).sort((a,b)=>a.depth-b.depth);
   for(const o of objects){if(o.type==='face'){const ps=o.verts.map(project);ctx.beginPath();ps.forEach((p,i)=>i?ctx.lineTo(p[0],p[1]):ctx.moveTo(p[0],p[1]));ctx.closePath();ctx.fillStyle=color(o.rgb,o.shade,o.alpha);ctx.fill();if(o.alpha===1){ctx.strokeStyle=color(o.rgb,o.shade,.85);ctx.lineWidth=.36;ctx.stroke();}}else{const ps=o.pts.map(project);ctx.beginPath();ps.forEach((p,i)=>i?ctx.lineTo(p[0],p[1]):ctx.moveTo(p[0],p[1]));ctx.strokeStyle=color(o.rgb,1,o.alpha);ctx.lineWidth=o.weight;ctx.lineCap='round';ctx.stroke();}}
   // Active-part locators identify concurrent work; they never indicate success.
   const selectedKeys=options.selectedKeys||[options.selected];for(const key of new Set(selectedKeys)){const selected=points[key];if(selected){const p=project(selected);ctx.beginPath();ctx.arc(p[0],p[1],4.5,0,Math.PI*2);ctx.fillStyle='#ccecf3';ctx.fill();ctx.strokeStyle='#233d54';ctx.lineWidth=2;ctx.stroke();}}
   host.dataset.renderer='geometry';host.dataset.ready='true';host.dataset.pose=options.phase;host.dataset.component=selectedKeys.filter(Boolean).join(' ');lastFaces=faces.length;draws++;
  }
  function tick(t){frame=0;if(document.hidden){running=false;return;}if(t-lastFrame<32){frame=requestAnimationFrame(tick);return;}lastFrame=t;const u=clamp((t-startAt)/duration),ease=1-Math.pow(1-u,3);for(const k of Object.keys(defaults))values[k]=startValues[k]+(target[k]-startValues[k])*ease;paint(t);if(u<1&&options.motion){frame=requestAnimationFrame(tick);}else{values={...target};running=false;}}
  function stop(settle=false){if(frame)cancelAnimationFrame(frame);frame=0;running=false;if(settle)values={...target};else target={...values};if(!document.hidden)paint();}
  function setState(next={},opts={}){
   const updated={...target};for(const k of Object.keys(defaults))if(Number.isFinite(+next[k]))updated[k]=k==='temperature'?clamp(next[k],15,30):clamp(next[k]);
   const changed=Object.keys(defaults).some(k=>Math.abs(updated[k]-target[k])>.0001),selectedChanged=opts.selected!==undefined&&opts.selected!==options.selected||opts.selectedKeys!==undefined&&JSON.stringify(opts.selectedKeys)!==JSON.stringify(options.selectedKeys),phaseChanged=opts.phase!==undefined&&opts.phase!==options.phase;
   if(opts.selected!==undefined&&opts.selectedKeys===undefined)opts={...opts,selectedKeys:[opts.selected]};options={...options,...opts};const reduced=root.matchMedia?.('(prefers-reduced-motion: reduce)').matches,canMove=options.motion&&opts.duration!==0&&!reduced&&!document.hidden;
   if(!changed){if(!canMove&&running)stop(true);else if(selectedChanged||phaseChanged||!draws)paint();return;}
   startValues={...values};target=updated;duration=clamp(opts.duration||650,100,2400);startAt=performance.now();if(frame)cancelAnimationFrame(frame);frame=0;
   if(canMove){running=true;frame=requestAnimationFrame(tick);}else{values={...target};running=false;paint();}
  }
  const resize=()=>paint();new ResizeObserver(resize).observe(host);
  document.addEventListener('visibilitychange',()=>{if(document.hidden){stop();}else{paint();}});
  root.matchMedia?.('(prefers-reduced-motion: reduce)').addEventListener('change',()=>stop(true));
  paint();
  return{setState,stop,set:(pose,motion)=>setState({}, {phase:pose,motion}),getDebug:()=>{const projected=faces.flatMap(f=>f.verts.map(project)),bounds={left:Math.min(...projected.map(p=>p[0])),top:Math.min(...projected.map(p=>p[1])),right:Math.max(...projected.map(p=>p[0])),bottom:Math.max(...projected.map(p=>p[1]))};return{values:{...values},target:{...target},selected:options.selected,running,draws,faces:lastFaces,renderer:'geometry',...stats,bounds};}};
 }
 root.CabinScene={create,defaults};
})(window);
