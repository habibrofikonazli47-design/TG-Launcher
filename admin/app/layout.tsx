import './globals.css';
import type { Metadata } from 'next';
export const metadata: Metadata = { title: 'TG Launcher Admin', description: 'TG Launcher control center' };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) { return <html lang="id"><body>{children}</body></html>; }
