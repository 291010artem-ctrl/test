/* Ночная тема + анимация комет и падающих звёзд.
   Тема хранится в localStorage (kg_theme). Подключать в <head>. */
(function(){
  const KEY='kg_theme';
  const root=document.documentElement;
  // применяем тему как можно раньше, чтобы не мигало
  let theme=localStorage.getItem(KEY)||'day';
  if(theme==='night') root.setAttribute('data-theme','night');

  /* ---------- звёздное небо: кометы и падающие звёзды ---------- */
  let cv=null, ctx=null, raf=null, spawnT=null, stars=[], running=false;
  function ensureCanvas(){
    if(cv) return;
    cv=document.createElement('canvas'); cv.id='kg-sky';
    cv.style.cssText='position:fixed;inset:0;width:100%;height:100%;pointer-events:none;z-index:9990';
    document.body.appendChild(cv); ctx=cv.getContext('2d');
    resize(); window.addEventListener('resize',resize);
  }
  function resize(){ if(!cv)return; cv.width=innerWidth; cv.height=innerHeight; }

  function spawnStar(comet){
    const w=cv.width, fromLeft=Math.random()<0.5;
    const speed=(comet?4.5:7)+Math.random()*4;
    const ang=(comet?0.28:0.42)+Math.random()*0.12;        // наклон падения
    const vx=(fromLeft?1:-1)*speed*Math.cos(ang);
    const vy=speed*Math.sin(ang);
    stars.push({
      x: fromLeft ? -40+Math.random()*w*0.4 : w*0.6+Math.random()*w*0.4+40,
      y: -30+Math.random()*(cv.height*0.35),
      vx, vy,
      len: comet?150:60, w: comet?3:1.6,
      hue: comet? 205+Math.random()*30 : 45+Math.random()*15, // комета голубоватая, звезда золотистая
      life:0, max: comet?150:80, comet:!!comet
    });
  }
  function frame(){
    ctx.clearRect(0,0,cv.width,cv.height);
    for(let i=stars.length-1;i>=0;i--){
      const s=stars[i];
      s.x+=s.vx; s.y+=s.vy; s.life++;
      const p=s.life/s.max, alpha=Math.sin(Math.min(1,p)*Math.PI); // плавно появ./гасн.
      const tx=s.x-s.vx*(s.len/ (Math.hypot(s.vx,s.vy)||1)), ty=s.y-s.vy*(s.len/(Math.hypot(s.vx,s.vy)||1));
      const g=ctx.createLinearGradient(s.x,s.y,tx,ty);
      g.addColorStop(0,`hsla(${s.hue},95%,85%,${alpha})`);
      g.addColorStop(1,`hsla(${s.hue},95%,85%,0)`);
      ctx.strokeStyle=g; ctx.lineWidth=s.w; ctx.lineCap='round';
      ctx.beginPath(); ctx.moveTo(s.x,s.y); ctx.lineTo(tx,ty); ctx.stroke();
      // яркая голова
      ctx.fillStyle=`hsla(${s.hue},100%,95%,${alpha})`;
      ctx.beginPath(); ctx.arc(s.x,s.y,s.w*(s.comet?1.8:1.3),0,6.28); ctx.fill();
      if(s.comet){ // мягкое свечение кометы
        ctx.fillStyle=`hsla(${s.hue},100%,80%,${alpha*0.25})`;
        ctx.beginPath(); ctx.arc(s.x,s.y,s.w*5,0,6.28); ctx.fill();
      }
      if(s.life>s.max || s.x<-200 || s.x>cv.width+200 || s.y>cv.height+120) stars.splice(i,1);
    }
    raf=requestAnimationFrame(frame);
  }
  function startSky(){
    if(running) return; running=true; ensureCanvas();
    raf=requestAnimationFrame(frame);
    let n=0;
    spawnT=setInterval(()=>{
      if(document.hidden) return;
      spawnStar(false);                      // падающая звезда
      if(Math.random()<0.35) spawnStar(false);
      n++; if(n%7===0) spawnStar(true);      // изредка — комета
    }, 1100);
  }
  function stopSky(){
    running=false; if(spawnT)clearInterval(spawnT); if(raf)cancelAnimationFrame(raf);
    stars=[]; if(ctx&&cv) ctx.clearRect(0,0,cv.width,cv.height);
  }

  /* ---------- космическая полоса внизу страницы ---------- */
  function addSpace(){
    if(document.getElementById('kg-space')) return;
    const d=document.createElement('div');
    d.className='kg-space'; d.id='kg-space'; d.setAttribute('aria-hidden','true');
    // звёзды по всей площади страницы (плотность зависит от высоты)
    const h=Math.max(document.body.scrollHeight, window.innerHeight);
    const count=Math.min(240, Math.max(80, Math.round(h/9)));
    let tw='';
    for(let i=0;i<count;i++){
      const big=Math.random()<0.22?' big':'';
      tw+='<span class="tw'+big+'" style="left:'+(1+Math.random()*98).toFixed(1)+'%;top:'+(1+Math.random()*98).toFixed(1)+
          '%;animation-delay:'+(-Math.random()*3).toFixed(2)+'s"></span>';
    }
    d.innerHTML=tw+
      '<div class="planet p1"></div>'+
      '<div class="planet p2"></div>'+
      '<div class="planet p3"><span class="ring"></span></div>'+
      '<div class="planet p4"></div>'+
      '<div class="planet p5"></div>'+
      '<div class="planet p6"></div>'+
      '<div class="ufo">🛸</div>'+
      '<div class="rocket">🚀</div>'+
      '<div class="rocket rocket2">🚀</div>';
    document.body.appendChild(d);
  }
  function removeSpace(){ const d=document.getElementById('kg-space'); if(d) d.remove(); }

  /* ---------- дневной декор: облака, полянка, бабочки ---------- */
  function grassBg(front){
    const top=front?'#8fca4c':'#79b23c', bot=front?'#5a9a2c':'#4a851f';
    // силуэт травы — мягкие «холмики» из кривых
    const svg="<svg xmlns='http://www.w3.org/2000/svg' width='220' height='100'>"+
      "<defs><linearGradient id='g' x1='0' y1='0' x2='0' y2='1'>"+
      "<stop offset='0' stop-color='"+top+"'/><stop offset='1' stop-color='"+bot+"'/></linearGradient></defs>"+
      "<path fill='url(#g)' d='M0,100 L0,64 Q7,32 15,60 Q23,28 32,58 Q42,24 51,56 Q62,40 71,62 "+
      "Q82,28 92,58 Q103,42 113,60 Q124,26 134,56 Q145,44 155,60 Q166,30 176,58 Q187,42 198,60 Q208,32 220,58 L220,100 Z'/>"+
      "</svg>";
    return "url(\"data:image/svg+xml,"+encodeURIComponent(svg)+"\")";
  }
  function addDay(){
    if(document.getElementById('kg-day')) return;
    const d=document.createElement('div'); d.className='kg-day'; d.id='kg-day'; d.setAttribute('aria-hidden','true');

    // облака вверху (нарисованные)
    [['3%',1.0,70],['8%',0.75,100],['5%',0.9,132],['12%',0.6,160]].forEach((c,i)=>{
      const [t,sc,dur]=c;
      const cl=document.createElement('div'); cl.className='cloud';
      cl.style.cssText='top:'+t+';transform:scale('+sc+');animation-duration:'+dur+'s;animation-delay:'+(-i*22)+'s';
      d.appendChild(cl);
    });

    // трава — два слоя
    const gb=document.createElement('div'); gb.className='grass back'; gb.style.backgroundImage=grassBg(false); d.appendChild(gb);
    const gf=document.createElement('div'); gf.className='grass'; gf.style.backgroundImage=grassBg(true); d.appendChild(gf);

    // цветы (нарисованные)
    const petals=['#ff8fb1','#ff6f91','#c48cff','#ff7a7a','#ffb14d','#ffffff'];
    for(let i=0;i<9;i++){
      const fl=document.createElement('div'); fl.className='flower';
      fl.style.setProperty('--pc', petals[Math.floor(Math.random()*petals.length)]);
      const sc=(0.8+Math.random()*0.6).toFixed(2);
      fl.style.cssText+='left:'+(3+Math.random()*94).toFixed(1)+'%;transform:scale('+sc+');'+
        'animation-duration:'+(3+Math.random()*2.5).toFixed(1)+'s;animation-delay:'+(-Math.random()*3).toFixed(1)+'s';
      fl.innerHTML='<span class="stem"></span><span class="leaf"></span><span class="head"></span><span class="core"></span>';
      d.appendChild(fl);
    }

    // лёгкие пушинки
    for(let i=0;i<7;i++){
      const p=document.createElement('span'); p.className='pollen';
      p.style.cssText='left:'+(4+Math.random()*92).toFixed(1)+'%;bottom:'+(20+Math.random()*40).toFixed(0)+'px;'+
        'animation-duration:'+(9+Math.random()*7).toFixed(1)+'s;animation-delay:'+(-Math.random()*9).toFixed(1)+'s';
      d.appendChild(p);
    }
    document.body.appendChild(d);
  }
  function removeDay(){ const x=document.getElementById('kg-day'); if(x) x.remove(); }

  /* ---------- смена фона-героя на главной ---------- */
  function swapHero(t){
    document.querySelectorAll('img[data-day]').forEach(img=>{
      const src = t==='night' ? img.getAttribute('data-night') : img.getAttribute('data-day');
      if(src) img.src=src;
    });
  }

  function apply(t,animate){
    theme=t; localStorage.setItem(KEY,t);
    if(t==='night'){ root.setAttribute('data-theme','night'); removeDay(); startSky(); addSpace(); }
    else{ root.removeAttribute('data-theme'); stopSky(); removeSpace(); addDay(); }
    swapHero(t);
    const knob=document.querySelector('.theme-toggle .tt-knob');
    if(knob) knob.textContent = t==='night' ? '🌙' : '🌞';
  }

  document.addEventListener('DOMContentLoaded',()=>{
    // строим переключатель
    const btn=document.createElement('button');
    btn.className='theme-toggle'; btn.type='button'; btn.title='День / Ночь';
    btn.setAttribute('aria-label','Переключить день и ночь');
    btn.innerHTML='<span class="tt-track"><span class="tt-knob">'+(theme==='night'?'🌙':'🌞')+'</span></span><small>Тема</small>';
    btn.addEventListener('click',()=>apply(theme==='night'?'day':'night',true));
    const actions=document.querySelector('.actions');
    if(actions) actions.appendChild(btn); else { btn.style.cssText+=';position:fixed;right:16px;bottom:16px;z-index:9995'; document.body.appendChild(btn); }

    swapHero(theme);
    if(theme==='night'){ startSky(); addSpace(); }
    else{ addDay(); }
  });
})();
