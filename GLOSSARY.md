# GLOSSARY.md

Domain vocabulary for OTTV2 — Battle Code Arena. Use these terms consistently in specs, issues, code, and agent discussions.

## Core Game Elements

- **Board (Bàn cờ)**: A 9×9 grid. Rows are indexed 0–8 (displayed visually as 1–9 from top to bottom in standard view), columns are indexed 0–8 (displayed visually as A–I from left to right).
- **Base / Destination (Căn cứ / Ô đích)**:
  - Team A (Blue) Base: cell `(8, 0)` (display: `A9`).
  - Team B (Red) Base: cell `(0, 8)` (display: `I1`).
  - Moving any piece into the opponent's base immediately wins the game.
- **Piece (Quân cờ)**: One of three types:
  - `ROCK` (Búa)
  - `PAPER` (Bao / Lá)
  - `SCISSORS` (Kéo)
- **Combat Resolution (Giao tranh Kéo-Búa-Bao)**:
  - Rock beats Scissors (`ROCK > SCISSORS`).
  - Scissors beats Paper (`SCISSORS > PAPER`).
  - Paper beats Rock (`PAPER > ROCK`).
  - Same type collision results in mutual elimination (cả hai quân cùng bị tiêu diệt).
- **Turn Limit (Giới hạn lượt)**: Maximum default turn limit is 200 turns. If neither player captures the enemy base or wipes out all enemy pieces within 200 turns, game resolves by material count (remaining pieces) or draws.

## Perspectives & Controls

- **Board Flip (`isFlipped`)**: UI perspective rotation by 180 degrees so Team B (Red) sees their pieces at the bottom moving upward. Does NOT change the internal `(r, c)` logic coordinates.
- **Fischer Clock**: Chess-style timer mechanism with an initial time bank and an increment added after each completed move:
  - `Blitz (Chớp)`: 03:00 base + 2s increment.
  - `Rapid (Nhanh)`: 05:00 base + 3s increment.
  - `Bullet (Siêu chớp)`: 01:00 base + 1s increment.

## Competitive Rating & Matching

- **Dual ELO Rating**:
  - `pvpElo`: Rating gained or lost exclusively from manual PvP matches against human players or sparring AI in PvP rooms. Default: 1200.
  - `botElo`: Rating gained or lost exclusively from automated Bot arena matches. Default: 1200.
- **Sparring AI (Máy tập luyện)**: 12 fallback offline bots (`Máy 1` to `Máy 12`) implementing 8 algorithmic behaviors (Hung hăng, Phòng thủ, Đột kích ô đích, Trinh sát, Cân bằng, Quyết tử, Chiến thuật, Biến ảo) used when matchmaking times out or in room sparring mode.

## System Components

- **Engine (`engine/`)**: Core Java module implementing rules, valid move generation, move execution, combat, and symmetric map generation.
- **API (`api/`)**: Spring Boot web server handling authentication, user profiles, room lifecycle, WebSocket relays, and leaderboard queries.
- **Worker (`worker/`)**: Match execution runner for sandboxed Bot vs Bot competitions.
- **Web (`web/`)**: React + Tailwind + Canvas/PixiJS frontend SPA.
