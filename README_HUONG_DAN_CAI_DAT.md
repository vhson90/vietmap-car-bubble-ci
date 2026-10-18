# 🚗 Vietmap Car Bubble (Offline 100%) — Bong Bóng Báo Tốc Độ & Camera Cho Android Box Ô Tô

Ứng dụng Android Native (`vn.vietmap.carbubble`) tạo **Bong bóng nổi (Floating HUD Overlay)** hiển thị đè lên trên **Google Maps, YouTube, Navitel, Apple CarPlay / Android Auto** trên màn hình Android Box của xe ô tô.

---

## ✨ Tính năng nổi bật

1. **Hoạt động Offline 100% (Không cần 4G, không cần tài khoản)**:
   - Tích hợp sẵn cơ sở dữ liệu không gian [`vietmap_offline.db`](app/src/main/assets/vietmap_offline.db) (`7.97 MB`) chứa toàn bộ **69,965 điểm Biển báo tốc độ (`30`..`120 km/h`), Khu dân cư, Camera phạt nguội, Camera tốc độ, Cấm vượt, Trạm thu phí ETC & Đèn tín hiệu Hybrid** trên toàn lãnh thổ Việt Nam.
   - Đã tạo sẵn chỉ mục không gian (`idx_edog_lat_lon`), thời gian truy vấn tọa độ mỗi nhịp GPS chỉ tốn **< 2 ms**.
2. **Bong bóng HUD 3-trong-1 (Kéo thả tự do trên màn hình xe)**:
   - **Đồng hồ tốc độ thực tế GPS (`km/h`)**: Đổi sang **Màu Đỏ Chớp** ngay lập tức khi xe vượt quá tốc độ giới hạn của đoạn đường.
   - **Biển báo tốc độ giới hạn hiện tại**: Sử dụng bộ icon gốc chuẩn Vietmap Live (`30`..`120 km/h`), tự động chốt trạng thái khi xe đi qua biển báo hoặc ra/vào khu đông dân cư.
   - **Thẻ cảnh báo mở rộng**: Hiển thị loại biển báo / camera phạt nguội sắp tới (trong bán kính `450m` phía trước mũi xe, lọc theo góc lái `Bearing` và độ lệch làn đường `Cross-track <= 35m`) kèm khoảng cách đếm ngược bằng mét (`m`).
3. **Cảnh báo Giọng nói Tiếng Việt (`TextToSpeech` + Âm Bíp)**:
   - Tự động đọc cảnh báo bằng Tiếng Việt khi cách biển báo / camera `< 350m` và khi xe chạy vượt quá tốc độ giới hạn.
4. **Tích hợp sẵn Chế độ Mô Phỏng Lộ Trình 30km TP.HCM**:
   - Giúp bạn kiểm tra trực tiếp hoạt động của bong bóng, giọng nói và hiệu ứng báo đỏ quá tốc độ ngay khi xe đang đỗ (hoặc trên máy tính).
5. **Tự động khởi chạy khi nổ máy xe (`BootReceiver`)**:
   - Tự động bật bong bóng tốc độ ngay khi Android Box vừa khởi động xong.

---

## 🛠️ Cách biên dịch ra file `.apk` để cài lên Box Ô Tô

### Cách 1: Dùng Android Studio trên máy tính (Nhanh nhất)
1. Mở **Android Studio** -> Chọn **Open** -> Trỏ tới thư mục:
   `e:\AI_TOOLS\AI Tool Trick\VietmapLive\android-car-bubble`
2. Đợi Android Studio tải Gradle khoảng 1–2 phút.
3. Trên thanh menu, chọn **Build ➔ Build Bundle(s) / APK(s) ➔ Build APK(s)**.
4. File cài đặt sẽ xuất ra tại:
   `app/build/outputs/apk/debug/app-debug.apk`

### Cách 2: Build tự động trên đám mây bằng GitHub Actions (Không cần cài Android Studio)
1. Đẩy thư mục `android-car-bubble` lên một kho chứa (Repository) trên GitHub.
2. File cấu hình tự động [`.github/workflows/build_apk.yml`](.github/workflows/build_apk.yml) đã được tích hợp sẵn.
3. Vào tab **Actions** trên GitHub -> Nhấn **Build VietmapCarBubble APK** -> Tải file `VietmapCarBubble-Offline-v1.0.zip` chứa `app-debug.apk` về chép vào USB cài lên Box ô tô.

---

## 🔄 Cách cập nhật dữ liệu Biển báo & Camera mới nhất từ Vietmap trong tương lai

Bất cứ khi nào bạn muốn cập nhật dữ liệu `edogen.bin` mới nhất vào ứng dụng trước khi build lại APK, chỉ cần mở Terminal tại thư mục gốc `e:\AI_TOOLS\AI Tool Trick\VietmapLive` và chạy:

```bash
python build_offline_db.py
```

Script sẽ tự động tải gói dữ liệu mới, giải mã S-box và cập nhật thẳng vào file `android-car-bubble/app/src/main/assets/vietmap_offline.db`.
