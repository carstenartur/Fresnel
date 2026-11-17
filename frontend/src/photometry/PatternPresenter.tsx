import { useEffect, useRef, useState } from 'react';
import { download, type Manifest, type Pattern } from './data';
import { stage, nativeSize, displayFrame } from './stage';
import './photometry.css';


export function PatternPresenter({ manifest, pattern }: { manifest: Manifest; pattern: Pattern }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [ready, setReady] = useState<Blob | null>(null);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const { widthPx, heightPx, settleDelayMs } = manifest.geometry;
  useEffect(() => {
    let active = true;
    async function load() {
      if (!canvas.current) return;
      const blob = await stage(canvas.current,manifest,pattern,()=>active);
      if (active) setReady(blob);
    }
    void load().catch(e => { if (active) setError(String(e)); });
    return () => { active = false; };
  }, [manifest,pattern,widthPx,heightPx]);
  useEffect(() => {
    const visible = () => {
      if (document.hidden && document.fullscreenElement === canvas.current) void document.exitFullscreen();
    };
    document.addEventListener('visibilitychange', visible);
    return () => document.removeEventListener('visibilitychange', visible);
  }, []);
  async function show() {
    const target = canvas.current;
    if (!target || !ready) return;
    setStatus(''); setError('');
    try {
      await target.requestFullscreen({ navigationUI:'hide' });
      await displayFrame(); await displayFrame();
      if (!nativeSize(target))
        throw new Error('Full-screen dimensions differ from the manifest. Use the native pixel dimensions of this screen at 100% browser zoom.');
      await new Promise(done => setTimeout(done,settleDelayMs));
      if (document.fullscreenElement === target && !document.hidden)
        setStatus(pattern.id + ' was displayed at native resolution for the configured settle time.');
      else setStatus('Presentation interrupted before the display settled.');
    } catch (e) {
      if (document.fullscreenElement === target) await document.exitFullscreen();
      setError(e instanceof Error ? e.message : String(e));
    }
  }
  return <div>
    <button disabled={!ready} onClick={() => void show()}>Show {pattern.id} full screen</button>
    <button disabled={!ready} onClick={() => ready && download(ready,pattern.id+'.png')}>Download pattern PNG</button>
    <canvas className="photometry-illumination" aria-label="Illumination pattern" ref={canvas} width={widthPx} height={heightPx}/>
    {status && <p role="status">{status}</p>}{error && <p role="alert">{error}</p>}
  </div>;
}
