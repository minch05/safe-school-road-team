// 위치 판정은 기기 안에서만 한다. 아래 수치는 초기 오작동 억제 기준이며 현장 검증이 필요하다.
const NavigationGuard = (() => {
  const radians = d => d * Math.PI / 180;
  function distance(a,b) {
    const x=radians(b[0]-a[0]),y=radians(b[1]-a[1]);
    const h=Math.sin(x/2)**2+Math.cos(radians(a[0]))*Math.cos(radians(b[0]))*Math.sin(y/2)**2;
    return 6371000*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));
  }
  function lineDistance(point,line) {
    if(!line?.length)return Infinity;
    const scale=Math.cos(radians(point[0]));
    const xy=p=>[(p[1]-point[1])*scale*111195,(p[0]-point[0])*111195];
    let min=distance(point,line[0]);
    for(let i=1;i<line.length;i++){
      const a=xy(line[i-1]),b=xy(line[i]),dx=b[0]-a[0],dy=b[1]-a[1];
      const den=dx*dx+dy*dy,t=den?Math.max(0,Math.min(1,-(a[0]*dx+a[1]*dy)/den)):0;
      min=Math.min(min,Math.hypot(a[0]+t*dx,a[1]+t*dy));
    }
    return min;
  }
  function fresh(position,now=Date.now()) {
    const {latitude,longitude,accuracy}=position.coords||{};
    return Number.isFinite(latitude)&&Number.isFinite(longitude)&&Math.abs(latitude)<=90&&Math.abs(longitude)<=180
      &&Number.isFinite(accuracy)&&accuracy>0&&Number.isFinite(position.timestamp)
      &&now-position.timestamp<=15000&&position.timestamp<=now+1000;
  }
  function create(){
    let nearSince=null,nearLast=null,awaySince=null,awayLast=null,lastAccepted=null;
    return {
      reset(){nearSince=nearLast=awaySince=awayLast=lastAccepted=null;},
      inspect(position,end,line,now=Date.now()){
        const valid=fresh(position,now),accuracy=position.coords?.accuracy;
        if(!valid||accuracy>35){this.reset();return {usable:false,arrived:false,offRoute:false};}
        const stamp=position.timestamp;
        if(lastAccepted!==null&&stamp<=lastAccepted)return {usable:false,arrived:false,offRoute:false};
        lastAccepted=stamp;
        const point=[position.coords.latitude,position.coords.longitude];
        const near=accuracy<=25&&distance(point,end)<=50;
        if(!near){nearSince=nearLast=null;}
        else if(nearSince===null||stamp-nearLast>15000){nearSince=stamp;nearLast=stamp;}
        else if(stamp>nearLast)nearLast=stamp;
        const away=line?.length>1&&lineDistance(point,line)>Math.max(45,accuracy*2);
        if(!away){awaySince=awayLast=null;}
        else if(awaySince===null||stamp-awayLast>15000){awaySince=stamp;awayLast=stamp;}
        else if(stamp>awayLast)awayLast=stamp;
        return {usable:true,arrived:nearSince!==null&&nearLast-nearSince>=5000,offRoute:awaySince!==null&&awayLast-awaySince>=10000};
      }
    };
  }
  return {create,distance,lineDistance,fresh};
})();
if(typeof module!=='undefined')module.exports=NavigationGuard;
