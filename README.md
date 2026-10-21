<img width="3200" height="2000" alt="image" src="https://github.com/user-attachments/assets/7f8574e3-8cf7-4614-a850-10f7216ed401" /># ⚔️ OTTV2 — Battle Code Arena

**OTTV2** là nền tảng thi đấu lập trình Bot AI và đối kháng trực tuyến (PvP) dành cho sinh viên. Hệ thống hỗ trợ nộp mã nguồn bot, thi đấu leo bảng xếp hạng ELO, tổ chức giải đấu và đấu realtime qua WebSockets.

## 🌐 1. Demo và Repository

- **Website:** https://ottv-2.vercel.app
- **Repository :** https://github.com/24020364-NguyenHuuVinh/OTTV2

### Quy ước cập nhật code

- Làm **frontend**: push code lên repository `ottv2-frontend`.
- Làm **backend, engine, worker**: push code lên repository `OTTV2`.
- Frontend đã được triển khai trên Vercel. Khi push code lên nhánh được cấu hình triển khai, Vercel sẽ tự động build và cập nhật website.
- Nếu thay đổi API hoặc WebSocket, cần kiểm tra khả năng tương thích giữa frontend và backend.

## 🏗️ 2. Kiến trúc hệ thống

| Module | Chức năng |
|---|---|
| `engine/` | Luật chơi, bàn cờ 9×9, xử lý nước đi và replay |
| `api/` | Spring Boot, REST API, WebSockets (STOMP), ELO và tournament |
| `worker/` | Biên dịch và thực thi bot, xử lý trận đấu |
| Frontend | React, Vite, Tailwind CSS |
| PostgreSQL | Lưu trữ dữ liệu |
| RabbitMQ | Điều phối tác vụ |
| Redis / MinIO | Hỗ trợ cache và lưu trữ dữ liệu |

## ⚙️ 3. Yêu cầu môi trường

- Windows 11 với **WSL2 Ubuntu 22.04 LTS** hoặc Linux.
- Docker Desktop đã bật WSL2 integration.
- Java JDK 17+.
- Node.js 18+.
- Git, Python 3, G++ và `isolate` để chạy bot trong sandbox.

Cài các công cụ cần thiết trên Ubuntu/WSL2:

```bash
sudo apt update
sudo apt install -y git g++ python3 isolate nlohmann-json3-dev
```

## 🚀 4. Chạy dự án local

### Bước 1: Clone repository

```bash
git clone https://github.com/24020364-NguyenHuuVinh/OTTV2.git
cd OTTV2
```

### Bước 2: Khởi động Docker

Mở Docker Desktop trên Windows, sau đó chạy trong terminal WSL:

```bash
docker compose up -d
```

Kiểm tra trạng thái dịch vụ:

```bash
docker compose ps
```

### Bước 3: Khởi chạy hệ thống

```bash
chmod +x run-all.sh
./run-all.sh
```

Script dùng để khởi tạo và chạy các thành phần của hệ thống theo cấu hình dự án. Có thể cần quyền `sudo` khi cấu hình `isolate`.

### Bước 4: Truy cập local

- **Frontend:** http://localhost:5173
- **Backend API:** http://localhost:8080

Dừng các tiến trình do script quản lý bằng `Ctrl + C`. Có thể dừng các dịch vụ Docker bằng:

```bash
docker compose down
```

## 🔌 5. Public Backend bằng ngrok

Dự án sử dụng ngrok để tạo đường dẫn truy cập backend từ Internet, giúp frontend đã deploy trên Vercel có thể gọi API hoặc kết nối WebSocket đến máy chạy backend.
Khởi động backend tại cổng `8080`, sau đó mở một terminal riêng trong WSL/Linux và chạy:
```bash
ngrok http --domain=revenue-princess-asleep.ngrok-free.dev 8080
```

### Lưu ý
- Backend phải đang chạy thì đường dẫn ngrok mới hoạt động.
- Máy chạy ngrok phải duy trì kết nối trong thời gian sử dụng.
- Frontend trên Vercel cần được cấu hình sử dụng URL backend public thay vì `localhost:8080`.
- Nếu sử dụng WebSockets, cần bảo đảm URL kết nối, CORS và cấu hình HTTPS/WSS phù hợp.
- Không chia sẻ token ngrok, mật khẩu hoặc thông tin bí mật trong repository.

## 🧩 6. Frontend và cập nhật website
Nếu muốn chỉnh sửa giao diện và cập nhật lên website đang deploy:
1. Sửa code và kiểm tra trên local.
2. Commit, push lên nhánh được cấu hình triển khai trên GitHub.
3. Vercel sẽ tự động build và cập nhật website sau khi triển khai thành công.


**OTTV2 — Build your bot. Challenge others. Climb the ladder.** ⚔️
