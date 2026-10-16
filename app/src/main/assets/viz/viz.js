// The app's visualizer (VisualizerOverlay.kt): the web player's butterchurn
// presets and drive (mStream/webapp/app/viz.js), fed by the app instead of a
// Web Audio analyser. `Zenelo` is the app's bridge:
//   Zenelo.frame()   "<base64 of 1024 unsigned 8-bit samples>,<rms>,<flux>" of
//                    what plays now, "" when nothing can be heard
//   Zenelo.playing() whether the player plays
//   Zenelo.onPreset(index, count, name)
// and the app calls VIZ.next() / VIZ.prev() / VIZ.setCycle(on) / VIZ.start(i).
var VIZ = (() => {
  const canvas = document.getElementById('c');
  const presets = window.BUTTERCHURN_PRESETS || {};
  const keys = Object.keys(presets);
  const N = 1024;
  const wave = new Uint8Array(N).fill(128);
  const drive = createDrive();
  const history = [];
  const CYCLE_S = 20;
  let visualizer = null;
  let index = 0;
  let cycle = true;
  let cycleS = 0;
  let clock = 0;
  let lastTick = 0;
  let stale = true;
  window.VIZ_DRIVE = { motion: 1 };

  // The frame the app heard: the samples into `wave`, its loudness and flux.
  function listen() {
    const s = Zenelo.frame();
    if (!s) { wave.fill(128); return [0, 0]; }
    const parts = s.split(',');
    const bin = atob(parts[0]);
    for (let i = 0; i < N && i < bin.length; i++) { wave[i] = bin.charCodeAt(i); }
    return [Number(parts[1]) || 0, Number(parts[2]) || 0];
  }

  function size() {
    canvas.width = canvas.clientWidth || window.innerWidth;
    canvas.height = canvas.clientHeight || window.innerHeight;
    if (visualizer) { visualizer.setRendererSize(canvas.width, canvas.height); stale = true; }
  }

  function load(i, blend) {
    if (!visualizer || !keys.length) { return; }
    index = (i + keys.length) % keys.length;
    visualizer.loadPreset(withDrive(presets[keys[index]]), blend);
    cycleS = 0;
    Zenelo.onPreset(index, keys.length, keys[index]);
  }

  function loop(now) {
    requestAnimationFrame(loop);
    const dt = lastTick ? Math.min(now - lastTick, 250) : 0;
    lastTick = now;
    const [rms, flux] = listen();
    const d = drive.update(rms, flux, dt / 1000);
    window.VIZ_DRIVE.motion = d.motion;
    canvas.style.opacity = d.brightness.toFixed(3);
    // Faded out on a pause: nothing to draw until the music is back.
    if (!Zenelo.playing() && d.env < 0.005 && !stale) { return; }
    stale = false;
    clock += (dt / 1000) * d.motion;
    const r = visualizer.renderer;
    if (r && r.fps > 0) { r.time = clock - 1 / r.fps; }
    visualizer.render({ audioLevels: { timeByteArray: wave, timeByteArrayL: wave, timeByteArrayR: wave } });
    if (cycle && (cycleS += (dt / 1000) * d.motion) >= CYCLE_S) { mod.next(2.7); }
  }

  const mod = {
    start(i) {
      size();
      // butterchurn wants an AudioContext; it hears nothing through it here.
      const ctx = new (window.AudioContext || window.webkitAudioContext)();
      visualizer = butterchurn.default.createVisualizer(ctx, canvas, {
        // CSS pixels, not the screen's: a quarter of the work on a 2x screen, and the
        // presets' soft pictures lose nothing by it.
        width: canvas.width, height: canvas.height, pixelRatio: 1, textureRatio: 1,
      });
      clock = visualizer.renderer ? visualizer.renderer.time : 0;
      load(i >= 0 ? i : Math.floor(Math.random() * keys.length), 0);
      window.addEventListener('resize', size);
      requestAnimationFrame(loop);
    },
    next(blend = 5.7) { history.push(index); load(index + 1, blend); },
    prev(blend = 5.7) { load(history.length ? history.pop() : index - 1, blend); },
    setCycle(on) { cycle = on; cycleS = 0; },
  };
  return mod;
})();
