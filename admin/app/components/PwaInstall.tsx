'use client';
import { useEffect, useState } from 'react';

type InstallPromptEvent = Event & {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>;
};

export default function PwaInstall() {
  const [installEvent, setInstallEvent] = useState<InstallPromptEvent | null>(null);
  const [installed, setInstalled] = useState(false);
  const [showHelp, setShowHelp] = useState(false);

  useEffect(() => {
    const media = window.matchMedia('(display-mode: standalone)');
    const syncInstalled = () => setInstalled(media.matches || (navigator as Navigator & { standalone?: boolean }).standalone === true);
    syncInstalled();
    media.addEventListener?.('change', syncInstalled);
    const onPrompt = (event: Event) => {
      event.preventDefault();
      setInstallEvent(event as InstallPromptEvent);
    };
    const onInstalled = () => { setInstalled(true); setInstallEvent(null); setShowHelp(false); };
    window.addEventListener('beforeinstallprompt', onPrompt);
    window.addEventListener('appinstalled', onInstalled);
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('/sw.js').catch(() => undefined);
    }
    return () => {
      media.removeEventListener?.('change', syncInstalled);
      window.removeEventListener('beforeinstallprompt', onPrompt);
      window.removeEventListener('appinstalled', onInstalled);
    };
  }, []);

  async function install() {
    if (!installEvent) { setShowHelp(true); return; }
    await installEvent.prompt();
    const choice = await installEvent.userChoice;
    if (choice.outcome === 'accepted') setInstallEvent(null);
  }

  if (installed) return <span className="pwa-installed" aria-label="Aplikasi sudah terpasang">✓ TG Admin Terpasang</span>;
  return <>
    <button type="button" className="pwa-install" onClick={install}>↓ Install App</button>
    {showHelp && <div className="pwa-help-backdrop" role="presentation" onClick={() => setShowHelp(false)}>
      <section className="pwa-help" role="dialog" aria-modal="true" aria-labelledby="pwa-help-title" onClick={(e) => e.stopPropagation()}>
        <button type="button" className="pwa-help-close" onClick={() => setShowHelp(false)} aria-label="Tutup">×</button>
        <h2 id="pwa-help-title">Pasang TG Launcher Admin</h2>
        <p>Jika tombol instalasi belum muncul, buka menu browser Chrome (⋮), lalu pilih <b>Install app</b> atau <b>Add to Home screen</b>.</p>
        <p>Setelah terpasang, TG Admin bisa dibuka dari layar utama seperti aplikasi.</p>
        <button type="button" className="primary" onClick={() => setShowHelp(false)}>Mengerti</button>
      </section>
    </div>}
  </>;
}