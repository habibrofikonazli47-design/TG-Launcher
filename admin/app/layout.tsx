import './globals.css';
import type { Metadata, Viewport } from 'next';
import PwaInstall from './components/PwaInstall';

export const metadata: Metadata = {
  title: 'TG Launcher Admin',
  description: 'TG Launcher control center',
  applicationName: 'TG Launcher Admin',
  manifest: '/manifest.webmanifest',
  appleWebApp: { capable: true, statusBarStyle: 'black-translucent', title: 'TG Admin' },
  icons: { icon: '/icons/tg-admin.svg', apple: '/icons/tg-admin.svg' },
};
export const viewport: Viewport = { themeColor: '#0b121b', width: 'device-width', initialScale: 1 };

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="id"><body><PwaInstall />{children}</body></html>;
}
