/* Мир Кирпи и Гофера — общий модуль: звуки, конфетти, наклейки-награды.
   Никаких внешних файлов и регистрации — всё хранится в браузере (localStorage). */
(function(){
  const HEROES={
    kirpi:"Кирпи", gofer:"Гофер", tisa:"Тиса", lulu:"Лулу",
    sonya:"Соня", borik:"Борик", bruno:"Бруно"
  };
  const KEY='kg_stickers';
  function getStickers(){ try{ return JSON.parse(localStorage.getItem(KEY))||{}; }catch(e){ return {}; } }
  function saveStickers(o){ try{ localStorage.setItem(KEY, JSON.stringify(o)); }catch(e){} }

  /* ---------- Звук (WebAudio, без файлов) ---------- */
  let actx=null;
  function ac(){ if(!actx){ try{ actx=new (window.AudioContext||window.webkitAudioContext)(); }catch(e){} } return actx; }
  function beep(freq,dur,type,vol,delay){
    const a=ac(); if(!a) return;
    if(a.state==='suspended'){ a.resume(); }
    const t=a.currentTime+(delay||0);
    const o=a.createOscillator(), g=a.createGain();
    o.type=type||'sine'; o.frequency.value=freq;
    g.gain.setValueAtTime(0.0001,t);
    g.gain.exponentialRampToValueAtTime(vol||0.2,t+0.02);
    g.gain.exponentialRampToValueAtTime(0.0001,t+dur);
    o.connect(g); g.connect(a.destination);
    o.start(t); o.stop(t+dur+0.03);
  }

  /* ---------- Конфетти ---------- */
  function confetti(){
    let cv=document.getElementById('kg-confetti');
    if(!cv){ cv=document.createElement('canvas'); cv.id='kg-confetti';
      cv.style.cssText='position:fixed;inset:0;width:100%;height:100%;pointer-events:none;z-index:9998';
      document.body.appendChild(cv); }
    const ctx=cv.getContext('2d');
    cv.width=innerWidth; cv.height=innerHeight;
    const colors=['#e8932e','#6e9d2f','#f4c430','#e85d9a','#37b6c9','#8a5cd4','#e23b3b'];
    const N=120, parts=[];
    for(let i=0;i<N;i++){
      parts.push({x:Math.random()*cv.width, y:-20-Math.random()*cv.height*0.4,
        r:6+Math.random()*8, c:colors[i%colors.length],
        vy:2+Math.random()*3.5, vx:-1.5+Math.random()*3,
        rot:Math.random()*6.28, vr:-0.2+Math.random()*0.4});
    }
    const start=performance.now();
    (function frame(now){
      const t=now-start;
      ctx.clearRect(0,0,cv.width,cv.height);
      parts.forEach(p=>{
        p.x+=p.vx; p.y+=p.vy; p.rot+=p.vr; p.vy+=0.03;
        ctx.save(); ctx.translate(p.x,p.y); ctx.rotate(p.rot);
        ctx.fillStyle=p.c; ctx.fillRect(-p.r/2,-p.r/2,p.r,p.r*0.6); ctx.restore();
      });
      if(t<2600){ requestAnimationFrame(frame); }
      else { ctx.clearRect(0,0,cv.width,cv.height); }
    })(start);
  }

  /* ---------- Всплывашка «новая наклейка» ---------- */
  function toast(key,isNew){
    const box=document.createElement('div');
    box.style.cssText='position:fixed;left:50%;top:22%;transform:translate(-50%,-10px);z-index:9999;'+
      'background:#fffdf6;border:3px solid #bcd98f;border-radius:22px;padding:18px 26px;text-align:center;'+
      'box-shadow:0 18px 44px rgba(120,100,60,.32);opacity:0;transition:opacity .3s,transform .3s;'+
      'font-family:"Nunito","Segoe UI",Arial,sans-serif;color:#5a4a34;max-width:88vw';
    box.innerHTML=
      '<div style="font-weight:900;color:#5a8423;font-size:1.15rem;margin-bottom:8px">'+
        (isNew?'🎉 Новая наклейка!':'🎉 Молодец!')+'</div>'+
      '<img src="assets/parts/av_'+key+'.jpg" alt="" style="width:96px;height:96px;border-radius:50%;'+
        'object-fit:cover;border:4px solid #f6e3a8;box-shadow:0 6px 16px rgba(120,100,60,.28)">'+
      '<div style="font-weight:900;margin-top:8px;font-size:1.1rem">'+(HEROES[key]||'')+'</div>'+
      '<div style="font-weight:700;color:#9a8a72;font-size:.85rem;margin-top:2px">'+
        (isNew?'добавлен в твою копилку 🌟':'уже в копилке — так держать!')+'</div>'+
      '<a href="stickers.html" style="display:inline-block;margin-top:12px;padding:8px 20px;border-radius:999px;'+
        'background:linear-gradient(180deg,#6e9d2f,#5a8423);color:#fff;font-weight:800;text-decoration:none">'+
        'Открыть копилку</a>';
    document.body.appendChild(box);
    requestAnimationFrame(()=>{ box.style.opacity='1'; box.style.transform='translate(-50%,0)'; });
    setTimeout(()=>{ box.style.opacity='0'; box.style.transform='translate(-50%,-10px)';
      setTimeout(()=>box.remove(),350); }, 4200);
  }

  const KG={
    heroes:HEROES,
    getStickers,
    click(){ beep(660,0.10,'triangle',0.16); },
    match(){ beep(740,0.11,'sine',0.2); beep(988,0.14,'sine',0.18,0.10); },
    miss(){ beep(190,0.14,'sine',0.10); },
    fanfare(){ [523,659,784,1047,1319].forEach((f,i)=>beep(f,0.24,'triangle',0.22,i*0.13)); },
    confetti,
    /* выдать наклейку героя key ('kirpi','tisa',...) */
    award(key){
      if(!HEROES[key]) return false;
      const o=getStickers(); const isNew=!o[key];
      o[key]=(o[key]||0)+1; saveStickers(o);
      confetti(); KG.fanfare(); toast(key,isNew);
      return isNew;
    }
  };
  window.KG=KG;
})();
