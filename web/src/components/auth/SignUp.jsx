import React, { useState } from 'react';
import { api, csrfHeaders, DEFAULT_HEADERS } from '../../utils/api.js';

export default function SignUp({ onRegistered, onShowLogin }) {
  const [studentCode, setStudentCode] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      const csrfH = await csrfHeaders();
      await api('/api/auth/register', {
        method: 'POST',
        headers: {
          ...DEFAULT_HEADERS,
          ...csrfH,
        },
        body: JSON.stringify({ studentCode, displayName, email, password }),
      });
      const registeredData = {
        studentCode,
        displayName,
        email,
        role: 'STUDENT',
        pvpElo: 1200,
        botElo: 1200,
      };
      localStorage.setItem('ottv2_last_active_user', JSON.stringify(registeredData));
      localStorage.setItem(`ottv2_user_${email}`, JSON.stringify(registeredData));
      onRegistered(email);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="min-h-screen bg-zinc-950 flex items-center justify-center p-4">
      <div className="w-full max-w-md bg-zinc-900 border border-zinc-800 rounded-3xl p-8 shadow-2xl space-y-6">
        <div className="text-center space-y-2">
          <div className="w-12 h-12 rounded-2xl bg-emerald-600 text-white flex items-center justify-center text-2xl mx-auto shadow-lg shadow-emerald-950/60 font-black">
            ⚔️
          </div>
          <p className="text-[11px] font-bold text-emerald-400 uppercase tracking-widest font-mono">OTTV2 · BOT ARENA</p>
          <h1 className="text-2xl font-black text-white">Đăng Ký Tài Khoản</h1>
        </div>

        <form onSubmit={submit} className="space-y-3.5 text-xs">
          <div className="space-y-1.5">
            <label className="text-zinc-400 font-semibold">Mã sinh viên</label>
            <input
              value={studentCode}
              onChange={(e) => setStudentCode(e.target.value)}
              required
              placeholder="VD: B22DCCN001"
              className="w-full bg-zinc-950 border border-zinc-700 rounded-xl px-4 py-2.5 text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500"
            />
          </div>

          <div className="space-y-1.5">
            <label className="text-zinc-400 font-semibold">Tên hiển thị</label>
            <input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              required
              placeholder="VD: Nguyen Van A"
              className="w-full bg-zinc-950 border border-zinc-700 rounded-xl px-4 py-2.5 text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500"
            />
          </div>

          <div className="space-y-1.5">
            <label className="text-zinc-400 font-semibold">Email sinh viên</label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              placeholder="email@ptit.edu.vn"
              className="w-full bg-zinc-950 border border-zinc-700 rounded-xl px-4 py-2.5 text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500"
            />
          </div>

          <div className="space-y-1.5">
            <label className="text-zinc-400 font-semibold">Mật khẩu (tối thiểu 12 ký tự)</label>
            <input
              type="password"
              minLength={12}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              placeholder="••••••••••••"
              className="w-full bg-zinc-950 border border-zinc-700 rounded-xl px-4 py-2.5 text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500"
            />
          </div>

          {error && <p className="text-rose-400 text-xs font-semibold">{error}</p>}

          <button
            disabled={busy}
            className="w-full py-3 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-sm shadow-lg shadow-emerald-950/50 transition-all disabled:opacity-50"
          >
            {busy ? 'Đang tạo tài khoản…' : 'Tạo Tài Khoản'}
          </button>
        </form>

        <p className="text-center text-xs text-zinc-400">
          Đã có tài khoản?{' '}
          <button
            type="button"
            onClick={onShowLogin}
            className="text-emerald-400 font-bold hover:underline"
          >
            Đăng nhập
          </button>
        </p>
      </div>
    </main>
  );
}
