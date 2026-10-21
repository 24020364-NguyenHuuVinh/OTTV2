import React, { useState, useEffect } from 'react';
import { filterActiveBots, getBotSource, saveBotSource } from '../utils/botStorage.js';

const MOCK_CODE_TEMPLATES = {
  PYTHON: `import json
import sys

def decide(state):
    # Lấy tọa độ đích của phe mình (I1 [0, 8] cho Đội Xanh, A9 [8, 0] cho Đội Đỏ)
    targets = state.get("targets", [[0, 8]])
    target = targets[0] if targets else [0, 8]
    your_pieces = state.get("yourPieces", [])
    
    if not your_pieces:
        return None
        
    # Tập hợp các ô bị chặn (quân mình + chướng ngại vật)
    blocked = {(p["r"], p["c"]) for p in your_pieces}
    for cell in state.get("visibleCells", state.get("cells", [])):
        if cell.get("type") in ("OBSTACLE", "TRAP"):
            blocked.add((cell.get("r"), cell.get("c")))
    for ob in state.get("obstacles", []):
        if isinstance(ob, list) and len(ob) == 2:
            blocked.add((ob[0], ob[1]))

    # Duyệt các quân để tìm nước đi hợp lệ tiến về ô đích
    for p in your_pieces:
        r, c = p["r"], p["c"]
        candidates = []
        if r != target[0]:
            candidates.append((r + (1 if target[0] > r else -1), c))
        if c != target[1]:
            candidates.append((r, c + (1 if target[1] > c else -1)))
        for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            candidates.append((r + dr, c + dc))

        for nr, nc in candidates:
            if 0 <= nr < 9 and 0 <= nc < 9 and (nr, nc) not in blocked:
                return {"pieceId": p["id"], "to": [nr, nc]}

    return None

for line in sys.stdin:
    try:
        state = json.loads(line)
        print(json.dumps(decide(state)), flush=True)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr, flush=True)
        break`,
  JAVA: `import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class Bot {
    private static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode decide(JsonNode state) {
        JsonNode piece = state.path("yourPieces").path(0);
        if (piece.isMissingNode()) return null;
        
        int r = piece.path("r").asInt();
        int c = piece.path("c").asInt();
        
        ObjectNode move = JSON.createObjectNode();
        move.put("pieceId", piece.path("id").asInt());
        move.putArray("to").add(r > 0 ? r - 1 : r + 1).add(c);
        return move;
    }

    public static void main(String[] args) throws Exception {
        try (var in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(JSON.writeValueAsString(decide(JSON.readTree(line))));
                System.out.flush();
            }
        }
    }
}`,
  CPP: `#include <iostream>
#include <string>
#include <nlohmann/json.hpp>
using json = nlohmann::json;

int main() {
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        try {
            json state = json::parse(line);
            // Ví dụ: Đọc thời gian còn lại
            // int timeLeftMs = state.value("timeLeftMs", 180000);
            
            auto myPieces = state["yourPieces"];
            if (myPieces.empty()) {
                std::cout << "{\"pieceId\": -1, \"to\": [0,0]}\n";
            } else {
                int pId = myPieces[0]["id"];
                int r = myPieces[0]["r"];
                int c = myPieces[0]["c"];
                std::cout << "{\"pieceId\": " << pId << ", \"to\": [" << r << "," << c+1 << "]}\n";
            }
        } catch (...) {
            std::cout << "{\"pieceId\": -1, \"to\": [0,0]}\n";
        }
        std::cout << std::flush;
    }
    return 0;
}`
};

export default function BattleCode({
  bots = [],
  submissions = [],
  onUploadSubmission,
  onChallenge,
  user,
  botElo = 1200,
  userElo = 1200,
  onDeleteBot,
  onCleanBots,
  onToggleLadderActive,
}) {
  const activeBots = filterActiveBots(bots);
  const MAX_BOTS = 5;
  const isLimitReached = activeBots.length >= MAX_BOTS;
  const currentBotElo = botElo || userElo;
  const [language, setLanguage] = useState('PYTHON');
  const [code, setCode] = useState(MOCK_CODE_TEMPLATES.PYTHON);
  const [botName, setBotName] = useState('');
  const [selectedBotId, setSelectedBotId] = useState(() => (activeBots.length >= 5 && activeBots[0]?.id ? activeBots[0].id : '__new__'));
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [statusMessage, setStatusMessage] = useState('');
  const [rightTab, setRightTab] = useState('LEADERBOARD'); // 'LEADERBOARD' | 'MY_BOTS'

  const handleSelectBot = (botId) => {
    setSelectedBotId(botId);
    if (botId === '__new__') {
      setBotName('');
      setCode(MOCK_CODE_TEMPLATES[language] || MOCK_CODE_TEMPLATES.PYTHON);
      setStatusMessage('Đã chọn tạo Bot mới. Hãy nhập tên Bot và soạn thảo chiến thuật!');
      return;
    }
    const targetBot = activeBots.find((b) => String(b.id) === String(botId));
    if (targetBot) {
      setBotName(targetBot.name);
      const sourceEntry = getBotSource(targetBot.id, targetBot.name);
      setCode(sourceEntry.code);
      if (sourceEntry.language) {
        setLanguage(sourceEntry.language);
      }
      setStatusMessage(`Đã nạp mã nguồn của bot [${targetBot.name}] (${sourceEntry.code.length} bytes).`);
    }
  };

  // Tự động tải mã nguồn của bot khi selectedBotId thay đổi hoặc khi danh sách bot nạp xong
  useEffect(() => {
    if (selectedBotId !== '__new__') {
      const targetBot = activeBots.find((b) => String(b.id) === String(selectedBotId));
      if (targetBot) {
        setBotName(targetBot.name);
        const sourceEntry = getBotSource(targetBot.id, targetBot.name);
        setCode(sourceEntry.code);
        if (sourceEntry.language) {
          setLanguage(sourceEntry.language);
        }
      }
    } else if (activeBots.length >= 5 && activeBots[0]?.id) {
      handleSelectBot(activeBots[0].id);
    }
  }, [selectedBotId, activeBots.length]);

  const baseMiniRankings = [
    { rank: 1, name: 'Vũ Nam Anh', bot: 'QuantumScissors', elo: 1540 },
    { rank: 2, name: 'AlgoNinja', bot: 'ShadowBot_v3', elo: 1515 },
    { rank: 3, name: 'CyberDragon', bot: 'RedFlame.py', elo: 1495 },
    { rank: 4, name: 'CodeHunter', bot: 'HunterV5', elo: 1420 },
    { rank: 5, name: 'DeepScissor', bot: 'CutEverything', elo: 1350 },
    { rank: 6, name: 'BeginnerBot', bot: 'NoviceRPS', elo: 1210 },
  ];

  const miniRankings = [
    ...baseMiniRankings,
    {
      rank: 0,
      name: `${user?.displayName || user?.studentCode || 'Người chơi'} (Bạn)`,
      bot: activeBots[0]?.name || 'Chưa nộp Bot',
      elo: currentBotElo,
      isYou: true,
    },
  ]
    .sort((a, b) => b.elo - a.elo)
    .map((item, idx) => ({ ...item, rank: idx + 10 }));

  const userMiniRank = miniRankings.find((r) => r.isYou)?.rank || 14;

  const handleLanguageChange = (lang) => {
    setLanguage(lang);
    if (selectedBotId === '__new__') {
      setCode(MOCK_CODE_TEMPLATES[lang] || '');
    }
  };

  const handleFileUpload = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
      setCode(event.target?.result || '');
    };
    reader.readAsText(file);
  };

  const handleSubmit = async () => {
    if (selectedBotId === '__new__' && isLimitReached) {
      setStatusMessage('⚠️ Bạn đã đạt giới hạn tối đa 5 Bot. Vui lòng xóa bớt các bot cũ hoặc chọn Cập nhật phiên bản trên bot hiện có!');
      return;
    }
    setIsSubmitting(true);
    setStatusMessage('');
    try {
      const targetName = selectedBotId === '__new__'
        ? botName
        : (activeBots.find((b) => String(b.id) === String(selectedBotId))?.name || botName);

      // Lưu mã nguồn vào localStorage key 'ottv2_bot_sources_map'
      saveBotSource(selectedBotId, targetName, code, language);

      if (onUploadSubmission) {
        await onUploadSubmission({
          botId: selectedBotId,
          newBotName: botName,
          language,
          source: code,
        });
        setStatusMessage(`Đã nộp và lưu mã nguồn [${targetName}] thành công! Hệ thống đang biên dịch...`);
      } else {
        setTimeout(() => {
          setIsSubmitting(false);
          setStatusMessage(`Biên dịch thành công (Mock)! Bot [${targetName}] đã sẵn sàng thi đấu.`);
        }, 1200);
      }
    } catch (err) {
      setStatusMessage('Lỗi: ' + (err.message || 'Không thể nộp bot'));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 min-h-[calc(100vh-6rem)] animate-fadeIn">
      {/* CỘT TRÁI (60% ~ 7 cols): Trình Soạn Thảo & Nộp Mã Nguồn */}
      <div className="lg:col-span-7 flex flex-col bg-zinc-850 border border-zinc-750 rounded-3xl overflow-hidden shadow-2xl">
        {/* Editor Toolbar Header */}
        <div className="flex flex-wrap items-center justify-between px-6 py-4 bg-zinc-900 border-b border-zinc-750 gap-4">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-lg">
              💻
            </div>
            <div>
              <h3 className="font-bold text-white text-sm">Bot Code Editor & Upload</h3>
              <p className="text-[11px] text-zinc-400">Khung thời gian: 3 Phút Tổng + 1s/turn</p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <div className="flex bg-zinc-950 p-1 rounded-xl border border-zinc-800">
              {['PYTHON', 'JAVA', 'CPP'].map((lang) => (
                <button
                  key={lang}
                  type="button"
                  onClick={() => handleLanguageChange(lang)}
                  className={`px-3 py-1 rounded-lg text-xs font-bold transition-all ${
                    language === lang
                      ? 'bg-emerald-600 text-white shadow'
                      : 'bg-transparent text-zinc-400 hover:text-white'
                  }`}
                >
                  {lang === 'CPP' ? 'C++' : lang}
                </button>
              ))}
            </div>

            <label className="cursor-pointer px-3 py-1.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-xs font-semibold text-zinc-200 border border-zinc-700 transition-colors">
              📁 Tải File Lên
              <input
                type="file"
                className="hidden"
                accept={language === 'PYTHON' ? '.py' : language === 'CPP' ? '.cpp,.cc,.cxx,.h,.hpp' : '.java'}
                onChange={handleFileUpload}
              />
            </label>
          </div>
        </div>

        {/* Bộ đếm & Cảnh báo giới hạn MAX_BOTS = 5 */}
        <div className="px-6 py-2.5 bg-zinc-900/90 border-b border-zinc-800 flex flex-wrap items-center justify-between gap-3 text-xs">
          <div className="flex items-center gap-2">
            <span className="text-zinc-400 font-semibold">Số lượng Bot sở hữu:</span>
            <span className={`font-mono font-bold px-2.5 py-0.5 rounded-full text-xs ${
              isLimitReached
                ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                : 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
            }`}>
              {activeBots.length} / {MAX_BOTS} Bot
            </span>
          </div>

          <div className="flex items-center gap-2">
            {activeBots.length > MAX_BOTS && onCleanBots && (
              <button
                type="button"
                onClick={onCleanBots}
                className="px-2.5 py-1 rounded-lg bg-amber-500/20 hover:bg-amber-500/30 text-amber-200 border border-amber-500/40 font-semibold text-[11px] transition-all flex items-center gap-1"
              >
                <span>🧹</span> Giữ lại 5 Bot mới nhất & Xóa các Bot thừa
              </button>
            )}
            <button
              type="button"
              onClick={() => setRightTab('MY_BOTS')}
              className="px-2.5 py-1 rounded-lg bg-zinc-800 hover:bg-zinc-750 text-zinc-300 border border-zinc-700 font-semibold text-[11px] transition-all"
            >
              Quản lý Bot ({activeBots.length})
            </button>
          </div>
        </div>

        {isLimitReached && (
          <div className="px-6 py-2.5 bg-amber-950/40 border-b border-amber-500/30 text-amber-200 text-xs flex items-center gap-2">
            <span className="text-sm">⚠️</span>
            <span>
              <strong>Bạn đã đạt giới hạn tối đa 5 Bot.</strong> Vui lòng xóa bớt các bot cũ hoặc chọn Cập nhật phiên bản trên bot hiện có!
            </span>
          </div>
        )}

        {/* Cấu hình Bot (Tên bot & Lựa chọn) */}
        <div className="px-6 py-3 bg-zinc-900/60 border-b border-zinc-800/80 flex flex-wrap items-center gap-4 text-xs">
          <div className="flex items-center gap-2">
            <span className="text-zinc-400 font-semibold">Chọn Bot:</span>
            <select
              value={selectedBotId}
              onChange={(e) => handleSelectBot(e.target.value)}
              className="bg-zinc-950 border border-zinc-700 rounded-lg px-2.5 py-1 text-white focus:outline-none focus:border-emerald-500 font-medium"
            >
              <option value="__new__" disabled={isLimitReached}>
                {isLimitReached ? '+ Tạo Bot Mới (Đã đạt giới hạn 5/5)' : '+ Tạo Bot Mới'}
              </option>
              {activeBots.map((b) => (
                <option key={b.id} value={b.id}>{b.name}</option>
              ))}
            </select>
          </div>

          {selectedBotId === '__new__' && !isLimitReached && (
            <div className="flex items-center gap-2 flex-1 min-w-[200px]">
              <span className="text-zinc-400 font-semibold">Tên Bot:</span>
              <input
                type="text"
                placeholder="Nhập tên bot (VD: MySuperBot)"
                value={botName}
                onChange={(e) => setBotName(e.target.value)}
                className="flex-1 bg-zinc-950 border border-zinc-700 rounded-lg px-2.5 py-1 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
          )}

          {selectedBotId !== '__new__' && (
            <div className="flex items-center gap-2 text-zinc-400 font-mono text-[11px]">
              <span>Đang chỉnh sửa:</span>
              <span className="text-emerald-400 font-bold font-sans">🤖 {botName}</span>
              <span className="text-zinc-500">(ID: {selectedBotId})</span>
            </div>
          )}
        </div>

        {/* Code Editor Body */}
        <div className="flex-1 relative bg-zinc-950 p-4 font-mono text-xs overflow-auto leading-relaxed min-h-[360px]">
          <textarea
            value={code}
            onChange={(e) => setCode(e.target.value)}
            spellCheck="false"
            className="w-full h-full min-h-[340px] bg-transparent text-emerald-300 resize-none outline-none font-mono text-xs selection:bg-emerald-800/50"
          />
        </div>

        {/* Status notice */}
        {statusMessage && (
          <div className="px-6 py-2 bg-zinc-900 border-t border-zinc-800 text-xs text-emerald-400 font-medium">
            {statusMessage}
          </div>
        )}

        {/* Action Footer Bar */}
        <div className="px-6 py-4 bg-zinc-900 border-t border-zinc-750 flex items-center justify-between">
          <span className="text-xs text-zinc-400 font-mono">
            {language === 'PYTHON' ? 'bot.py' : language === 'CPP' ? 'bot.cpp' : 'Bot.java'} · {code.length} bytes
          </span>

          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => {
                if (code.includes('decide') || code.includes('class Bot') || code.includes('main')) {
                  setStatusMessage('✓ Kiểm tra cú pháp thành công: Đã tìm thấy hàm/phương thức xử lý nước đi hợp lệ.');
                } else {
                  setStatusMessage('⚠️ Cảnh báo: Mã nguồn chưa chứa định nghĩa hàm decide(state) hoặc main().');
                }
              }}
              className="px-4 py-2 rounded-xl bg-zinc-800 hover:bg-zinc-750 text-zinc-300 font-semibold text-xs border border-zinc-700 transition-colors"
            >
              Kiểm Tra Cú Pháp
            </button>
            <button
              type="button"
              disabled={isSubmitting || (selectedBotId === '__new__' && (!botName || isLimitReached))}
              onClick={handleSubmit}
              className="px-6 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-bold text-xs tracking-wide transition-all shadow-md shadow-emerald-950/40 flex items-center gap-2"
            >
              {isSubmitting ? (
                <>
                  <span className="inline-block w-3 h-3 border-2 border-white/20 border-t-white rounded-full animate-spin"></span>
                  <span>Đang nộp...</span>
                </>
              ) : (
                <>
                  <span>🚀</span>
                  <span>Biên Dịch & Nộp Bot</span>
                </>
              )}
            </button>
          </div>
        </div>
      </div>

      {/* CỘT PHẢI (40% ~ 5 cols): Mini Leaderboard / Quản Lý Bot */}
      <div className="lg:col-span-5 flex flex-col bg-zinc-850 border border-zinc-750 rounded-3xl p-6 shadow-2xl">
        <div className="flex items-center justify-between pb-4 border-b border-zinc-800 gap-2">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setRightTab('LEADERBOARD')}
              className={`px-3 py-1.5 rounded-xl font-bold text-xs transition-all flex items-center gap-1.5 ${
                rightTab === 'LEADERBOARD'
                  ? 'bg-emerald-600 text-white shadow'
                  : 'text-zinc-400 hover:text-white bg-zinc-900 border border-zinc-800'
              }`}
            >
              <span>🔥</span> Mini Rank
            </button>
            <button
              type="button"
              onClick={() => setRightTab('MY_BOTS')}
              className={`px-3 py-1.5 rounded-xl font-bold text-xs transition-all flex items-center gap-1.5 ${
                rightTab === 'MY_BOTS'
                  ? 'bg-emerald-600 text-white shadow'
                  : 'text-zinc-400 hover:text-white bg-zinc-900 border border-zinc-800'
              }`}
            >
              <span>🤖</span> Bot Của Tôi ({activeBots.length}/{MAX_BOTS})
            </button>
          </div>
          <span className="text-xs font-mono font-bold px-2 py-0.5 rounded bg-zinc-900 border border-zinc-750 text-emerald-400">
            {rightTab === 'LEADERBOARD' ? 'Ladder' : `${activeBots.length} Bot`}
          </span>
        </div>

        {rightTab === 'MY_BOTS' ? (
          <div className="flex-1 flex flex-col pt-3 overflow-hidden">
            <div className="flex items-center justify-between mb-3 text-xs">
              <span className="text-zinc-400 font-semibold">Danh sách Bot sở hữu:</span>
              <span className="text-emerald-400 font-mono font-bold">{activeBots.length} / {MAX_BOTS}</span>
            </div>

            {activeBots.length > MAX_BOTS && onCleanBots && (
              <button
                type="button"
                onClick={onCleanBots}
                className="w-full mb-3 py-2 px-3 rounded-xl bg-amber-500/20 hover:bg-amber-500/30 text-amber-200 border border-amber-500/40 font-bold text-xs transition-all flex items-center justify-center gap-2"
              >
                <span>🧹</span> Giữ lại 5 Bot mới nhất & Xóa các Bot thừa
              </button>
            )}

            {activeBots.length === 0 ? (
              <div className="flex-1 flex items-center justify-center text-center p-6 text-zinc-500 text-xs">
                Bạn chưa có bot nào. Hãy đặt tên bot, viết code và bấm "Biên Dịch & Nộp Bot" để tạo bot mới!
              </div>
            ) : (
              <div className="flex-1 overflow-auto space-y-2.5 pr-1">
                {activeBots.map((b, idx) => (
                  <div
                    key={b.id}
                    className={`p-3 rounded-2xl border flex items-center justify-between transition-all ${
                      selectedBotId == b.id ? 'bg-emerald-950/40 border-emerald-500/50' : 'bg-zinc-900/60 border-zinc-800'
                    }`}
                  >
                    <div className="flex items-center gap-2.5 min-w-0">
                      <span className="w-5 text-center font-bold text-xs text-zinc-500">#{idx + 1}</span>
                      <div className="min-w-0">
                        <p className="text-xs font-bold text-white truncate flex items-center gap-1.5">
                          <span>🤖</span>
                          <span className="truncate">{b.name}</span>
                          <span className="text-amber-400 font-mono font-bold">({b.elo ?? 1200} ELO)</span>
                        </p>
                        <p className="text-[10px] text-zinc-400 font-mono">
                          ID: {b.id} · {b.language || 'PYTHON'}
                        </p>
                      </div>
                    </div>

                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => onToggleLadderActive && onToggleLadderActive(b.id, !b.ladderEnabled)}
                        className={`px-2.5 py-1 rounded-lg text-[11px] font-bold border transition-colors flex items-center gap-1 ${
                          b.ladderEnabled
                            ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40 hover:bg-emerald-500/30'
                            : 'bg-zinc-800 text-zinc-400 border-zinc-700 hover:text-white'
                        }`}
                        title={b.ladderEnabled ? 'Bấm để tắt đấu Ranked' : 'Bấm để chọn bot này đi đấu Ranked'}
                      >
                        <span>{b.ladderEnabled ? '🟢 Đang Đấu Ranked' : '⚪ Vào Đấu Ranked'}</span>
                      </button>
                      <button
                        type="button"
                        onClick={() => handleSelectBot(b.id)}
                        className="px-2.5 py-1 rounded-lg bg-zinc-800 hover:bg-zinc-750 text-zinc-300 hover:text-white text-[11px] font-semibold border border-zinc-700 transition-colors"
                        title="Cập nhật phiên bản code cho bot này"
                      >
                        ✏️ Chọn
                      </button>
                      {onDeleteBot && (
                        <button
                          type="button"
                          onClick={() => {
                            if (window.confirm(`Bạn có chắc muốn xóa bot "${b.name}"?`)) {
                              onDeleteBot(b.id);
                            }
                          }}
                          className="px-2.5 py-1 rounded-lg bg-rose-950/60 hover:bg-rose-900/80 text-rose-300 border border-rose-800/60 text-[11px] font-bold transition-colors flex items-center gap-1"
                          title="Xóa bot"
                        >
                          🗑️ Xóa
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        ) : (
          <div className="flex-1 flex flex-col pt-3 overflow-hidden">
            {/* Thẻ Vị Trí Cá Nhân */}
            <div className="mb-4 p-4 rounded-2xl bg-emerald-950/40 border border-emerald-500/30 flex items-center justify-between shadow-lg shadow-emerald-950/30">
              <div className="flex items-center gap-3">
                <span className="w-8 h-8 rounded-lg bg-emerald-600 text-white font-black text-sm flex items-center justify-center">
                  #{userMiniRank}
                </span>
                <div>
                  <p className="text-sm font-bold text-white flex items-center gap-1.5">
                    {user?.displayName || user?.studentCode || 'Người chơi'}{' '}
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-emerald-500 text-zinc-950 font-black">BẠN</span>
                  </p>
                  <p className="text-xs text-zinc-400">
                    {activeBots[0]?.name || 'Chưa nộp Bot'} · {activeBots.length} / {MAX_BOTS} bot
                  </p>
                </div>
              </div>
              <span className="text-base font-black font-mono text-amber-400 tabular-nums">{currentBotElo} ELO</span>
            </div>

            {/* Danh sách đối thủ Mini */}
            <div className="flex-1 overflow-auto space-y-2 pr-1">
              {miniRankings.map((target) => {
                const isUnlocked = target.elo <= currentBotElo + 150;
                return (
                  <div
                    key={target.rank}
                    className={`p-3 rounded-2xl border flex items-center justify-between transition-all group ${
                      target.isYou
                        ? 'bg-zinc-800/80 border-emerald-500/40'
                        : 'bg-zinc-900/60 hover:bg-zinc-800/80 border-zinc-800 hover:border-zinc-750'
                    }`}
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <span className="w-6 text-center font-bold text-xs text-zinc-400">#{target.rank}</span>
                      <div className="min-w-0">
                        <p className="text-xs font-bold text-zinc-200 group-hover:text-white truncate">{target.name}</p>
                        <p className="text-[11px] text-zinc-500 font-mono truncate">{target.bot}</p>
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      <span className="font-mono text-xs font-bold text-amber-400 tabular-nums">{target.elo}</span>
                      {!target.isYou && (
                        isUnlocked ? (
                          <button
                            type="button"
                            onClick={() => onChallenge({ ...target, matchType: 'BOT', isPvP: false, isPractice: true })}
                            className="p-1.5 rounded-lg bg-zinc-800 hover:bg-emerald-600 text-zinc-400 hover:text-white transition-all text-xs"
                            title="Đấu tập luyện với bot ngay (Chế độ Giao Hữu)"
                          >
                            ⚔️
                          </button>
                        ) : (
                          <button
                            type="button"
                            disabled
                            className="p-1.5 rounded-lg bg-zinc-900 text-zinc-600 cursor-not-allowed text-xs"
                            title={`Cần tối thiểu ${target.elo - 150} ELO để mở khóa`}
                          >
                            🔒
                          </button>
                        )
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
