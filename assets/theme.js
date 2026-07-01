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
    let tw='';
    for(let i=0;i<14;i++){
      tw+='<span class="tw" style="left:'+(4+Math.random()*92).toFixed(1)+'%;top:'+(6+Math.random()*88).toFixed(1)+
          '%;animation-delay:'+(-Math.random()*3).toFixed(2)+'s"></span>';
    }
    d.innerHTML=tw+
      '<div class="planet p1"></div>'+
      '<div class="planet p2"></div>'+
      '<div class="planet p3"><span class="ring"></span></div>'+
      '<div class="ufo">🛸</div>'+
      '<div class="rocket">🚀</div>';
    document.body.appendChild(d);
  }
  function removeSpace(){ const d=document.getElementById('kg-space'); if(d) d.remove(); }

  /* ---------- смена фона-героя на главной ---------- */
  function swapHero(t){
    document.querySelectorAll('img[data-day]').forEach(img=>{
      const src = t==='night' ? img.getAttribute('data-night') : img.getAttribute('data-day');
      if(src) img.src=src;
    });
  }

  function apply(t,animate){
    theme=t; localStorage.setItem(KEY,t);
    if(t==='night'){ root.setAttribute('data-theme','night'); startSky(); addSpace(); }
    else{ root.removeAttribute('data-theme'); stopSky(); removeSpace(); }
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
  });
})();
