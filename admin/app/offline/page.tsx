export default function OfflinePage() {
  return <main style={{minHeight:'100dvh',display:'grid',placeItems:'center',padding:24,background:'#090b12',color:'#eef2f7',fontFamily:'system-ui, sans-serif'}}>
    <section style={{maxWidth:460,textAlign:'center',padding:32,border:'1px solid #293746',borderRadius:20,background:'#111824'}}>
      <div style={{fontSize:28,fontWeight:900,letterSpacing:2,color:'#39dfb0'}}>TG ADMIN</div>
      <h1 style={{fontSize:24}}>Koneksi sedang offline</h1>
      <p style={{color:'#aab6c5',lineHeight:1.7}}>Panel admin membutuhkan koneksi internet untuk memuat data akun dan pengaturan. Sambungkan internet lalu coba lagi.</p>
      <button onClick={() => window.location.reload()} style={{padding:'12px 20px',borderRadius:10,border:0,background:'#16c997',color:'#06130f',fontWeight:800}}>Coba lagi</button>
    </section>
  </main>;
}